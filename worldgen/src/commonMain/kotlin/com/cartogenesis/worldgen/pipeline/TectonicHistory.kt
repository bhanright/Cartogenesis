package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.SphereNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the tectonic history leaves on its own lattice: the present plates and their rotations, the
 * crust under every point and what has happened to it, the present boundaries classified by the
 * plates' relative motion, and the books of the continental crust.
 *
 * Every per-point array is one entry per [FibonacciSphere] point, read from the crust sample that
 * owns the point at the present. Plates are numbered 0 until [plateRotations]`.size`.
 */
internal class CrustHistory(
    val sphere: FibonacciSphere,
    val plateOf: IntArray,
    val continental: BooleanArray,
    val baseKm: FloatArray,
    val excessKm: FloatArray,
    val formedMyrAgo: FloatArray,
    val orogenyMyrAgo: FloatArray,
    val orogenyKind: IntArray,
    val terrane: IntArray,
    val riftMyrAgo: FloatArray,
    /** [BoundaryClass] ordinal of a boundary point, -1 elsewhere. */
    val boundaryClass: IntArray,
    /** At a boundary point, the plate across the boundary; -1 elsewhere. */
    val boundaryOtherPlate: IntArray,
    /** At a convergent boundary point, the plate that stays on top; -1 elsewhere. */
    val boundaryOverridingPlate: IntArray,
    /** At a boundary point, the plates' relative speed normal to it, mm a year, closing positive. */
    val boundaryClosingMmPerYear: FloatArray,
    /** Each plate's rotation, radians per Myr, as (x, y, z) triples. */
    val plateRotations: Array<DoubleArray>,
    val budget: CrustBudget,
    /** When the supercontinent broke up, Myr before the present, or NaN if it has not. */
    val breakupMyrAgo: Double,
    val historyMyr: Double,
    /** How many plates split during the history, and how many collisions welded a piece on. */
    val splits: Int,
    val sutures: Int,
    /** Every live plate's speed and make-up at the end of every step, for the guards that hold them to Earth's. */
    val plateSpeeds: PlateSpeedRecord,
    /**
     * Over the exposure window ([exposureWindowMyr], the history's last steps): what tectonics did to
     * the crust's thickness at each point, km, thickening positive; what the orogens' wear took, km;
     * when the window's first thickening began, Myr ago, -1 where none did; and the last step's
     * tectonic change over the step's length, km per Myr, the present thickening rate.
     */
    val windowTectonicKm: FloatArray,
    val windowWearKm: FloatArray,
    val windowOnsetMyrAgo: FloatArray,
    val presentThickeningKmPerMyr: FloatArray,
    val exposureWindowMyr: Double,
    /**
     * The orogenic excess's gradient tensor at each point, smoothed over a belt's width: the six
     * components of the symmetric 3 by 3 sum of `g g^T`, `g` the excess's gradient in the tangent
     * plane, km² per km², in the order xx, xy, xz, yy, yz, zz. Frame-free and sign-free, so it reads
     * onto the map by the same weighted mean as every other field; a belt's strike is square to the
     * tensor's leading direction.
     */
    val strikeTensor: Array<FloatArray>
)

/**
 * One row per live plate per step of the history: when, the plate's share of the sphere, the share
 * of it that is continental crust, the share of its edge where it goes down a trench, and its
 * root-mean-square speed in mm a year, the figures Zahirovic et al. (2015) sort Earth's plates by.
 */
internal class PlateSpeedRecord {
    val myrAgo = ArrayList<Float>()
    val areaShare = ArrayList<Float>()
    val continentalShare = ArrayList<Float>()
    val subductingEdgeShare = ArrayList<Float>()
    val rmsSpeedMmPerYear = ArrayList<Float>()

    val size: Int get() = myrAgo.size

    fun add(ago: Double, area: Double, continental: Double, subducting: Double, speed: Double) {
        myrAgo.add(ago.toFloat()); areaShare.add(area.toFloat()); continentalShare.add(continental.toFloat())
        subductingEdgeShare.add(subducting.toFloat()); rmsSpeedMmPerYear.add(speed.toFloat())
    }
}

/**
 * The crust made by a history rather than by whole plates: Part A of the tectonic-history design
 * (docs/DESIGN_LEDGER.md, TH1-1).
 *
 * A supercontinent of cratons welded along old sutures waits a while, breaks up along those
 * sutures, and its pieces are carried apart by rigid rotations on the sphere, with ocean floor made
 * in the gaps they open and consumed where a plate goes under another. Arcs grow on the overriding
 * plate; continents meet, thicken, slow each other and weld; plates split along paths through their
 * weakest ground. The crust is carried as samples ([CrustParticles]) moved by their plate's rotation
 * exactly, and read on a [FibonacciSphere] of [com.cartogenesis.worldgen.model.TectonicsConfig.historyCellKm]
 * each step to find which plate owns each place.
 *
 * Each step, in order:
 *  1. the supercontinent breaks, if this is the step;
 *  2. every plate takes the rotation its forces settle at ([PlateForces]);
 *  3. a plate that is too large, or a planet short of plates, splits where its forces pull it apart;
 *  4. every sample is carried by its plate's rotation;
 *  5. each lattice point is owned by the plates whose crust covers it; where two do, continental
 *     crust stays up over ocean floor, and of two ocean floors the younger;
 *  6. crust a converging plate has overridden goes down: ocean floor is recycled into the mantle,
 *     continental crust becomes thickness in the belt of the plate it went under;
 *  7. ground no plate covers is new ocean floor, its age exact from the ridge it was made at, or
 *     the stretched crust of a rifted margin where the margin has some to give;
 *  8. the boundaries are classified by the plates' relative motion (Bird 2003's 20 degrees for a
 *     transform), the forces for the next step booked on them, arcs grown behind the trenches,
 *     forearcs worn by subduction erosion, and margins stretched where a continent has just begun to
 *     part;
 *  9. thick crust flows and orogens wear down;
 * 10. a terrane, or a collision too slow to tell its plates apart, welds onto the plate it met.
 *
 * Determinism: every draw is a seeded stream or a hash taken in a fixed order, the parallel passes
 * write only their own index, and every sum that decides anything runs in index order.
 */
internal object TectonicHistory {

    /**
     * The history of [config]'s world. [latticeOrientation] turns the lattice it runs on (see
     * [FibonacciSphere]); the guards use it, and a world is always made on the lattice as drawn.
     */
    fun run(config: WorldGenConfig, trace: ((String) -> Unit)? = null, latticeOrientation: DoubleArray? = null): CrustHistory {
        var mark = kotlin.time.TimeSource.Monotonic.markNow()
        val simulation = Simulation(config, trace, latticeOrientation)
        trace?.invoke("lattice ms ${mark.elapsedNow().inWholeMilliseconds}")
        mark = kotlin.time.TimeSource.Monotonic.markNow()
        simulation.build()
        trace?.invoke("build ms ${mark.elapsedNow().inWholeMilliseconds}")
        mark = kotlin.time.TimeSource.Monotonic.markNow()
        simulation.runSteps()
        trace?.invoke("steps ms ${mark.elapsedNow().inWholeMilliseconds}")
        mark = kotlin.time.TimeSource.Monotonic.markNow()
        return simulation.present().also { trace?.invoke("present ms ${mark.elapsedNow().inWholeMilliseconds}") }
    }

    private class Simulation(
        private val config: WorldGenConfig,
        private val trace: ((String) -> Unit)?,
        latticeOrientation: DoubleArray?
    ) {
        private val tectonics = config.tectonics
        private val isostasy = config.isostasy
        private val radiusKm = config.scale.radiusMeters / 1_000.0
        val sphere = FibonacciSphere.forCellKm(radiusKm, tectonics.historyCellKm, latticeOrientation)
        private val cellCount = sphere.cellCount
        private val sampleAreaKm2 = sphere.cellAreaKm2
        private val spacingKm = sphere.spacingKm
        private val samples = CrustParticles(cellCount * 5 / 4)
        private val plates = ArrayList<HistoryPlate>()
        private val budget = CrustBudget()
        private val random = Random(config.seed * HISTORY_STREAM_MULTIPLIER + HISTORY_STREAM_OFFSET)

        private val stepMyr = tectonics.historyStepMyr
        private val historyMyr = tectonics.historyLengthMyr
        private val stepCount = max(1, (historyMyr / stepMyr).roundToInt())
        private val oceanicKm = isostasy.oceanicCrustThicknessKm
        private val continentalKm = isostasy.continentalCrustThicknessKm
        private val targetSpeedKmPerMyr = tectonics.medianPlateSpeedMmPerYear
        private val targetPlates = tectonics.plateCount(config.scale)
        private val orogenDecayMyr = tectonics.orogenDecayTimeYears / 1e6
        private val exposureWindowMyr = tectonics.exposureWindowMyr.coerceIn(0.0, historyMyr)

        /**
         * The ocean floor's slab buoyancy, drawn where the floor is made: a noise on the sphere
         * read at the place and time of birth, so floor made at one place on a ridge over many steps
         * carries one value in a stripe along the spreading, as a hotspot's track and the plateau
         * it builds do; its values sorted over the lattice once, so a draw maps to its rank and the
         * buoyancy is uniform over the floor ([slabBuoyancyAt]).
         */
        private val slabNoise = SphereNoise(config.seed * SHAPE_SALT + SLAB_NOISE_STREAM)
        private var slabNoiseRanks = DoubleArray(0)

        /** When the supercontinent breaks up, Myr before the present; at or below zero it never does. */
        private val breakupMyrAgo: Double
        private var brokenUp = false
        private var supercontinentCenter = DoubleArray(3)

        // Per point, rebuilt every step.
        private val owner = IntArray(cellCount)
        private val ownerSample = IntArray(cellCount)
        private var sampleCell = IntArray(samples.x.size)
        private var bucketStart = IntArray(cellCount + 1)
        private var bucketSamples = IntArray(0)
        private val boundaryClass = IntArray(cellCount)
        private val boundaryOther = IntArray(cellCount)
        private val boundaryOverriding = IntArray(cellCount)
        private val boundaryClosing = FloatArray(cellCount)
        private val forces = PlateForces(cellCount)
        private var removed = BooleanArray(samples.x.size)
        private var splitCount = 0
        private var sutureCount = 0
        private var stepAgoEnd = historyMyr

        init {
            // The phase of the supercontinent cycle the present falls in: assembled for
            // [assembledShareOfCycle] of the cycle, so the breakup is drawn uniformly over a span
            // reaching that far before the history's start, and a draw at or below zero is a
            // supercontinent that has not yet broken.
            val assembled = tectonics.assembledShareOfCycle.coerceIn(0.0, 0.95)
            val reachBefore = historyMyr * assembled / (1.0 - assembled)
            breakupMyrAgo = -reachBefore + random.nextDouble() * (historyMyr + reachBefore)
        }

        // ------------------------------------------------------------------------------- building

        fun build() {
            val buildMark = kotlin.time.TimeSource.Monotonic.markNow()
            val continentalShare = targetContinentalShare()
            val microShare = MICROCONTINENT_SHARE_OF_CRUST * continentalShare
            val blockOf = IntArray(cellCount) { -1 }
            val superCells = supercontinentCells((continentalShare - microShare).coerceAtLeast(0.01), blockOf)
            val cratonOf = cratonDomains(superCells, blockOf)
            val sutureAgeMyr = sutureAges()
            val sutureDistanceKm = sutureDistances(superCells, cratonOf)
            val micro = microcontinentCells(superCells, microShare)
            trace?.invoke("build fields ms ${buildMark.elapsedNow().inWholeMilliseconds}")

            slabNoiseRanks = DoubleArray(cellCount) { slabNoiseAt(sphere.x[it], sphere.y[it], sphere.z[it]) }.also { it.sort() }

            // Every point starts as one sample of crust at the point itself.
            for (cell in 0 until cellCount) {
                if (superCells[cell]) {
                    val craton = cratonOf[cell]
                    val (distanceKm, pair) = sutureDistanceKm[cell]
                    var excess = 0f
                    var orogeny = Float.NaN
                    var kind = OrogenyKind.NONE
                    if (pair >= 0 && distanceKm < SUTURE_REACH_BELT_WIDTHS * BELT_EFOLD_KM) {
                        val age = sutureAgeMyr[pair % sutureAgeMyr.size]
                        excess = (TIBET_EXCESS_KM * exp(-(age - historyMyr) / orogenDecayMyr) *
                            exp(-distanceKm / BELT_EFOLD_KM)).toFloat()
                        orogeny = age.toFloat()
                        kind = OrogenyKind.ASSEMBLY_SUTURE
                    }
                    val formed = (CRATON_OLDEST_MYR - CRATON_AGE_SPREAD_MYR * hash01(config.seed xor CRATON_AGE_SALT, craton.toLong())).toFloat()
                    samples.add(sphere.x[cell], sphere.y[cell], sphere.z[cell], 0, true, continentalKm, excess,
                        formed, orogeny, kind, craton, Float.NaN)
                } else if (micro[cell] >= 0) {
                    val formed = (CRATON_OLDEST_MYR - CRATON_AGE_SPREAD_MYR * hash01(config.seed xor MICRO_AGE_SALT, micro[cell].toLong())).toFloat()
                    samples.add(sphere.x[cell], sphere.y[cell], sphere.z[cell], 1, true, continentalKm, 0f,
                        formed, Float.NaN, OrogenyKind.NONE, MICROCONTINENT_TERRANE_BASE + micro[cell], Float.NaN)
                } else {
                    val floor = samples.add(sphere.x[cell], sphere.y[cell], sphere.z[cell], 1, false, oceanicKm, 0f,
                        historyMyr.toFloat(), Float.NaN, OrogenyKind.NONE, -1, Float.NaN)
                    samples.slabBuoyancy[floor] = slabBuoyancyAt(sphere.x[cell], sphere.y[cell], sphere.z[cell])
                }
            }
            plates.add(HistoryPlate(0).also { it.assembled = true })
            plates.add(HistoryPlate(1))
            ensureCapacity()
            for (sample in 0 until samples.count) sampleCell[sample] = sample
            rebuildBuckets()
            for (cell in 0 until cellCount) {
                owner[cell] = samples.plate[cell]
                ownerSample[cell] = cell
            }

            // The plates of the assembled world: the supercontinent cut along its sutures into its
            // share of the planet's plates, as Pangea was carried on several, and the ocean round it
            // cut into the rest, each split along its weakest path.
            val supercontinentPlates = max(2, (targetPlates * continentalShare).roundToInt())
            while (plates.count { it.alive && it.assembled } < supercontinentPlates) {
                val largest = largestPlate { plates[it].assembled } ?: break
                if (!splitAlongWeakestPath(largest)) break
            }
            while (livePlateCount() < targetPlates) {
                val largest = largestPlate { !plates[it].assembled } ?: break
                if (!splitAlongWeakestPath(largest)) break
            }
            trace?.invoke("build splits ms ${buildMark.elapsedNow().inWholeMilliseconds}")
            initialOceanAges()
            initialMotions()
            trace?.invoke("built plates=${livePlateCount()} target=$targetPlates breakup=$breakupMyrAgo cells=$cellCount shares=${shareSummary()}")

            budget.startingAreaKm2 = continentalAreaKm2()
            budget.startingVolumeKm3 = continentalVolumeKm3()
        }

        private fun slabNoiseAt(x: Double, y: Double, z: Double): Double =
            slabNoise.fbmOnSphere(x, y, z, radiusKm, SLAB_FEATURE_WAVELENGTH_KM, SLAB_FEATURE_OCTAVES)

        /** The slab buoyancy of floor made at the unit vector given: its noise's rank among the lattice's, 0 to 1. */
        private fun slabBuoyancyAt(x: Double, y: Double, z: Double): Float {
            val value = slabNoiseAt(x, y, z)
            var low = 0
            var high = slabNoiseRanks.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (slabNoiseRanks[middle] < value) low = middle + 1 else high = middle
            }
            return (low.toDouble() / slabNoiseRanks.size).toFloat()
        }

        /** The share of the sphere that is continental crust for the sea to cover what the settings ask. */
        private fun targetContinentalShare(): Double {
            val landShare = (1.0 - config.seaLevel).coerceIn(0.0, 1.0)
            val dryShare = (1.0 - tectonics.continentalCrustSubmergedShare).coerceIn(0.05, 1.0)
            return (landShare / dryShare).coerceIn(0.02, 0.9)
        }

