package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.SphereNoise
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tectonic history's crust read onto the map, one entry per map cell, row-major.
 *
 * [continentalShare] is the crust's mixture, 0 ocean floor to 1 continent; [thicknessKm] and
 * [excessKm] are the continent's crust where there is any (the standard column where there is
 * none), the second the part orogeny piled on; [seafloorAgeMyr] the ocean floor's age where there
 * is any. [crustAgeMyr] is how long ago the crust was last made or deformed, [riftAgeMyr] when it
 * was stretched (-1 where never), [orogenyKind] an [OrogenyKind] ordinal, [terrane] the craton,
 * microcontinent or arc a continent began as (-1 for ocean floor), [plateId] the present plate.
 */
internal class CrustOnMap(
    val continentalShare: FloatArray,
    val thicknessKm: FloatArray,
    val excessKm: FloatArray,
    val seafloorAgeMyr: FloatArray,
    val crustAgeMyr: FloatArray,
    val riftAgeMyr: FloatArray,
    val orogenyKind: IntArray,
    val terrane: IntArray,
    val plateId: IntArray
)

/**
 * Somewhere other than the processor to read the history's crust onto the map.
 *
 * The pass is per map cell — find the history's points near the cell, weigh them, sum — with no
 * dependence between cells, which is work a graphics card does well, and at 1,024 rows it is two
 * million cells. As with the ocean and the atmosphere, the engine knows nothing about how that is
 * done: this interface is the seam, and the implementation lives with the front end that has the
 * platform's graphics API.
 *
 * **The processor's [CrustMap.read] is the reference.** Anything implementing this is held to its
 * answer by a test, within a stated tolerance: the card sums in single precision, so it does not
 * promise the last bit (`GpuCrustMapTest`).
 *
 * Not yet called by the engine: the history's own steps are on the processor in TH1-1, and the
 * card is wired in, with the time budget measured, in TH1-4 (docs/DESIGN_LEDGER.md, TH1-1).
 */
interface CrustMapAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * [CrustMap.read] of [inputs] on the device, or null if this accelerator cannot do the job
     * after all, in which case the caller runs the processor's. Returning null is a normal outcome.
     */
    suspend fun read(inputs: CrustMapInputs): CrustMapOutputs?
}

/**
 * Everything the map pass reads, flattened for a device: the history's points and their fields, the
 * lattice's search bins, the noises' permutation tables, and the map's size. Built by
 * [CrustMap.inputs]; every array is the history's, in its own order.
 */
class CrustMapInputs internal constructor(
    val mapColumns: Int,
    val mapRows: Int,
    val radiusKm: Double,
    val spacingRadians: Double,
    /** The history's points, x, y, z interleaved. */
    val pointXyz: DoubleArray,
    val pointContinental: IntArray,
    val pointPlate: IntArray,
    val pointThicknessKm: FloatArray,
    val pointExcessKm: FloatArray,
    val pointFormedMyrAgo: FloatArray,
    val pointCrustAgeMyr: FloatArray,
    /** Rift age where stretched, -1 where never: a device has no NaN it can be trusted with. */
    val pointRiftMyrAgo: FloatArray,
    val pointOrogenyKind: IntArray,
    val pointTerrane: IntArray,
    val bandCount: Int,
    val binsInBand: IntArray,
    val firstBinOfBand: IntArray,
    val binStart: IntArray,
    val binPoints: IntArray,
    /** The warp's three noises' and the margin noise's permutation tables, 512 entries each, in that order. */
    val permutations: IntArray,
    val standardThicknessKm: Float
)

/** What the map pass writes, one entry per map cell, row-major: [CrustOnMap]'s fields, rift age -1 where never. */
class CrustMapOutputs(
    val continentalShare: FloatArray,
    val thicknessKm: FloatArray,
    val excessKm: FloatArray,
    val seafloorAgeMyr: FloatArray,
    val crustAgeMyr: FloatArray,
    val riftAgeMyr: FloatArray,
    val orogenyKind: IntArray,
    val terrane: IntArray,
    val plateId: IntArray
)

/**
 * The history's crust read onto the map grid.
 *
 * Each map cell weighs the history's points within [KERNEL_REACH_SPACINGS] of it by a Gaussian of
 * their distance on the sphere, [KERNEL_SPREAD_SPACINGS] of the history's spacing, and reads every
 * field as the weighted mean of the points that carry it: the continental share over all of them,
 * the continent's thickness over its continental points and the floor's age over its oceanic ones;
 * a category (the plate, the kind of orogeny, the terrane) from whichever weighs most. A kernel
 * round on the sphere has no preferred bearing, so the history's lattice does not show through as a
 * grid of its own (docs/CONVENTIONS.md, rule 13), and the map is the same at every grid, drawn
 * finer.
 *
 * The place each cell reads at is moved by a noise on the sphere a few tenths of the history's
 * spacing, and the crust's mixture is roughened inside its own margin by another, as
 * `TectonicsConfig.marginRoughness` sets: what the history resolves is a coast's shape at its 80 km
 * spacing, and below that a coast and a plate boundary are as ragged as their rock.
 */
internal object CrustMap {

    fun read(config: WorldGenConfig, history: CrustHistory): CrustOnMap {
        val outputs = readOnProcessor(inputs(config, history), config.tectonics.marginRoughness)
        return CrustOnMap(
            continentalShare = outputs.continentalShare,
            thicknessKm = outputs.thicknessKm,
            excessKm = outputs.excessKm,
            seafloorAgeMyr = outputs.seafloorAgeMyr,
            crustAgeMyr = outputs.crustAgeMyr,
            riftAgeMyr = outputs.riftAgeMyr,
            orogenyKind = outputs.orogenyKind,
            terrane = outputs.terrane,
            plateId = outputs.plateId
        )
    }

    /** The map pass's inputs for [history] on [config]'s grid. */
    fun inputs(config: WorldGenConfig, history: CrustHistory): CrustMapInputs {
        val sphere = history.sphere
        val count = sphere.cellCount
        val xyz = DoubleArray(3 * count)
        for (point in 0 until count) {
            xyz[3 * point] = sphere.x[point]
            xyz[3 * point + 1] = sphere.y[point]
            xyz[3 * point + 2] = sphere.z[point]
        }
        val noises = (0 until NOISES).map { SphereNoise(config.seed * NOISE_STREAM + it) }
        val permutations = IntArray(NOISES * SphereNoise.PERMUTATION_ENTRIES)
        noises.forEachIndexed { index, noise -> noise.permutationTable().copyInto(permutations, index * SphereNoise.PERMUTATION_ENTRIES) }
        return CrustMapInputs(
            mapColumns = config.width,
            mapRows = config.height,
            radiusKm = sphere.radiusKm,
            spacingRadians = sphere.spacingRadians,
            pointXyz = xyz,
            pointContinental = IntArray(count) { if (history.continental[it]) 1 else 0 },
            pointPlate = history.plateOf.copyOf(),
            pointThicknessKm = FloatArray(count) { history.baseKm[it] + history.excessKm[it] },
            pointExcessKm = history.excessKm.copyOf(),
            pointFormedMyrAgo = history.formedMyrAgo.copyOf(),
            pointCrustAgeMyr = FloatArray(count) {
                val orogeny = history.orogenyMyrAgo[it]
                if (history.continental[it] && !orogeny.isNaN()) orogeny else history.formedMyrAgo[it]
            },
            pointRiftMyrAgo = FloatArray(count) { if (history.riftMyrAgo[it].isNaN()) -1f else history.riftMyrAgo[it] },
            pointOrogenyKind = history.orogenyKind.copyOf(),
            pointTerrane = history.terrane.copyOf(),
            bandCount = sphere.bandCount,
            binsInBand = sphere.binsInBand.copyOf(),
            firstBinOfBand = sphere.firstBinOfBand.copyOf(),
            binStart = sphere.binStart.copyOf(),
            binPoints = sphere.binPoints.copyOf(),
            permutations = permutations,
            standardThicknessKm = config.isostasy.continentalCrustThicknessKm
        )
    }