        /**
         * The supercontinent, assembled from blocks: continents gathered from the cycle before, each
         * a cap of [CONTINENT_BLOCK_RADIUS_KM], docked one at a time against a block already in place
         * at a drawn bearing, overlapping it by a suture's sliver and no other further, so the assembly
         * grows as Pangea's did, by collisions at its edge, into an irregular, lobed and embayed whole
         * rather than one round cap.
         *
         * A cap as large as the supercontinent bounds itself by a circle seventy to ninety degrees
         * across, which turns too slowly to read as anything but straight over a continent's side,
         * and a hemisphere's circle is two meridians on the map; a cap a craton's size bends faster
         * than Earth's edge does and strings the outline into beads. A block's radius is where its
         * own curvature puts the edge as far from its chord as Earth's edge stands over the longest
         * stretch it was read on (see [CONTINENT_BLOCK_RADIUS_KM]), about thirty degrees on Earth's
         * planet: six such blocks make Earth's continental crust, as six continents do.
         *
         * Inside a block's own width the outline wanders as Earth's continental edge does: over a
         * stretch of any length from 250 to 2,000 km, ETOPO5's 2,000 m isobath stands
         * [EARTH_EDGE_WANDER_PER_LENGTH] of the stretch's length from its chord, root mean square, and
         * its octaves fall as Earth's edge's divider dimension says
         * ([SphereNoise.EARTH_OUTLINE_PERSISTENCE]); so the noise's longest wavelength is a block's
         * diameter and its amplitude moves the edge that share of it. The points within the wandering
         * union are taken to the share asked for exactly.
         *
         * Each point's block, written to [blockOf], is the earliest block docked that covers it: a
         * block docks against the margin of the assembly already there, so its suture is that
         * margin, the arc of the block it docked against as it wanders, and not a line midway
         * between the two blocks' centers, which is a bisector (docs/CONVENTIONS.md, rule 13).
         */
        private fun supercontinentCells(share: Double, blockOf: IntArray): BooleanArray {
            val blockRadians = CONTINENT_BLOCK_RADIUS_KM / radiusKm
            val blockShare = (1.0 - kotlin.math.cos(blockRadians)) / 2.0
            val blockCount = max(1, (share / blockShare).roundToInt())
            val centers = assembleBlocks(blockCount, blockRadians)
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (center in centers) { cx += center[0]; cy += center[1]; cz += center[2] }
            val length = sqrt(cx * cx + cy * cy + cz * cz)
            supercontinentCenter = if (length > 1e-9) doubleArrayOf(cx / length, cy / length, cz / length) else centers[0]
            val noise = SphereNoise(config.seed * SHAPE_SALT + 1)
            val wavelengthKm = 2.0 * blockRadians * radiusKm
            val octaves = octavesToSpacing(wavelengthKm)
            val warp = DoubleArray(cellCount) { cell ->
                noise.fbmOnSphere(
                    sphere.x[cell], sphere.y[cell], sphere.z[cell], radiusKm, wavelengthKm, octaves,
                    SphereNoise.EARTH_OUTLINE_PERSISTENCE
                )
            }
            val mean = warp.average()
            val spread = sqrt(warp.sumOf { (it - mean) * (it - mean) } / warp.size).coerceAtLeast(1e-9)
            val amplitudeRadians = EARTH_EDGE_WANDER_PER_LENGTH * wavelengthKm / radiusKm / spread
            val reach = DoubleArray(cellCount)
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    var nearest = -1.0
                    for (center in centers) {
                        val cosine = sphere.x[cell] * center[0] + sphere.y[cell] * center[1] + sphere.z[cell] * center[2]
                        if (cosine > nearest) nearest = cosine
                    }
                    reach[cell] = blockRadians - acos(nearest.coerceIn(-1.0, 1.0)) + amplitudeRadians * warp[cell]
                }
            }
            val chosen = topShare(reach, share)
            var threshold = Double.MAX_VALUE
            for (cell in 0 until cellCount) if (chosen[cell]) threshold = min(threshold, reach[cell])
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    if (!chosen[cell]) continue
                    for (block in centers.indices) {
                        val center = centers[block]
                        val cosine = sphere.x[cell] * center[0] + sphere.y[cell] * center[1] + sphere.z[cell] * center[2]
                        if (blockRadians - acos(cosine.coerceIn(-1.0, 1.0)) + amplitudeRadians * warp[cell] >= threshold) {
                            blockOf[cell] = block
                            break
                        }
                    }
                }
            }
            return chosen
        }

        /**
         * The blocks' centers, docked one at a time inside the hemisphere the assembly gathers in.
         * Mantle convection with continents absent settles into one hemisphere of downwelling and one
         * of upwelling, and the continents converge into the downwelling one to make a supercontinent
         * (Zhong, Zhang, Li and Roberts, *Supercontinent cycles, true polar wander, and very
         * long-wavelength mantle convection*, EPSL 261, 2007); the first block stands at that
         * hemisphere's pole. Each later block docks against a block already placed, drawn evenly, at a
         * drawn bearing, its center two block radii less [BLOCK_OVERLAP_SHARE] of a diameter away so
         * the two weld along a suture: the first of [BLOCK_DOCKING_TRIES] such dockings that lies
         * wholly inside the hemisphere and comes no nearer any other block is taken, so the assembly
         * grows at whatever free margin a block reaches, as an aggregate of collisions does, and not
         * toward its own center, which rounds it into one cap again. Failing one, the first free
         * docking anywhere, and failing that the one that comes least near the others.
         */
        private fun assembleBlocks(blockCount: Int, blockRadians: Double): List<DoubleArray> {
            val centers = ArrayList<DoubleArray>()
            val gathering = randomUnit(random)
            centers.add(gathering)
            val docking = 2.0 * blockRadians * (1.0 - BLOCK_OVERLAP_SHARE)
            val insideHemisphere = sin(blockRadians)
            while (centers.size < blockCount) {
                var inside: DoubleArray? = null
                var free: DoubleArray? = null
                var crowded: DoubleArray? = null
                var crowdedClearance = Double.NEGATIVE_INFINITY
                for (attempt in 0 until BLOCK_DOCKING_TRIES) {
                    val anchor = centers[random.nextInt(centers.size)]
                    val candidate = stepFrom(anchor, randomTangent(anchor), docking)
                    var clearance = Double.POSITIVE_INFINITY
                    for (other in centers) {
                        if (other === anchor) continue
                        val cosine = candidate[0] * other[0] + candidate[1] * other[1] + candidate[2] * other[2]
                        clearance = min(clearance, acos(cosine.coerceIn(-1.0, 1.0)) - docking)
                    }
                    if (clearance >= -DOCKING_TOLERANCE_RADIANS) {
                        val height = candidate[0] * gathering[0] + candidate[1] * gathering[1] + candidate[2] * gathering[2]
                        if (height >= insideHemisphere) {
                            inside = candidate
                            break
                        }
                        if (free == null) free = candidate
                    } else if (clearance > crowdedClearance) {
                        crowdedClearance = clearance
                        crowded = candidate
                    }
                }
                centers.add(inside ?: free ?: crowded!!)
            }
            return centers
        }

        /** A unit tangent at [point] at a drawn bearing. */
        private fun randomTangent(point: DoubleArray): DoubleArray {
            val other = randomUnit(random)
            val along = other[0] * point[0] + other[1] * point[1] + other[2] * point[2]
            var tx = other[0] - along * point[0]
            var ty = other[1] - along * point[1]
            var tz = other[2] - along * point[2]
            val length = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-12)
            tx /= length; ty /= length; tz /= length
            return doubleArrayOf(tx, ty, tz)
        }

        /** The point [angleRadians] along the great circle leaving [point] along the unit tangent [tangent]. */
        private fun stepFrom(point: DoubleArray, tangent: DoubleArray, angleRadians: Double): DoubleArray {
            val c = kotlin.math.cos(angleRadians)
            val s = sin(angleRadians)
            return doubleArrayOf(c * point[0] + s * tangent[0], c * point[1] + s * tangent[1], c * point[2] + s * tangent[2])
        }

        /** How many cratons the planet's continents were assembled from: Earth's count, by area. */
        private fun cratonCount(): Int = max(2, (CRATONS_ON_EARTH * config.scale.sphereAreaKm2 / EARTH_SURFACE_KM2).roundToInt())

        /**
         * Each supercontinent point's craton: the seed it is nearest along a path whose every step
         * costs its length times a noise on the sphere, so the domains meet along wandering sutures
         * rather than along the straight bisectors a nearest-seed partition draws (docs/CONVENTIONS.md,
         * rule 13); and a craton the docking of two blocks cut is two, one on each side of the
         * docking suture. Numbered block by block, so a label less than the planet's craton count
         * times its block count tells both apart.
         */
        private fun cratonDomains(superCells: BooleanArray, blockOf: IntArray): IntArray {
            val cratonCount = cratonCount()
            val candidates = (0 until cellCount).filter { superCells[it] }
            val seeds = ArrayList<Int>()
            val separation = sqrt(candidates.size * sampleAreaKm2 / cratonCount) * CRATON_SEED_SEPARATION_SHARE
            var tries = 0
            while (seeds.size < cratonCount && tries < cratonCount * 400) {
                tries++
                val cell = candidates[random.nextInt(candidates.size)]
                if (seeds.any { sphere.distanceKm(it, cell) < separation }) continue
                seeds.add(cell)
            }
            val noise = SphereNoise(config.seed * SHAPE_SALT + 2)
            val roughness = DoubleArray(cellCount) { cell ->
                1.0 + CRATON_METRIC_NOISE * noise.fbmOnSphere(
                    sphere.x[cell], sphere.y[cell], sphere.z[cell], radiusKm, CRATON_NOISE_WAVELENGTH_KM,
                    octavesToSpacing(CRATON_NOISE_WAVELENGTH_KM), SphereNoise.EARTH_OUTLINE_PERSISTENCE
                )
            }
            val cost = DoubleArray(cellCount) { Double.MAX_VALUE }
            val craton = IntArray(cellCount) { -1 }
            val heap = LongMinHeap(cellCount)
            seeds.forEachIndexed { index, cell ->
                cost[cell] = 0.0
                craton[cell] = index
                heap.push(floodKey(0.0, cell))
            }
            while (!heap.isEmpty()) {
                val key = heap.pop()
                val cell = (key and 0xFFFFFFFFL).toInt()
                val here = Float.fromBits((key ushr 32).toInt()).toDouble()
                if (here > cost[cell] + 1e-6) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (!superCells[next]) continue
                    val step = sphere.distanceKm(cell, next) * 0.5 * (roughness[cell] + roughness[next]).coerceAtLeast(0.1)
                    if (here + step < cost[next]) {
                        cost[next] = here + step
                        craton[next] = craton[cell]
                        heap.push(floodKey(cost[next], next))
                    }
                }
            }
            for (cell in 0 until cellCount) {
                if (craton[cell] >= 0 && blockOf[cell] >= 0) craton[cell] += blockOf[cell] * cratonCount
            }
            return craton
        }

        /** When each pair of cratons welded, Myr before the present, by the pair's place in a hashed list. */
        private fun sutureAges(): DoubleArray = DoubleArray(SUTURE_AGE_TABLE) {
            historyMyr + SUTURE_AGE_SPAN_MYR * hash01(config.seed xor SUTURE_AGE_SALT, it.toLong())
        }

        /** How far each supercontinent point is from the nearest suture, km, and that suture's pair key. */
        private fun sutureDistances(superCells: BooleanArray, cratonOf: IntArray): Array<Pair<Double, Int>> {
            val sutureCells = ArrayList<Int>()
            val pairOf = IntArray(cellCount) { -1 }
            val labels = (cratonOf.maxOrNull() ?: 0) + 1
            for (cell in 0 until cellCount) {
                if (!superCells[cell]) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (!superCells[next] || cratonOf[next] == cratonOf[cell] || cratonOf[next] < 0) continue
                    val low = min(cratonOf[cell], cratonOf[next])
                    val high = max(cratonOf[cell], cratonOf[next])
                    pairOf[cell] = low * labels + high
                    sutureCells.add(cell)
                    break
                }
            }
            if (sutureCells.isEmpty()) return Array(cellCount) { Double.MAX_VALUE to -1 }
            val (distanceKm, source) = sphere.distanceFlood(sutureCells.toIntArray(), superCells)
            return Array(cellCount) { cell ->
                if (source[cell] < 0) Double.MAX_VALUE to -1 else distanceKm[cell] to pairOf[source[cell]]
            }
        }

        /** A few slivers of continent out in the ocean, the terranes of the future, each its own number. */
        private fun microcontinentCells(superCells: BooleanArray, share: Double): IntArray {
            val micro = IntArray(cellCount) { -1 }
            val count = max(1, (MICROCONTINENTS_ON_EARTH * config.scale.sphereAreaKm2 / EARTH_SURFACE_KM2).roundToInt())
            val cellsEach = (share * cellCount / count).roundToInt()
            if (cellsEach <= 0) return micro
            val noise = SphereNoise(config.seed * SHAPE_SALT + 3)
            var placed = 0
            var tries = 0
            while (placed < count && tries < count * 50) {
                tries++
                val center = randomUnit(random)
                val nearestCell = sphere.nearest(center[0], center[1], center[2])
                if (superCells[nearestCell] || micro[nearestCell] >= 0) continue
                val radius = sqrt(cellsEach * sampleAreaKm2 / PI) / radiusKm
                // The axis lies in the ground at the sliver's center, so the sliver is an ellipse about
                // that center: an axis drawn anywhere on the sphere put the shortest crossing off the
                // center and cut the sliver along a great circle, a straight side.
                val axis = randomTangent(center)
                // Long across its axis and short along it, as a rifted sliver is.
                val scored = ArrayList<Long>()
                sphere.forEachWithin(center[0], center[1], center[2], radius * MICROCONTINENT_SEARCH_RADII) { cell, cosine ->
                    if (superCells[cell] || micro[cell] >= 0) return@forEachWithin
                    val across = acos(cosine.coerceIn(-1.0, 1.0)) / radius
                    val along = MICROCONTINENT_ELONGATION * (sphere.x[cell] * axis[0] + sphere.y[cell] * axis[1] + sphere.z[cell] * axis[2]) / radius
                    val score = sqrt(across * across + along * along) +
                        MICROCONTINENT_WARP * noise.fbmOnSphere(
                            sphere.x[cell], sphere.y[cell], sphere.z[cell], radiusKm, MICROCONTINENT_WARP_WAVELENGTH_KM,
                            octavesToSpacing(MICROCONTINENT_WARP_WAVELENGTH_KM), SphereNoise.EARTH_OUTLINE_PERSISTENCE
                        )
                    scored.add((score.toFloat().toRawBits().toLong() shl 32) or cell.toLong())
                }
                scored.sortWith(compareBy({ Float.fromBits((it ushr 32).toInt()) }, { it and 0xFFFFFFFFL }))
                for (index in 0 until min(cellsEach, scored.size)) micro[(scored[index] and 0xFFFFFFFFL).toInt()] = placed
                placed++
            }
            return micro
        }

        /**
         * The ocean floor's age at the start: its distance from the ridges between the ocean's plates
         * over the spreading rate a median plate's speed makes, as old as Earth's oldest floor at most,
         * because floor older than that has gone down a trench (Müller et al. 2008, as
         * `IsostasyConfig.oldestSeafloorAgeMyr` cites them).
         */
        private fun initialOceanAges() {
            val ridgeCells = ArrayList<Int>()
            val ocean = BooleanArray(cellCount) { !samples.continental[ownerSample[it]] }
            for (cell in 0 until cellCount) {
                if (!ocean[cell]) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (ocean[next] && owner[next] != owner[cell]) {
                        ridgeCells.add(cell)
                        break
                    }
                }
            }
            val halfRateKmPerMyr = targetSpeedKmPerMyr / 2.0
            val (distanceKm, _) = if (ridgeCells.isEmpty()) DoubleArray(cellCount) { Double.MAX_VALUE } to IntArray(0)
            else sphere.distanceFlood(ridgeCells.toIntArray(), ocean)
            val oldest = isostasy.oldestSeafloorAgeMyr.toDouble()
            for (cell in 0 until cellCount) {
                val sample = ownerSample[cell]
                if (samples.continental[sample]) continue
                val age = if (distanceKm[cell] == Double.MAX_VALUE) oldest else min(oldest, distanceKm[cell] / halfRateKmPerMyr)
                samples.formedMyrAgo[sample] = (historyMyr + age).toFloat()
            }
        }

        /**
         * The plates' motions at the start: the ocean's plates moving toward the supercontinent at a
         * median plate's speed, which is the ring of subduction an assembled supercontinent stands in;
         * the supercontinent itself still, until its trenches' pull is booked after the first step.
         */
        private fun initialMotions() {
            val center = supercontinentCenter
            for (plate in plates) {
                if (!plate.alive || plate.assembled) continue
                val centroid = plateCentroid(plate.id) ?: continue
                var ax = centroid[1] * center[2] - centroid[2] * center[1]
                var ay = centroid[2] * center[0] - centroid[0] * center[2]
                var az = centroid[0] * center[1] - centroid[1] * center[0]
                val length = sqrt(ax * ax + ay * ay + az * az)
                if (length < 1e-9) continue
                val rate = targetSpeedKmPerMyr / radiusKm / length
                ax *= rate; ay *= rate; az *= rate
                plate.setOmega(ax, ay, az)
            }
        }

        // -------------------------------------------------------------------------------- stepping

        fun runSteps() {
            val phaseNanos = LongArray(PHASES)
            var mark = kotlin.time.TimeSource.Monotonic.markNow()
            fun lap(phase: Int) {
                phaseNanos[phase] += mark.elapsedNow().inWholeNanoseconds
                mark = kotlin.time.TimeSource.Monotonic.markNow()
            }
            for (step in 0 until stepCount) {
                val agoStart = historyMyr - step * stepMyr
                val agoEnd = agoStart - stepMyr
                stepAgoEnd = agoEnd
                openStepBooks()
                if (!brokenUp && breakupMyrAgo > 0.0 && breakupMyrAgo >= agoEnd) breakUp()
                if (brokenUp && step > 0) releaseRiftingPlates()
                if (step > 0) settleMotions()
                lap(0)
                maintainPlates(agoEnd)
                lap(1)
                carry()
                assignOwners()
                lap(2)
                val collisions = consumeOverriddenCrust()
                fillGaps(agoEnd)
                rebuildBuckets()
                lap(3)
                classifyBoundaries(agoEnd, withEffects = true)
                recordSpeeds(agoEnd)
                lap(4)
                distributeCollisions(collisions, agoEnd)
                flowAndWear()
                closeStepBooks(agoStart, agoEnd)
                lap(5)
                weldLockedCollisions(collisions)
                compact()
                lap(6)
                trace?.invoke(
                    "step $step ago=$agoEnd live=${plates.count { it.alive }} samples=${samples.count} " +
                        "continentKm2=${continentalAreaKm2().roundToInt()} collisions=${collisions.size} splits=$splitCount sutures=$sutureCount" +
                        (if (step % 5 == 4) " shares=${shareSummary()}" else "")
                )
            }
            trace?.invoke("phases ms: " + phaseNanos.joinToString(" ") { (it / 1_000_000).toString() })
        }

        /** Every sample's thickness as the step opens, and its wear so far nothing, for [closeStepBooks]. */
        private fun openStepBooks() {
            for (sample in 0 until samples.count) {
                samples.stepStartKm[sample] = samples.thicknessKm(sample)
                samples.stepWearKm[sample] = 0f
            }
        }

        /**
         * What the step from [agoStart] to [agoEnd] did to each sample: its change in thickness less
         * the wear, which is tectonics' share, booked into the exposure window when the step lies
         * inside it, the window's onset where it is the first to thicken the sample there; and the
         * step's own change, which the last step leaves as the present rate's numerator. A sample
         * made during the step counts from what it was made with.
         */
        private fun closeStepBooks(agoStart: Double, agoEnd: Double) {
            val inWindow = agoEnd < exposureWindowMyr - WINDOW_ROUNDING_MYR
            for (sample in 0 until samples.count) {
                if (removed[sample]) continue
                val change = samples.thicknessKm(sample) - samples.stepStartKm[sample] + samples.stepWearKm[sample]
                samples.lastStepTectonicKm[sample] = change
                if (!inWindow) continue
                samples.windowTectonicKm[sample] += change
                samples.windowWearKm[sample] += samples.stepWearKm[sample]
                if (change > OROGENY_MARK_KM && samples.windowOnsetMyrAgo[sample].isNaN()) {
                    samples.windowOnsetMyrAgo[sample] = minOf(agoStart, exposureWindowMyr).toFloat()
                }
            }
        }

        /** The supercontinent may begin to come apart: from now on its forces decide where it rifts. */
        private fun breakUp() {
            brokenUp = true
        }

        /**
         * The supercontinent comes apart a plate at a time, where its own forces pull a plate off
         * it: of the plates still welded into it, the one whose force-balanced rotation carries it
         * away from the rest fastest is released, if it is carried away at all. The plates of the
         * supercontinent meet along its sutures, its weakest ground (Vauchez, Barruol and Tommasi
         * 1997), so any extension across one opens it; one a step, so the breakup is a sequence of
         * rifts over tens of millions of years, as Pangea's was (the central Atlantic first, then
         * the south Atlantic and the Indian Ocean's rifts, then Australia from Antarctica), rather
         * than one moment in which every piece leaves at once. A plate the forces press against the
         * rest is held in.
         */
        private fun releaseRiftingPlates() {
            val members = plates.filter { it.alive && it.assembled }
            if (members.size < 2) {
                members.forEach { it.assembled = false }
                return
            }
            val balances = forceBalances()
            val raw = Array(plates.size) { DoubleArray(3) }
            for (plate in plates) if (plate.alive) raw[plate.id] = balances[plate.id].solve()
            val scale = speedScale(raw) ?: return
            var released: HistoryPlate? = null
            var fastest = 0.0
            for (member in members) {
                val rest = TorqueBalance()
                for (other in members) if (other !== member) rest.add(balances[other.id])
                val restRotation = rest.solve().map { it * scale }.toDoubleArray()
                val own = raw[member.id].map { it * scale }.toDoubleArray()
                val sides = IntArray(cellCount) { cell ->
                    val plate = owner[cell]
                    when {
                        plate == member.id -> 0
                        plate >= 0 && plates[plate].assembled -> 1
                        else -> -1
                    }
                }
                val opening = openingAcross(sides, own, restRotation)
                if (opening > fastest) {
                    fastest = opening
                    released = member
                }
            }
            val plate = released ?: return
            plate.assembled = false
            trace?.invoke("  released plate ${plate.id} from the supercontinent, opening ${"%.1f".format(fastest)} km/Myr")
            if (members.size == 2) members.forEach { it.assembled = false }
        }

        /**
         * The slab's resistance at one trench point, in points of basal drag: the base area that drags
         * as much as one spacing of trench resists, `slabResistanceKm` times a spacing, over a point's
         * area.
         */
        private val slabResistancePerPoint = tectonics.slabResistanceKm / spacingKm

        /**
         * Each plate's torque balance: the drag on its base, one for every point whatever its crust,
         * the resistance of the slabs it sinks into at its trenches, and the forces booked on it the
         * last step (see [PlateForces]).
         */
        private fun forceBalances(): Array<TorqueBalance> {
            val balances = Array(plates.size) { TorqueBalance() }
            for (cell in 0 until cellCount) {
                val plate = owner[cell]
                if (plate < 0) continue
                balances[plate].addDrag(sphere.x[cell], sphere.y[cell], sphere.z[cell], 1.0)
            }
            for (event in 0 until forces.count) {
                val cell = forces.point[event]
                balances[forces.plate[event]].addForce(
                    sphere.x[cell], sphere.y[cell], sphere.z[cell], forces.forceX[event], forces.forceY[event], forces.forceZ[event]
                )
            }
            val resistance = slabResistancePerPoint
            for (event in 0 until forces.resistCount) {
                val cell = forces.resistPoint[event]
                balances[forces.resistPlate[event]].addLineDrag(
                    sphere.x[cell], sphere.y[cell], sphere.z[cell], forces.resistX[event], forces.resistY[event], forces.resistZ[event], resistance
                )
            }
            return balances
        }

        /**
         * Every plate's rotation from the forces booked on the last step, scaled together so the
         * median plate's root-mean-square speed is Earth's, and approached over one step so a boundary
         * that changes class between steps does not make a plate jitter. The assembled supercontinent's
         * plates take one rotation from the forces on all of them together.
         */
        private fun settleMotions() {
            val balances = forceBalances()
            val raw = Array(plates.size) { DoubleArray(3) }
            val assembledBalance = TorqueBalance()
            var anyAssembled = false
            for (plate in plates) {
                if (!plate.alive) continue
                if (plate.assembled) {
                    assembledBalance.add(balances[plate.id])
                    anyAssembled = true
                } else {
                    raw[plate.id] = balances[plate.id].solve()
                }
            }
            if (anyAssembled) {
                val together = assembledBalance.solve()
                for (plate in plates) if (plate.alive && plate.assembled) raw[plate.id] = together
            }
            val scale = speedScale(raw) ?: return
            val keep = exp(-1.0)
            for (plate in plates) {
                if (!plate.alive) continue
                val target = raw[plate.id]
                plate.setOmega(
                    plate.omegaX * keep + scale * target[0] * (1 - keep),
                    plate.omegaY * keep + scale * target[1] * (1 - keep),
                    plate.omegaZ * keep + scale * target[2] * (1 - keep)
                )
            }
        }

        /**
         * The factor that brings the plates' speeds under [rotations] to Earth's: the median
         * root-mean-square speed of the oceanic plates a slab pulls to Zahirovic et al.'s 85 mm a
         * year for such plates, or, while there are none, the median of every plate to their 40.
         * The other plates' speeds are what the forces make of them, and the cratonic plates' come
         * out near their 28 when the forces' ratios are right (`TectonicHistoryTest`). Null where no
         * plate moves at all.
         */
        private fun speedScale(rotations: Array<DoubleArray>): Double? {
            val squares = DoubleArray(plates.size)
            val counts = IntArray(plates.size)
            val continent = IntArray(plates.size)
            for (cell in 0 until cellCount) {
                val plate = owner[cell]
                if (plate < 0) continue
                val omega = rotations[plate]
                val along = omega[0] * sphere.x[cell] + omega[1] * sphere.y[cell] + omega[2] * sphere.z[cell]
                squares[plate] += omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2] - along * along
                counts[plate]++
                if (samples.continental[ownerSample[cell]]) continent[plate]++
            }
            fun medianOf(speeds: List<Double>): Double? {
                if (speeds.isEmpty()) return null
                val sorted = speeds.sorted()
                return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else 0.5 * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
            }
            val moving = (0 until plates.size).filter { counts[it] >= MIN_PLATE_CELLS }
            fun speedOf(plate: Int) = sqrt(max(0.0, squares[plate] / counts[plate])) * radiusKm
            // Before the first boundaries are read, and for a plate made since, no slab has pulled.
            val pulled = moving.filter {
                val slab = slabPulledPoints.getOrElse(it) { 0 }
                slab > 0 && slab > SUBDUCTION_BOUNDED_SHARE * edgePoints.getOrElse(it) { 0 } &&
                    continent[it] < CRATONIC_SHARE * counts[it]
            }
                .map { speedOf(it) }.filter { it > 0.0 }
            medianOf(pulled)?.let { return tectonics.slabPulledPlateSpeedMmPerYear / it }
            val all = moving.map { speedOf(it) }.filter { it > 0.0 }
            return medianOf(all)?.let { targetSpeedKmPerMyr / it }
        }

        private val speedRecord = PlateSpeedRecord()

        /** Each live plate's speed and make-up as the boundaries were just read, into [speedRecord]. */
        private fun recordSpeeds(agoEnd: Double) {
            val squares = DoubleArray(plates.size)
            val counts = IntArray(plates.size)
            val continent = IntArray(plates.size)
            for (cell in 0 until cellCount) {
                val plate = owner[cell]
                if (plate < 0) continue
                val record = plates[plate]
                val along = record.omegaX * sphere.x[cell] + record.omegaY * sphere.y[cell] + record.omegaZ * sphere.z[cell]
                squares[plate] += record.omegaX * record.omegaX + record.omegaY * record.omegaY + record.omegaZ * record.omegaZ - along * along
                counts[plate]++
                if (samples.continental[ownerSample[cell]]) continent[plate]++
            }
            for (plate in 0 until plates.size) {
                if (!plates[plate].alive || counts[plate] < MIN_PLATE_CELLS) continue
                val edge = edgePoints.getOrElse(plate) { 0 }
                speedRecord.add(
                    agoEnd, counts[plate].toDouble() / cellCount, continent[plate].toDouble() / counts[plate],
                    if (edge > 0) slabPulledPoints.getOrElse(plate) { 0 }.toDouble() / edge else 0.0,
                    sqrt(squares[plate] / counts[plate]) * radiusKm
                )
            }
        }

        /** How many points of each plate's edge a slab pulled at, and how many points its edge has, as the last boundaries were read. */
        private var slabPulledPoints = IntArray(0)
        private var edgePoints = IntArray(0)

        /** Every sample carried one step by its plate's rotation. */
        private fun carry() {
            val rotations = plates.map { Rotation.of(it, stepMyr) }
            val count = samples.count
            parallelChunks(0, count) { from, to ->
                for (sample in from until to) {
                    val rotation = rotations[samples.plate[sample]]
                    val px = samples.x[sample]
                    val py = samples.y[sample]
                    val pz = samples.z[sample]
                    samples.place(sample, rotation.x(px, py, pz), rotation.y(px, py, pz), rotation.z(px, py, pz))
                }
            }
        }

        /** Which lattice point each sample is nearest, gathered by point. */
        private fun rebuildBuckets() {
            ensureCapacity()
            val count = samples.count
            parallelChunks(0, count) { from, to ->
                for (sample in from until to) {
                    if (sampleCell[sample] >= 0 && sampleCell[sample] < cellCount && sampleCellFresh[sample]) continue
                    sampleCell[sample] = sphere.nearest(samples.x[sample], samples.y[sample], samples.z[sample])
                }
            }
            sampleCellFresh.fill(false, 0, count)
            bucketStart.fill(0)
            for (sample in 0 until count) bucketStart[sampleCell[sample] + 1]++
            for (cell in 0 until cellCount) bucketStart[cell + 1] += bucketStart[cell]
            if (bucketSamples.size < count) bucketSamples = IntArray(count * 5 / 4)
            val cursor = bucketStart.copyOf()
            for (sample in 0 until count) bucketSamples[cursor[sampleCell[sample]]++] = sample
        }

        private var sampleCellFresh = BooleanArray(samples.x.size)

        private fun ensureCapacity() {
            if (sampleCell.size < samples.x.size) {
                sampleCell = sampleCell.copyOf(samples.x.size)
                removed = removed.copyOf(samples.x.size)
                sampleCellFresh = sampleCellFresh.copyOf(samples.x.size)
            }
        }

        /**
         * Which plate owns each lattice point: of the plates with crust within [COVER_SPACINGS] of
         * it, continental crust before ocean floor; and between two plates whose crusts of one kind
         * meet, the one that stays up along the whole of their contact, decided for the pair rather
         * than point by point: of two ocean floors the younger on the mean of their overlap, or the
         * thicker arc's, as Cortial et al. send the older plate down; of two continents the thicker.
         * A subduction zone keeps one polarity along its length, and deciding it point by point
         * interleaved two overlapping plates where their ages were alike.
         */
        private fun assignOwners() {
            rebuildBuckets()
            val coverCosine = kotlin.math.cos(COVER_SPACINGS * sphere.spacingRadians)
            val found = IntArray(cellCount)
            val candidatePlate = IntArray(cellCount * MAX_PLATES_AT_A_POINT)
            val candidateSample = IntArray(cellCount * MAX_PLATES_AT_A_POINT)
            parallelChunks(0, cellCount) { from, to ->
                val bestCosine = DoubleArray(MAX_PLATES_AT_A_POINT)
                for (cell in from until to) {
                    val first = cell * MAX_PLATES_AT_A_POINT
                    var count = 0
                    val cx = sphere.x[cell]
                    val cy = sphere.y[cell]
                    val cz = sphere.z[cell]
                    fun consider(near: Int) {
                        for (index in bucketStart[near] until bucketStart[near + 1]) {
                            val sample = bucketSamples[index]
                            val cosine = samples.x[sample] * cx + samples.y[sample] * cy + samples.z[sample] * cz
                            if (cosine < coverCosine) continue
                            val plate = samples.plate[sample]
                            var slot = -1
                            for (k in 0 until count) if (candidatePlate[first + k] == plate) slot = k
                            if (slot < 0) {
                                if (count == MAX_PLATES_AT_A_POINT) continue
                                slot = count++
                                candidatePlate[first + slot] = plate
                                candidateSample[first + slot] = sample
                                bestCosine[slot] = cosine
                            } else if (cosine > bestCosine[slot] || (cosine == bestCosine[slot] && sample < candidateSample[first + slot])) {
                                candidateSample[first + slot] = sample
                                bestCosine[slot] = cosine
                            }
                        }
                    }
                    consider(cell)
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) consider(sphere.neighbors[index])
                    found[cell] = count
                }
            }
            // Each contested pair's polarity, from the means over every point they both cover.
            val pairStats = HashMap<Long, DoubleArray>()
            for (cell in 0 until cellCount) {
                val count = found[cell]
                if (count < 2) continue
                val first = cell * MAX_PLATES_AT_A_POINT
                for (a in 0 until count) for (b in a + 1 until count) {
                    val plateA = candidatePlate[first + a]
                    val plateB = candidatePlate[first + b]
                    val sampleA = candidateSample[first + a]
                    val sampleB = candidateSample[first + b]
                    if (samples.continental[sampleA] != samples.continental[sampleB]) continue
                    val low = min(plateA, plateB)
                    val high = max(plateA, plateB)
                    val lowSample = if (plateA == low) sampleA else sampleB
                    val highSample = if (plateA == low) sampleB else sampleA
                    val stats = pairStats.getOrPut(low.toLong() * PAIR_STRIDE + high) { DoubleArray(PAIR_STATS) }
                    stats[0] += samples.thicknessKm(lowSample).toDouble()
                    stats[1] += samples.thicknessKm(highSample).toDouble()
                    stats[2] += samples.formedMyrAgo[lowSample].toDouble()
                    stats[3] += samples.formedMyrAgo[highSample].toDouble()
                    stats[4] += 1.0
                }
            }
            val overrides = HashMap<Long, Int>()
            for ((key, stats) in pairStats) {
                val low = (key / PAIR_STRIDE).toInt()
                val high = (key % PAIR_STRIDE).toInt()
                val lowThickness = stats[0] / stats[4]
                val highThickness = stats[1] / stats[4]
                val lowAge = stats[2] / stats[4]
                val highAge = stats[3] / stats[4]
                overrides[key] = when {
                    abs(lowThickness - highThickness) > BUOYANT_ARC_EXCESS_KM -> if (lowThickness > highThickness) low else high
                    lowAge != highAge -> if (lowAge < highAge) low else high
                    else -> low
                }
            }
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    val count = found[cell]
                    val first = cell * MAX_PLATES_AT_A_POINT
                    if (count == 0) {
                        owner[cell] = -1
                        ownerSample[cell] = -1
                        continue
                    }
                    var winner = 0
                    for (k in 1 until count) {
                        val challenger = candidateSample[first + k]
                        val holder = candidateSample[first + winner]
                        val challengerPlate = candidatePlate[first + k]
                        val holderPlate = candidatePlate[first + winner]
                        val takes = if (samples.continental[challenger] != samples.continental[holder]) {
                            samples.continental[challenger]
                        } else {
                            val key = min(challengerPlate, holderPlate).toLong() * PAIR_STRIDE + max(challengerPlate, holderPlate)
                            overrides[key] == challengerPlate
                        }
                        if (takes) winner = k
                    }
                    owner[cell] = candidatePlate[first + winner]
                    ownerSample[cell] = candidateSample[first + winner]
                }
            }
            lastOverrides = overrides
        }

        /** The polarity of every contested pair from the last ownership pass, low id times the stride plus high. */
        private var lastOverrides: Map<Long, Int> = emptyMap()

        /** Whether [sample] on [plate] stays on top of [other] on [otherPlate] where both cover a point. */
        private fun staysUp(sample: Int, plate: Int, other: Int, otherPlate: Int): Boolean {
            val continent = samples.continental[sample]
            val otherContinent = samples.continental[other]
            if (continent != otherContinent) return continent
            lastOverrides[min(plate, otherPlate).toLong() * PAIR_STRIDE + max(plate, otherPlate)]?.let { return it == plate }
            if (continent) {
                val thickness = samples.thicknessKm(sample)
                val otherThickness = samples.thicknessKm(other)
                if (thickness != otherThickness) return thickness > otherThickness
                return plate < otherPlate
            }
            // Arc crust a few kilometers thicker than ordinary floor is buoyant enough to stay up.
            val base = samples.baseKm[sample]
            val otherBase = samples.baseKm[other]
            if (abs(base - otherBase) > BUOYANT_ARC_EXCESS_KM) return base > otherBase
            val formed = samples.formedMyrAgo[sample]
            val otherFormed = samples.formedMyrAgo[other]
            if (formed != otherFormed) return formed < otherFormed
            return plate < otherPlate
        }

        /** A continental sample that went under another continent: where, how much, and between whom. */
        private class Collision(val sample: Int, val underPlate: Int, val overPlate: Int)

        /**
         * Crust overridden by a converging plate goes down. A sample goes when the point it lies at is
         * owned by another plate and that plate is closing on it there: the other plate's crust moves
         * toward this sample, faster than Bird's 20 degrees from sliding past it, so two plates that
         * merely slide by one another along a transform keep their crust; or the overriding crust lies
         * so close over it that the two occupy one place, which two plates' crusts side by side never
         * do, since new crust is laid no nearer than [COVER_SPACINGS] to old; or its plate owns less
         * than a third of the ground around it, so it lies inside the plate over it rather than at
         * its edge. Two rigid plates side by side cannot interleave, and a sample left inside
         * another's ground by the lattices sliding past each other along a transform is crust that
         * plate has overridden.
         */
        private fun consumeOverriddenCrust(): List<Collision> {
            removed.fill(false, 0, samples.count)
            val approach = sin(STRIKE_SLIP_TOLERANCE_RADIANS)
            val overlapCosine = kotlin.math.cos(OVERLAP_SPACINGS * sphere.spacingRadians)
            val consumed = BooleanArray(samples.count)
            parallelChunks(0, samples.count) { from, to ->
                for (sample in from until to) {
                    val cell = sampleCell[sample]
                    val over = owner[cell]
                    val under = samples.plate[sample]
                    if (over < 0 || over == under) continue
                    val top = ownerSample[cell]
                    if (samples.continental[sample] && !samples.continental[top]) continue
                    val overlapping = samples.x[sample] * samples.x[top] + samples.y[sample] * samples.y[top] +
                        samples.z[sample] * samples.z[top] > overlapCosine
                    var ownGround = 0
                    var ground = 0
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        ground++
                        if (owner[sphere.neighbors[index]] == under) ownGround++
                    }
                    val surrounded = ownGround * SURROUNDED_SHARE_INVERSE < ground
                    if (overlapping || surrounded || closingRate(sample, top, under, over) > approach) consumed[sample] = true
                }
            }
            val collisions = ArrayList<Collision>()
            for (sample in 0 until samples.count) {
                if (!consumed[sample]) continue
                removed[sample] = true
                if (samples.continental[sample]) {
                    collisions.add(Collision(sample, samples.plate[sample], owner[sampleCell[sample]]))
                }
            }
            return collisions
        }

        /**
         * How squarely [over] closes on [under] at [sample], as the cosine between their relative
         * velocity and the line from [top] (the overriding crust) to the sample, 1 where it drives
         * straight at it and 0 where it slides by. A sample sitting on the crust over it closes.
         */
        private fun closingRate(sample: Int, top: Int, under: Int, over: Int): Double {
            val px = samples.x[sample]; val py = samples.y[sample]; val pz = samples.z[sample]
            var nx = px - samples.x[top]; var ny = py - samples.y[top]; var nz = pz - samples.z[top]
            val radial = nx * px + ny * py + nz * pz
            nx -= radial * px; ny -= radial * py; nz -= radial * pz
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            val a = plates[over]
            val b = plates[under]
            val wx = a.omegaX - b.omegaX; val wy = a.omegaY - b.omegaY; val wz = a.omegaZ - b.omegaZ
            val vx = wy * pz - wz * py
            val vy = wz * px - wx * pz
            val vz = wx * py - wy * px
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            if (speed * radiusKm < MIN_RELATIVE_RATE_KM_PER_MYR) return 0.0
            if (length < 1e-9) return 1.0
            return (vx * nx + vy * ny + vz * nz) / (length * speed)
        }

        /**
         * Ground no plate covers is new crust, given to the plate it is nearest, so the ridge is the
         * line midway between the two plates parting. Its age is exact kinematics: its distance from
         * that midline over the half rate the two plates part at there. Beside a margin that has
         * stretched, it is that margin's stretched crust for as long as the stretching has volume to
         * give (McKenzie 1978: a margin stretched by beta covers beta times the ground at a beta-th of
         * the thickness).
         */
        private fun fillGaps(agoEnd: Double) {
            val gap = BooleanArray(cellCount) { owner[it] < 0 }
            if (gap.none { it }) return
            val sources = ArrayList<Int>()
            val allowed = BooleanArray(cellCount)
            for (cell in 0 until cellCount) {
                if (gap[cell]) {
                    allowed[cell] = true
                    continue
                }
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    if (gap[sphere.neighbors[index]]) {
                        sources.add(cell)
                        allowed[cell] = true
                        break
                    }
                }
            }
            if (sources.isEmpty()) return
            val (_, source) = sphere.distanceFlood(sources.toIntArray(), allowed)
            // The midline: gap points whose neighbor in the gap was given to another plate.
            val ridge = ArrayList<Int>()
            val acrossPlate = IntArray(cellCount) { -1 }
            for (cell in 0 until cellCount) {
                if (!gap[cell] || source[cell] < 0) continue
                val plate = owner[source[cell]]
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    val nextSource = source[next]
                    if (nextSource < 0) continue
                    val nextPlate = owner[nextSource]
                    if (nextPlate != plate) {
                        ridge.add(cell)
                        acrossPlate[cell] = nextPlate
                        break
                    }
                }
            }
            val ridgeFlood = if (ridge.isEmpty()) null else sphere.distanceFlood(ridge.toIntArray(), gap)
            for (cell in 0 until cellCount) {
                if (!gap[cell] || source[cell] < 0) continue
                val from = source[cell]
                val plate = owner[from]
                val beside = ownerSample[from]
                val plateRecord = plates[plate]
                var ageMyr = stepMyr / 2
                if (ridgeFlood != null && ridgeFlood.second[cell] >= 0) {
                    val ridgeCell = ridgeFlood.second[cell]
                    val other = acrossPlate[ridgeCell]
                    if (other >= 0) {
                        val opening = relativeSpeedKmPerMyr(plate, other, cell)
                        ageMyr = if (opening > MIN_RELATIVE_RATE_KM_PER_MYR) ridgeFlood.first[cell] / (opening / 2.0) else stepMyr / 2
                    }
                }
                ageMyr = ageMyr.coerceIn(0.0, stepMyr * MAX_GAP_AGE_STEPS)
                // The stretched crust's thickness is the pool's own: the volume the thinning freed over
                // the area beta says it now covers, so area and volume both come out as McKenzie's.
                val stretchedBase = if (plateRecord.stretchPoolAreaKm2 > 0.0)
                    (plateRecord.stretchPoolKm3 / plateRecord.stretchPoolAreaKm2).toFloat() else 0f
                if (samples.continental[beside] && plateRecord.stretchPoolAreaKm2 >= sampleAreaKm2 && stretchedBase > oceanicKm) {
                    plateRecord.stretchPoolKm3 -= stretchedBase.toDouble() * sampleAreaKm2
                    plateRecord.stretchPoolAreaKm2 -= sampleAreaKm2
                    budget.stretchedAreaMadeKm2 += sampleAreaKm2
                    val sample = samples.add(sphere.x[cell], sphere.y[cell], sphere.z[cell], plate, true, stretchedBase, 0f,
                        samples.formedMyrAgo[beside], agoEnd.toFloat(), OrogenyKind.RIFT, samples.terrane[beside], samples.riftMyrAgo[beside])
                    adopt(sample, cell)
                } else {
                    val sample = samples.add(sphere.x[cell], sphere.y[cell], sphere.z[cell], plate, false, oceanicKm, 0f,
                        (agoEnd + ageMyr).toFloat(), Float.NaN, OrogenyKind.NONE, -1, Float.NaN)
                    samples.slabBuoyancy[sample] = slabBuoyancyAt(sphere.x[cell], sphere.y[cell], sphere.z[cell])
                    adopt(sample, cell)
                }
            }
        }

        /** A new sample takes the point it was laid at. */
        private fun adopt(sample: Int, cell: Int) {
            ensureCapacity()
            sampleCell[sample] = cell
            sampleCellFresh[sample] = true
            removed[sample] = false
            owner[cell] = samples.plate[sample]
            ownerSample[cell] = sample
        }

        /** How fast [first] and [second] move apart or together at [cell], km per Myr. */
        private fun relativeSpeedKmPerMyr(first: Int, second: Int, cell: Int): Double {
            val a = plates[first]
            val b = plates[second]
            val wx = a.omegaX - b.omegaX; val wy = a.omegaY - b.omegaY; val wz = a.omegaZ - b.omegaZ
            val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
            val vx = wy * pz - wz * py
            val vy = wz * px - wx * pz
            val vz = wx * py - wy * px
            return sqrt(vx * vx + vy * vy + vz * vz) * radiusKm
        }

        /**
         * The present boundaries, read from the plates' relative motion; and everything that happens
         * on them: the forces for the next step, arcs behind the trenches, the forearcs' wear, the
         * collision zones' losses, and the stretching of a continent where it has just begun to part.
         */
        private fun classifyBoundaries(agoEnd: Double, withEffects: Boolean) {
            forces.clear()
            boundaryClass.fill(-1)
            boundaryOther.fill(-1)
            boundaryOverriding.fill(-1)
            boundaryClosing.fill(0f)
            val normalX = DoubleArray(cellCount)
            val normalY = DoubleArray(cellCount)
            val normalZ = DoubleArray(cellCount)
            // Each boundary point's plate across, and the direction toward it, from the neighbors.
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    val plate = owner[cell]
                    if (plate < 0) continue
                    var other = -1
                    var otherCount = 0
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val next = owner[sphere.neighbors[index]]
                        if (next < 0 || next == plate) continue
                        var count = 0
                        for (again in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                            if (owner[sphere.neighbors[again]] == next) count++
                        }
                        if (count > otherCount || (count == otherCount && next < other)) {
                            other = next
                            otherCount = count
                        }
                    }
                    if (other < 0) continue
                    boundaryOther[cell] = other
                    var sx = 0.0; var sy = 0.0; var sz = 0.0
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val next = sphere.neighbors[index]
                        if (owner[next] != other) continue
                        sx += sphere.x[next] - sphere.x[cell]; sy += sphere.y[next] - sphere.y[cell]; sz += sphere.z[next] - sphere.z[cell]
                    }
                    normalX[cell] = sx; normalY[cell] = sy; normalZ[cell] = sz
                }
            }
            // The direction across the boundary, smoothed over the boundary's own points two rings of
            // neighbors out, so the class does not flicker with the lattice's step at each point.
            val smoothX = DoubleArray(cellCount)
            val smoothY = DoubleArray(cellCount)
            val smoothZ = DoubleArray(cellCount)
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    if (boundaryOther[cell] < 0) continue
                    val plate = owner[cell]
                    val other = boundaryOther[cell]
                    var sx = normalX[cell]; var sy = normalY[cell]; var sz = normalZ[cell]
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val near = sphere.neighbors[index]
                        for (again in sphere.neighborStart[near] until sphere.neighborStart[near + 1]) {
                            val far = sphere.neighbors[again]
                            if (far == cell || owner[far] != plate || boundaryOther[far] != other) continue
                            sx += normalX[far]; sy += normalY[far]; sz += normalZ[far]
                        }
                    }
                    val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
                    val radial = sx * px + sy * py + sz * pz
                    sx -= radial * px; sy -= radial * py; sz -= radial * pz
                    val length = sqrt(sx * sx + sy * sy + sz * sz)
                    if (length > 0.0) {
                        smoothX[cell] = sx / length; smoothY[cell] = sy / length; smoothZ[cell] = sz / length
                    }
                }
            }
            val strikeSlip = sin(STRIKE_SLIP_TOLERANCE_RADIANS)
            val riftStart = ArrayList<Int>()
            val trenchCells = ArrayList<Int>()
            // At each trench point of a continental upper plate: how fast that plate moves toward its
            // trench, km per Myr (negative where it retreats), and the slab's buoyancy there.
            val advanceKmPerMyr = FloatArray(cellCount)
            val slabBuoyancyAtTrench = FloatArray(cellCount)
            val collisionCells = ArrayList<Int>()
            val slabPulled = IntArray(plates.size)
            val lengthKm = spacingKm
            for (cell in 0 until cellCount) {
                val other = boundaryOther[cell]
                if (other < 0) continue
                val plate = owner[cell]
                val nx = smoothX[cell]; val ny = smoothY[cell]; val nz = smoothZ[cell]
                if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue
                val a = plates[other]
                val b = plates[plate]
                val wx = a.omegaX - b.omegaX; val wy = a.omegaY - b.omegaY; val wz = a.omegaZ - b.omegaZ
                val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
                // The other plate's velocity relative to this one, km per Myr.
                val vx = (wy * pz - wz * py) * radiusKm
                val vy = (wz * px - wx * pz) * radiusKm
                val vz = (wx * py - wy * px) * radiusKm
                val speed = sqrt(vx * vx + vy * vy + vz * vz)
                val opening = vx * nx + vy * ny + vz * nz
                boundaryClosing[cell] = (-opening).toFloat()
                val sample = ownerSample[cell]
                val continent = samples.continental[sample]
                val otherContinent = otherSideContinental(cell, other)
                if (speed < LOCK_RATE_KM_PER_MYR || abs(opening) <= strikeSlip * speed) {
                    boundaryClass[cell] = BoundaryClass.TRANSFORM_FAULT.ordinal
                    continue
                }
                if (opening > 0.0) {
                    // A rift is continental lithosphere pulled apart, continent on both sides. A
                    // divergent boundary at a continent's edge with ocean floor across it is the
                    // ridge the continent has already parted from: Bird (2003) counts a boundary
                    // oceanic where it lies deeper than 2,000 m, the isobath a continent's edge is
                    // read at (D3), and the slow opening that holds the ridge against the margin
                    // for a step or two is the lattice's, not a rift valley on land
                    // (docs/DESIGN_LEDGER.md, TH1-2).
                    boundaryClass[cell] =
                        if (continent && otherContinent) BoundaryClass.CONTINENTAL_RIFT.ordinal
                        else BoundaryClass.OCEAN_RIDGE.ordinal
                    forces.add(cell, plate, PlateForces.RIDGE_PUSH * lengthKm, -nx, -ny, -nz)
                    // A continent begins to part only where it meets the other side's continent: a
                    // ridge along a margin already rifted is ocean opening, not new stretching.
                    if (continent && otherContinent && samples.riftMyrAgo[sample].isNaN()) riftStart.add(cell)
                    continue
                }
                // Converging: who goes down.
                val goesDown = when {
                    continent && otherContinent -> null
                    continent -> false
                    otherContinent -> true
                    else -> !staysUp(sample, plate, otherSideSample(cell, other), other)
                }
                if (goesDown == null) {
                    boundaryClass[cell] = BoundaryClass.COLLISION_PLATEAU.ordinal
                    boundaryOverriding[cell] = min(plate, other)
                    forces.add(cell, plate, PlateForces.COLLISION_RESISTANCE * lengthKm, -nx, -ny, -nz)
                    collisionCells.add(cell)
                    continue
                }
                boundaryClass[cell] =
                    if (continent || otherContinent) BoundaryClass.ANDEAN_MARGIN.ordinal else BoundaryClass.ISLAND_ARC.ordinal
                if (goesDown) {
                    boundaryOverriding[cell] = other
                    forces.add(cell, plate, PlateForces.SLAB_PULL * lengthKm, nx, ny, nz)
                    forces.addSlabResistance(cell, plate, nx, ny, nz)
                    slabPulled[plate]++
                } else {
                    boundaryOverriding[cell] = plate
                    forces.add(cell, plate, PlateForces.TRENCH_SUCTION * lengthKm, nx, ny, nz)
                    trenchCells.add(cell)
                    // The upper plate's own motion, in the mantle's frame the rotations are written
                    // in, along the normal toward its trench: Heuret and Lallemand's Vup(n).
                    val upper = plates[plate]
                    val ux = (upper.omegaY * pz - upper.omegaZ * py) * radiusKm
                    val uy = (upper.omegaZ * px - upper.omegaX * pz) * radiusKm
                    val uz = (upper.omegaX * py - upper.omegaY * px) * radiusKm
                    advanceKmPerMyr[cell] = (ux * nx + uy * ny + uz * nz).toFloat()
                    slabBuoyancyAtTrench[cell] = samples.slabBuoyancy[otherSideSample(cell, other)]
                }
            }
            slabPulledPoints = slabPulled
            edgePoints = IntArray(plates.size).also { edges ->
                for (cell in 0 until cellCount) if (boundaryOther[cell] >= 0) edges[owner[cell]]++
            }
            if (!withEffects) return
            if (trenchCells.isNotEmpty()) {
                val trenches = trenchCells.toIntArray()
                val slabRank = slabRanksUnderContinents(trenches, slabBuoyancyAtTrench)
                val flatSlab = BooleanArray(cellCount)
                for (cell in trenches) flatSlab[cell] = !slabRank[cell].isNaN() && slabRank[cell] >= 1f - FLAT_SLAB_SHARE
                growArcsAndWearForearcs(trenches, lengthKm, agoEnd, flatSlab)
                deformUpperPlates(trenches, lengthKm, agoEnd, flatSlab, advanceKmPerMyr, slabRank)
            }
            if (collisionCells.isNotEmpty()) exchangeInCollisionZones(collisionCells.toIntArray(), lengthKm, agoEnd)
            if (riftStart.isNotEmpty()) {
                val before = budget.stretchedAreaMadeKm2
                stretchMargins(riftStart, agoEnd)
                trace?.invoke("  rift starts=${riftStart.size} at ago=$agoEnd pools=${plates.sumOf { it.stretchPoolAreaKm2 }.roundToInt()} madeSoFar=${before.roundToInt()}")
            }
        }

        /** Whether the crust across the boundary from [cell], on [other], is mostly continental. */
        private fun otherSideContinental(cell: Int, other: Int): Boolean {
            var continental = 0
            var all = 0
            for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                val next = sphere.neighbors[index]
                if (owner[next] != other) continue
                all++
                if (samples.continental[ownerSample[next]]) continental++
            }
            return all > 0 && 2 * continental >= all
        }

        /** The sample owning the nearest point across the boundary from [cell] on [other]. */
        private fun otherSideSample(cell: Int, other: Int): Int {
            for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                val next = sphere.neighbors[index]
                if (owner[next] == other) return ownerSample[next]
            }
            return ownerSample[cell]
        }

        /**
         * Spreads an amount given at each of [sources] over the samples of the source's own plate
         * that lie nearer it than any other source, within [reachKm], by [weight] of their distance;
         * [visit] receives each sample and its share of its source's amount, the shares of one source
         * summing to one. A source with no sample in reach gives nothing.
         *
         * One flood across the plates from every source at once, so a trench's whole arc costs a
         * pass over the lattice rather than a search about each of its points.
         */
        private inline fun spreadFromSources(
            sources: IntArray,
            reachKm: Double,
            weight: (distanceKm: Double, source: Int, sample: Int) -> Double,
            visit: (sample: Int, source: Int, share: Double) -> Unit
        ) {
            val (distanceKm, nearest) = sphere.distanceFlood(sources, reachKm = reachKm, region = owner)
            val totals = DoubleArray(cellCount)
            for (sample in 0 until samples.count) {
                if (removed[sample]) continue
                val cell = sampleCell[sample]
                val source = nearest[cell]
                if (source < 0 || samples.plate[sample] != owner[source]) continue
                totals[source] += weight(distanceKm[cell], source, sample)
            }
            for (sample in 0 until samples.count) {
                if (removed[sample]) continue
                val cell = sampleCell[sample]
                val source = nearest[cell]
                if (source < 0 || samples.plate[sample] != owner[source] || totals[source] <= 0.0) continue
                val share = weight(distanceKm[cell], source, sample) / totals[source]
                if (share > 0.0) visit(sample, source, share)
            }
        }

        /**
         * At every trench, on the overriding plate: the arc's magma added behind it and the forearc
         * worn away in front of it, each [ARC_ADDITION_KM3_PER_KM_MYR] and
         * [TRENCH_RECYCLING_KM3_PER_KM_MYR] per kilometer of trench, which are Scholl and von Huene's
         * and equal: on Earth's ocean margins what arcs add and what subduction takes back balance.
         * The arc stands [com.cartogenesis.worldgen.model.TectonicsConfig.arcOffsetKm] behind a
         * continental margin's trench and `islandArcOffsetKm` behind an island arc's.
         */
        private fun growArcsAndWearForearcs(trenches: IntArray, lengthKm: Double, agoEnd: Double, flatSlab: BooleanArray) {
            val continentalTrench = BooleanArray(cellCount)
            for (cell in trenches) continentalTrench[cell] = samples.continental[ownerSample[cell]]
            fun offsetOf(source: Int) = if (continentalTrench[source]) tectonics.arcOffsetKm else tectonics.islandArcOffsetKm
            fun widthOf(source: Int) = if (continentalTrench[source]) tectonics.arcWidthKm else tectonics.islandArcWidthKm
            val reachKm = max(tectonics.arcOffsetKm + 2 * tectonics.arcWidthKm, tectonics.islandArcOffsetKm + 2 * tectonics.islandArcWidthKm)
            val addedKm3 = ARC_ADDITION_KM3_PER_KM_MYR * lengthKm * stepMyr
            // Over a flat slab the arc goes out: the slab runs under the upper plate too shallow to
            // melt (Gutscher, Spakman, Bijwaard and Engdahl 2000), so a flat segment adds no arc.
            spreadFromSources(trenches, reachKm, { distanceKm, source, _ ->
                val off = (distanceKm - offsetOf(source)) / widthOf(source)
                if (flatSlab[source] || distanceKm > offsetOf(source) + 2 * widthOf(source)) 0.0 else exp(-off * off)
            }) { sample, source, share ->
                val km = (addedKm3 * share / sampleAreaKm2).toFloat()
                if (samples.continental[sample]) {
                    samples.excessKm[sample] += km
                    budget.arcVolumeAddedKm3 += km.toDouble() * sampleAreaKm2
                    if (km > OROGENY_MARK_KM) markOrogeny(sample, agoEnd, OrogenyKind.ANDEAN)
                } else {
                    samples.baseKm[sample] += km
                    if (samples.baseKm[sample] >= ARC_CONTINENT_KM) {
                        samples.continental[sample] = true
                        if (samples.terrane[sample] < 0) samples.terrane[sample] = ARC_TERRANE_BASE + owner[source]
                        samples.formedMyrAgo[sample] = agoEnd.toFloat()
                        markOrogeny(sample, agoEnd, OrogenyKind.ISLAND_ARC)
                        budget.arcAreaMadeKm2 += sampleAreaKm2
                        budget.oceanArcVolumeBecameContinentKm3 += samples.thicknessKm(sample).toDouble() * sampleAreaKm2
                    }
                }
            }
            val wornKm3 = TRENCH_RECYCLING_KM3_PER_KM_MYR * lengthKm * stepMyr
            spreadFromSources(trenches, tectonics.arcOffsetKm, { distanceKm, source, _ ->
                if (distanceKm > offsetOf(source)) 0.0 else exp(-distanceKm / (offsetOf(source) / 2))
            }) { sample, _, share ->
                wear(sample, (wornKm3 * share / sampleAreaKm2).toFloat(), forearc = true)
            }
        }

        /**
         * The upper plate's deformation above its slab, at every trench point of a continental upper
         * plate: the plate is shortened where it advances on its trench and stretched behind its arc
         * where it retreats, by the share of its motion the slab holds against it.
         *
         * Heuret and Lallemand (*Plate motions, slab dynamics and back-arc deformation*, PEPI 149,
         * 2005): over Earth's oceanic subduction zones the back-arc deformation follows the upper
         * plate's absolute motion, compression where it advances and extension where it retreats,
         * the slab partly anchored so the deformation rate is a share of that motion, `Vd = a Vup`,
         * `a` from nothing (a free slab, the trench following the upper plate) to one (an anchored
         * slab, the trench fixed). Here `a` is the slab's own: drawn from the subducting floor's
         * buoyancy ([anchoringOf]), so a trench is segmented as its slab is and the segments ride in
         * with the floor. A flat slab, the floor's most buoyant tenth, couples the plates harder and
         * deforms the upper plate far inboard (Gutscher et al. 2000).
         *
         * Conservative: shortening moves the crust the upper plate's edge gives up at the trench, the
         * width the slab holds back, into the thrust belt behind the arc, so the trench migrates into
         * the plate as `Vt = Vup - Vd` says and the volume stays in the continent; stretching thins
         * the back-arc and hands the volume it frees to the plate's stretched crust ([fillGaps]).
         */
        private fun deformUpperPlates(
            trenches: IntArray,
            lengthKm: Double,
            agoEnd: Double,
            flatSlab: BooleanArray,
            advanceKmPerMyr: FloatArray,
            slabRank: FloatArray
        ) {
            val shortened = ArrayList<Int>()
            val shortenedFlat = ArrayList<Int>()
            val extended = ArrayList<Int>()
            val widthKm = DoubleArray(cellCount)
            for (cell in trenches) {
                if (slabRank[cell].isNaN()) continue
                val coupling = if (flatSlab[cell]) FLAT_SLAB_COUPLING else anchoringOf(slabRank[cell])
                val rate = advanceKmPerMyr[cell].toDouble()
                val width = coupling * abs(rate) * stepMyr
                if (width <= 0.0) continue
                widthKm[cell] = width
                when {
                    rate < 0.0 -> extended.add(cell)
                    flatSlab[cell] -> shortenedFlat.add(cell)
                    else -> shortened.add(cell)
                }
            }
            if (shortened.isNotEmpty() || shortenedFlat.isNotEmpty()) {
                val taken = consumeAtTrenches((shortened + shortenedFlat).toIntArray(), widthKm, lengthKm)
                thickenBehindTrenches(shortened.toIntArray(), taken, agoEnd, THRUST_BELT_OFFSET_KM + 2 * THRUST_BELT_HALF_WIDTH_KM) { distanceKm ->
                    val off = (distanceKm - THRUST_BELT_OFFSET_KM) / THRUST_BELT_HALF_WIDTH_KM
                    exp(-off * off)
                }
                thickenBehindTrenches(shortenedFlat.toIntArray(), taken, agoEnd, FLAT_SLAB_FARTHEST_KM + FLAT_SLAB_RAMP_KM) { distanceKm ->
                    smoothBox(distanceKm, FLAT_SLAB_NEAREST_KM, FLAT_SLAB_FARTHEST_KM, FLAT_SLAB_RAMP_KM)
                }
            }
            if (extended.isNotEmpty()) stretchBackArcs(extended.toIntArray(), widthKm, lengthKm, agoEnd)
            trace?.invoke(
                "  upper plates at ago=$agoEnd: shortened ${shortened.size}+${shortenedFlat.size} points, " +
                    "${(shortened + shortenedFlat).sumOf { widthKm[it] * lengthKm }.roundToInt()} km2; " +
                    "stretched ${extended.size} points, ${extended.sumOf { widthKm[it] * lengthKm }.roundToInt()} km2; advance " +
                    (shortened + shortenedFlat + extended).map { advanceKmPerMyr[it] }.sorted().let { list ->
                        if (list.isEmpty()) "-" else "p10 %.0f p50 %.0f p90 %.0f".format(list[list.size / 10], list[list.size / 2], list[list.size * 9 / 10])
                    }
            )
        }

        /**
         * Each trench point of a continent's plate, its slab's buoyancy ranked among every such point
         * of the step, 0 to 1; NaN at every other trench point. A continent's plate is more than a
         * quarter continental, as Zahirovic et al. sort plates: an oceanic plate carrying an arc's
         * crust at its trench is not deformed as a continent's upper plate is.
         *
         * Ranked, because the statistics the coupling is drawn from are Earth's trenches' own, a
         * share of the trench at one time: Gutscher et al.'s tenth of the convergent margins flat,
         * Heuret and Lallemand's segments sampled every 2 degrees along them. Which segment is which
         * is the slab's: its buoyancy, carried in from where its floor was made.
         */
        private fun slabRanksUnderContinents(trenches: IntArray, slabBuoyancyAtTrench: FloatArray): FloatArray {
            val points = IntArray(plates.size)
            val continentPoints = IntArray(plates.size)
            for (cell in 0 until cellCount) {
                val plate = owner[cell]
                if (plate < 0) continue
                points[plate]++
                if (samples.continental[ownerSample[cell]]) continentPoints[plate]++
            }
            val ranked = trenches.filter {
                samples.continental[ownerSample[it]] && continentPoints[owner[it]] > CRATONIC_SHARE * points[owner[it]]
            }.sortedWith(compareBy({ slabBuoyancyAtTrench[it] }, { it }))
            val rank = FloatArray(cellCount) { Float.NaN }
            ranked.forEachIndexed { index, cell -> rank[cell] = ((index + 0.5) / ranked.size).toFloat() }
            return rank
        }

        /**
         * The share of an upper plate's motion a slab ranked [rank] holds against it: Heuret and
         * Lallemand's `a`, drawn in the order of their own statistics. The least buoyant
         * [QUIET_BACK_ARC_SHARE] of the ordinary floor, old and dense, has a back-arc that does not
         * deform measurably; of the rest, the least buoyant [SLAB_CONTROLLED_SHARE] is the
         * slab-controlled kind, its trench moving on its own, and holds nothing of the upper plate's
         * motion; the remainder is spread by [ANCHORING_EXPONENT].
         */
        private fun anchoringOf(rank: Float): Double {
            val share = (rank / (1.0 - FLAT_SLAB_SHARE)).coerceIn(0.0, 1.0)
            if (share <= QUIET_BACK_ARC_SHARE) return 0.0
            val deforming = (share - QUIET_BACK_ARC_SHARE) / (1.0 - QUIET_BACK_ARC_SHARE)
            if (deforming <= SLAB_CONTROLLED_SHARE) return 0.0
            return ((deforming - SLAB_CONTROLLED_SHARE) / (1.0 - SLAB_CONTROLLED_SHARE)).pow(ANCHORING_EXPONENT)
        }

        /**
         * The upper plate's crust at each trench point of [sources] given up to the shortening: the
         * width [widthKm] of crust as thick as the trench point's, taken from the plate's own samples
         * within [CONSUMED_REACH_KM] of the trench, nearest most. A sample worn thinner than ocean
         * floor goes, its area booked as shortened and all its volume taken. Returns the volume each
         * source took, km³.
         */
        private fun consumeAtTrenches(sources: IntArray, widthKm: DoubleArray, lengthKm: Double): DoubleArray {
            val taken = DoubleArray(cellCount)
            val wantedKm3 = DoubleArray(cellCount)
            for (cell in sources) wantedKm3[cell] = widthKm[cell] * lengthKm * samples.thicknessKm(ownerSample[cell])
            spreadFromSources(sources, CONSUMED_REACH_KM, { distanceKm, _, sample ->
                if (samples.continental[sample]) exp(-distanceKm / CONSUMED_EFOLD_KM) else 0.0
            }) { sample, source, share ->
                if (removed[sample]) return@spreadFromSources
                val km = (wantedKm3[source] * share / sampleAreaKm2).toFloat()
                val fromExcess = min(km, samples.excessKm[sample])
                samples.excessKm[sample] -= fromExcess
                val fromBase = min(km - fromExcess, samples.baseKm[sample])
                samples.baseKm[sample] -= fromBase
                var takenKm = fromExcess + fromBase
                if (samples.thicknessKm(sample) < oceanicKm) {
                    takenKm += samples.thicknessKm(sample)
                    samples.excessKm[sample] = 0f
                    samples.baseKm[sample] = 0f
                    removed[sample] = true
                    budget.shortenedAtMarginsAreaKm2 += sampleAreaKm2
                }
                taken[source] += takenKm.toDouble() * sampleAreaKm2
            }
            return taken
        }

        /**
         * Lays the volume each of [sources] took ([taken], km³) on the continental crust of its own
         * plate behind it, by [weight] of the distance from the trench out to [reachKm], as orogenic
         * excess; what a source finds no continent to lay on goes to the mantle with the slab.
         */
        private inline fun thickenBehindTrenches(
            sources: IntArray,
            taken: DoubleArray,
            agoEnd: Double,
            reachKm: Double,
            crossinline weight: (distanceKm: Double) -> Double
        ) {
            if (sources.isEmpty()) return
            val given = DoubleArray(cellCount)
            spreadFromSources(sources, reachKm, { distanceKm, _, sample ->
                if (samples.continental[sample] && !removed[sample]) weight(distanceKm) else 0.0
            }) { sample, source, share ->
                val km = (taken[source] * share / sampleAreaKm2).toFloat()
                samples.excessKm[sample] += km
                given[source] += km.toDouble() * sampleAreaKm2
                if (km > OROGENY_MARK_KM) markOrogeny(sample, agoEnd, OrogenyKind.ANDEAN)
            }
            for (source in sources) {
                val lost = taken[source] - given[source]
                if (lost > 0.0) budget.recycledAtTrenchesKm3 += lost
            }
        }

        /**
         * Stretches the back-arc behind each of [sources] by the width [widthKm] its slab holds the
         * retreating plate back by: the crust of the back-arc band thinned by the volume of a
         * standard column that wide, never past McKenzie's beta at a break, the volume it frees and
         * the area it opens handed to the plate's stretched crust; the crust is marked stretched,
         * and carries a stretched margin's heat ([Isostasy.Columns.stretchedThermalBuoyancyMetres]).
         */
        private fun stretchBackArcs(sources: IntArray, widthKm: DoubleArray, lengthKm: Double, agoEnd: Double) {
            val freeKm3 = DoubleArray(cellCount)
            for (cell in sources) freeKm3[cell] = widthKm[cell] * lengthKm * continentalKm
            val thinnestKm = (continentalKm / BETA_AT_BREAK).toFloat()
            val nearestKm = tectonics.arcOffsetKm
            val farthestKm = tectonics.arcOffsetKm + BACK_ARC_WIDTH_KM
            spreadFromSources(sources, farthestKm + BACK_ARC_RAMP_KM, { distanceKm, _, sample ->
                if (!samples.continental[sample] || removed[sample]) 0.0
                else smoothBox(distanceKm, nearestKm, farthestKm, BACK_ARC_RAMP_KM)
            }) { sample, source, share ->
                val wantedKm = (freeKm3[source] * share / sampleAreaKm2).toFloat()
                val km = min(wantedKm, (samples.thicknessKm(sample) - thinnestKm).coerceAtLeast(0f))
                if (km <= 0f) return@spreadFromSources
                val fromExcess = min(km, samples.excessKm[sample])
                samples.excessKm[sample] -= fromExcess
                samples.baseKm[sample] -= km - fromExcess
                val record = plates[samples.plate[sample]]
                record.stretchPoolKm3 += km.toDouble() * sampleAreaKm2
                record.stretchPoolAreaKm2 += widthKm[source] * lengthKm * share
                samples.riftMyrAgo[sample] = agoEnd.toFloat()
                if (km > OROGENY_MARK_KM) markOrogeny(sample, agoEnd, OrogenyKind.BACK_ARC_EXTENSION)
            }
        }

        /** One between [from] and [to] km, falling to nothing over [rampKm] about each edge by a smoothstep. */
        private fun smoothBox(distanceKm: Double, from: Double, to: Double, rampKm: Double): Double {
            fun step(edge: Double): Double {
                val t = ((distanceKm - edge) / rampKm + 0.5).coerceIn(0.0, 1.0)
                return t * t * (3 - 2 * t)
            }
            return step(from) * (1.0 - step(to))
        }

        /**
         * In every collision zone, per kilometer of it and on each side of it half: the lower crust
         * lost to the mantle and the magma added, Scholl and von Huene's 115 and 33 km³ per kilometer
         * per Myr for crust-suturing zones, spread over the belt.
         */
        private fun exchangeInCollisionZones(zones: IntArray, lengthKm: Double, agoEnd: Double) {
            val lostKm3 = COLLISION_RECYCLING_KM3_PER_KM_MYR * lengthKm * stepMyr / 2
            val addedKm3 = COLLISION_ADDITION_KM3_PER_KM_MYR * lengthKm * stepMyr / 2
            spreadFromSources(zones, BELT_REACH_KM, { distanceKm, _, sample ->
                if (samples.continental[sample]) exp(-distanceKm / BELT_EFOLD_KM) else 0.0
            }) { sample, _, share ->
                val added = (addedKm3 * share / sampleAreaKm2).toFloat()
                samples.excessKm[sample] += added
                budget.arcVolumeAddedKm3 += added.toDouble() * sampleAreaKm2
                wear(sample, (lostKm3 * share / sampleAreaKm2).toFloat(), forearc = false)
                if (added > OROGENY_MARK_KM) markOrogeny(sample, agoEnd, OrogenyKind.COLLISION)
            }
        }

        /** Takes [km] of thickness off a sample, the orogen's excess first, and books it. */
        private fun wear(sample: Int, km: Float, forearc: Boolean) {
            if (km <= 0f || removed[sample]) return
            if (!samples.continental[sample]) {
                // An arc's crust worn back toward ordinary floor; not continental crust, not on the books.
                samples.baseKm[sample] = max(oceanicKm, samples.baseKm[sample] - km)
                return
            }
            val fromExcess = min(km, samples.excessKm[sample])
            samples.excessKm[sample] -= fromExcess
            val fromBase = min(km - fromExcess, samples.baseKm[sample])
            samples.baseKm[sample] -= fromBase
            val takenKm3 = (fromExcess + fromBase).toDouble() * sampleAreaKm2
            if (forearc) budget.recycledAtTrenchesKm3 += takenKm3 else budget.recycledAtCollisionsKm3 += takenKm3
            if (samples.thicknessKm(sample) < oceanicKm) {
                // Worn thinner than ocean floor: no longer a continent's crust. What is left goes too.
                val leftKm3 = samples.thicknessKm(sample).toDouble() * sampleAreaKm2
                if (forearc) budget.recycledAtTrenchesKm3 += leftKm3 else budget.recycledAtCollisionsKm3 += leftKm3
                budget.erodedForearcAreaKm2 += sampleAreaKm2
                removed[sample] = true
            }
        }

        /**
         * Visits every sample of [plate] within [reachKm] of [cell] with its share of a total spread
         * by [weight] of distance, the shares summing to one; nothing if no sample is in reach.
         */
        private inline fun spread(
            cell: Int,
            plate: Int,
            reachKm: Double,
            weight: (distanceKm: Double, sample: Int) -> Double,
            visit: (sample: Int, shareOfTotal: Double) -> Unit
        ) {
            val reach = reachKm / radiusKm
            var total = 0.0
            val cx = sphere.x[cell]; val cy = sphere.y[cell]; val cz = sphere.z[cell]
            val minimumCosine = kotlin.math.cos(reach)
            sphere.forEachWithin(cx, cy, cz, reach + sphere.spacingRadians) { near, _ ->
                for (index in bucketStart[near] until bucketStart[near + 1]) {
                    val sample = bucketSamples[index]
                    if (samples.plate[sample] != plate || removed[sample]) continue
                    val cosine = samples.x[sample] * cx + samples.y[sample] * cy + samples.z[sample] * cz
                    if (cosine < minimumCosine) continue
                    total += weight(acos(cosine.coerceIn(-1.0, 1.0)) * radiusKm, sample)
                }
            }
            if (total <= 0.0) return
            sphere.forEachWithin(cx, cy, cz, reach + sphere.spacingRadians) { near, _ ->
                for (index in bucketStart[near] until bucketStart[near + 1]) {
                    val sample = bucketSamples[index]
                    if (samples.plate[sample] != plate || removed[sample]) continue
                    val cosine = samples.x[sample] * cx + samples.y[sample] * cy + samples.z[sample] * cz
                    if (cosine < minimumCosine) continue
                    val share = weight(acos(cosine.coerceIn(-1.0, 1.0)) * radiusKm, sample) / total
                    if (share > 0.0) visit(sample, share)
                }
            }
        }

        /** Records an orogeny on a sample: when, and what kind. */
        private fun markOrogeny(sample: Int, agoEnd: Double, kind: OrogenyKind) {
            samples.orogenyMyrAgo[sample] = agoEnd.toFloat()
            samples.orogenyKind[sample] = kind.ordinal.toByte()
        }

        /**
         * A continent beginning to part is stretched: thinned by McKenzie's beta, four at the break
         * and falling to one inland over the rifted margin's width, and the volume the thinning frees
         * is held for the stretched crust the opening lays beside it ([fillGaps]).
         */
        private fun stretchMargins(riftCells: List<Int>, agoEnd: Double) {
            val widthKm = tectonics.riftedMarginWidthKm / STRETCHED_WIDTH_PER_UNSTRETCHED
            val efoldKm = widthKm / MARGIN_FALLOFFS
            val (distanceKm, source) = sphere.distanceFlood(riftCells.toIntArray(), reachKm = widthKm)
            for (sample in 0 until samples.count) {
                if (removed[sample] || !samples.continental[sample] || !samples.riftMyrAgo[sample].isNaN()) continue
                val cell = sampleCell[sample]
                if (source[cell] < 0 || owner[source[cell]] != samples.plate[sample]) continue
                val distance = distanceKm[cell]
                if (distance > widthKm) continue
                val beta = 1.0 + (BETA_AT_BREAK - 1.0) * exp(-distance / efoldKm)
                val before = samples.thicknessKm(sample)
                samples.baseKm[sample] = (samples.baseKm[sample] / beta).toFloat()
                samples.excessKm[sample] = (samples.excessKm[sample] / beta).toFloat()
                val record = plates[samples.plate[sample]]
                record.stretchPoolKm3 += (before - samples.thicknessKm(sample)).toDouble() * sampleAreaKm2
                record.stretchPoolAreaKm2 += (beta - 1.0) * sampleAreaKm2
                samples.riftMyrAgo[sample] = agoEnd.toFloat()
                if (distance < BELT_EFOLD_KM) markOrogeny(sample, agoEnd, OrogenyKind.RIFT)
            }
        }

        /**
         * Each continental sample that went under another continent becomes thickness in the belt:
         * its volume spread over the continental crust of both plates within [BELT_REACH_KM] of where
         * it went down, falling off over [BELT_EFOLD_KM], so the convergence is a flux through a
         * deforming belt rather than one column stacked on another.
         */
        private fun distributeCollisions(collisions: List<Collision>, agoEnd: Double) {
            // Only crust at the surface takes the belt's thickness: a sample hidden under another
            // plate's crust is already on its way down, and would carry the volume out of the flow.
            val atSurface = BooleanArray(samples.count)
            for (cell in 0 until cellCount) if (ownerSample[cell] >= 0) atSurface[ownerSample[cell]] = true
            for (collision in collisions) {
                val sample = collision.sample
                val volumeKm3 = samples.thicknessKm(sample).toDouble() * sampleAreaKm2
                val cell = sampleCell[sample]
                var given = 0.0
                for (plate in intArrayOf(collision.overPlate, collision.underPlate)) {
                    var total = 0.0
                    spread(cell, plate, BELT_REACH_KM, { distanceKm, near ->
                        if (samples.continental[near] && atSurface[near]) exp(-distanceKm / BELT_EFOLD_KM) else 0.0
                    }) { _, _ -> total += 1.0 }
                    if (total > 0.0) given += 1.0
                }
                if (given == 0.0) {
                    // Nothing continental within reach to take it: the crust goes to the mantle.
                    budget.recycledAtCollisionsKm3 += volumeKm3
                } else {
                    for (plate in intArrayOf(collision.overPlate, collision.underPlate)) {
                        spread(cell, plate, BELT_REACH_KM, { distanceKm, near ->
                            if (samples.continental[near] && atSurface[near]) exp(-distanceKm / BELT_EFOLD_KM) else 0.0
                        }) { near, share ->
                            val km = (volumeKm3 / given * share / sampleAreaKm2).toFloat()
                            samples.excessKm[near] += km
                            if (km > OROGENY_MARK_KM) markOrogeny(near, agoEnd, OrogenyKind.COLLISION)
                        }
                    }
                }
                budget.shortenedAreaKm2 += sampleAreaKm2
            }
        }

        /**
         * Thick crust flows out from under itself and orogens wear down.
         *
         * The flow is Bird's (*Lateral extrusion of lower crust from under high topography, in the
         * isostatic limit*, JGR 96, 1991): the lower crust, pressed by the load above it, flows as a
         * channel with a flux going as the cube of the slope and the tenth power of its absolute
         * temperature, insignificant under shields and dominant under plateaus; isolated highs settle
         * to flat-topped pancakes and neighbors merge. Here the temperature is taken as the crust's
         * thickness against the standard column's, the Moho's temperature on a linear geotherm, and
         * the flux's scale is set from Bird's Tibet: features 400 km across survive about 20 Myr there
         * ([FLOW_COEFFICIENT_KM2_PER_MYR]). The flow moves volume from sample to sample across the
         * lattice, so it conserves it to the bit. A sample that owns several points stands at each of
         * them with a share of its area, so a point's volume is its share's and no sample can give
         * away more crust than it has.
         *
         * The wear is erosion: an orogen's excess falls as `exp(-t / orogenDecayTime)`, the decay of
         * an unforced orogen the settings carry, and the volume it loses leaves the continental crust
         * as sediment.
         */
        private var stiffEdgeTally = 0
        private var stiffCycleTally = 0

        private fun flowAndWear() {
            val thickness = FloatArray(cellCount)
            val flows = BooleanArray(cellCount)
            val continent = BooleanArray(cellCount)
            val pointsOwned = IntArray(samples.count)
            for (cell in 0 until cellCount) {
                val sample = ownerSample[cell]
                if (sample < 0 || removed[sample]) continue
                thickness[cell] = samples.thicknessKm(sample)
                flows[cell] = true
                continent[cell] = samples.continental[sample]
                pointsOwned[sample]++
            }
            // Each point's share of its sample's area: the point stands for that much crust.
            val areaShare = FloatArray(cellCount) { cell ->
                val sample = ownerSample[cell]
                if (flows[cell]) 1f / pointsOwned[sample] else 1f
            }
            val before = thickness.copyOf()
            val change = FloatArray(cellCount)
            // Which of a point's edges toward a higher-numbered point flowed too fast for the
            // substep, one bit per neighbor slot, for the stiff pass below.
            val stiffEdges = IntArray(cellCount)
            val dtMyr = stepMyr / FLOW_SUBSTEPS
            repeat(FLOW_SUBSTEPS) {
                // Each point gathers what crosses each of its edges, worked out the same way from
                // either end, so the two ends see equal and opposite amounts and the passes can run
                // in parallel and still move nothing out of the crust. An edge whose flow would
                // level more than its share within the substep is left to the stiff pass.
                parallelChunks(0, cellCount) { from, to ->
                    for (cell in from until to) {
                        change[cell] = 0f
                        stiffEdges[cell] = 0
                        if (!flows[cell]) continue
                        var gained = 0.0
                        for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                            val next = sphere.neighbors[index]
                            // Under the oceans the flow is nothing (Bird 1991); a continent's thick
                            // crust may still escape sideways onto the floor beside it.
                            if (!flows[next] || (!continent[cell] && !continent[next])) continue
                            val low = min(cell, next)
                            val high = max(cell, next)
                            val shareCap = edgeShareCap(low, high, min(areaShare[low], areaShare[high]))
                            if (edgeRate(low, high, thickness[low], thickness[high]) * dtMyr > shareCap) {
                                if (cell == low) stiffEdges[cell] = stiffEdges[cell] or (1 shl (index - sphere.neighborStart[cell]))
                                continue
                            }
                            val moved = edgeFlow(low, high, thickness[low], thickness[high], dtMyr, min(areaShare[low], areaShare[high]))
                            gained += if (cell == low) -moved else moved
                        }
                        // The volume a point gains spreads over the area it stands for.
                        change[cell] = (gained / areaShare[cell]).toFloat()
                    }
                }
                for (cell in 0 until cellCount) if (flows[cell]) thickness[cell] += change[cell]
                flowStiffEdges(stiffEdges, thickness, areaShare, dtMyr)
            }
            widenPastTheForces(thickness, flows, continent, areaShare)
            // Each point's gain or loss goes to the sample that owns it, by the share of the sample's
            // area the point stood for, so the volume moved still sums to nothing.
            for (cell in 0 until cellCount) {
                if (!flows[cell]) continue
                val sample = ownerSample[cell]
                val gained = (thickness[cell] - before[cell]) * areaShare[cell]
                // A floor sample that became continent at its first point takes its second point's
                // share as a continent's, so it is booked once.
                if (continent[cell] || samples.continental[sample]) {
                    samples.excessKm[sample] += gained
                } else if (gained > 0f) {
                    // Crust escaped onto ocean floor: it leaves the continent's books until the floor
                    // has gathered enough to stand as continent, as an arc's crust does.
                    samples.baseKm[sample] += gained
                    budget.escapedOntoOceanKm3 += gained.toDouble() * sampleAreaKm2
                    if (samples.baseKm[sample] >= ARC_CONTINENT_KM) {
                        samples.continental[sample] = true
                        samples.formedMyrAgo[sample] = stepAgoEnd.toFloat()
                        markOrogeny(sample, stepAgoEnd, OrogenyKind.COLLISION)
                        budget.escapedAreaMadeKm2 += sampleAreaKm2
                        budget.escapedBecameContinentKm3 += samples.thicknessKm(sample).toDouble() * sampleAreaKm2
                    }
                }
            }
            for (cell in 0 until cellCount) {
                if (!flows[cell] || !continent[cell]) continue
                val sample = ownerSample[cell]
                if (samples.excessKm[sample] < 0f) {
                    samples.baseKm[sample] += samples.excessKm[sample]
                    samples.excessKm[sample] = 0f
                }
            }
            var thickest = 0f
            for (cell in 0 until cellCount) if (flows[cell] && continent[cell]) thickest = max(thickest, thickness[cell])
            trace?.invoke("  flow at ago=$stepAgoEnd: stiff edges $stiffEdgeTally, most cycles $stiffCycleTally, thickest ${"%.1f".format(thickest)} km")
            stiffEdgeTally = 0
            stiffCycleTally = 0
            val keep = exp(-stepMyr / orogenDecayMyr).toFloat()
            for (sample in 0 until samples.count) {
                if (removed[sample] || !samples.continental[sample]) continue
                val excess = samples.excessKm[sample]
                if (excess <= 0f) continue
                samples.excessKm[sample] = excess * keep
                samples.stepWearKm[sample] += excess - samples.excessKm[sample]
                budget.erodedFromOrogensKm3 += (excess - samples.excessKm[sample]).toDouble() * sampleAreaKm2
            }
        }

        /**
         * The thickness moved from [low] to [high] across their edge in [dtMyr], km: positive from
         * the thicker to the thinner. The graph Laplacian of a near-hexagonal lattice is `4 / (n s^2)`
         * per neighbor, `n` taken as the larger of the two points' counts so the edge is the same
         * from either end; and never more crosses one edge in a substep than would level the pair,
         * shared among the neighbors and scaled to the smaller [areaShare] of the two, which is the
         * explicit scheme's stability and not a limit on the flow, since a pair that fast levels
         * within the substep anyway. In thickness over a whole point's area.
         */
        private fun edgeFlow(low: Int, high: Int, lowKm: Float, highKm: Float, dtMyr: Double, areaShare: Float): Double {
            val share = min(edgeRate(low, high, lowKm, highKm) * dtMyr, edgeShareCap(low, high, areaShare))
            return share * (lowKm - highKm)
        }

        /** How fast the flow across the edge levels its two ends, per Myr: the diffusivity over the edge's Laplacian scale. */
        private fun edgeRate(low: Int, high: Int, lowKm: Float, highKm: Float): Double {
            val distance = sphere.distanceKm(low, high)
            val neighborCount = edgeNeighborCount(low, high)
            return edgeDiffusivity(lowKm, highKm, distance) * LAPLACIAN_PER_NEIGHBOR / (neighborCount * distance * distance)
        }

        /** The most of the difference across an edge one step may move: what keeps a point's total outflow under half. */
        private fun edgeShareCap(low: Int, high: Int, areaShare: Float): Double = areaShare / (2.0 * edgeNeighborCount(low, high))

        private fun edgeNeighborCount(low: Int, high: Int): Int = max(
            sphere.neighborStart[low + 1] - sphere.neighborStart[low],
            sphere.neighborStart[high + 1] - sphere.neighborStart[high]
        )

        /**
         * Crust thicker than the plates' forces can hold spreads out over the ground round it.
         *
         * Thickening a belt stores gravitational potential energy in it, and the forces driving the
         * plates together are what supply it; once they can supply no more, further convergence
         * widens the belt instead of raising it (Molnar and Lyon-Caen, *Some simple physical aspects
         * of the support, structure, and evolution of mountain belts*, GSA Special Paper 218, 1988,
         * as Pusok and Kaus give the argument in their supporting information to *Development of
         * topography in 3D continental collision models*, G-cubed 16, 2015). The largest such force
         * Earth shows is Tibet's, whose crust is [TIBET_EXCESS_KM] past the standard column: so the
         * crust of a plateau past Tibet's is laid on the ground beside it instead, nearest first by
         * distance on the sphere, onto the floor beside a continent where the continent has no room,
         * as the lower crust's flow escapes. Every point's volume moves to another, so the crust's
         * volume is kept to the bit; a belt's height is what its crust's volume and its width make.
         */
        private fun widenPastTheForces(thickness: FloatArray, flows: BooleanArray, continent: BooleanArray, areaShare: FloatArray) {
            val ceilingKm = (continentalKm + TIBET_EXCESS_KM).toFloat()
            val plateau = IntArray(cellCount) { -1 }
            var plateaus = 0
            for (start in 0 until cellCount) {
                if (plateau[start] >= 0 || !flows[start] || !continent[start] || thickness[start] <= ceilingKm) continue
                // The plateau the over-thick point stands in: its connected crust at the ceiling or past it.
                val members = ArrayList<Int>()
                members.add(start)
                plateau[start] = plateaus
                var head = 0
                while (head < members.size) {
                    val cell = members[head++]
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val next = sphere.neighbors[index]
                        if (plateau[next] >= 0 || !flows[next] || !continent[next] || thickness[next] < ceilingKm) continue
                        plateau[next] = plateaus
                        members.add(next)
                    }
                }
                plateaus++
                var excess = 0.0
                for (cell in members) {
                    if (thickness[cell] > ceilingKm) {
                        excess += (thickness[cell] - ceilingKm).toDouble() * areaShare[cell]
                        thickness[cell] = ceilingKm
                    }
                }
                // As far out as the excess could need if the ground round it stood at the standard
                // column, and twice as far again until it is laid.
                var reachKm = sqrt((members.size + excess / (ceilingKm - continentalKm)) / PI) * spacingKm + WIDENING_MARGIN_SPACINGS * spacingKm
                val sources = members.toIntArray()
                while (excess > 0.0 && reachKm < PI * radiusKm) {
                    val (distanceKm, _) = sphere.distanceFlood(sources, allowed = flows, reachKm = reachKm)
                    var found = 0
                    var keys = LongArray(64)
                    for (cell in 0 until cellCount) {
                        if (plateau[cell] >= 0 || !flows[cell] || distanceKm[cell] == Double.MAX_VALUE || thickness[cell] >= ceilingKm) continue
                        if (found == keys.size) keys = keys.copyOf(found * 2)
                        keys[found++] = floodKey(distanceKm[cell], cell)
                    }
                    keys = keys.copyOf(found)
                    keys.sort()
                    for (key in keys) {
                        if (excess <= 0.0) break
                        val cell = (key and 0xFFFFFFFFL).toInt()
                        val room = (ceilingKm - thickness[cell]).toDouble() * areaShare[cell]
                        val laid = min(room, excess)
                        thickness[cell] += (laid / areaShare[cell]).toFloat()
                        excess -= laid
                    }
                    reachKm *= 2.0
                }
                // A world with no ground left to widen onto keeps what it could not lay.
                if (excess > 0.0) {
                    thickness[start] += (excess / areaShare[start]).toFloat()
                }
            }
        }

        /**
         * The edges [stiffEdges] marks, whose flow would level them faster than the substep [dtMyr]
         * can carry: integrated over the substep in as many shorter ones as their fastest needs, so
         * the lower crust under a thickened belt spreads as fast as Bird's flux says and not as fast
         * as an explicit step allows, up to [MAX_FLOW_SUBCYCLES]. Every edge moves equal and opposite
         * volumes, so the crust's volume is kept to the bit as the ordinary pass keeps it.
         */
        private fun flowStiffEdges(stiffEdges: IntArray, thickness: FloatArray, areaShare: FloatArray, dtMyr: Double) {
            var lows = IntArray(0)
            var highs = IntArray(0)
            var count = 0
            var fastest = 0.0
            for (cell in 0 until cellCount) {
                val bits = stiffEdges[cell]
                if (bits == 0) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    if (bits and (1 shl (index - sphere.neighborStart[cell])) == 0) continue
                    val next = sphere.neighbors[index]
                    if (count == lows.size) {
                        lows = lows.copyOf(max(16, count * 2))
                        highs = highs.copyOf(max(16, count * 2))
                    }
                    lows[count] = cell
                    highs[count] = next
                    count++
                    val share = min(areaShare[cell], areaShare[next])
                    fastest = max(fastest, edgeRate(cell, next, thickness[cell], thickness[next]) * dtMyr / edgeShareCap(cell, next, share))
                }
            }
            if (count == 0) return
            val cycles = min(MAX_FLOW_SUBCYCLES, kotlin.math.ceil(fastest).toInt())
            stiffEdgeTally += count
            stiffCycleTally = max(stiffCycleTally, cycles)
            val cycleMyr = dtMyr / cycles
            val moved = DoubleArray(count)
            repeat(cycles) {
                for (edge in 0 until count) {
                    val low = lows[edge]
                    val high = highs[edge]
                    moved[edge] = edgeFlow(low, high, thickness[low], thickness[high], cycleMyr, min(areaShare[low], areaShare[high]))
                }
                for (edge in 0 until count) {
                    thickness[lows[edge]] -= (moved[edge] / areaShare[lows[edge]]).toFloat()
                    thickness[highs[edge]] += (moved[edge] / areaShare[highs[edge]]).toFloat()
                }
            }
        }

        /**
         * The lower crust's diffusivity across one edge, km² per Myr: Bird's cubic flux written as a
         * diffusivity that goes as the slope squared and the temperature to the tenth.
         */
        private fun edgeDiffusivity(first: Float, second: Float, distanceKm: Double): Double {
            val mean = 0.5 * (first + second) / continentalKm
            val slope = (first - second) / distanceKm
            // The tenth power by squaring: this runs for every edge of every substep.
            val squared = mean * mean
            val fourth = squared * squared
            val tenth = fourth * fourth * squared
            return FLOW_COEFFICIENT_KM2_PER_MYR * tenth * slope * slope
        }

        /**
         * A small piece of continent that went under another this step welds onto it: a terrane,
         * carried in on the ocean and scraped off, as Cortial et al. move a terrane's samples to the
         * plate it hits. The plate that carried it goes on with whatever else it holds, its ocean
         * floor now going down in front of the welded piece. Two large continents are not welded:
         * they go on colliding, slowed by each other ([PlateForces.COLLISION_RESISTANCE]), for as
         * long as their forces drive them together, as India has for fifty million years, and part
         * again along the suture if their forces turn; whole plates are never merged by a collision,
         * which is what gathered most of the planet into one plate in the design's prototype.
         */
        private fun weldLockedCollisions(collisions: List<Collision>) {
            val pairs = collisions.map { it.underPlate.toLong() * PAIR_STRIDE + it.overPlate }.distinct().sorted()
            for (pair in pairs) {
                val under = (pair / PAIR_STRIDE).toInt()
                val over = (pair % PAIR_STRIDE).toInt()
                if (!plates[under].alive || !plates[over].alive) continue
                val contact = collisions.filter { it.underPlate == under && it.overPlate == over }.map { sampleCell[it.sample] }
                val underPiece = continentPiece(under, over)
                val overPiece = continentPiece(over, under)
                if (underPiece.isEmpty() || overPiece.isEmpty()) continue
                val smaller = if (underPiece.size <= overPiece.size) underPiece else overPiece
                val from = if (smaller === underPiece) under else over
                val to = if (smaller === underPiece) over else under
                if (smaller.size > terraneMaximumCells()) continue
                transferPiece(smaller, from, to)
                sutureCount++
            }
        }

        /** A tenth of a craton block of the supercontinent, in lattice points: the largest piece that docks as a terrane. */
        private fun terraneMaximumCells(): Int {
            return max(1, (targetContinentalShare() * cellCount / cratonCount() * TERRANE_SHARE_OF_CRATON).roundToInt())
        }

        /** [plate]'s continental points connected to its boundary with [other]'s continent. */
        private fun continentPiece(plate: Int, other: Int): List<Int> {
            val starts = ArrayList<Int>()
            for (cell in 0 until cellCount) {
                if (owner[cell] != plate || !samples.continental[ownerSample[cell]]) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (owner[next] == other && samples.continental[ownerSample[next]]) {
                        starts.add(cell)
                        break
                    }
                }
            }
            if (starts.isEmpty()) return emptyList()
            val seen = BooleanArray(cellCount)
            val piece = ArrayList<Int>()
            for (start in starts) {
                if (seen[start]) continue
                seen[start] = true
                piece.add(start)
            }
            var head = 0
            while (head < piece.size) {
                val cell = piece[head++]
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (seen[next] || owner[next] != plate || !samples.continental[ownerSample[next]]) continue
                    seen[next] = true
                    piece.add(next)
                }
            }
            return piece
        }

        /** Moves the samples at [cells] from plate [from] to plate [to], marking the welded edge. */
        private fun transferPiece(cells: List<Int>, from: Int, to: Int) {
            val inPiece = BooleanArray(cellCount)
            for (cell in cells) inPiece[cell] = true
            for (sample in 0 until samples.count) {
                if (removed[sample] || samples.plate[sample] != from) continue
                if (!inPiece[sampleCell[sample]]) continue
                samples.plate[sample] = to
                if (samples.continental[sample]) markOrogeny(sample, stepAgoEnd, OrogenyKind.ACCRETED_TERRANE)
            }
            for (cell in cells) if (owner[cell] == from) owner[cell] = to
        }

        /** Drops removed samples, retires plates with no crust, and folds tiny plates into a neighbor. */
        private fun compact() {
            val gone = BooleanArray(samples.count) { removed[it] }
            val oldCount = samples.count
            val newIndex = IntArray(oldCount)
            var next = 0
            for (sample in 0 until oldCount) newIndex[sample] = if (gone[sample]) -1 else next++
            for (cell in 0 until cellCount) {
                val sample = ownerSample[cell]
                ownerSample[cell] = if (sample >= 0 && sample < oldCount) newIndex[sample] else -1
                if (ownerSample[cell] < 0) owner[cell] = -1
            }
            val cells = sampleCell.copyOf()
            samples.removeWhere(gone)
            for (sample in 0 until oldCount) if (newIndex[sample] >= 0) sampleCell[newIndex[sample]] = cells[sample]
            removed.fill(false)
            val counts = IntArray(plates.size)
            for (sample in 0 until samples.count) counts[samples.plate[sample]]++
            for (plate in plates) {
                if (plate.alive && counts[plate.id] == 0) plate.alive = false
            }
            for (plate in plates) {
                if (!plate.alive || counts[plate.id] >= MIN_PLATE_CELLS) continue
                val into = neighborPlate(plate.id) ?: continue
                for (sample in 0 until samples.count) if (samples.plate[sample] == plate.id) samples.plate[sample] = into
                for (cell in 0 until cellCount) if (owner[cell] == plate.id) owner[cell] = into
                plate.alive = false
            }
        }

        /** The plate sharing the longest boundary with [plate], or null if it touches none. */
        private fun neighborPlate(plate: Int): Int? {
            val shared = IntArray(plates.size)
            for (cell in 0 until cellCount) {
                if (owner[cell] != plate) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = owner[sphere.neighbors[index]]
                    if (next >= 0 && next != plate && plates[next].alive) shared[next]++
                }
            }
            var best = -1
            for (candidate in shared.indices) if (shared[candidate] > 0 && (best < 0 || shared[candidate] > shared[best])) best = candidate
            return if (best < 0) null else best
        }

        // ------------------------------------------------------------------------------ splitting

        /**
         * A planet short of plates, or a plate larger than Earth's largest, splits a plate where its
         * forces pull it apart. Cortial et al. rift a plate with a chance that grows with its area;
         * here the largest plate is the one tried, along the cheapest of several paths through its
         * weakest ground, and only a split whose two halves the forces would carry apart is made, so a
         * plate held together by its forces stays whole and no continent is dispersed by rule.
         */
        private fun maintainPlates(agoEnd: Double) {
            val sizes = plateSizes()
            val live = plates.count { it.alive }
            val largest = (0 until plates.size).filter { plates[it].alive }.maxByOrNull { sizes[it] } ?: return
            val tooLarge = sizes[largest] > LARGEST_PLATE_SHARE * cellCount
            if (live >= targetPlates && !tooLarge) return
            // A plate tried and held together by its forces is not tried again until they have had
            // time to change: the forces move with the boundaries, which take steps to move. A plate
            // of the assembled supercontinent is tried only when it has grown larger than Earth's
            // largest, and then only through its ocean floor (see [splitForForces]).
            val candidates = (0 until plates.size)
                .filter {
                    plates[it].alive && sizes[it] >= MIN_SPLIT_CELLS && plates[it].heldTogetherUntilMyrAgo > agoEnd &&
                        (!plates[it].assembled || (tooLarge && it == largest))
                }
                .sortedByDescending { sizes[it] }
                .take(SPLIT_PLATES_TRIED)
            for (plate in candidates) {
                if (splitForForces(plate, agoEnd, initiate = tooLarge && plate == largest)) return
                plates[plate].heldTogetherUntilMyrAgo = agoEnd - SPLIT_RETRY_STEPS * stepMyr
            }
        }

        private fun plateSizes(): IntArray {
            val sizes = IntArray(plates.size)
            for (cell in 0 until cellCount) if (owner[cell] >= 0) sizes[owner[cell]]++
            return sizes
        }

        private fun livePlateCount(): Int = plates.count { it.alive }

        private fun largestPlate(eligible: (Int) -> Boolean): Int? {
            val sizes = plateSizes()
            return (0 until plates.size).filter { plates[it].alive && eligible(it) }.maxByOrNull { sizes[it] }
        }

        /** The live plates' shares of the sphere, largest first, for the trace. */
        private fun shareSummary(): String {
            val sizes = plateSizes()
            return (0 until plates.size).filter { plates[it].alive && sizes[it] > 0 }
                .sortedByDescending { sizes[it] }
                .joinToString(",") { "${(1000 * sizes[it] / cellCount)}${if (plates[it].assembled) "a" else ""}" }
        }

        /**
         * The split of the assembled world's construction: the weakest of several paths, the one
         * whose ground is softest on the mean, each leaving both halves at least [MIN_SPLIT_SHARE] of
         * the plate. A plate parts where its rock is weakest (Vauchez, Barruol and Tommasi 1997), and
         * taking the most even cut instead stacks a long plate into parallel strips.
         */
        private fun splitAlongWeakestPath(plate: Int): Boolean {
            val cut = candidateCuts(plate).filterNotNull().minByOrNull { it.meanHardness } ?: return false
            makeSplit(plate, cut.sides)
            return true
        }

        /**
         * A split made only where it opens: of [SPLIT_CANDIDATES] paths, the one whose halves the
         * forces now on the plate carry apart fastest, if any opens faster than plates can be told
         * apart at all.
         */
        private fun splitForForces(plate: Int, agoEnd: Double, initiate: Boolean): Boolean {
            var best: IntArray? = null
            var bestOpening = LOCK_RATE_KM_PER_MYR
            var bestRotations: Pair<DoubleArray, DoubleArray>? = null
            // Where nothing opens a plate larger than Earth's largest, a new boundary through its
            // ocean floor: a ridge jump or a subduction zone begun at a weakness of the old floor, as
            // the Izu-Bonin trench began in the western Pacific's. The cut crossing the least
            // continent is the one taken.
            var initiated: IntArray? = null
            var initiatedContinent = Int.MAX_VALUE
            var initiatedRotations: Pair<DoubleArray, DoubleArray>? = null
            val scale = currentScale(plate)
            var paths = 0
            var widest = Double.NEGATIVE_INFINITY
            // A plate of the assembled supercontinent parts only through its ocean floor: the floor
            // round a supercontinent breaks into plates of its own, as Panthalassa's did, while the
            // supercontinent stands until its own breakup.
            val oceanOnly = plates[plate].assembled
            for (cut in candidateCuts(plate)) {
                if (cut == null) continue
                val sides = cut.sides
                if (oceanOnly && continentOnCut(sides) > 0) continue
                paths++
                val halves = halfRotations(plate, sides)
                val first = halves.first.map { it * scale }.toDoubleArray()
                val second = halves.second.map { it * scale }.toDoubleArray()
                val opening = openingAcross(sides, first, second)
                widest = max(widest, opening)
                if (initiate) {
                    val continentOnCut = continentOnCut(sides)
                    if (continentOnCut < initiatedContinent) {
                        initiatedContinent = continentOnCut
                        initiated = sides
                        initiatedRotations = first to second
                    }
                }
                if (opening > bestOpening) {
                    bestOpening = opening
                    best = sides
                    bestRotations = first to second
                }
            }
            trace?.invoke("  split try plate=$plate paths=$paths scale=$scale bestOpening=$widest initiate=$initiate")
            if (best == null && initiated != null) {
                best = initiated
                bestRotations = initiatedRotations
            }
            val sides = best ?: return false
            val created = makeSplit(plate, sides)
            val rotations = bestRotations!!
            plates[plate].setOmega(rotations.first[0], rotations.first[1], rotations.first[2])
            plates[created].setOmega(rotations.second[0], rotations.second[1], rotations.second[2])
            splitCount++
            return true
        }

        /** The scale that turned this plate's forces into its present rotation, so its halves move on the same footing. */
        private fun currentScale(plate: Int): Double {
            val raw = halfRotations(plate, IntArray(cellCount) { if (owner[it] == plate) 0 else -1 }).first
            val rawRate = sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2])
            val p = plates[plate]
            val rate = sqrt(p.omegaX * p.omegaX + p.omegaY * p.omegaY + p.omegaZ * p.omegaZ)
            if (rawRate <= 0.0 || rate <= 0.0) return globalScaleGuess()
            return rate / rawRate
        }

        /** Earth's median speed over a force-balanced rotation of unit torque, a fallback scale for a still plate. */
        private fun globalScaleGuess(): Double {
            val raw = Array(plates.size) { DoubleArray(3) }
            val balances = forceBalances()
            for (plate in plates) if (plate.alive) raw[plate.id] = balances[plate.id].solve()
            return speedScale(raw) ?: 0.0
        }

        /** The force-balanced rotations of a plate's two halves under the forces booked on it last step. */
        private fun halfRotations(plate: Int, sides: IntArray): Pair<DoubleArray, DoubleArray> {
            val halves = arrayOf(TorqueBalance(), TorqueBalance())
            for (cell in 0 until cellCount) {
                val side = sides[cell]
                if (side < 0) continue
                halves[side].addDrag(sphere.x[cell], sphere.y[cell], sphere.z[cell], 1.0)
            }
            for (event in 0 until forces.count) {
                if (forces.plate[event] != plate) continue
                val cell = forces.point[event]
                val side = sides[cell]
                if (side < 0) continue
                halves[side].addForce(sphere.x[cell], sphere.y[cell], sphere.z[cell], forces.forceX[event], forces.forceY[event], forces.forceZ[event])
            }
            val resistance = slabResistancePerPoint
            for (event in 0 until forces.resistCount) {
                if (forces.resistPlate[event] != plate) continue
                val cell = forces.resistPoint[event]
                val side = sides[cell]
                if (side < 0) continue
                halves[side].addLineDrag(sphere.x[cell], sphere.y[cell], sphere.z[cell], forces.resistX[event], forces.resistY[event], forces.resistZ[event], resistance)
            }
            return halves[0].solve() to halves[1].solve()
        }

        /** How many continental points lie along a cut, on its first side. */
        private fun continentOnCut(sides: IntArray): Int {
            var count = 0
            for (cell in 0 until cellCount) {
                if (sides[cell] != 0 || !samples.continental[ownerSample[cell]]) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    if (sides[sphere.neighbors[index]] == 1) {
                        count++
                        break
                    }
                }
            }
            return count
        }

        /** The mean rate the two halves open at across the cut, km per Myr; negative where they close. */
        private fun openingAcross(sides: IntArray, first: DoubleArray, second: DoubleArray): Double {
            var sum = 0.0
            var count = 0
            for (cell in 0 until cellCount) {
                if (sides[cell] != 0) continue
                var sx = 0.0; var sy = 0.0; var sz = 0.0
                var touches = false
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (sides[next] != 1) continue
                    touches = true
                    sx += sphere.x[next] - sphere.x[cell]; sy += sphere.y[next] - sphere.y[cell]; sz += sphere.z[next] - sphere.z[cell]
                }
                if (!touches) continue
                val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
                val radial = sx * px + sy * py + sz * pz
                sx -= radial * px; sy -= radial * py; sz -= radial * pz
                val length = sqrt(sx * sx + sy * sy + sz * sz)
                if (length <= 0.0) continue
                val wx = second[0] - first[0]; val wy = second[1] - first[1]; val wz = second[2] - first[2]
                val vx = wy * pz - wz * py
                val vy = wz * px - wx * pz
                val vz = wx * py - wy * px
                sum += (vx * sx + vy * sy + vz * sz) / length * radiusKm
                count++
            }
            return if (count == 0) 0.0 else sum / count
        }

        /** A pole a quarter turn from the plate's centroid, at a drawn bearing, so the plate straddles its equator. */
        private fun poleAcross(plate: Int): DoubleArray? {
            val centroid = plateCentroid(plate) ?: return null
            val other = randomUnit(random)
            var px = centroid[1] * other[2] - centroid[2] * other[1]
            var py = centroid[2] * other[0] - centroid[0] * other[2]
            var pz = centroid[0] * other[1] - centroid[1] * other[0]
            val length = sqrt(px * px + py * py + pz * pz)
            if (length < 1e-9) return null
            px /= length; py /= length; pz /= length
            return doubleArrayOf(px, py, pz)
        }

        private fun plateCentroid(plate: Int): DoubleArray? {
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            var count = 0
            for (cell in 0 until cellCount) {
                if (owner[cell] != plate) continue
                sx += sphere.x[cell]; sy += sphere.y[cell]; sz += sphere.z[cell]
                count++
            }
            val length = sqrt(sx * sx + sy * sy + sz * sz)
            if (count == 0 || length < 1e-9) return null
            return doubleArrayOf(sx / length, sy / length, sz / length)
        }

        /**
         * A plate cut in two along a connected path: the cheapest path across it from the stretch of
         * its edge nearest [pole] to the stretch farthest from it, each step costing its length times
         * how hard the ground is to break. A craton nucleus is four times as hard as ordinary ground
         * and an old suture or belt half as hard (Vauchez, Barruol and Tommasi 1997: continents break
         * parallel to old orogens, and craton nuclei stop a rift); a noise on the sphere roughens the
         * cost; and through ocean floor a step square to the spreading, or along it, is cheaper than one
         * between, so the path comes out as ridge segments offset by transforms. Returns the side of
         * each of the plate's points, -1 off the plate, or null if the path does not cut it.
         */
        private fun splitAlongPath(ground: SplitGround, pole: DoubleArray, via: Int): Cut? {
            val inPlate = ground.inPlate
            val edge = ground.edge
            val hardness = ground.hardness
            val angleFromPole = DoubleArray(cellCount)
            for (cell in edge) {
                angleFromPole[cell] = acos((sphere.x[cell] * pole[0] + sphere.y[cell] * pole[1] + sphere.z[cell] * pole[2]).coerceIn(-1.0, 1.0))
            }
            val nearest = edge.minOf { angleFromPole[it] }
            val farthest = edge.maxOf { angleFromPole[it] }
            val band = SPLIT_END_BAND_SPACINGS * sphere.spacingRadians
            if (farthest - nearest < 4 * band) return null
            val starts = edge.filter { angleFromPole[it] <= nearest + band }
            val ends = BooleanArray(cellCount)
            for (cell in edge) if (angleFromPole[cell] >= farthest - band) ends[cell] = true
            val viaOnly = BooleanArray(cellCount).also { it[via] = true }
            val toVia = shortestPath(starts, viaOnly, inPlate, hardness, pole) ?: return null
            val fromVia = shortestPath(listOf(via), ends, inPlate, hardness, pole) ?: return null
            val path = toVia + fromVia
            val sides = sidesOfCut(inPlate, path) ?: return null
            return Cut(sides, path.sumOf { hardness[it] } / path.size)
        }

        /** A cut across a plate: the side of each of its points, -1 off it, and how hard its path's ground is on the mean. */
        private class Cut(val sides: IntArray, val meanHardness: Double)

        /**
         * [SPLIT_CANDIDATES] cuts across [plate], each from a drawn pole and a drawn deep point, null
         * where one does not cut it. The draws are made in turn, so the cuts are the same however
         * many workers find them; the paths are found side by side, since each only reads.
         */
        private fun candidateCuts(plate: Int): List<Cut?> {
            val ground = splitGround(plate) ?: return emptyList()
            val draws = ArrayList<Pair<DoubleArray, Int>>()
            repeat(SPLIT_CANDIDATES) {
                val pole = poleAcross(plate) ?: return@repeat
                draws.add(pole to ground.deep[random.nextInt(ground.deep.size)])
            }
            val cuts = arrayOfNulls<Cut>(draws.size)
            parallelChunks(0, draws.size) { from, to ->
                for (index in from until to) cuts[index] = splitAlongPath(ground, draws[index].first, draws[index].second)
            }
            return cuts.toList()
        }

        /**
         * What every candidate cut across one plate shares, worked out once: the plate's points, its
         * edge, how hard each point is to break, and the deep points a cut runs by way of.
         */
        private class SplitGround(val inPlate: BooleanArray, val edge: List<Int>, val hardness: DoubleArray, val deep: List<Int>)

        private fun splitGround(plate: Int): SplitGround? {
            val inPlate = BooleanArray(cellCount) { owner[it] == plate }
            val edge = ArrayList<Int>()
            for (cell in 0 until cellCount) {
                if (!inPlate[cell]) continue
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    if (!inPlate[sphere.neighbors[index]]) {
                        edge.add(cell)
                        break
                    }
                }
            }
            if (edge.size < 2) return null
            val noise = SphereNoise(config.seed * SPLIT_NOISE_SALT + splitCount * 31L + plate)
            val hardness = DoubleArray(cellCount)
            for (cell in 0 until cellCount) {
                if (!inPlate[cell]) continue
                val sample = ownerSample[cell]
                val base = when {
                    !samples.continental[sample] -> 1.0
                    samples.orogenyKind[sample].toInt() == OrogenyKind.NONE.ordinal -> CRATON_HARDNESS
                    else -> WEAK_ZONE_HARDNESS
                }
                hardness[cell] = base * (1.0 + PATH_NOISE * noise.fbmOnSphere(
                    sphere.x[cell], sphere.y[cell], sphere.z[cell], radiusKm, PATH_NOISE_WAVELENGTH_KM,
                    octavesToSpacing(PATH_NOISE_WAVELENGTH_KM), SphereNoise.EARTH_OUTLINE_PERSISTENCE
                ))
                    .coerceAtLeast(MIN_PATH_HARDNESS)
            }
            // Through the plate's middle: by way of a point far from its edge, so a cut across a plate
            // that wraps round a continent does not run along the coast and cut nothing.
            val (edgeDistanceKm, _) = sphere.distanceFlood(edge.toIntArray(), inPlate)
            var deepest = 0.0
            for (cell in 0 until cellCount) if (inPlate[cell] && edgeDistanceKm[cell] != Double.MAX_VALUE) deepest = max(deepest, edgeDistanceKm[cell])
            val deep = (0 until cellCount).filter { inPlate[it] && edgeDistanceKm[it] != Double.MAX_VALUE && edgeDistanceKm[it] >= VIA_DEPTH_SHARE * deepest }
            if (deep.isEmpty()) return null
            return SplitGround(inPlate, edge, hardness, deep)
        }

        /** The two sides of [inPlate] a cut along [path] leaves, as [splitAlongPath] returns them. */
        private fun sidesOfCut(inPlate: BooleanArray, path: List<Int>): IntArray? {
            // A path one point wide can be stepped across between two of its neighbors, so the cut is
            // the path and the ring of points round it.
            val onPath = BooleanArray(cellCount)
            for (cell in path) {
                onPath[cell] = true
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (inPlate[next]) onPath[next] = true
                }
            }
            // The plate less the path, in pieces.
            val piece = IntArray(cellCount) { -1 }
            val pieceSizes = ArrayList<Int>()
            for (start in 0 until cellCount) {
                if (!inPlate[start] || onPath[start] || piece[start] >= 0) continue
                val id = pieceSizes.size
                val queue = ArrayList<Int>()
                queue.add(start)
                piece[start] = id
                var head = 0
                while (head < queue.size) {
                    val cell = queue[head++]
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val next = sphere.neighbors[index]
                        if (!inPlate[next] || onPath[next] || piece[next] >= 0) continue
                        piece[next] = id
                        queue.add(next)
                    }
                }
                pieceSizes.add(queue.size)
            }
            if (pieceSizes.size < 2) return null
            val order = pieceSizes.indices.sortedByDescending { pieceSizes[it] }
            val minimum = MIN_SPLIT_SHARE * (pieceSizes.sum())
            if (pieceSizes[order[1]] < minimum) return null
            // The two largest pieces are the halves; the path and any sliver go to the half they are nearest.
            val sides = IntArray(cellCount) { -1 }
            val seeds = ArrayList<Int>()
            for (cell in 0 until cellCount) {
                if (piece[cell] == order[0]) { sides[cell] = 0; seeds.add(cell) }
                if (piece[cell] == order[1]) { sides[cell] = 1; seeds.add(cell) }
            }
            val (_, source) = sphere.distanceFlood(seeds.toIntArray(), inPlate)
            for (cell in 0 until cellCount) {
                if (!inPlate[cell] || sides[cell] >= 0) continue
                sides[cell] = if (source[cell] >= 0) sides[source[cell]] else 0
            }
            return sides
        }

        /**
         * The cheapest path within [inPlate] from any of [starts] to any point [ends] marks, each step
         * costing its length times the mean [hardness] of its two points, and through ocean floor
         * times [stepAnisotropy] about [pole]; null if none reaches.
         */
        private fun shortestPath(
            starts: List<Int>,
            ends: BooleanArray,
            inPlate: BooleanArray,
            hardness: DoubleArray,
            pole: DoubleArray
        ): List<Int>? {
            val cost = DoubleArray(cellCount) { Double.MAX_VALUE }
            val previous = IntArray(cellCount) { -1 }
            val heap = LongMinHeap(1024)
            for (start in starts) {
                cost[start] = 0.0
                heap.push(floodKey(0.0, start))
            }
            var reached = -1
            while (!heap.isEmpty()) {
                val key = heap.pop()
                val cell = (key and 0xFFFFFFFFL).toInt()
                val here = Float.fromBits((key ushr 32).toInt()).toDouble()
                if (here > cost[cell] * (1 + 1e-6) + 1e-3) continue
                if (ends[cell]) {
                    reached = cell
                    break
                }
                val oceanHere = !samples.continental[ownerSample[cell]]
                for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                    val next = sphere.neighbors[index]
                    if (!inPlate[next]) continue
                    var step = sphere.distanceKm(cell, next) * 0.5 * (hardness[cell] + hardness[next])
                    if (oceanHere && !samples.continental[ownerSample[next]]) step *= stepAnisotropy(cell, next, pole)
                    if (here + step < cost[next]) {
                        cost[next] = here + step
                        previous[next] = cell
                        heap.push(floodKey(cost[next], next))
                    }
                }
            }
            if (reached < 0) return null
            val path = ArrayList<Int>()
            var walk = reached
            while (walk >= 0) {
                path.add(walk)
                walk = previous[walk]
            }
            return path
        }

        /**
         * How much dearer a step through ocean floor is for running obliquely to the spreading: one
         * along the spreading direction (a transform) or square to it (a ridge segment) costs its
         * length, one at 45 degrees [RIDGE_ANISOTROPY] more besides. The spreading direction at a
         * point is the small circle about [pole] the halves will turn along.
         */
        private fun stepAnisotropy(cell: Int, next: Int, pole: DoubleArray): Double {
            val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
            var sx = pole[1] * pz - pole[2] * py
            var sy = pole[2] * px - pole[0] * pz
            var sz = pole[0] * py - pole[1] * px
            val sLength = sqrt(sx * sx + sy * sy + sz * sz)
            if (sLength < 1e-9) return 1.0
            sx /= sLength; sy /= sLength; sz /= sLength
            var tx = sphere.x[next] - px; var ty = sphere.y[next] - py; var tz = sphere.z[next] - pz
            val tLength = sqrt(tx * tx + ty * ty + tz * tz)
            if (tLength < 1e-12) return 1.0
            tx /= tLength; ty /= tLength; tz /= tLength
            val cosine = abs(tx * sx + ty * sy + tz * sz).coerceAtMost(1.0)
            val sine = sqrt(1.0 - cosine * cosine)
            return 1.0 + RIDGE_ANISOTROPY * 2.0 * sine * cosine
        }

        /** Splits [plate] by [sides]: side 1's samples go to a new plate, which is returned. */
        private fun makeSplit(plate: Int, sides: IntArray): Int {
            val created = plates.size
            val parent = plates[plate]
            plates.add(HistoryPlate(created).also {
                it.setOmega(parent.omegaX, parent.omegaY, parent.omegaZ)
                it.assembled = parent.assembled
            })
            val continentOnSide = IntArray(2)
            for (sample in 0 until samples.count) {
                if (samples.plate[sample] != plate) continue
                val cell = sampleCell[sample]
                val side = if (cell >= 0 && sides[cell] == 1) 1 else 0
                if (side == 1) samples.plate[sample] = created
                if (samples.continental[sample]) continentOnSide[side]++
            }
            for (cell in 0 until cellCount) if (owner[cell] == plate && sides[cell] == 1) owner[cell] = created
            // Of the assembled supercontinent's plates, a half with no continent on it is ocean floor
            // and goes its own way.
            if (parent.assembled) {
                if (continentOnSide[0] == 0) parent.assembled = false
                if (continentOnSide[1] == 0) plates[created].assembled = false
            }
            return created
        }

        // ------------------------------------------------------------------------------- present

        private fun continentalAreaKm2(): Double {
            var count = 0
            for (sample in 0 until samples.count) if (samples.continental[sample]) count++
            return count * sampleAreaKm2
        }

        private fun continentalVolumeKm3(): Double {
            var sum = 0.0
            for (sample in 0 until samples.count) if (samples.continental[sample]) sum += samples.thicknessKm(sample).toDouble()
            return sum * sampleAreaKm2
        }

        /** The present, read onto the lattice, with the plates renumbered 0 up in id order. */
        fun present(): CrustHistory {
            assignOwners()
            fillGaps(0.0)
            rebuildBuckets()
            classifyBoundaries(0.0, withEffects = false)
            budget.endingAreaKm2 = continentalAreaKm2()
            budget.endingVolumeKm3 = continentalVolumeKm3()
            budget.stretchingStillHeldKm3 = plates.sumOf { if (it.alive) it.stretchPoolKm3 else 0.0 }
            budget.stretchingLostWithPlatesKm3 = plates.sumOf { if (!it.alive) it.stretchPoolKm3 else 0.0 }
            val live = plates.filter { it.alive }.map { it.id }.filter { id -> (0 until cellCount).any { owner[it] == id } }
            val renumber = IntArray(plates.size) { -1 }
            live.forEachIndexed { index, id -> renumber[id] = index }
            val plateOf = IntArray(cellCount) { if (owner[it] >= 0) renumber[owner[it]] else 0 }
            fun field(read: (Int) -> Float) = FloatArray(cellCount) { read(ownerSample[it]) }
            return CrustHistory(
                sphere = sphere,
                plateOf = plateOf,
                continental = BooleanArray(cellCount) { samples.continental[ownerSample[it]] },
                baseKm = field { samples.baseKm[it] },
                excessKm = field { samples.excessKm[it] },
                formedMyrAgo = field { samples.formedMyrAgo[it] },
                orogenyMyrAgo = field { samples.orogenyMyrAgo[it] },
                orogenyKind = IntArray(cellCount) { samples.orogenyKind[ownerSample[it]].toInt() },
                terrane = IntArray(cellCount) { samples.terrane[ownerSample[it]] },
                riftMyrAgo = field { samples.riftMyrAgo[it] },
                boundaryClass = boundaryClass.copyOf(),
                boundaryOtherPlate = IntArray(cellCount) { if (boundaryOther[it] >= 0) renumber[boundaryOther[it]] else -1 },
                boundaryOverridingPlate = IntArray(cellCount) { if (boundaryOverriding[it] >= 0) renumber[boundaryOverriding[it]] else -1 },
                boundaryClosingMmPerYear = boundaryClosing.copyOf(),
                plateRotations = Array(live.size) { doubleArrayOf(plates[live[it]].omegaX, plates[live[it]].omegaY, plates[live[it]].omegaZ) },
                budget = budget,
                breakupMyrAgo = if (brokenUp) breakupMyrAgo else Double.NaN,
                historyMyr = historyMyr,
                splits = splitCount,
                sutures = sutureCount,
                plateSpeeds = speedRecord,
                windowTectonicKm = field { samples.windowTectonicKm[it] },
                windowWearKm = field { samples.windowWearKm[it] },
                windowOnsetMyrAgo = field { if (samples.windowOnsetMyrAgo[it].isNaN()) -1f else samples.windowOnsetMyrAgo[it] },
                presentThickeningKmPerMyr = field { (samples.lastStepTectonicKm[it] / stepMyr).toFloat() },
                exposureWindowMyr = exposureWindowMyr,
                strikeTensor = strikeTensor(field { if (samples.continental[it]) samples.excessKm[it] else 0f })
            )
        }

        /**
         * The gradient tensor of [excessKm] at every point, smoothed over [STRIKE_SMOOTHING_KM]: each
         * point's gradient in its tangent plane by least squares over its neighbors, its outer
         * product with itself as a symmetric 3 by 3 tensor (six components, see
         * [CrustHistory.strikeTensor]), averaged with its neighbors' as many times as spreads it over
         * a belt's width. A tensor and not a direction, so opposite gradients on a belt's two flanks
         * add instead of cancelling, and the result has no frame and no sign for the map to read.
         */
        private fun strikeTensor(excessKm: FloatArray): Array<FloatArray> {
            val components = Array(STRIKE_COMPONENTS) { FloatArray(cellCount) }
            parallelChunks(0, cellCount) { from, to ->
                for (cell in from until to) {
                    val px = sphere.x[cell]; val py = sphere.y[cell]; val pz = sphere.z[cell]
                    // Normal equations of the least-squares gradient in the tangent plane, in 3D:
                    // sum t t^T and sum (dh) t over the neighbors, t the tangent offset in km.
                    var axx = 0.0; var axy = 0.0; var axz = 0.0; var ayy = 0.0; var ayz = 0.0; var azz = 0.0
                    var bx = 0.0; var by = 0.0; var bz = 0.0
                    for (index in sphere.neighborStart[cell] until sphere.neighborStart[cell + 1]) {
                        val next = sphere.neighbors[index]
                        var tx = sphere.x[next] - px; var ty = sphere.y[next] - py; var tz = sphere.z[next] - pz
                        val radial = tx * px + ty * py + tz * pz
                        tx = (tx - radial * px) * radiusKm; ty = (ty - radial * py) * radiusKm; tz = (tz - radial * pz) * radiusKm
                        val rise = (excessKm[next] - excessKm[cell]).toDouble()
                        axx += tx * tx; axy += tx * ty; axz += tx * tz; ayy += ty * ty; ayz += ty * tz; azz += tz * tz
                        bx += rise * tx; by += rise * ty; bz += rise * tz
                    }
                    // The tangent-plane system is singular along the radial direction; adding the
                    // radial direction's outer product makes it invertible without moving the
                    // tangent answer, since the right side has no radial part.
                    val scale = axx + ayy + azz
                    if (scale <= 0.0) continue
                    axx += scale * px * px; axy += scale * px * py; axz += scale * px * pz
                    ayy += scale * py * py; ayz += scale * py * pz; azz += scale * pz * pz
                    val c00 = ayy * azz - ayz * ayz
                    val c01 = axz * ayz - axy * azz
                    val c02 = axy * ayz - axz * ayy
                    val determinant = axx * c00 + axy * c01 + axz * c02
                    if (determinant == 0.0) continue
                    val c11 = axx * azz - axz * axz
                    val c12 = axy * axz - axx * ayz
                    val c22 = axx * ayy - axy * axy
                    val gx = (c00 * bx + c01 * by + c02 * bz) / determinant
                    val gy = (c01 * bx + c11 * by + c12 * bz) / determinant
                    val gz = (c02 * bx + c12 * by + c22 * bz) / determinant
                    components[0][cell] = (gx * gx).toFloat()
                    components[1][cell] = (gx * gy).toFloat()
                    components[2][cell] = (gx * gz).toFloat()
                    components[3][cell] = (gy * gy).toFloat()
                    components[4][cell] = (gy * gz).toFloat()
                    components[5][cell] = (gz * gz).toFloat()
                }
            }
            // Each averaging over a point and its neighbors spreads the field by about half a
            // spacing squared in variance, so a spread of the smoothing length takes twice its
            // square in spacings.
            val passes = (2.0 * (STRIKE_SMOOTHING_KM / spacingKm) * (STRIKE_SMOOTHING_KM / spacingKm)).roundToInt().coerceAtLeast(1)
            var current = components
            var next = Array(STRIKE_COMPONENTS) { FloatArray(cellCount) }
            repeat(passes) {
                val read = current
                val write = next
                parallelChunks(0, cellCount) { from, to ->
                    for (cell in from until to) {
                        val first = sphere.neighborStart[cell]
                        val last = sphere.neighborStart[cell + 1]
                        for (component in 0 until STRIKE_COMPONENTS) {
                            var sum = read[component][cell].toDouble()
                            for (index in first until last) sum += read[component][sphere.neighbors[index]]
                            write[component][cell] = (sum / (1 + last - first)).toFloat()
                        }
                    }
                }
                current = write
                next = read
            }
            return current
        }

        // ------------------------------------------------------------------------------- helpers

        private fun topShare(values: DoubleArray, share: Double): BooleanArray {
            val order = values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it })
            val take = (share * values.size).roundToInt().coerceIn(1, values.size)
            val chosen = BooleanArray(values.size)
            for (index in 0 until take) chosen[order[index]] = true
            return chosen
        }

        private fun randomUnit(random: Random): DoubleArray {
            val height = 2.0 * random.nextDouble() - 1.0
            val turn = 2.0 * PI * random.nextDouble()
            val ring = sqrt(max(0.0, 1.0 - height * height))
            return doubleArrayOf(ring * kotlin.math.cos(turn), ring * sin(turn), height)
        }

        /**
         * How many octaves a noise whose longest wavelength is [wavelengthKm] needs to reach the
         * lattice's spacing, below which the lattice cannot carry a shape: at least one.
         */
        private fun octavesToSpacing(wavelengthKm: Double): Int =
            max(1, kotlin.math.ceil(ln(wavelengthKm / sphere.spacingKm) / ln(2.0)).toInt())

        private fun floodKey(cost: Double, cell: Int): Long =
            (cost.toFloat().toRawBits().toLong() shl 32) or cell.toLong()
    }

    /** A uniform draw in 0..1 from a seed and an index, by splitmix64's finalizer: no shared stream. */
    private fun hash01(seed: Long, index: Long): Double {
        val gamma = 0x9E3779B97F4A7C15UL.toLong()
        var z = (seed xor (index * gamma)) + gamma
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    // ---------------------------------------------------------------------------------- figures

    /**
     * Flat subduction at about a tenth of today's convergent margins (Gutscher, Spakman, Bijwaard and
     * Engdahl, *Geodynamics of flat subduction*, Tectonics 19, 2000, abstract): the floor's most
     * buoyant tenth, its thickened crust of plateaus and aseismic ridges, goes down flat.
     */
    private const val FLAT_SLAB_SHARE = 0.10f


    /** Where a flat slab deforms the upper plate, km from the trench (Gutscher et al. 2000), and the ramp at each edge. */
    private const val FLAT_SLAB_NEAREST_KM = 250.0
    private const val FLAT_SLAB_FARTHEST_KM = 800.0
    private const val FLAT_SLAB_RAMP_KM = 100.0

    /**
     * The ordinary slab's anchoring `a` is the slab buoyancy's share below the flat slabs raised to
     * this power. Heuret and Lallemand (2005) find nearly 45% of the subduction zones whose back-arc
     * deformation the upper plate's motion explains lying near the free slab's line, and a perfectly
     * anchored slab rare: `a` under a tenth on 45% of them is `ln 0.1 / ln 0.45`, 2.88, which leaves
     * `a` over 0.9 on 3.6% and a median of 0.14.
     */
    private val ANCHORING_EXPONENT = ln(FREE_SLAB_ANCHORING) / ln(FREE_SLAB_SHARE)
    private const val FREE_SLAB_ANCHORING = 0.1
    private const val FREE_SLAB_SHARE = 0.45

    /**
     * The share of the ordinary floor's trenches whose back-arc does not deform measurably: of
     * Heuret and Lallemand's 159 segments, 80 have a back-arc deformation well enough characterized
     * to be given a rate (their classes E2, E3, C2 and C3), and the rest are neutral or deform only
     * diffusely (classes 0, E1 and C1). Their statistics of the anchoring, below, are of the 80.
     */
    private const val QUIET_BACK_ARC_SHARE = 1.0 - 80.0 / 159.0

    /**
     * The share of the deforming trenches whose back-arc deformation the upper plate's motion does
     * not explain: Heuret and Lallemand's 40% of the 80 in the slab-motion-controlled field, the
     * trench moving on its own (rollback, mantle flow), mostly back-arc spreading. Here their upper
     * plates are not deformed by their own motion; the slab's own extension is not modeled.
     */
    private const val SLAB_CONTROLLED_SHARE = 0.4

    /**
     * Over a flat slab the plates are coupled harder: Gutscher et al. find the upper plate's seismic
     * energy release 250 to 800 km from the trench three to five times that above the steep slabs
     * beside it, and a strain rate goes as the moment released (Kostrov), so four times the ordinary
     * slab's mean anchoring: the deforming share of the trenches, less its slab-controlled share,
     * times the mean of `u^2.88` for `u` even over 0 to 1, `1 / 3.88`; 0.078, so 0.31.
     */
    private val FLAT_SLAB_COUPLING =
        FLAT_SLAB_DEFORMATION_RATIO * (1.0 - QUIET_BACK_ARC_SHARE) * (1.0 - SLAB_CONTROLLED_SHARE) / (1.0 + ANCHORING_EXPONENT)
    private const val FLAT_SLAB_DEFORMATION_RATIO = 4.0

    /**
     * The slab buoyancy noise's longest wavelength, km, and its octaves: features of the floor a few
     * hundred kilometers across, the scale over which Heuret and Lallemand's samples, every 2 degrees
     * of trench (about 220 km), change their anchoring along one trench; two octaves, down to 500 km.
     */
    private const val SLAB_FEATURE_WAVELENGTH_KM = 1_000.0
    private const val SLAB_FEATURE_OCTAVES = 2
    private const val SLAB_NOISE_STREAM = 4L

    /**
     * The thrust belt behind an Andean margin, km from the trench, and its half-width: the Central
     * Andes at 20 S, where the shortening east of the arc (Allmendinger, Jordan, Kay and Isacks 1997,
     * Table 1) lies in the Eastern Cordillera and the Subandean belt, 65.5 to 63.5 W against a trench
     * at 71.5 W and an arc in the Western Cordillera at 68.5 to 69 W: 450 to 750 km behind the
     * trench, at 105 km a degree of longitude there.
     */
    private const val THRUST_BELT_OFFSET_KM = 600.0
    private const val THRUST_BELT_HALF_WIDTH_KM = 150.0

    /**
     * How far from the trench the upper plate's edge gives up its crust to the shortening, km, and
     * the e-folding of the take: the forearc within two of the history's spacings, mostly its edge.
     */
    private const val CONSUMED_REACH_KM = 160.0
    private const val CONSUMED_EFOLD_KM = 40.0

    /**
     * The back-arc band a retreating upper plate stretches, km beyond the arc, and the ramp at each
     * edge: the Great Basin's breadth from the Sierra Nevada's crest at 119 W to the Wasatch front at
     * 111.8 W, 620 km at 39 N, its extension 48 to 97 km over 17 Myr in grabens 24 to 32 km apart
     * (Stewart, *Basin and range structure*, GSA Bulletin 82, 1971, abstract).
     */
    private const val BACK_ARC_WIDTH_KM = 620.0
    private const val BACK_ARC_RAMP_KM = 100.0


    /** How far past the widening's own estimate its first search reaches, in the lattice's spacings. */
    private const val WIDENING_MARGIN_SPACINGS = 3.0

    /** A step ends inside the exposure window if it ends this much before the window's start, Myr: rounding only. */
    private const val WINDOW_ROUNDING_MYR = 1e-6

    /** Earth's surface, km², for the counts set per Earth. */
    private const val EARTH_SURFACE_KM2 = 510.1e6

    /**
     * How many cratons Earth's continents were assembled from: about 35 Archean cratons (Bleeker,
     * *The late Archean record: a puzzle in ca. 35 pieces*, Lithos 71, 2003). Scaled by area on a
     * planet of another size.
     */
    private const val CRATONS_ON_EARTH = 35.0

    /** Craton seeds are kept apart by this share of the spacing their count implies, so none crowd. */
    private const val CRATON_SEED_SEPARATION_SHARE = 0.6

    /**
     * How far the cost of crossing ground strays either way from one when the cratons' domains are
     * grown, and the noise's longest wavelength: enough that two domains meet along a wandering line
     * rather than a bisector, at the scale of a craton.
     */
    private const val CRATON_METRIC_NOISE = 0.8
    private const val CRATON_NOISE_WAVELENGTH_KM = 1_500.0

    /**
     * The longest stretch over which Earth's continental edge was read for its wander, km
     * ([EARTH_EDGE_WANDER_PER_LENGTH]).
     */
    private const val EARTH_EDGE_LONGEST_STRETCH_KM = 2_000.0

    /**
     * A supercontinent block's radius, km: the circle whose own curvature puts its edge as far from
     * a chord as Earth's edge stands over the longest stretch it was read on. An arc of radius `R`
     * stands `L^2 / (sqrt(120) R)` from a chord of length `L`, root mean square, so a block bent no
     * more than Earth's edge over [EARTH_EDGE_LONGEST_STRETCH_KM] has `R = L / (sqrt(120) w)`, `w`
     * being [EARTH_EDGE_WANDER_PER_LENGTH]: 3,260 km. Smaller blocks bend faster than Earth's edge
     * and string the outline into beads; larger ones straighten it.
     */
    private val CONTINENT_BLOCK_RADIUS_KM = EARTH_EDGE_LONGEST_STRETCH_KM / (sqrt(120.0) * EARTH_EDGE_WANDER_PER_LENGTH)

    /**
     * How far a docking block overlaps the block it docks against, as a share of a block's
     * diameter: a tenth, the sliver of each that the suture's belt takes, so the two weld rather
     * than touch at a point and leave a gap of ocean between their rounded sides.
     */
    private const val BLOCK_OVERLAP_SHARE = 0.1

    /**
     * How many bearings a docking block tries before it takes the one that comes least near the
     * other blocks: enough that a block finds the open side of an assembly ringed by others.
     */
    private const val BLOCK_DOCKING_TRIES = 24

    /** A docking block may come this much nearer another block than the docking distance, radians: rounding only. */
    private const val DOCKING_TOLERANCE_RADIANS = 1e-9

    /** Cratons formed between 1,500 and 3,000 Myr ago: Archean and Paleoproterozoic. */
    private const val CRATON_OLDEST_MYR = 3_000.0
    private const val CRATON_AGE_SPREAD_MYR = 1_500.0

    /**
     * The supercontinent's sutures were made over the 320 Myr before the history starts: half
     * Rolf et al.'s 640 Myr cycle, the time a supercontinent takes to gather (Nance, Evans and
     * Murphy, *Pannotia: To be or not to be?*, Earth-Science Reviews 232, 2022, which quotes it).
     */
    private const val SUTURE_AGE_SPAN_MYR = 320.0
    private const val SUTURE_AGE_TABLE = 4_096

    /**
     * The thickness a collision adds to its crust at its height, km: Tibet's. Its interior stands
     * 5,023 m (Fielding et al. 1994, as `TectonicsConfig.beltReliefMetres` cites them) against a
     * standard column's 374, and Airy turns a kilometer of crust into `(rho_m - rho_c) / rho_m`, 141 m,
     * of altitude (`IsostasyConfig`'s densities), so (5,023 - 374) / 141 = 33 km.
     */
    internal const val TIBET_EXCESS_KM = 33.0

    /**
     * How fast a collision belt's thickening falls off away from its axis, km: the e-folding of the
     * deforming belt, 100 km, Earth's young collisional ranges' half-width at half their crest — the
     * Himalaya's 104 km and the Alps' 107 on GMRT's crests, as the design measured them
     * (notes, measures). The belt is taken to three of these.
     */
    const val BELT_EFOLD_KM = 100.0
    private const val SUTURE_REACH_BELT_WIDTHS = 3.0
    private const val BELT_REACH_KM = 3.0 * BELT_EFOLD_KM

    /** The strike tensor's six components, and the length it is smoothed over: a belt's width, three of its e-folds. */
    private const val STRIKE_COMPONENTS = 6
    private const val STRIKE_SMOOTHING_KM = 3.0 * BELT_EFOLD_KM

    /** What share of the continental crust starts as microcontinents in the ocean, and how many on an Earth. */
    private const val MICROCONTINENT_SHARE_OF_CRUST = 0.03
    private const val MICROCONTINENTS_ON_EARTH = 3.0
    private const val MICROCONTINENT_SEARCH_RADII = 3.0
    private const val MICROCONTINENT_ELONGATION = 1.5
    private const val MICROCONTINENT_WARP = 0.6
    private const val MICROCONTINENT_WARP_WAVELENGTH_KM = 800.0
    private const val MICROCONTINENT_TERRANE_BASE = 10_000
    private const val ARC_TERRANE_BASE = 20_000

    /**
     * How far Earth's continental edge stands from the chord of a stretch of it, root mean square, as
     * a share of the stretch's length: ETOPO5's 2,000 m isobath equatorward of 60 degrees, on its own
     * five-minute grid, read 14, 29, 57 and 110 km over stretches of 250, 500, 1,000 and 2,000 km
     * (docs/DESIGN_LEDGER.md, TH1-1).
     */
    private const val EARTH_EDGE_WANDER_PER_LENGTH = 0.056

    /**
     * How near a sample must lie to a lattice point to cover it, in spacings: past the lattice's
     * covering radius, about 0.6 of a spacing for a rotated copy of the lattice, so a plate's own
     * interior never shows a hole, and short of the one spacing at which two plates side by side
     * would both cover the ground between them throughout.
     */
    private const val COVER_SPACINGS = 0.8

    /**
     * Two samples of different plates nearer than this, in spacings, lie on top of one another, and
     * the lower goes down whatever the plates' motion: half a spacing, well inside the closest two
     * plates' crusts come when they lie side by side.
     */
    private const val OVERLAP_SPACINGS = 0.5

    /**
     * An oceanic plate is bounded by subduction, in Zahirovic et al.'s sense, when it goes down a
     * trench along more than a fifth of its edge: their fastest category, plates under a quarter
     * continental with more than 20% of their perimeter subducting, whose median is the 85 mm a year
     * the speeds are scaled to (*Tectonic speed limits from plate kinematic reconstructions*, EPSL
     * 418, 2015, section 3.5). A plate whose edge is mostly ridge and transform is not that kind.
     */
    private const val SUBDUCTION_BOUNDED_SHARE = 0.20

    /**
     * A plate more than a quarter continental is a cratonic one, as Zahirovic et al. sort plates for
     * their speeds; the rest are oceanic.
     */
    private const val CRATONIC_SHARE = 0.25

    /** A sample whose plate owns under one in this many of its neighbors is surrounded: a third. */
    private const val SURROUNDED_SHARE_INVERSE = 3

    /** More plates than ever meet at a lattice point. */
    private const val MAX_PLATES_AT_A_POINT = 8

    /**
     * Bird 2003's tolerance for a strike-slip boundary: relative motion within 20 degrees of the
     * boundary's own azimuth.
     */
    private val STRIKE_SLIP_TOLERANCE_RADIANS = 20.0 * PI / 180.0

    /**
     * The relative speed below which two plates are not told apart, km per Myr (mm a year): the
     * middle of the 2 to 8 mm a year Bird 2003 overlooks as internal deformation of one plate.
     */
    private const val LOCK_RATE_KM_PER_MYR = 5.0

    /** A relative motion slower than this, km per Myr, is no motion at all for deciding who closes on whom. */
    private const val MIN_RELATIVE_RATE_KM_PER_MYR = 0.5

    /** Ocean floor born in a step is no older than this many steps, however slowly its ridge opens. */
    private const val MAX_GAP_AGE_STEPS = 2.0

    /** The boundary's direction is the mean of its own points within this many spacings. */
    private const val BOUNDARY_SMOOTHING_SPACINGS = 2.5

    /** Arc crust this much thicker than the floor beside it stays up over it. */
    private const val BUOYANT_ARC_EXCESS_KM = 3f

    /**
     * An arc's crust counts as continental, buoyant and accreting rather than subducting, at 20 km:
     * the middle Izu-Ogasawara arc's crust (Kodaira et al., Earth Planets Space 63, 2011,
     * doi:10.5047/eps.2011.06.036; oceanic arcs run 10 to 35 km).
     */
    private const val ARC_CONTINENT_KM = 20f

    /**
     * Scholl and von Huene (*Implications of estimated magmatic additions and recycling losses at
     * the subduction zones of accretionary (non-collisional) and collisional (suturing) orogens*,
     * Geol. Soc. London Spec. Publ. 318, 2009): modern ocean-margin subduction zones recycle about
     * 60 km³ per Myr per km of margin, 2.5 km³ a year in all, and their arcs add 1.5 + 1.0 = 2.5,
     * which over the same margins is 60 km³ per Myr per km again. Their crust-suturing zones lose
     * about 115 and add 0.2 km³ a year over about 6,000 km, 33.
     */
    private const val ARC_ADDITION_KM3_PER_KM_MYR = 60.0
    private const val TRENCH_RECYCLING_KM3_PER_KM_MYR = 60.0
    private const val COLLISION_RECYCLING_KM3_PER_KM_MYR = 115.0
    private const val COLLISION_ADDITION_KM3_PER_KM_MYR = 33.3

    /** A sample given more than this much thickness in a step is marked as deformed by it. */
    private const val OROGENY_MARK_KM = 0.5f

    /**
     * McKenzie's stretching at a rifted margin: beta four at the break, where Watts's crust has
     * thinned from 40 km to 10 (as `TectonicsConfig.riftedMarginWidthKm` cites him), falling to
     * within a tenth of one at the margin's inner edge.
     */
    private const val BETA_AT_BREAK = 4.0
    private const val BETA_AT_MARGIN_EDGE_EXCESS = 0.1

    /** How many e-folds the stretching falls through between the break and the margin's inner edge. */
    private val MARGIN_FALLOFFS = ln((BETA_AT_BREAK - 1.0) / BETA_AT_MARGIN_EDGE_EXCESS)

    /**
     * Watts's width is the margin as it stands, stretched; the band that stretched to it was
     * narrower by the area beta added: the mean of `1 + (beta0 - 1) exp(-x / L)` over the band,
     * `1 + (beta0 - 1) / ln((beta0 - 1) / e) (1 - e / (beta0 - 1))` with `e` the excess at its inner
     * edge, about 1.853. So a rifted margin adds `W (1 - 1 / 1.853)`, about 138 km, of crust for
     * every kilometer of its length.
     */
    internal val STRETCHED_WIDTH_PER_UNSTRETCHED =
        1.0 + (BETA_AT_BREAK - 1.0) / MARGIN_FALLOFFS * (1.0 - BETA_AT_MARGIN_EDGE_EXCESS / (BETA_AT_BREAK - 1.0))

    /**
     * Bird's flux goes as the tenth power of the lower crust's absolute temperature, read here as
     * the crust's thickness against the standard column's.
     */
    private const val FLOW_TEMPERATURE_EXPONENT = 10.0

    /**
     * The flow's coefficient, km² per Myr per unit slope squared at the standard column's
     * temperature, from Bird 1991's Tibet: a thickness feature 400 km across and 7 km in amplitude
     * (a kilometer of relief) under 70 km of crust survives about 20 Myr. A sinusoid of amplitude
     * `A` and wavenumber `k` under a diffusivity `K T g^2`, `g` its slope, decays in
     * `2 / (K T A^2 k^4)`; with `T = (70/41)^10` that is `K = 2 / (20 T A^2 k^4)`.
     */
    private val FLOW_COEFFICIENT_KM2_PER_MYR: Double = run {
        val wavenumber = 2.0 * PI / 400.0
        val temperature = (70.0 / 41.0).pow(FLOW_TEMPERATURE_EXPONENT)
        2.0 / (20.0 * temperature * 7.0 * 7.0 * wavenumber.pow(4))
    }

    /** The near-hexagonal lattice's Laplacian, `2d / n` per neighbor over the squared spacing, `d` two. */
    private const val LAPLACIAN_PER_NEIGHBOR = 4.0

    /**
     * How many substeps the flow takes per step: with each edge's exchange held under what would level
     * it, any number is stable, and ten resolves the flow's own front to a tenth of a step.
     */
    private const val FLOW_SUBSTEPS = 10

    /**
     * The most shorter substeps an edge too stiff for its substep is integrated in: Bird's flux
     * under 120 km of crust, the thickest a collision piles before the flow spreads it, levels an
     * edge some six hundred times faster than a substep allows, and a thousand covers it.
     */
    private const val MAX_FLOW_SUBCYCLES = 1_024

    /**
     * A plate larger than Earth's largest, the Pacific at 0.205 of the sphere (Bird 2003, Table 1),
     * is tried for a split even on a planet with its full count of plates.
     */
    private const val LARGEST_PLATE_SHARE = 0.205

    /** A plate of fewer lattice points than this folds into its neighbor: about Bird's smallest microplates. */
    private const val MIN_PLATE_CELLS = 20

    /** No plate this small is split, and no split leaves a half under this share of the plate. */
    private const val MIN_SPLIT_CELLS = 200
    private const val MIN_SPLIT_SHARE = 0.15

    /**
     * A split's path passes through a point at least this share of the plate's greatest depth from
     * its own edge, drawn among all such, so it crosses the plate rather than skirting it.
     */
    private const val VIA_DEPTH_SHARE = 0.6

    /** How many steps a plate its forces held together waits before it is tried for a split again. */
    private const val SPLIT_RETRY_STEPS = 3

    /** How many paths are tried for one split, and how many plates, largest first. */
    private const val SPLIT_CANDIDATES = 6
    private const val SPLIT_PLATES_TRIED = 2

    /** The band of the plate's edge each end of a split's path may start or finish in, spacings. */
    private const val SPLIT_END_BAND_SPACINGS = 3.0

    /** Vauchez et al.'s weak and strong ground, as factors on the cost of a step. */
    private const val CRATON_HARDNESS = 4.0
    private const val WEAK_ZONE_HARDNESS = 0.5
    private const val PATH_NOISE = 0.5
    private const val PATH_NOISE_WAVELENGTH_KM = 600.0
    private const val MIN_PATH_HARDNESS = 0.1

    /** A ridge segment's or transform's advantage over an oblique step: an oblique step at 45 degrees costs 2.5 times. */
    private const val RIDGE_ANISOTROPY = 1.5

    /** The largest welded piece that docks as a terrane, as a share of a craton block. */
    private const val TERRANE_SHARE_OF_CRATON = 0.1

    private const val PAIR_STRIDE = 1L shl 20
    private const val PAIR_STATS = 5
    private const val PHASES = 7
    private const val HISTORY_STREAM_MULTIPLIER = 2_654_435_761L
    private const val HISTORY_STREAM_OFFSET = 17L
    private const val SHAPE_SALT = 31L
    private const val SPLIT_NOISE_SALT = 977L
    private const val CRATON_AGE_SALT = 0x5A7EL
    private const val MICRO_AGE_SALT = 0x3C3CL
    private const val SUTURE_AGE_SALT = 0x5E7EL
}