    /** The processor's map pass: the reference every accelerator is held to. */
    fun readOnProcessor(inputs: CrustMapInputs, marginRoughness: Float): CrustMapOutputs {
        val columns = inputs.mapColumns
        val rows = inputs.mapRows
        val cells = columns * rows
        val share = FloatArray(cells)
        val thickness = FloatArray(cells)
        val excess = FloatArray(cells)
        val floorAge = FloatArray(cells)
        val crustAge = FloatArray(cells)
        val riftAge = FloatArray(cells)
        val kind = IntArray(cells)
        val terrane = IntArray(cells)
        val plate = IntArray(cells)
        val noises = Array(NOISES) { SphereNoise.fromTable(inputs.permutations, it * SphereNoise.PERMUTATION_ENTRIES) }
        val spread = KERNEL_SPREAD_SPACINGS * inputs.spacingRadians
        val reach = KERNEL_REACH_SPACINGS * inputs.spacingRadians
        val warpRadians = WARP_SPACINGS * inputs.spacingRadians
        val radiusKm = inputs.radiusKm
        val warpWavelengthKm = WARP_WAVELENGTH_SPACINGS * inputs.spacingRadians * radiusKm
        parallelChunks(0, rows) { fromRow, toRow ->
            val votePlate = IntArray(MAX_PLATES_NEAR)
            val voteWeight = DoubleArray(MAX_PLATES_NEAR)
            for (row in fromRow until toRow) {
                val latitude = PI / 2 - (row + 0.5) / rows * PI
                for (column in 0 until columns) {
                    val cell = row * columns + column
                    val longitude = (column + 0.5) / columns * 2.0 * PI
                    val px = cos(latitude) * cos(longitude)
                    val py = cos(latitude) * sin(longitude)
                    val pz = sin(latitude)
                    // Where the cell reads: moved a few tenths of the history's spacing by a noise
                    // on the sphere, so a boundary between two of its points is not a straight cut.
                    var qx = px + warpRadians * noises[WARP_X].fbmOnSphere(px, py, pz, radiusKm, warpWavelengthKm, WARP_OCTAVES)
                    var qy = py + warpRadians * noises[WARP_Y].fbmOnSphere(px, py, pz, radiusKm, warpWavelengthKm, WARP_OCTAVES)
                    var qz = pz + warpRadians * noises[WARP_Z].fbmOnSphere(px, py, pz, radiusKm, warpWavelengthKm, WARP_OCTAVES)
                    val length = sqrt(qx * qx + qy * qy + qz * qz)
                    qx /= length; qy /= length; qz /= length

                    var weightSum = 0.0
                    var continentWeight = 0.0
                    var thicknessSum = 0.0
                    var excessSum = 0.0
                    var oceanWeight = 0.0
                    var floorAgeSum = 0.0
                    var crustAgeSum = 0.0
                    var riftWeight = 0.0
                    var riftSum = 0.0
                    var heaviest = -1
                    var heaviestWeight = -1.0
                    var votes = 0
                    forEachPointWithin(inputs, qx, qy, qz, reach) { point, angle ->
                        val weight = exp(-(angle / spread) * (angle / spread))
                        weightSum += weight
                        crustAgeSum += weight * inputs.pointCrustAgeMyr[point]
                        if (inputs.pointContinental[point] == 1) {
                            continentWeight += weight
                            thicknessSum += weight * inputs.pointThicknessKm[point]
                            excessSum += weight * inputs.pointExcessKm[point]
                        } else {
                            oceanWeight += weight
                            floorAgeSum += weight * inputs.pointFormedMyrAgo[point]
                        }
                        if (inputs.pointRiftMyrAgo[point] >= 0f) {
                            riftWeight += weight
                            riftSum += weight * inputs.pointRiftMyrAgo[point]
                        }
                        if (weight > heaviestWeight) {
                            heaviestWeight = weight
                            heaviest = point
                        }
                        val pointPlate = inputs.pointPlate[point]
                        var slot = -1
                        for (index in 0 until votes) if (votePlate[index] == pointPlate) slot = index
                        if (slot < 0 && votes < MAX_PLATES_NEAR) {
                            slot = votes++
                            votePlate[slot] = pointPlate
                            voteWeight[slot] = 0.0
                        }
                        if (slot >= 0) voteWeight[slot] += weight
                    }
                    if (heaviest < 0) {
                        // Only where the lattice is sparser than its covering radius, which it never is.
                        heaviest = nearestPoint(inputs, qx, qy, qz)
                        weightSum = 1.0
                        if (inputs.pointContinental[heaviest] == 1) continentWeight = 1.0 else oceanWeight = 1.0
                        thicknessSum = inputs.pointThicknessKm[heaviest].toDouble()
                        excessSum = inputs.pointExcessKm[heaviest].toDouble()
                        floorAgeSum = inputs.pointFormedMyrAgo[heaviest].toDouble()
                        crustAgeSum = inputs.pointCrustAgeMyr[heaviest].toDouble()
                        votes = 1
                        votePlate[0] = inputs.pointPlate[heaviest]
                        voteWeight[0] = 1.0
                    }
                    var mixture = continentWeight / weightSum
                    // Roughened inside the margin and nowhere else: `4 s (1 - s)` is nothing where
                    // the crust is one thing or the other.
                    val band = 4.0 * mixture * (1.0 - mixture)
                    if (band > 0.0) {
                        mixture += marginRoughness * band *
                            noises[MARGIN].fbmOnSphere(
                                px, py, pz, radiusKm, MARGIN_WAVELENGTH_KM, MARGIN_OCTAVES, SphereNoise.EARTH_OUTLINE_PERSISTENCE
                            )
                    }
                    share[cell] = mixture.coerceIn(0.0, 1.0).toFloat()
                    thickness[cell] = if (continentWeight > 0.0) (thicknessSum / continentWeight).toFloat() else inputs.standardThicknessKm
                    excess[cell] = if (continentWeight > 0.0) (excessSum / continentWeight).toFloat() else 0f
                    floorAge[cell] = if (oceanWeight > 0.0) (floorAgeSum / oceanWeight).toFloat()
                    else inputs.pointFormedMyrAgo[heaviest]
                    crustAge[cell] = (crustAgeSum / weightSum).toFloat()
                    riftAge[cell] = if (riftWeight * 2 >= weightSum) (riftSum / riftWeight).toFloat() else -1f
                    kind[cell] = inputs.pointOrogenyKind[heaviest]
                    terrane[cell] = inputs.pointTerrane[heaviest]
                    var winner = 0
                    for (index in 1 until votes) {
                        if (voteWeight[index] > voteWeight[winner] ||
                            (voteWeight[index] == voteWeight[winner] && votePlate[index] < votePlate[winner])
                        ) winner = index
                    }
                    plate[cell] = votePlate[winner]
                }
            }
        }
        return CrustMapOutputs(share, thickness, excess, floorAge, crustAge, riftAge, kind, terrane, plate)
    }

    /**
     * Calls [visit] with every history point within [reachRadians] of the unit vector and its angle
     * from it, by the lattice's latitude bands and longitude bins: [FibonacciSphere.forEachWithin]'s
     * search, on the flattened arrays a device reads.
     */
    private inline fun forEachPointWithin(
        inputs: CrustMapInputs,
        unitX: Double,
        unitY: Double,
        unitZ: Double,
        reachRadians: Double,
        visit: (point: Int, angle: Double) -> Unit
    ) {
        val bandHeight = PI / inputs.bandCount
        val colatitude = kotlin.math.acos(unitZ.coerceIn(-1.0, 1.0))
        val firstBand = ((colatitude - reachRadians) / bandHeight).toInt().coerceIn(0, inputs.bandCount - 1)
        val lastBand = ((colatitude + reachRadians) / bandHeight).toInt().coerceIn(0, inputs.bandCount - 1)
        var longitude = kotlin.math.atan2(unitY, unitX)
        if (longitude < 0.0) longitude += 2.0 * PI
        val centerSine = sin(colatitude)
        val reachSine = sin(reachRadians)
        val wholeRing = colatitude - reachRadians <= 0.0 || colatitude + reachRadians >= PI || centerSine <= reachSine
        val halfWidth = if (wholeRing) PI else kotlin.math.asin(reachSine / centerSine)
        val minimumCosine = cos(reachRadians)
        for (band in firstBand..lastBand) {
            val bins = inputs.binsInBand[band]
            val first = inputs.firstBinOfBand[band]
            val binWidth = 2.0 * PI / bins
            val lowBin = kotlin.math.floor((longitude - halfWidth) / binWidth).toInt()
            val highBin = kotlin.math.floor((longitude + halfWidth) / binWidth).toInt()
            val span = if (wholeRing || bins <= 3 || highBin - lowBin + 1 >= bins) bins else highBin - lowBin + 1
            val start = if (span == bins) 0 else lowBin
            for (step in 0 until span) {
                val bin = first + (start + step).mod(bins)
                for (index in inputs.binStart[bin] until inputs.binStart[bin + 1]) {
                    val point = inputs.binPoints[index]
                    val cosine = inputs.pointXyz[3 * point] * unitX + inputs.pointXyz[3 * point + 1] * unitY +
                        inputs.pointXyz[3 * point + 2] * unitZ
                    if (cosine < minimumCosine) continue
                    visit(point, kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)))
                }
            }
        }
    }

    private fun nearestPoint(inputs: CrustMapInputs, unitX: Double, unitY: Double, unitZ: Double): Int {
        var best = 0
        var bestCosine = -2.0
        for (point in 0 until inputs.pointPlate.size) {
            val cosine = inputs.pointXyz[3 * point] * unitX + inputs.pointXyz[3 * point + 1] * unitY + inputs.pointXyz[3 * point + 2] * unitZ
            if (cosine > bestCosine) {
                bestCosine = cosine
                best = point
            }
        }
        return best
    }

    /**
     * The Gaussian's spread and how far it is read, in the history's spacings: the spread a little
     * over half a spacing, so a map cell between two points weighs both and one beside a point
     * still hears its neighbors; read to two and a half spreads, past which the weight is under
     * 0.2%.
     */
    const val KERNEL_SPREAD_SPACINGS = 0.6
    const val KERNEL_REACH_SPACINGS = 1.5

    /**
     * How far the reading place is moved, in the history's spacings, and the warp noise's longest
     * wavelength, in spacings: a third of a spacing over two, enough to break the line between two
     * points without moving a coast by more than the lattice resolves.
     */
    const val WARP_SPACINGS = 0.35
    const val WARP_WAVELENGTH_SPACINGS = 2.0
    const val WARP_OCTAVES = 3

    /**
     * The margin noise's longest wavelength on the ground, km, and its octaves: 500 km, the scale of
     * a coastal embayment, down six octaves to 15.6 km, as the stage's margin noise was before the
     * history (the coastline's box count over tens to hundreds of kilometers sits inside it), each
     * octave's amplitude [SphereNoise.EARTH_OUTLINE_PERSISTENCE] of the last, so the margin is as
     * rough at every scale as Earth's.
     */
    const val MARGIN_WAVELENGTH_KM = 500.0
    const val MARGIN_OCTAVES = 6

    /** More plates than ever lie within a kernel's reach of one map cell. */
    const val MAX_PLATES_NEAR = 8

    /** The noises the pass reads: the warp's three axes and the margin. */
    const val NOISES = 4
    const val WARP_X = 0
    const val WARP_Y = 1
    const val WARP_Z = 2
    const val MARGIN = 3
    private const val NOISE_STREAM = 7_727L
}
