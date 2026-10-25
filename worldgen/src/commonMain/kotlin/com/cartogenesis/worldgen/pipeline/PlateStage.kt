package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.GroundLattice
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
enum class PlateType { OCEANIC, CONTINENTAL }

enum class BoundaryType { CONVERGENT, DIVERGENT, TRANSFORM }

/**
 * A boundary refined by the crusts either side of it.
 *
 * Convergence alone does not say what a collision builds. Oceanic crust is dense and subducts;
 * continental crust is buoyant and does not. So an ocean meeting a continent gives a trench and a
 * narrow volcanic range along the coast, two continents meeting give a broad thickened plateau
 * with nothing to subduct and nowhere for the crust to go but up, and two oceans meeting give a
 * trench and a chain of volcanic islands. Those are three different shapes, not three labels on
 * one shape, which is why this exists alongside [BoundaryType].
 *
 * The class is a property of the plate *pair*, exactly as [BoundaryType] is. Which side of the
 * pair a given cell sits on is a separate question, and it is what makes these profiles
 * asymmetric: the trench belongs to the subducting plate and the range to the overriding one.
 */
enum class BoundaryClass {
    /** Oceanic under continental: trench offshore, coastal range and volcanic arc inland. */
    ANDEAN_MARGIN,

    /** Continental against continental: a broad, high, flat-topped plateau. */
    COLLISION_PLATEAU,

    /** Oceanic under oceanic: trench, and an arc of volcanic islands on the overriding plate. */
    ISLAND_ARC,

    /** Divergent, both sides oceanic: a spreading ridge. */
    OCEAN_RIDGE,

    /** Divergent with continental crust on at least one side: a rift valley between shoulders. */
    CONTINENTAL_RIFT,

    /** Plates sliding past one another; little relief either way. */
    TRANSFORM_FAULT
}

/**
 * One present plate: [id], where its middle stands on the map ([seedX], [seedY], a column and a
 * row), which way it moves there ([driftX] east and [driftY] south, a unit vector on the ground),
 * whether it is mostly a continent's ([type]: [PlateType.CONTINENTAL] where more than a quarter of
 * it is continental crust, as Zahirovic et al. sort plates), and the rotation it moves by: its Euler
 * pole's latitude and longitude in degrees and its rate in degrees per million years, positive
 * counterclockwise about the pole.
 *
 * Since the tectonic history (docs/DESIGN_LEDGER.md, TH1-1) the rotation is the plate's motion and
 * the drift is read from it; a plate carries both crusts, and its type says only which it mostly is.
 */
@Serializable
data class Plate(
    val id: Int,
    val seedX: Int,
    val seedY: Int,
    val driftX: Float,
    val driftY: Float,
    val type: PlateType,
    val eulerPoleLatitudeDegrees: Float = 0f,
    val eulerPoleLongitudeDegrees: Float = 0f,
    val rotationDegreesPerMyr: Float = 0f
)

data class PlateResult(
    val plates: List<Plate>,
    /** Plate id per cell. */
    val plateId: IntArray,
    /** Distance on the ground to the nearest plate boundary, in cell widths. */
    val boundaryDistance: FloatField,
    /** [BoundaryType] ordinal of the nearest boundary, per cell. */
    val nearestBoundaryType: IntArray,
    /**
     * [BoundaryClass] ordinal of the nearest boundary, per cell — the crust pair that built
     * whatever relief this cell carries, and what tells an Andean margin from a Tibetan plateau
     * without measuring either.
     */
    val nearestBoundaryClass: IntArray,
    /**
     * Terrain height with tectonic uplift applied, on the height field's absolute ruler: 0 is
     * `WorldScale.deepestOceanMetres` below the water and 1 is `highestLandMetres` above it, so
     * the waterline stands at `WorldScale.shorelineFieldLevel` whatever the world turned out like.
     *
     * Absolute since S2, where before it was renormalised to its own extremes. The difference is
     * the whole of what made "62% ocean" a statement about a histogram rather than about a planet:
     * with the field normalised, one tall massif pushed every other landform down the ramp, the
     * hypsometric curve came out as a single peak straddling the shoreline, and the same 120 m of
     * sea-level fall was a different level on every seed. See [Isostasy] and docs/DESIGN_LEDGER.md, S2.
     */
    val height: FloatField,
    /**
     * How long ago the sea floor under each cell was made, in millions of years, and how fast the
     * ridges that made it spread.
     *
     * The ocean's whole shape, since S2's second pass. Depth goes as the square root of age
     * (Parsons & Sclater 1977), so a ridge stands two and a half kilometres down and the floor
     * sinks away from it along a smooth curve — where before, every piece of floor was of one age
     * and the deep sea was a set of flat plate polygons. Since TH1-1 the age is the tectonic
     * history's: each piece of floor was made at a ridge at a known time and carried from it, so
     * its age is the history's and not a distance read off the map. Continental cells carry an age
     * too; it simply does nothing there, because their altitude is their crust's business. The
     * rate is the mean half rate of the history's present ridges (docs/DESIGN_LEDGER.md, TH1-1).
     */
    val seafloorAgeMyr: FloatField,
    val seafloorHalfSpreadingRateKmPerMyr: Double,
    /**
     * How much of each cell's crust is continental rather than oceanic, 0 to 1 — which is what
     * decides the altitude the cell floats at before anything is stamped on it.
     *
     * Not a label but a mixture, because a continental margin is a mixture: crust stretched and
     * thinned on its way out to the ocean floor. Since TH1-1 it is the tectonic history's crust
     * read onto the map ([CrustMap]): 1 inside a continent and 0 on the ocean floor, wherever the
     * plates' boundaries run, and the band between is the margin the history rifted or the arc it
     * built. [Isostasy.Columns] turns it into metres.
     */
    val continentalShare: FloatField,
    /**
     * How fast the rock is still rising under each cell, in millimetres a year — zero everywhere
     * except on the belts of the present boundaries.
     *
     * The other half of what retires H1's decay factors. An old belt is low because its uplift
     * stopped and erosion went on, so an old belt is the crust's thickening the history left, not
     * stamped, while a present one goes on being pushed up through every hydraulic round. See
     * [com.cartogenesis.worldgen.model.TectonicsConfig.collisionUpliftMmPerYear] and
     * `HydraulicErosion.apply`.
     */
    val upliftRateMmPerYear: FloatField,
    /**
     * How long ago the crust under each cell was last made or deformed, in millions of years: on a
     * continent the time since its last orogeny, or since it formed where it never had one (a
     * craton's 1,500 to 3,000 Myr); on the ocean floor the floor's own age.
     *
     * A real age from the tectonic history (docs/DESIGN_LEDGER.md, TH1-1), where it was a band of 0
     * to 1 counted in epochs before: what lets a consumer tell an Appalachian belt from an Alpine
     * one without measuring either.
     */
    val crustAgeMyr: FloatField,
    /**
     * The continental crust's thickness under each cell, in kilometers, from the history: a craton's
     * column, thinned where a margin was stretched, thickened where orogeny piled crust on. The
     * standard column (`IsostasyConfig.continentalCrustThicknessKm`) where there is no continent.
     */
    val crustThicknessKm: FloatField,
    /** The part of [crustThicknessKm] orogeny added and erosion has not yet taken, km. */
    val orogenicExcessKm: FloatField,
    /** What last deformed the crust under each cell, an [OrogenyKind] ordinal. */
    val orogenyKind: IntArray,
    /** Which craton, microcontinent or island arc a continent's crust began as; -1 under ocean floor. */
    val terraneId: IntArray,
    /** When the crust under each cell was stretched at a rift, Myr ago; -1 where it never was, as a save holds no NaN. */
    val riftAgeMyr: FloatField,
    /**
     * How readily each cell's rock is cut, as a factor on `ErosionConfig.bedrockErodibilityPerYear`:
     * 1 for a craton's crystalline basement to the configured bedrock band's top for the weakest
     * rock a belt or a terrane boundary holds ([BeltFields]; docs/DESIGN_LEDGER.md, TH1-2).
     */
    val rockErodibility: FloatField,
    /**
     * The belts' recent history, for the erosion to replay ([exposureWindowMyr], the history's last
     * steps): what tectonics did to each cell's crust over the window, km of thickness, thickening
     * positive; what the orogens' wear took over it, km; and when its thickening began, Myr ago,
     * -1 where none did. A continent's own; nothing on the ocean floor.
     */
    val windowTectonicKm: FloatField,
    val windowWearKm: FloatField,
    val windowOnsetMyrAgo: FloatField,
    /** The exposure window's length, Myr. */
    val exposureWindowMyr: Double
)

/**
 * Step 2: divide the world into drifting plates and deform the terrain along their boundaries —
 * mountains where plates collide, trenches where oceanic crust subducts, rifts where they separate.
 */
object PlateStage {

    /**
     * How a pair of plates interacts. This is a property of the pair, not of any one cell — two
     * plates converge, separate, or slide past each other along the whole of their shared
     * boundary. Deriving it per-cell from whichever neighbour happened to be sampled makes the
     * type flip between adjacent boundary cells, which the distance transform then smears
     * outward as stripes.
     */
    private class PairInteraction(
        val type: BoundaryType,
        /** Convergence magnitude, 0..1. */
        val strength: Float,
        val continentalCollision: Boolean,
        /** The crust pair, which is what decides the profile. */
        val pairClass: BoundaryClass,
        /**
         * Which plate of the pair overrides the other — the one that keeps its surface while the
         * other goes under. Continental crust always overrides oceanic; between two plates of the
         * same kind the choice is arbitrary and is made by the lower id, a total order on the
         * pair, so that it never depends on which cell asked or on any hash order.
         */
        val overridingId: Int,
        /**
         * The lower of the pair's two plate ids. A rift's half-grabens alternate their polarity
         * along strike, and "which flank is the footwall" has to be said in terms that hold for
         * the whole pair; the lower id is the same total order [overridingId] uses.
         */
        val lowId: Int,
        /** The higher of the pair's two plate ids: with [lowId], the pair's name at every grid. */
        val highId: Int
    )

    /**
     * What one cell of a segmented rift's corridor stands in: its half-graben's shape, as
     * [riftSegmentAt] reads it for that cell.
     */
    private class RiftSegment(
        /** This segment's trough depth, as a factor on `riftDepth`. */
        val depthFactor: Float,
        /** This segment's shoulder height, as a factor on `riftShoulderHeight`. */
        val shoulderFactor: Float,
        /** This segment's shoulder half-width, as a factor on `riftShoulderWidthKm`. */
        val widthFactor: Float,
        /**
         * Whether the high footwall stands on the pair's [PairInteraction.lowId] plate. Alternates
         * from segment to segment, which is what makes the chain a chain rather than one trough.
         */
        val footwallOnLow: Boolean,
        /**
         * 0 at the join, 1 well inside the segment. The trough's depth and its asymmetry are both
         * multiplied by it, so a half-graben dies out at each end into a symmetric sill rather than
         * meeting its neighbor's opposite polarity at a step.
         */
        val taper: Float
    )

    private class Boundary(
        val interaction: PairInteraction,
        /** Whether this particular cell sits on the oceanic plate of the pair. */
        val oceanicSide: Boolean,
        /** Whether this particular cell sits on the pair's overriding plate. */
        val overridingSide: Boolean,
        /** Set by [segmentRifts]; null unless this is a continental rift being segmented. */
        var segment: RiftPlace? = null
    )

    /**
     * Where along a segmented rift one boundary cell sits: its course's layout and centerline, and
     * its distance along the course in km.
     *
     * Filled in by [segmentRifts] for the cells of a [BoundaryClass.CONTINENTAL_RIFT] boundary and
     * null everywhere else. Every cell in the corridor either side of the rift finds its course from
     * its *nearest* boundary cell — the label the distance transform already carries — and its own
     * place on the course from the nearest point of the course's centerline. Read off the label
     * alone, every cell of the strip a boundary cell owns would share one distance along the rift,
     * and a join would follow the strip's edge: along a row wherever the rift runs down a column,
     * which is a rung; and where the rift's cells step across a column, a boundary cell rows away
     * owns cells far out on the shoulder, and the place jumps there by as many rows.
     */
    private class RiftPlace(val layout: RiftLayout, val alongKm: Double, val centerline: RiftCenterline)

    /**
     * A course's centerline on the ground: the mean place of its cells in each stretch of
     * [CENTERLINE_STEP_KM] along it, joined in order, each vertex at its cells' mean distance along,
     * with a direction at each vertex from its two neighbors. The bins are lengths on the ground, so
     * the line is the same line at every grid.
     */
    private class RiftCenterline(
        private val eastKm: DoubleArray,
        private val southKm: DoubleArray,
        private val alongKm: DoubleArray,
        private val worldWidthKm: Double
    ) {
        private val last = alongKm.size - 1
        private val directionEast = DoubleArray(alongKm.size)
        private val directionSouth = DoubleArray(alongKm.size)

        init {
            for (vertex in 0..last) {
                val from = (vertex - 1).coerceAtLeast(0)
                val to = (vertex + 1).coerceAtMost(last)
                val stepEastKm = shortestEastKm(eastKm[to] - eastKm[from])
                val stepSouthKm = southKm[to] - southKm[from]
                val lengthKm = sqrt(stepEastKm * stepEastKm + stepSouthKm * stepSouthKm)
                if (lengthKm > 0.0) {
                    directionEast[vertex] = stepEastKm / lengthKm
                    directionSouth[vertex] = stepSouthKm / lengthKm
                }
            }
        }

        /**
         * The distance along the course of the ground point [pointEastKm], [pointSouthKm]: the
         * place on the centerline whose square line across the rift runs through the point, the
         * direction turning evenly from one vertex's to the next, so the lines across sweep without
         * a gap or a jump where a nearest point would jump from one straight stretch to the next.
         * Searched over the stretches within [reachKm] along of [nearAlongKm], and the nearest such
         * place taken; past either end the end's own line runs on. [nearAlongKm] where the line
         * has no stretch or no line across passes through the point.
         */
        fun alongKmAt(pointEastKm: Double, pointSouthKm: Double, nearAlongKm: Double, reachKm: Double): Double {
            if (last < 1) return nearAlongKm
            var bestKm2 = Double.MAX_VALUE
            var bestAlongKm = nearAlongKm
            for (stretch in 0 until last) {
                if (alongKm[stretch + 1] < nearAlongKm - reachKm || alongKm[stretch] > nearAlongKm + reachKm) continue
                val atStart = aheadKm(stretch, 0.0, pointEastKm, pointSouthKm)
                val atEnd = aheadKm(stretch, 1.0, pointEastKm, pointSouthKm)
                val share = when {
                    // Before the first vertex, or past the last, the end's direction runs on.
                    stretch == 0 && atStart < 0.0 -> atStart / stepKm(0)
                    stretch == last - 1 && atEnd > 0.0 -> 1.0 + atEnd / stepKm(last - 1)
                    atStart >= 0.0 && atEnd < 0.0 -> {
                        var low = 0.0
                        var high = 1.0
                        repeat(CENTERLINE_BISECTIONS) {
                            val middle = (low + high) / 2
                            if (aheadKm(stretch, middle, pointEastKm, pointSouthKm) >= 0.0) low = middle else high = middle
                        }
                        (low + high) / 2
                    }
                    else -> continue
                }
                val km2 = awayKm2(stretch, share.coerceIn(0.0, 1.0), pointEastKm, pointSouthKm)
                if (km2 < bestKm2) {
                    bestKm2 = km2
                    bestAlongKm = alongKm[stretch] + share * (alongKm[stretch + 1] - alongKm[stretch])
                }
            }
            return bestAlongKm
        }

        /** How far ahead of the line across at [share] of [stretch] the point lies, along its direction. */
        private fun aheadKm(stretch: Int, share: Double, pointEastKm: Double, pointSouthKm: Double): Double {
            val offEastKm = shortestEastKm(pointEastKm - eastKm[stretch]) -
                share * shortestEastKm(eastKm[stretch + 1] - eastKm[stretch])
            val offSouthKm = pointSouthKm - southKm[stretch] - share * (southKm[stretch + 1] - southKm[stretch])
            return offEastKm * ((1 - share) * directionEast[stretch] + share * directionEast[stretch + 1]) +
                offSouthKm * ((1 - share) * directionSouth[stretch] + share * directionSouth[stretch + 1])
        }

        /** The square of the point's distance from the centerline at [share] of [stretch]. */
        private fun awayKm2(stretch: Int, share: Double, pointEastKm: Double, pointSouthKm: Double): Double {
            val offEastKm = shortestEastKm(pointEastKm - eastKm[stretch]) -
                share * shortestEastKm(eastKm[stretch + 1] - eastKm[stretch])
            val offSouthKm = pointSouthKm - southKm[stretch] - share * (southKm[stretch + 1] - southKm[stretch])
            return offEastKm * offEastKm + offSouthKm * offSouthKm
        }

        private fun stepKm(stretch: Int): Double {
            val stepEastKm = shortestEastKm(eastKm[stretch + 1] - eastKm[stretch])
            val stepSouthKm = southKm[stretch + 1] - southKm[stretch]
            return sqrt(stepEastKm * stepEastKm + stepSouthKm * stepSouthKm).coerceAtLeast(MIN_STEP_KM)
        }

        private fun shortestEastKm(km: Double): Double = when {
            km > worldWidthKm / 2 -> km - worldWidthKm
            km < -worldWidthKm / 2 -> km + worldWidthKm
            else -> km
        }
    }

    /**
     * One stretch of rift's half-grabens: the joins between them in km along the course, the rift's
     * two ends included, each half-graben's draws, and each interior join's relay: how far the two
     * border faults overlap there, over how much of the trough's width the crest swings between
     * them, and how far it bows.
     */
    private class RiftLayout(
        val joinsKm: DoubleArray,
        val ordinals: IntArray,
        val depthFactor: FloatArray,
        val shoulderFactor: FloatArray,
        val footwallOnLow: BooleanArray,
        /** Half the two border faults' overlap along strike at each join, km; 0 at the rift's ends. */
        val relayOverlapHalfKm: DoubleArray,
        /** Across the trough, how far from the axis the crest has swung its whole overlap, km. */
        val relayRampKm: DoubleArray,
        /** How far the crest bows off its swing halfway across, km, either way. */
        val relayBowKm: DoubleArray,
        val accommodationKm: Double
    )

    /**
     * The noise fields every belt profile reads, built once and shared by every epoch.
     *
     * Shared on purpose: the swell of a belt along its own length, the jitter of its rim and the
     * spacing of its volcanoes are properties of the ground, not of the epoch that happened to
     * raise it, so an old belt and a young one crossing the same country swell in the same places.
     * Reseeding per epoch would also have made a one-epoch history a different world from the
     * generator this chunk replaced, which it must not be.
     */
    private class BeltNoise(seed: Long) {
        val ridge = PerlinNoise(seed * 104729 + 5)
        val range = PerlinNoise(seed * 104729 + 911)
        val width = PerlinNoise(seed * 104729 + 1733)
        val arc = PerlinNoise(seed * 104729 + 2477)
        val relay = PerlinNoise(seed * 104729 + 3313)
    }

    /**
     * The plate stage on the processor: the history, the crust read onto the map, and the ground the
     * two make.
     */
    fun generate(config: WorldGenConfig, terrain: TerrainResult): PlateResult {
        val history = TectonicHistory.run(config)
        return generate(config, terrain, history, CrustMap.read(config, history)) { null }
    }

    /**
     * The same, with the crust read onto the map on [crustAccelerator]'s device where the settings
     * ask for the card (`ErosionConfig.acceleration`) and the device takes the job; the processor's
     * reading otherwise. The history itself runs on the processor (docs/DESIGN_LEDGER.md, TH1-1).
     */
    suspend fun generate(config: WorldGenConfig, terrain: TerrainResult, crustAccelerator: CrustMapAccelerator?): PlateResult {
        val history = TectonicHistory.run(config)
        val onTheCard =
            if (crustAccelerator != null && config.erosion.acceleration == Acceleration.GPU) {
                CrustMap.readOn(crustAccelerator, config, history)
            } else null
        return generate(config, terrain, history, onTheCard ?: CrustMap.read(config, history)) { inputs ->
            if (crustAccelerator != null && config.erosion.acceleration == Acceleration.GPU) crustAccelerator.beltFields(inputs) else null
        }
    }

    /** The belts' map pass's inputs on [config]'s world, as the stage builds them: the fixture a device's pass is held to ([BeltFieldsParity]). */
    internal fun beltFieldInputsOf(config: WorldGenConfig): BeltFieldInputs {
        val history = TectonicHistory.run(config)
        var captured: BeltFieldInputs? = null
        generate(config, TerrainStage.generate(config), history, CrustMap.read(config, history)) { inputs ->
            captured = inputs
            null
        }
        return captured!!
    }

    /**
     * The stage itself, the belts' map pass ([BeltFields]) offered to [readBeltFields] first, which
     * may decline it, in which case the processor's runs.
     */
    private inline fun generate(
        config: WorldGenConfig,
        terrain: TerrainResult,
        history: CrustHistory,
        crust: CrustOnMap,
        readBeltFields: (BeltFieldInputs) -> BeltFieldOutputs?
    ): PlateResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val cellWidths = BeltCellWidths.of(config)

        // The history that made the crust, and the crust read onto the map: the continents, the
        // plates that carry them and the boundaries between those plates are the history's present,
        // not a partition drawn for the map (docs/DESIGN_LEDGER.md, TH1-1).
        val plates = presentPlates(config, history)
        val plateId = crust.plateId

        // The present boundaries, classified by the history's plates' relative motion, and the
        // features stamped on them that are not the continent's own crust: the ocean's trenches,
        // ridges, arcs and transforms, and the present rifts' half-grabens. The continent's belts
        // are its crust's thickness from the history, with ridges laid along their strike
        // (docs/DESIGN_LEDGER.md, TH1-2).
        val noise = BeltNoise(config.seed)
        val uplift = FloatField(cellsAcross, cellsDown)
        val upliftRate = FloatField(cellsAcross, cellsDown)
        val continentalShare = FloatField(cellsAcross, cellsDown, crust.continentalShare)
        val boundaries = classifyPresentBoundaries(config, plateId, plates, history, continentalShare)
        segmentRifts(config, boundaries, plates)
        val distance = boundaryDistance(config, boundaries.keys)
        val presentDistanceCells = distance.distanceCellWidths
        val nearestBoundaryType = IntArray(cellsAcross * cellsDown) { -1 }
        val nearestBoundaryClass = IntArray(cellsAcross * cellsDown) { -1 }
        if (boundaries.isNotEmpty()) {
            stampEpoch(
                config = config,
                cellWidths = cellWidths,
                noise = noise,
                boundaries = boundaries,
                nearestBoundaryCell = distance.nearestBoundaryCell,
                distanceCells = presentDistanceCells,
                plateId = plateId,
                uplift = uplift,
                nearestBoundaryType = nearestBoundaryType,
                nearestBoundaryClass = nearestBoundaryClass,
                upliftRate = upliftRate
            )
        }
        // How much of each cell's relief the present belts stamped, 0 to 1, read before the
        // hotspots add their cones: where a belt stands, its stamp is the relief, and the crust's
        // own thickening there is not counted a second time below.
        val crustAgeReference = tectonics.crustAgeReference.coerceAtLeast(1e-6f)
        val stampedShare = FloatArray(cellsAcross * cellsDown) { (uplift.data[it] / crustAgeReference).coerceIn(0f, 1f) }

        stampHotspotChains(config, plates, plateId, continentalShare.data, uplift)

        val scale = config.scale
        val columns = Isostasy.Columns(config.isostasy)
        val isostatic = config.isostasy.enabled
        val beltReliefMetres = tectonics.beltReliefMetres
        val marginReliefMetres = tectonics.marginReliefStandardDeviationMetres
        val cratonReliefMetres = tectonics.cratonReliefStandardDeviationMetres
        val oceanicReliefMetres = tectonics.oceanicReliefStandardDeviationMetres
        val orogenReliefMetres = tectonics.orogenReliefStandardDeviationMetres
        val height = FloatField(cellsAcross, cellsDown)
        val seafloorAgeMyr = crust.seafloorAgeMyr
        // A kilometer of orogenic excess is worth this much of a stamped belt's relief, as a share
        // of what makes a belt fully orogenic: Airy's freeboard per kilometer of crust over the
        // relief [TectonicsConfig.crustAgeReference] stands for.
        val freeboardMetresPerKm = config.isostasy.let {
            (it.mantleDensity - it.continentalCrustDensity) / it.mantleDensity * METRES_PER_KILOMETRE
        }
        val excessForFullOrogen = crustAgeReference * beltReliefMetres / freeboardMetresPerKm
        val standardThicknessKm = config.isostasy.continentalCrustThicknessKm

        // The belts' own relief at the map's scale and the rock's erodibility, read off the crust
        // the history left (see [BeltFields]).
        val beltInputs = beltFieldInputs(config, crust, continentalShare.data, stampedShare, excessForFullOrogen)
        val beltFields = readBeltFields(beltInputs) ?: BeltFields.readOnProcessor(beltInputs)

        // The rock rising under the belts the history is still thickening: Airy's freeboard of the
        // crust added a year, the plate stage's own column (`IsostasyConfig`), where a stamped
        // feature's rate is not already larger. A kilometer of crust a million years is a
        // millimeter a year.
        val freeboardShare = config.isostasy.let { (it.mantleDensity - it.continentalCrustDensity) / it.mantleDensity }
        for (cell in 0 until cellsAcross * cellsDown) {
            val share = continentalShare.data[cell]
            val thickening = crust.presentThickeningKmPerMyr[cell]
            if (share <= 0f || thickening <= 0f) continue
            val rate = freeboardShare * thickening * share
            if (rate > upliftRate.data[cell]) upliftRate.data[cell] = rate
        }

        // The base noise as a standard score: zero mean, unit deviation. The settings above are
        // deviations in metres, so this is what makes them mean that — and it is what makes them
        // mean the same thing at every grid, since a filtered field's extremes are two outlying
        // cells while its spread is a property of the filter. See
        // [com.cartogenesis.worldgen.model.TerrainConfig.reliefCornerKm].
        val standardisedNoise = standardised(terrain.height)

        // Fine relief, an order of magnitude below anything the eye picks out of the shading. Both
        // the isostatic base and the uplift falloff are very smooth, which leaves some plains
        // locally planar; D8 routing over a plane sends every cell the same way, so rivers there
        // come out as straight parallel lines that never join. This gives the water something to
        // converge on.
        val detailNoise = PerlinNoise(config.seed * 7919 + 13)
        val detailLattice = GroundLattice(config, tectonics.detailWavelengthKm)

        // How far in from the edge of its own crust each cell sits, 0 at the edge and 1 in the
        // craton: the relief the crust carries is read off it. With isostasy off there is no crust
        // to have a profile — one datum, one relief — which is the world before S2 and the control
        // this chunk's guards are shown to fail against.
        val interiorShare =
            if (isostatic) cratonInteriorShare(config, continentalShare)
            else FloatArray(cellsAcross * cellsDown)

        // The base noise split at `TectonicsConfig.textureCornerKm`: the shape of the country, and
        // the texture on it. The shape keeps the crust's own deviation; the texture is scaled by
        // the local relief, cell by cell, which is what makes a plain smooth and a range rough.
        // A corner of zero leaves the whole field as shape and the texture at nothing, which is
        // the stationary field this pass replaced and the control `GroundTextureTest` shows the
        // texture guard failing against.
        val textured = tectonics.textureCornerKm > 0.0
        val shapeNoise = FloatField(cellsAcross, cellsDown, standardisedNoise.copyOf())
        if (textured) {
            BoxBlur.apply(
                shapeNoise,
                radiusAcross = config.wholeCellsFor(tectonics.textureCornerKm / 2.0),
                radiusDown = wholeRowsFor(config, tectonics.textureCornerKm / 2.0),
                passes = 1
            )
        }
        val textureNoise = FloatArray(standardisedNoise.size) {
            standardisedNoise[it] - shapeNoise.data[it]
        }
        // To unit deviation, so the amplitude assigned below is the deviation it asks for rather
        // than that times whatever share of the field's spread happened to fall in this band.
        val textureSpread = deviationOf(textureNoise)
        if (textureSpread > 0f) {
            for (cell in textureNoise.indices) textureNoise[cell] /= textureSpread
        }

        // Everything but the texture — the crust's datum, the shape of the base relief on it, and
        // the belts. Position-derived, so every cell writes only its own index and the row bands
        // may be filled in any order.
        val datumMetres = FloatArray(cellsAcross * cellsDown)
        val shapeMetres = FloatArray(cellsAcross * cellsDown)
        // The belts' share of [shapeMetres], kept apart so the platform under them can be filled
        // without filling them: see [fillContinentalBasins].
        val beltMetres = FloatArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val share = continentalShare.data[cell]
                    // Where the crust floats: the history's own column, beyond the standard one.
                    // A present belt's stamp is its relief, so the thickening orogeny gave the
                    // crust there is left out where the stamp stands, and an old belt's is not.
                    val excessKm = crust.excessKm[cell]
                    val thickeningKm =
                        crust.thicknessKm[cell] - standardThicknessKm - excessKm * stampedShare[cell]
                    datumMetres[cell] =
                        if (isostatic) {
                            columns.altitudeMetres(
                                share, seafloorAgeMyr[cell], thickeningKm,
                                columns.stretchedThermalBuoyancyMetres(crust.thicknessKm[cell], crust.riftAgeMyr[cell])
                            )
                        } else {
                            0f
                        }
                    // How orogenic a stamped feature makes the ground, a rift's shoulders or an
                    // arc: rougher than the plain round it in every direction. The continent's own
                    // belts carry their relief as ridges along strike instead ([BeltFields]).
                    val orogenShare = stampedShare[cell]
                    // Flat in the middle of a continent and loud at its rim: see
                    // `TectonicsConfig.cratonReliefStandardDeviationMetres`.
                    val crustReliefMetres =
                        marginReliefMetres +
                            interiorShare[cell] * (cratonReliefMetres - marginReliefMetres)
                    val reliefMetres =
                        if (isostatic) {
                            oceanicReliefMetres +
                                share * (crustReliefMetres - oceanicReliefMetres) +
                                orogenShare * orogenReliefMetres
                        } else {
                            crustReliefMetres + orogenShare * orogenReliefMetres
                        }
                    val detail = tectonics.detailAmplitude * detailNoise.fbm(
                        detailLattice.x(column),
                        detailLattice.y(row),
                        4,
                        detailLattice.period,
                        detailLattice.period
                    )
                    // The base noise is a standard score, so multiplying by a deviation in metres
                    // is all there is to it, and its mean of zero is what keeps it from moving the
                    // level the crust floats at.
                    beltMetres[cell] = (uplift.data[cell] + detail) * beltReliefMetres +
                        beltFields.ridgeMeters[cell] + beltFields.provinceMeters[cell]
                    shapeMetres[cell] = shapeNoise.data[cell] * reliefMetres + beltMetres[cell]
                }
            }
        }

        // The relief the texture answers to, measured on the ground the noise and the belts make
        // rather than on the finished surface: the crust's own four and a half kilometre step from
        // continent to ocean floor is structure and not relief, and reading it as relief would put
        // a mountain range's worth of texture on every coast.
        val localReliefMetres =
            if (textured) localSpread(config, shapeMetres, tectonics.textureReliefWindowKm)
            else FloatArray(cellsAcross * cellsDown)
        val textureReliefThresholdMetres =
            tectonics.textureReliefThresholdMetres.coerceAtLeast(1e-3f)
        val textureShareOfRelief =
            if (!textured) 0f
            else {
                (tectonics.textureCornerKm / tectonics.textureReliefWindowKm)
                    .pow(tectonics.topographyHurstExponent)
                    .toFloat()
            }

        // Second pass: the texture, and the platform the belts stand on.
        val textureMetres = FloatArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    // Ahnert's line where there is relief to spend it on, and a landscape that
                    // stays flat where there is not: see
                    // `TectonicsConfig.textureReliefThresholdMetres`.
                    val relief = localReliefMetres[cell]
                    val dissected = relief * relief / (relief + textureReliefThresholdMetres)
                    textureMetres[cell] = textureNoise[cell] * dissected * textureShareOfRelief
                }
            }
        }
        val filledPlatform =
            if (isostatic && tectonics.basinFill) {
                val platformMetres = FloatArray(cellsAcross * cellsDown) {
                    datumMetres[it] + (shapeMetres[it] - beltMetres[it]) + textureMetres[it]
                }
                fillContinentalBasins(config, platformMetres, continentalShare.data)
            } else null
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val metres =
                        if (filledPlatform == null) {
                            datumMetres[cell] + shapeMetres[cell] + textureMetres[cell]
                        } else {
                            filledPlatform[cell] + beltMetres[cell]
                        }
                    // No ceiling: what holds a belt down is the lower crust's flow in the history,
                    // which spreads crust past its strength into a plateau (docs/DESIGN_LEDGER.md, TH1-2).
                    height.data[cell] = scale.fieldAtAltitude(metres)
                }
            }
        }

        return PlateResult(
            plates = plates,
            plateId = plateId,
            boundaryDistance = FloatField(cellsAcross, cellsDown, presentDistanceCells),
            nearestBoundaryType = nearestBoundaryType,
            nearestBoundaryClass = nearestBoundaryClass,
            height = height,
            continentalShare = continentalShare,
            seafloorAgeMyr = FloatField(cellsAcross, cellsDown, seafloorAgeMyr),
            seafloorHalfSpreadingRateKmPerMyr = meanHalfSpreadingRateKmPerMyr(history),
            upliftRateMmPerYear = upliftRate,
            crustAgeMyr = FloatField(cellsAcross, cellsDown, crust.crustAgeMyr),
            crustThicknessKm = FloatField(cellsAcross, cellsDown, crust.thicknessKm),
            orogenicExcessKm = FloatField(cellsAcross, cellsDown, crust.excessKm),
            orogenyKind = crust.orogenyKind,
            terraneId = crust.terrane,
            riftAgeMyr = FloatField(cellsAcross, cellsDown, crust.riftAgeMyr),
            rockErodibility = FloatField(cellsAcross, cellsDown, beltFields.rockErodibility),
            windowTectonicKm = FloatField(cellsAcross, cellsDown, crust.windowTectonicKm),
            windowWearKm = FloatField(cellsAcross, cellsDown, crust.windowWearKm),
            windowOnsetMyrAgo = FloatField(cellsAcross, cellsDown, crust.windowOnsetMyrAgo),
            exposureWindowMyr = history.exposureWindowMyr
        )
    }

    /**
     * The belts' map pass's inputs: the strike tensor on the map, the ridges' amplitude where the
     * history left orogenic excess, a sub-cell province's where a back-arc was stretched, the
     * rock's softness, and each cell's distance from a terrane boundary.
     *
     * The ridges carry [com.cartogenesis.worldgen.model.TectonicsConfig.orogenReliefStandardDeviationMetres]
     * in proportion to how much of a full orogen's excess the crust still holds, which is how young
     * and how thick it is at once: the excess decays as the belt wears (`orogenDecayTimeYears`).
     *
     * The province is the Basin and Range's horsts and grabens, 24 to 32 km apart (Stewart 1971,
     * abstract), finer than a cell: drawn at [PROVINCE_NYQUIST_CELLS] cells, the finest wavelength the
     * grid draws without aliasing, with the relief a cell's mean keeps of ranges standing
     * [PROVINCE_RANGE_RELIEF_METERS] over their basins ([BeltFields.cellMeanShare]), fading as the
     * stretching ages, as an orogen's excess does.
     *
     * The softness: a craton's crystalline basement nothing; an arc's volcanics, a rift's or a
     * back-arc's fill and an accreted terrane's arc and melange the weakest; a collision or an
     * Andean belt half, its crystalline core and metamorphosed basement exhumed beside its
     * sedimentary and volcanic cover; each fading as the crust ages, at the orogens' decay time, as
     * the weak cover is stripped. Stock and Montgomery
     * (*Geologic constraints on bedrock river incision using the stream power law*, JGR 104, 1999,
     * abstract) find K of 10^-7 to 10^-6 for granite and metamorphic rock, 10^-5 to 10^-4 for
     * volcaniclastics and more for mudstones, so the band's two ends are the two kinds of rock; age
     * and kind alone are an imperfect stand-in for the lithology, which nothing here records.
     */
    private fun beltFieldInputs(
        config: WorldGenConfig,
        crust: CrustOnMap,
        continentalShare: FloatArray,
        stampedShare: FloatArray,
        excessForFullOrogen: Float
    ): BeltFieldInputs {
        val tectonics = config.tectonics
        val cells = config.width * config.height
        val decayMyr = (tectonics.orogenDecayTimeYears / 1e6).toFloat()
        val ridgeAmplitude = FloatArray(cells)
        val provinceAmplitude = FloatArray(cells)
        val softness = FloatArray(cells)
        val cellKm = sqrt(config.cellWidthKm * config.cellHeightKm)
        val provinceWavelengthKm = maxOf(PROVINCE_RANGE_SPACING_KM, PROVINCE_NYQUIST_CELLS * cellKm)
        // A sinusoid's standard deviation is its amplitude over root two, half the relief it spans.
        val provinceStandardMeters = (PROVINCE_RANGE_RELIEF_METERS / 2.0 / sqrt(2.0) *
            BeltFields.cellMeanShare(PROVINCE_RANGE_SPACING_KM, cellKm)).toFloat()
        for (cell in 0 until cells) {
            val share = continentalShare[cell]
            val kind = crust.orogenyKind[cell]
            val ageMyr = crust.crustAgeMyr[cell].coerceAtLeast(0f)
            val fading = exp(-ageMyr / decayMyr)
            if (share > 0f) {
                val inherited = (crust.excessKm[cell] * (1f - stampedShare[cell]) / excessForFullOrogen).coerceIn(0f, 1f)
                ridgeAmplitude[cell] = tectonics.orogenReliefStandardDeviationMetres * inherited * share
                if (kind == OrogenyKind.BACK_ARC_EXTENSION.ordinal) provinceAmplitude[cell] = provinceStandardMeters * fading * share
            }
            val young = when (kind) {
                OrogenyKind.NONE.ordinal -> if (share >= 0.5f) 0f else 1f
                OrogenyKind.COLLISION.ordinal, OrogenyKind.ASSEMBLY_SUTURE.ordinal, OrogenyKind.ANDEAN.ordinal -> MIXED_BELT_SOFTNESS
                else -> 1f
            }
            softness[cell] = if (kind == OrogenyKind.NONE.ordinal) young else young * fading
        }
        return BeltFieldInputs(
            mapColumns = config.width,
            mapRows = config.height,
            radiusKm = config.scale.radiusMeters / 1_000.0,
            seed = (config.seed xor (config.seed ushr 32)).toInt() xor BELT_FIELD_SALT,
            strikeEastEast = crust.strikeEastEast,
            strikeEastNorth = crust.strikeEastNorth,
            strikeNorthNorth = crust.strikeNorthNorth,
            ridgeAmplitudeMeters = ridgeAmplitude,
            provinceAmplitudeMeters = provinceAmplitude,
            softness = softness,
            weakZoneKm = terraneBoundaryKm(config, crust.terrane, continentalShare),
            ridgeWavelengthsKm = doubleArrayOf(RIDGE_LONGEST_WAVELENGTH_KM, RIDGE_LONGEST_WAVELENGTH_KM / 2),
            ridgeOctaveWeights = BeltFields.octaveWeights(RIDGE_OCTAVES, tectonics.topographyHurstExponent),
            provinceWavelengthKm = provinceWavelengthKm,
            elongation = RIDGE_ELONGATION,
            impulsesPerCube = GABOR_IMPULSES_PER_CUBE,
            weakZoneEfoldKm = WEAK_ZONE_EFOLD_KM,
            erodibilityBandFactor = ERODIBILITY_BAND_FACTOR
        )
    }

    /**
     * How far each cell lies from the nearest boundary between two terranes of continental crust,
     * km on the ground, by jump flooding from the cells beside another terrane; -1 everywhere on a
     * world with no such boundary.
     */
    private fun terraneBoundaryKm(config: WorldGenConfig, terrane: IntArray, continentalShare: FloatArray): FloatArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cells = cellsAcross * cellsDown
        val distance = FloatArray(cells) { JumpFloodDistance.INFINITE }
        val nearest = IntArray(cells) { -1 }
        var any = false
        for (cell in 0 until cells) {
            if (continentalShare[cell] < 0.5f || terrane[cell] < 0) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            for (step in 0 until 4) {
                val neighborRow = row + NEIGHBOR_ROW_STEPS[step]
                if (neighborRow < 0 || neighborRow >= cellsDown) continue
                val neighbor = neighborRow * cellsAcross + (column + NEIGHBOR_COLUMN_STEPS[step]).mod(cellsAcross)
                if (continentalShare[neighbor] >= 0.5f && terrane[neighbor] >= 0 && terrane[neighbor] != terrane[cell]) {
                    distance[cell] = 0f
                    nearest[cell] = cell
                    any = true
                    break
                }
            }
        }
        if (!any) return FloatArray(cells) { -1f }
        JumpFloodDistance.run(cellsAcross, cellsDown, distance, nearest, config.cellHeightInCellWidths)
        val kmPerCellWidth = config.cellWidthKm.toFloat()
        return FloatArray(cells) { if (distance[it] >= JumpFloodDistance.INFINITE) -1f else distance[it] * kmPerCellWidth }
    }

    /**
     * The ridges along a belt's strike: two octaves, the longest 150 km, the spacing of the Andes'
     * Cordilleras Occidental and Oriental across the Altiplano, the next 75 km, of the Greater and
     * Lesser Himalaya across the Midlands (the figures [com.cartogenesis.worldgen.model.TectonicsConfig.orogenReliefStandardDeviationMetres]
     * cites for an orogen's relief), weighted by the topography's Hurst exponent.
     */
    private const val RIDGE_LONGEST_WAVELENGTH_KM = 150.0
    private const val RIDGE_OCTAVES = 2

    /**
     * How many crests long a ridge kernel runs: three, the length over the spacing of the Basin and
     * Range's ranges, 80 to 150 km long and 24 to 32 km apart.
     */
    private const val RIDGE_ELONGATION = 3.0

    /**
     * Impulses in each cube of the noise: each point then hears the kernels of about 17, `4/3 pi`
     * cubes' worth, enough that the sum is near Gaussian (Lagae et al. 2009).
     */
    private const val GABOR_IMPULSES_PER_CUBE = 4

    /** Stewart's (1971) spacing of the Basin and Range's grabens, 24 to 32 km: the middle. */
    private const val PROVINCE_RANGE_SPACING_KM = 28.0

    /**
     * How high the Basin and Range's ranges stand over their basins, m: 900 to 1,500 m, 3,000 to
     * 5,000 ft (Encyclopaedia Britannica, *Basin and Range Province*): the middle.
     */
    private const val PROVINCE_RANGE_RELIEF_METERS = 1_200.0

    /** The finest wavelength a grid draws without aliasing it into a coarser one, in cells: three. */
    private const val PROVINCE_NYQUIST_CELLS = 3.0

    /** A collisional or Andean belt's softness while young: half, its crystalline core beside its cover. */
    private const val MIXED_BELT_SOFTNESS = 0.5f

    /**
     * How far a terrane boundary's weakness reaches, km: a suture zone's melange and its faults,
     * tens of kilometers wide, as the Indus-Yarlung suture's; the e-folding of the softness.
     */
    private const val WEAK_ZONE_EFOLD_KM = 25.0

    /**
     * The bedrock band's span: K from 10^-6 to 10^-5 a year across lithologies (Whipple and Tucker
     * 1999, as `ErosionConfig.bedrockErodibilityPerYear` cites them), so the factor runs 1 to 10.
     */
    private const val ERODIBILITY_BAND_FACTOR = 10.0

    /** Keeps the belt fields' hash apart from every other stream the seed feeds. */
    private const val BELT_FIELD_SALT = 0x2B1E7

    /**
     * [field] as a standard score: the same shape with a mean of zero and a deviation of one.
     *
     * So that a relief written in metres in `TectonicsConfig` is a *deviation* in metres, which is
     * the only reading of a band-filtered field that means the same thing at every grid — the
     * extremes of one are a pair of outlying cells and move with the cell count, its spread does
     * not. A field with no spread at all (a constant, which is what a zeroed terrain stage gives)
     * comes back as zeros rather than as a division by nothing.
     */
    private fun standardised(field: FloatField): FloatArray {
        val values = field.data
        var sum = 0.0
        for (value in values) sum += value.toDouble()
        val mean = sum / values.size
        var squares = 0.0
        for (value in values) {
            val offset = value.toDouble() - mean
            squares += offset * offset
        }
        val deviation = kotlin.math.sqrt(squares / values.size)
        if (deviation <= 0.0) return FloatArray(values.size)
        return FloatArray(values.size) { ((values[it].toDouble() - mean) / deviation).toFloat() }
    }

    /** The standard deviation of [values], in whatever unit they are in. */
    private fun deviationOf(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (value in values) sum += value.toDouble()
        val mean = sum / values.size
        var squares = 0.0
        for (value in values) {
            val offset = value.toDouble() - mean
            squares += offset * offset
        }
        return sqrt(squares / values.size).toFloat()
    }

    /** The mean of [values] weighted by [weights], and zero where the weights sum to nothing. */
    private fun weightedMean(values: FloatArray, weights: FloatArray): Float {
        var weighted = 0.0
        var total = 0.0
        for (cell in values.indices) {
            weighted += values[cell].toDouble() * weights[cell]
            total += weights[cell].toDouble()
        }
        return if (total <= 0.0) 0f else (weighted / total).toFloat()
    }

    /** [kilometres] of ground as a whole number of *rows*, which are not as tall as a cell is wide. */
    private fun wholeRowsFor(config: WorldGenConfig, kilometres: Double): Int =
        kotlin.math.round(kilometres / config.cellHeightKm).toInt().coerceAtLeast(1)

    /**
     * How far in from the edge of its own crust each cell sits: 0 where the crust is half oceanic
     * and approaching 1 in the middle of a continent.
     *
     * `1 - exp(-distance / cratonReachKm)` of the distance to the nearest cell that is not mostly
     * continental crust. Exponential rather than a ramp because a ramp finishes at a distance
     * contour and a distance contour drawn on a map is a visible ring — the annulus S2's earlier
     * passes were called out for.
     *
     * The distance is Euclidean and in kilometres, by the same jump flood every other distance
     * field in this stage uses, told how tall a row is so that it propagates a length on the ground
     * rather than a count of cells. That matters here more than anywhere: a cell of this map is
     * twice as wide as it is tall, so a reach counted in cells and converted with the cell's width
     * thickens a craton over half the distance northward that it takes westward — two hundred
     * kilometres where `TectonicsConfig.cratonReachKm` asks for four hundred — and every continent
     * comes out with its profile squashed into an ellipse lying east-west.
     *
     * Internal rather than private so `CratonReachTest` measures the profile the stage actually
     * draws on an edge it can put where it likes, instead of hunting for a straight coast in a
     * generated world.
     */
    internal fun cratonInteriorShare(
        config: WorldGenConfig,
        continentalShare: FloatField
    ): FloatArray {
        val cellCount = config.width * config.height
        val distanceCellWidths = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearest = IntArray(cellCount) { -1 }
        var anyEdge = false
        for (cell in 0 until cellCount) {
            if (continentalShare.data[cell] < 0.5f) {
                distanceCellWidths[cell] = 0f
                nearest[cell] = cell
                anyEdge = true
            }
        }
        // A world with no ocean at all has no crustal edge to measure from, and every cell of it is
        // as cratonic as ground gets.
        if (!anyEdge) return FloatArray(cellCount) { 1f }
        JumpFloodDistance.run(
            config.width, config.height, distanceCellWidths, nearest, config.cellHeightInCellWidths
        )
        val kilometresPerCellWidth = config.cellWidthKm.toFloat()
        val reachKm = config.tectonics.cratonReachKm.toFloat().coerceAtLeast(1e-3f)
        return FloatArray(cellCount) {
            val distance = distanceCellWidths[it]
            if (distance >= JumpFloodDistance.INFINITE) 1f
            else 1f - exp(-distance * kilometresPerCellWidth / reachKm)
        }
    }

    /**
     * The continental platform with every closed hollow in it filled to the level it spills at, in
     * metres; cells that are not mostly continental crust, and every cell no hollow holds, come
     * back as they were.
     *
     * A priority flood (Barnes, Lehman and Mulla 2014) seeded from the ocean floor at its own level,
     * so a hollow open to the sea by ground lower than its rim is not a hollow, and stays as deep as
     * it is.
     */
    internal fun fillContinentalBasins(
        config: WorldGenConfig,
        platformMetres: FloatArray,
        continentalShare: FloatArray
    ): FloatArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val scale = config.scale
        val filled = platformMetres.copyOf()
        val visited = BooleanArray(filled.size)
        val heap = LongMinHeap(cellsAcross * 4)
        for (cell in filled.indices) {
            if (continentalShare[cell] >= 0.5f) continue
            visited[cell] = true
            var touchesContinent = false
            FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) {
                if (continentalShare[it] >= 0.5f) touchesContinent = true
            }
            if (touchesContinent) heap.push(FlowRouting.encode(scale.fieldAtAltitude(filled[cell]), cell))
        }
        while (!heap.isEmpty()) {
            val cell = FlowRouting.decodeIndex(heap.pop())
            val level = filled[cell]
            FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbour ->
                if (!visited[neighbour]) {
                    visited[neighbour] = true
                    if (filled[neighbour] < level) filled[neighbour] = level
                    heap.push(FlowRouting.encode(scale.fieldAtAltitude(filled[neighbour]), neighbour))
                }
            }
        }
        return filled
    }

    /**
     * The standard deviation of [field] within a window [windowKm] across, one entry per cell.
     *
     * `sqrt(mean of the squares - square of the mean)` over two box means, which is the cheapest
     * honest reading of "how much relief is there around here" and costs four sweeps of the grid.
     * The window is a length rather than a count of cells, so what it measures is the same
     * quantity at every grid.
     *
     * The arithmetic is done in kilometres rather than in metres, and that is not cosmetic. Both
     * box means are running sums in single precision, and the difference of two numbers near
     * `10,000^2` carries three fewer significant digits than either of them: in metres the
     * variance of a plain would be lost in the rounding of the sum it is subtracted from. A
     * thousandth of the height squares the same field a millionth as large.
     */
    private fun localSpread(
        config: WorldGenConfig,
        field: FloatArray,
        windowKm: Double
    ): FloatArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val radiusAcross = config.wholeCellsFor(windowKm / 2.0)
        val radiusDown = wholeRowsFor(config, windowKm / 2.0)
        val kilometres = FloatArray(field.size) { field[it] / METRES_PER_KILOMETRE }
        val mean = FloatField(cellsAcross, cellsDown, kilometres)
        val squares = FloatField(
            cellsAcross,
            cellsDown,
            FloatArray(field.size) { kilometres[it] * kilometres[it] }
        )
        BoxBlur.apply(mean, radiusAcross, radiusDown, passes = 1)
        BoxBlur.apply(squares, radiusAcross, radiusDown, passes = 1)
        return FloatArray(field.size) {
            val variance = squares.data[it] - mean.data[it] * mean.data[it]
            if (variance <= 0f) 0f else sqrt(variance) * METRES_PER_KILOMETRE
        }
    }

    /** How far every cell stands from the nearest of a set of boundary cells, and which one that is. */
    internal class BoundaryDistance(
        /** The distance, in cell widths; [JumpFloodDistance.INFINITE] where there is no boundary. */
        val distanceCellWidths: FloatArray,
        /** The index of the nearest boundary cell, or -1 where there is none. */
        val nearestBoundaryCell: IntArray
    )

    /**
     * The distance every belt profile is a function of: from each cell to the nearest of
     * [boundaryCells], Euclidean by jump flooding, so a metric whose contours are octagons does not
     * hand its facets to the plateau rims and the trench walls. See [JumpFloodDistance].
     *
     * On the ground and in cell widths, told how tall a row is: every belt's half-width and offset
     * in `TectonicsConfig` is a length on the ground, read here in cell widths ([BeltCellWidths]),
     * so a belt reaches the same kilometers from a boundary running east-west as from one running
     * north-south. Counted in plain cells it
     * reached half as far north and south, and every range was twice as broad east-west as the
     * same range turned through a right angle.
     *
     * Internal so `TectonicGroundTest` can measure it on a boundary it lays where it likes.
     */
    internal fun boundaryDistance(config: WorldGenConfig, boundaryCells: Collection<Int>): BoundaryDistance {
        val cellCount = config.width * config.height
        val distanceCellWidths = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearestBoundaryCell = IntArray(cellCount) { -1 }
        boundaryCells.forEach { cell ->
            distanceCellWidths[cell] = 0f
            nearestBoundaryCell[cell] = cell
        }
        if (boundaryCells.isNotEmpty()) {
            JumpFloodDistance.run(
                config.width, config.height, distanceCellWidths, nearestBoundaryCell,
                config.cellHeightInCellWidths
            )
        }
        return BoundaryDistance(distanceCellWidths, nearestBoundaryCell)
    }

    /**
     * Every length in `TectonicsConfig` that a belt profile, a drift or a hotspot trail is drawn
     * with, in cell widths of this grid: the kilometers the settings state, converted once by
     * [WorldScale] so the per-cell pass below reads the distance field's own unit.
     *
     * Kept as floats in cell widths because the profiles were written in them, and converted
     * before any arithmetic rather than inside it, so a width is the same float here that the
     * 512 grid's count scaled by a power of two was: `km / cellWidthKm` is exact whenever the
     * grid's width is a power of two. See docs/DESIGN_LEDGER.md, Q2.
     */
    internal data class BeltCellWidths(
        val boundaryFalloffCells: Float,
        val andeanWidthCells: Float,
        val arcOffsetCells: Float,
        val arcWidthCells: Float,
        val collisionWidthCells: Float,
        val islandArcOffsetCells: Float,
        val islandArcWidthCells: Float,
        val riftWidthCells: Float,
        val riftShoulderOffsetCells: Float,
        val riftShoulderWidthCells: Float,
        val hotspotChainLengthCells: Float,
        val hotspotSpacingCells: Float,
        val hotspotRadiusCells: Float
    ) {
        companion object {
            /** [config]'s tectonic lengths in cell widths of its own grid. */
            fun of(config: WorldGenConfig): BeltCellWidths {
                val tectonics = config.tectonics
                return BeltCellWidths(
                    boundaryFalloffCells = config.cellsFor(tectonics.boundaryFalloffKm),
                    andeanWidthCells = config.cellsFor(tectonics.andeanWidthKm),
                    arcOffsetCells = config.cellsFor(tectonics.arcOffsetKm),
                    arcWidthCells = config.cellsFor(tectonics.arcWidthKm),
                    collisionWidthCells = config.cellsFor(tectonics.collisionWidthKm),
                    islandArcOffsetCells = config.cellsFor(tectonics.islandArcOffsetKm),
                    islandArcWidthCells = config.cellsFor(tectonics.islandArcWidthKm),
                    riftWidthCells = config.cellsFor(tectonics.riftWidthKm),
                    riftShoulderOffsetCells = config.cellsFor(tectonics.riftShoulderOffsetKm),
                    riftShoulderWidthCells = config.cellsFor(tectonics.riftShoulderWidthKm),
                    hotspotChainLengthCells = config.cellsFor(tectonics.hotspotChainLengthKm),
                    hotspotSpacingCells = config.cellsFor(tectonics.hotspotSpacingKm),
                    hotspotRadiusCells = config.cellsFor(tectonics.hotspotRadiusKm)
                )
            }
        }
    }

    /**
     * The present boundaries' belts, stamped into [uplift], with each cell's nearest boundary's type
     * and class and its rate of rock uplift.
     *
     * The belts' profiles as they were before the tectonic history, read from the history's present
     * boundaries; the belts of the past are not stamped, since the history leaves them in the crust's
     * thickness (docs/DESIGN_LEDGER.md, TH1-1). The profiles' redesign from the history's own
     * thickening is TH1-2's. A per-cell pass, specified with a GPU path in mind.
     */
    private fun stampEpoch(
        config: WorldGenConfig,
        cellWidths: BeltCellWidths,
        noise: BeltNoise,
        boundaries: Map<Int, Boundary>,
        nearestBoundaryCell: IntArray,
        distanceCells: FloatArray,
        plateId: IntArray,
        uplift: FloatField,
        nearestBoundaryType: IntArray,
        nearestBoundaryClass: IntArray,
        upliftRate: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val ridgeNoise = noise.ridge
        val rangeNoise = noise.range
        val widthNoise = noise.width
        val arcNoise = noise.arc
        // Every belt noise on a lattice square on the ground, so a belt swells, pinches and breaks
        // into massifs at the same spacing whichever way it runs.
        val widthSwellLattice = GroundLattice(config, WIDTH_SWELL_WAVELENGTH_KM)
        val roughnessLattice = GroundLattice(config, ROUGHNESS_WAVELENGTH_KM)
        val rangeLattice = GroundLattice(config, tectonics.rangeVariationWavelengthKm)
        val edgeJitterLattice = GroundLattice(config, EDGE_JITTER_WAVELENGTH_KM)
        val arcChainLattice = GroundLattice(config, ARC_CHAIN_WAVELENGTH_KM)
        // The relay crest's own irregularity.
        val relayLattice = GroundLattice(config, RELAY_CREST_WAVELENGTH_KM)

        run {
            val beltReachCells = cellWidths.boundaryFalloffCells
            // The farthest any profile below reaches from its boundary, so a cell out in a plate
            // interior can be skipped before any of the noise is sampled. The widest is whichever
            // of the belts is broadest once the along-strike width swell is at its maximum.
            val widestScale = MAX_WIDTH_SCALE * MAX_EDGE_JITTER
            val maxReachCells = maxOf(
                beltReachCells * MAX_WIDTH_SCALE,
                cellWidths.andeanWidthCells * widestScale,
                cellWidths.collisionWidthCells * widestScale,
                cellWidths.arcOffsetCells + cellWidths.arcWidthCells,
                cellWidths.islandArcOffsetCells + cellWidths.islandArcWidthCells * widestScale,
                cellWidths.riftShoulderOffsetCells +
                    cellWidths.riftShoulderWidthCells * (1f + tectonics.riftSegmentShoulderVariation)
            )
            // Each cell writes only its own uplift entry, and the roughness comes from position
            // rather than a running RNG, so this splits cleanly across cores.
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        val boundary = boundaries[nearestBoundaryCell[cell]] ?: continue
                        val interaction = boundary.interaction
                        nearestBoundaryType[cell] = interaction.type.ordinal
                        nearestBoundaryClass[cell] = interaction.pairClass.ordinal

                        val distanceFromBoundary = distanceCells[cell]
                        if (distanceFromBoundary >= maxReachCells) continue
                        // A belt that keeps the same width for its whole length reads as drawn on
                        // even once its height varies, so the width swells and pinches too. The
                        // noise is sampled on position, so neighbouring cells agree and the belt
                        // stays continuous rather than dissolving into blotches.
                        val widthScale = WIDTH_SWELL_MIN + WIDTH_SWELL_SPAN * (0.5f + 0.5f *
                            widthNoise.fbm(
                                widthSwellLattice.x(column),
                                widthSwellLattice.y(row),
                                3,
                                widthSwellLattice.period,
                                widthSwellLattice.period
                            ))
                        val narrow = sharpFalloff(
                            distanceFromBoundary,
                            beltReachCells * NARROW_SHARE_OF_BELT
                        )

                        // Breaks up the otherwise uniform ridge profile into distinct peaks.
                        val roughness = ROUGHNESS_MIN + ROUGHNESS_SPAN * ridgeNoise.fbm(
                            roughnessLattice.x(column),
                            roughnessLattice.y(row),
                            4,
                            roughnessLattice.period,
                            roughnessLattice.period
                        )

                        // Slow variation along the length of a belt. Without it, every convergent
                        // boundary rises to the same height for its entire run — which is the one
                        // thing that makes plate edges read as drawn on rather than grown. Real
                        // ranges swell, sag, and break into separate massifs.
                        val alongStrikeAmount = tectonics.rangeVariation.coerceIn(0f, 1f)
                        val swell = rangeNoise.fbm(
                            rangeLattice.x(column),
                            rangeLattice.y(row),
                            3,
                            rangeLattice.period,
                            rangeLattice.period
                        )
                        // Raised to a power so the belt spends more of its length low and rises
                        // into discrete massifs, rather than undulating gently about its mean. A
                        // belt that only ever sags to a third of its height stays a continuous
                        // wall; one that sags near to nothing becomes a chain with gaps in it.
                        val swollen = (0.5f + 0.5f * swell).coerceIn(0f, 1f).pow(1.6f)
                        val alongRange =
                            ((1f - alongStrikeAmount) + alongStrikeAmount * swollen * 1.9f).coerceIn(0f, 1.9f)

                        val strength = interaction.strength
                        val upliftHere: Float
                        if (!tectonics.crustPairProfiles) {
                            // The generator before the crust pairs: one belt profile for every
                            // convergent pair, whatever the crusts. Kept so `BoundaryPairTest`
                            // can measure the world that was replaced rather than take its word
                            // for it. See docs/DESIGN_LEDGER.md, B2.
                            val broadFalloff = beltFalloff(distanceFromBoundary, beltReachCells * widthScale)
                            if (broadFalloff <= 0f && narrow <= 0f) continue
                            upliftHere = when (interaction.type) {
                                BoundaryType.CONVERGENT ->
                                    if (interaction.continentalCollision) {
                                        tectonics.mountainHeight * strength *
                                            broadFalloff * roughness * alongRange
                                    } else if (boundary.oceanicSide) {
                                        -tectonics.trenchDepth * strength * narrow
                                    } else {
                                        tectonics.mountainHeight * OVERRIDING_BELT_SHARE *
                                            strength * broadFalloff * roughness * alongRange
                                    }

                                BoundaryType.DIVERGENT ->
                                    if (boundary.oceanicSide) {
                                        tectonics.mountainHeight * 0.22f * strength * narrow
                                    } else {
                                        -tectonics.mountainHeight * 0.3f * strength * narrow
                                    }

                                BoundaryType.TRANSFORM ->
                                    tectonics.mountainHeight * 0.12f * strength * narrow *
                                        (roughness - 0.75f)
                            }
                            uplift.data[cell] += upliftHere
                            recordUpliftRate(upliftRate, cell, upliftHere, interaction.pairClass, tectonics)
                            continue
                        }

                        // A plateau that sagged to nothing between massifs would be a chain again,
                        // so it feels only a fraction of the along-strike variation; an island arc
                        // wants the opposite, sagging hard so that only its swells clear the water.
                        val plateauAmount =
                            (alongStrikeAmount * tectonics.plateauAlongVariation).coerceIn(0f, 1f)
                        val alongPlateau = ((1f - plateauAmount) + plateauAmount * swollen * 1.9f)
                            .coerceIn(0f, 1.9f)
                        val sagged = (0.5f + 0.5f * swell).coerceIn(0f, 1f).pow(ARC_SAG_EXPONENT)
                        val alongArc = ((1f - alongStrikeAmount) + alongStrikeAmount * sagged * 1.9f)
                            .coerceIn(0f, 1.9f)

                        // A second, finer swell of the width, on top of `widthScale`: a rim
                        // that meanders within itself at three times the frequency is what keeps
                        // a plateau edge from reading as a drawn curve. It was written to hide the
                        // octagonal facets a chamfer distance left on the widest plateaus and is
                        // kept as scenery now that the distance is Euclidean. See
                        // docs/DESIGN_LEDGER.md, G4.
                        val edgeJitter = EDGE_JITTER_MIN + EDGE_JITTER_SPAN *
                            (0.5f + 0.5f * widthNoise.fbm(
                                edgeJitterLattice.x(column),
                                edgeJitterLattice.y(row),
                                3,
                                edgeJitterLattice.period,
                                edgeJitterLattice.period
                            )).coerceIn(0f, 1f)

                        upliftHere = when (interaction.pairClass) {
                            // Oceanic under continental. The trench is the subducting plate's and
                            // the range the overriding one's, so the two sides of the same
                            // boundary get quite different ground — which is the asymmetry a
                            // symmetric distance profile could not express.
                            // The trench is the ocean's; the range behind it is the upper plate's
                            // crust, thickened in the history by its arc and its shortening.
                            BoundaryClass.ANDEAN_MARGIN ->
                                if (!boundary.overridingSide) -tectonics.trenchDepth * strength * narrow else 0f

                            // Continental against continental. Neither side will go under, so the
                            // crust thickens over a wide area instead of piling onto a line: broad,
                            // high, flat-topped and very nearly symmetric.
                            // Two continents' crust stacked in the history, spread by its lower
                            // crust's flow: the thickness is the belt, so nothing is stamped.
                            BoundaryClass.COLLISION_PLATEAU -> 0f

                            // Oceanic under oceanic. Same trench, but what rises behind it is a
                            // line of volcanoes on oceanic crust, most of which never reaches the
                            // surface. The overriding plate is the lower id (see [overridingId]).
                            BoundaryClass.ISLAND_ARC ->
                                // The trench stays on both sides, as it was before this chunk: an
                                // oceanic pair has deep water either side of it, and the arc is a
                                // ridge rising out of that rather than instead of it. Keeping it
                                // is what holds the arc mostly under water — only where
                                // `rangeVariation` swells does a volcano clear the surface — and it
                                // is also what keeps this boundary's share of the world's uplift
                                // close to what the single profile gave it, so the sea-level
                                // percentile does not move out from under every other coastline.
                                -tectonics.trenchDepth * strength * narrow +
                                    if (boundary.overridingSide) {
                                        tectonics.islandArcHeight * strength *
                                            ridgeAt(
                                                distanceFromBoundary,
                                                cellWidths.islandArcOffsetCells,
                                                cellWidths.islandArcWidthCells * widthScale * edgeJitter
                                            ) * roughness * alongArc
                                    } else {
                                        0f
                                    }

                            // The ridge is the floor's: where a ridge runs against a continent's
                            // edge, the continent beside it stands as its margin and is not raised.
                            BoundaryClass.OCEAN_RIDGE ->
                                if (boundary.oceanicSide) tectonics.mountainHeight * 0.22f * strength * narrow else 0f

                            // Continental crust being pulled apart: the floor drops between two
                            // rebounding shoulders. Where such a pair has ocean on one side, that
                            // side is a spreading ridge as before — the rift is what the
                            // continental crust does.
                            BoundaryClass.CONTINENTAL_RIFT ->
                                if (boundary.oceanicSide) {
                                    tectonics.mountainHeight * 0.22f * strength * narrow
                                } else {
                                    val place = boundary.segment
                                    if (place == null) {
                                        // Unsegmented: the rift before segmentation, one trough
                                        // of constant depth between two shoulders of constant
                                        // height for the whole run of the boundary. Kept so
                                        // `RiftSegmentationTest` can measure the world that was
                                        // replaced rather than take its word for it. See
                                        // docs/DESIGN_LEDGER.md, E4.
                                        -tectonics.riftDepth * strength *
                                            plateauFalloff(
                                                distanceFromBoundary,
                                                cellWidths.riftWidthCells,
                                                tectonics.riftFloorShare
                                            ) +
                                            tectonics.riftShoulderHeight * strength *
                                            ridgeAt(
                                                distanceFromBoundary,
                                                cellWidths.riftShoulderOffsetCells,
                                                cellWidths.riftShoulderWidthCells
                                            ) * roughness * alongRange
                                    } else {
                                        // Which flank of the half-graben this cell is on. The
                                        // distance transform gives distance and not side, so the
                                        // side comes from the cell's own plate against the pair's
                                        // lower id — a total order, never a hash order. A cell on
                                        // some third plate near a triple junction falls to the
                                        // hinge side, which is the quieter of the two.
                                        val onLowIdPlate = plateId[cell] == interaction.lowId
                                        val segment = riftSegmentAt(
                                            place.layout,
                                            place.centerline.alongKmAt(
                                                (column + 0.5) * config.cellWidthKm,
                                                (row + 0.5) * config.cellHeightKm,
                                                place.alongKm,
                                                distanceFromBoundary * config.cellWidthKm + CENTERLINE_STEP_KM
                                            ),
                                            onLowIdPlate, distanceFromBoundary * config.cellWidthKm,
                                            noise.relay.fbm(
                                                relayLattice.x(column), relayLattice.y(row), RELAY_CREST_OCTAVES,
                                                relayLattice.period, relayLattice.period
                                            )
                                        )
                                        val footwall = onLowIdPlate == segment.footwallOnLow

                                        // A half-graben is a wedge: the floor hangs from the fault
                                        // under the footwall and rises across to the hinge. The
                                        // signed across-strike coordinate saturates at the edge of
                                        // the flat floor, so the tilt is spent inside the trough
                                        // rather than out on the shoulder slope.
                                        //
                                        // Floored at one cell, and the floor stays a count of
                                        // cells: it is the least floor the grid can draw, not a
                                        // length on the ground. At Earth's valley width the floor
                                        // is 27.5 x 0.55 = 15.1 km, so it binds where a cell is
                                        // wider than that: at 256 rows of square cells (23.4 km)
                                        // and fewer, where the floor is the rift's own cells and
                                        // their neighbors; from 512 rows up it is 1.3 cells or
                                        // more and the floor does nothing (docs/DESIGN_LEDGER.md,
                                        // Q2 and L1).
                                        val flatFloorHalfWidthCells =
                                            (cellWidths.riftWidthCells * tectonics.riftFloorShare)
                                                .coerceAtLeast(1f)
                                        val acrossStrike =
                                            (distanceFromBoundary / flatFloorHalfWidthCells)
                                                .coerceAtMost(1f) * (if (footwall) 1f else -1f)
                                        val hingeFloorShare =
                                            tectonics.riftHingeFloorShare.coerceIn(0f, 1f)
                                        val wedge = hingeFloorShare +
                                            (1f - hingeFloorShare) * (0.5f + 0.5f * acrossStrike)
                                        // Symmetric again through the accommodation zone, so two
                                        // segments of opposite polarity meet without a step.
                                        val tilt = 1f + (wedge - 1f) * segment.taper

                                        val floor = -tectonics.riftDepth * segment.depthFactor *
                                            segment.taper * tilt * strength *
                                            plateauFalloff(
                                                distanceFromBoundary,
                                                cellWidths.riftWidthCells,
                                                tectonics.riftFloorShare
                                            )

                                        // High footwall on one flank, low hinge on the other —
                                        // and both fade to their mean at the join, as the trough
                                        // does.
                                        val flankShare = if (footwall) {
                                            1f
                                        } else {
                                            tectonics.riftHingeShoulderShare.coerceAtLeast(0f)
                                        }
                                        val shoulder = tectonics.riftShoulderHeight *
                                            segment.shoulderFactor *
                                            (1f + (flankShare - 1f) * segment.taper) * strength *
                                            ridgeAt(
                                                distanceFromBoundary,
                                                cellWidths.riftShoulderOffsetCells,
                                                cellWidths.riftShoulderWidthCells * segment.widthFactor
                                            ) * roughness * alongRange

                                        // The accommodation zone itself: ground that rises between
                                        // two half-grabens, which is where the sill and the land
                                        // bridge between two gulfs come from.
                                        //
                                        // Its height is what the outlet notch has to saw through
                                        // where a rift stands on land, so it is deliberately
                                        // modest: a sill high enough to dam a half-graben for
                                        // twelve rounds of erosion leaves a lake the notch cannot
                                        // drain. See docs/DESIGN_LEDGER.md, E4, for what that measured.
                                        val sill = tectonics.riftSillHeight * strength *
                                            (1f - segment.taper) *
                                            beltFalloff(
                                                distanceFromBoundary,
                                                cellWidths.riftShoulderOffsetCells
                                            )

                                        floor + shoulder + sill
                                    }
                                }

                            BoundaryClass.TRANSFORM_FAULT ->
                                tectonics.mountainHeight * 0.12f * strength * narrow *
                                    (roughness - 0.75f)
                        }
                        uplift.data[cell] += upliftHere
                        recordUpliftRate(upliftRate, cell, upliftHere, interaction.pairClass, tectonics)
                    }
                }
            }
        }
    }

    /**
     * How fast the rock is still rising at one cell of a *present* belt, in millimetres a year.
     *
     * The rate is the crust pair's — a collision pushes harder than a margin, a margin harder than
     * an island arc, and a craton not at all — shaped by how hard this epoch worked on this
     * particular cell. That shaping is what keeps the uplift a belt rather than a rectangle: the
     * same [TectonicsConfig.crustAgeReference] of relief that marks crust as this epoch's own marks
     * it as fully active, and a cell the profile barely reached rises proportionally less.
     *
     * Only what the belt *raised* counts. A trench and a rift floor are the negative half of a
     * profile and are subsiding rather than rising, so they take nothing here; giving them a
     * negative rate would deepen every rift lake over the twelve rounds and is E7's ground rather
     * than this chunk's. See `TODO.md`.
     */
    private fun recordUpliftRate(
        upliftRate: FloatField,
        cell: Int,
        upliftHere: Float,
        pairClass: BoundaryClass,
        tectonics: TectonicsConfig
    ) {
        if (upliftHere <= 0f) return
        val rate = when (pairClass) {
            BoundaryClass.COLLISION_PLATEAU -> tectonics.collisionUpliftMmPerYear
            BoundaryClass.ANDEAN_MARGIN -> tectonics.andeanUpliftMmPerYear
            BoundaryClass.ISLAND_ARC -> tectonics.islandArcUpliftMmPerYear
            BoundaryClass.CONTINENTAL_RIFT -> tectonics.riftShoulderUpliftMmPerYear
            // A spreading ridge stands high because it is hot, not because anything is pushing it
            // up, and a transform fault slides rather than shortens.
            BoundaryClass.OCEAN_RIDGE, BoundaryClass.TRANSFORM_FAULT -> 0f
        }
        if (rate <= 0f) return
        val influence = (upliftHere / tectonics.crustAgeReference).coerceAtMost(1f)
        val here = rate * influence
        if (here > upliftRate.data[cell]) upliftRate.data[cell] = here
    }

    /**
     * Hotspots: a point fixed in the mantle that a plate drifts over, leaving its volcanoes behind
     * it as a line of seamounts that subside with age. Hawaii, the Emperor chain, Réunion.
     *
     * Nearly free, and it puts islands somewhere other than a plate boundary — which is otherwise
     * the only place this generator has anything to offer the open ocean.
     *
     * Fixed in the mantle and so drawn without regard to the plates: [TectonicsConfig.hotspotCount]
     * points spread uniformly over the sphere, each carried by whichever plate lies over it.
     * Restricted to plumes under ocean floor, so what comes out is island chains in deep water
     * rather than volcanic fields inland, and clipped to the carrying plate, so a trail stops at the boundary
     * instead of running on across a neighbor that never passed over the hotspot. A point is
     * read on the plate that owns its own cell, so the clip trims a chain where it leaves the plate
     * and never erases one outright, which is what a point drawn on the map and stamped on a plate
     * chosen apart from it did in the first version of this, on every seed tried.
     *
     * Determinism: the hotspots are walked in draw order and both draws are taken for every one
     * whether or not it lands on oceanic crust, so the sequence does not depend on the outcome of
     * any test — and never on a hash order. Each stamped seamount also gets a running index, walked
     * in the same fixed order, which seeds its own rim modulation (see [stampSeamount]) — again
     * never from a hash order, and never from the [Random] shared by the placement draws above, so
     * tuning one does not reseed the other.
     */
    private fun stampHotspotChains(
        config: WorldGenConfig,
        plates: List<Plate>,
        plateId: IntArray,
        continentalShare: FloatArray,
        uplift: FloatField
    ) {
        val tectonics = config.tectonics
        for (trail in hotspotTrails(config, plates, plateId, continentalShare)) {
            for (vent in trail.vents) {
                stampSeamount(
                    uplift = uplift,
                    cellHeightInCellWidths = config.cellHeightInCellWidths.toFloat(),
                    plateId = plateId,
                    plate = trail.plate,
                    ventX = vent.column,
                    ventY = vent.row,
                    radius = vent.radiusCellWidths,
                    amplitude = vent.amplitude,
                    seed = config.seed,
                    ventIndex = vent.index,
                    detail = tectonics.hotspotConeDetail
                )
            }
        }
    }

    /**
     * One hotspot's trail: the [plate] that carries it and its volcanoes, youngest first.
     *
     * [vents] holds every volcano the hotspot built over [TectonicsConfig.hotspotChainLengthKm] of
     * plate travel, including those carried off the plate's edge, which the stamp clips away.
     */
    internal class HotspotTrail(val plate: Int, val vents: List<HotspotVent>)

    /**
     * One volcano of a hotspot trail: where it stands now, as a column and a row of the map (not
     * wrapped east-west), its base radius in cell widths of ground, its height in normalized
     * elevation units, and its running [index] over the whole world, which seeds its rim and size.
     */
    internal class HotspotVent(
        val column: Float,
        val row: Float,
        val radiusCellWidths: Float,
        val amplitude: Float,
        val index: Int
    )

    /**
     * The volcanoes each hotspot on an oceanic plate has built, where they stand today: see
     * [stampHotspotChains] for the placement and its determinism.
     *
     * A volcano built when the plate had travelled `s` since stands where the plume stood then,
     * carried `s` along the plate's courses: the plume's position now, plus the plate's travel,
     * minus the plume's own displacement since. A plume fixed in the mantle under a plate that never
     * turns leaves a trail as straight as the plate's drift, and every trail on a plate parallel to
     * the next, ruled lines across the ocean
     * (docs/CONVENTIONS.md, rule 13). Earth's trails bend for two reasons, both drawn here. Plates
     * change course: the Hawaiian-Emperor bend of 60 degrees is the Pacific plate turning about 47
     * million years ago (Torsvik et al. 2017, see [LARGEST_PLATE_TURN_DEGREES]), and the Louisville
     * chain on the same plate bends at the same age. So each plate's past drift turns at the planet's
     * reorganizations, [REORGANIZATION_INTERVAL_KM] of travel apart, every trail on a plate bending
     * at the same age and by the plate's own angle. And plumes move: the Hawaiian plume drifted south
     * as the Emperor seamounts were built (Tarduno et al. 2003, see [PLUME_SPEED_SHARE_OF_PLATE]).
     * Each plume here drifts at [plumeSpeedShare] of its plate's speed on a course that wanders over
     * [PLUME_COURSE_KM] of plate travel, its own and not its neighbors', so no two trails are alike.
     *
     * The plate's present drift, its rotation in the tectonic history at the present, is the one
     * the belts are built from; the turns before it are the trail's alone.
     *
     * Each plume also has its own strength ([plumeFluxShare]) and its volcanoes their own spacing
     * ([ventSpacingShare]) and size ([volcanoSizeShare]): Earth's chains are neither one size nor
     * evenly spaced.
     *
     * [plumeSpeedShare] and [plateTurns] are parameters so a guard can draw the fixed-plume,
     * fixed-course trails it is set against; the generator always passes the defaults.
     */
    internal fun hotspotTrails(
        config: WorldGenConfig,
        plates: List<Plate>,
        plateId: IntArray,
        continentalShare: FloatArray,
        plumeSpeedShare: Float = PLUME_SPEED_SHARE_OF_PLATE,
        plateTurns: Boolean = true
    ): List<HotspotTrail> {
        val tectonics = config.tectonics
        val cellWidths = BeltCellWidths.of(config)
        val hotspots = tectonics.hotspotCount(config.scale)
        if (hotspots <= 0 || tectonics.hotspotHeight == 0f) return emptyList()
        if (cellWidths.hotspotRadiusCells <= 0f || cellWidths.hotspotSpacingCells <= 0f) return emptyList()

        val cellsAcross = config.width
        val cellsDown = config.height
        val random = Random(config.seed * 31337 + 7)
        val sizeNoise = PerlinNoise(config.seed * 104729 + 4441)
        val sizeLattice = GroundLattice(config, SEAMOUNT_SIZE_WAVELENGTH_KM)
        val plumeCourseCells = config.cellsFor(PLUME_COURSE_KM)
        val reorganizationCells = config.cellsFor(REORGANIZATION_INTERVAL_KM)
        // How far the plates have travelled since the planet's latest reorganization: anywhere in
        // one interval, drawn once for the world, since a reorganization is every plate's at once.
        val latestReorganizationCells =
            (unitDraw(config.seed xor REORGANIZATION_SALT, 0L) * reorganizationCells).toFloat()
        // A chain's lengths are in cell widths of ground, so a step north or south of one is this
        // many rows.
        val rowsPerCellWidth = (1.0 / config.cellHeightInCellWidths).toFloat()
        val trails = ArrayList<HotspotTrail>()
        var ventIndex = 0

        for (hotspot in 0 until hotspots) {
            // Uniform on the sphere: uniform in longitude, and in the sine of latitude, since a
            // band of latitude holds ground in proportion to the cosine.
            val longitudeShare = random.nextDouble()
            val sineOfLatitude = 2.0 * random.nextDouble() - 1.0
            val rowsFromNorth =
                (0.5 - kotlin.math.asin(sineOfLatitude) / PI) * cellsDown
            val originX = (longitudeShare * cellsAcross).toFloat()
            val originY = rowsFromNorth.toFloat()
            val originCell = originY.toInt().coerceIn(0, cellsDown - 1) * cellsAcross +
                originX.toInt().coerceIn(0, cellsAcross - 1)
            val plate = plates[plateId[originCell]]
            // A plate carries both crusts now: what decides is the crust over the plume.
            if (continentalShare[originCell] >= 0.5f) continue

            // A volcano's volume goes with its plume's flux, so its radius and height go with
            // the flux's cube root, as [volcanoSizeShare]'s go with its own volume's.
            val plumeSize = plumeFluxShare(config.seed, hotspot).pow(1.0 / 3.0).toFloat()
            val initialHeadingRadians = unitDraw(config.seed xor PLUME_HEADING_SALT, hotspot.toLong()) * 2.0 * PI
            // The plume's displacement since the youngest volcano was built, in cell widths of
            // ground east and south.
            var plumeShiftEast = 0f
            var plumeShiftSouth = 0f
            // How far the plate has carried the oldest volcano so far, east and south, on the
            // courses it held since.
            var carriedEast = 0f
            var carriedSouth = 0f
            val presentHeadingRadians = atan2(plate.driftY, plate.driftX).toDouble()
            val vents = ArrayList<HotspotVent>()
            var travelledCells = 0f
            while (travelledCells <= cellWidths.hotspotChainLengthCells) {
                // The plate slides over the plume, so the volcano it built a while ago has since
                // been carried along the plate's courses, from wherever the plume then stood.
                // Older means further along, and lower: the crust cools and the seamount subsides
                // with it.
                val ventX = originX + carriedEast - plumeShiftEast
                val ventY = originY + (carriedSouth - plumeShiftSouth) * rowsPerCellWidth
                val ageAlongChain = travelledCells / cellWidths.hotspotChainLengthCells
                val sizeJitter = 0.6f + 0.8f * (0.5f + 0.5f * sizeNoise.fbm(
                    sizeLattice.x(ventX), sizeLattice.y(ventY), 2, sizeLattice.period, sizeLattice.period
                )).coerceIn(0f, 1f)
                val volcanoSize = volcanoSizeShare(config.seed, ventIndex) * plumeSize
                vents.add(
                    HotspotVent(
                        column = ventX,
                        row = ventY,
                        radiusCellWidths = cellWidths.hotspotRadiusCells * volcanoSize,
                        // The crust cools and the seamount subsides with it, as the square of age.
                        amplitude = tectonics.hotspotHeight *
                            (1f - ageAlongChain) * (1f - ageAlongChain) * sizeJitter * volcanoSize,
                        index = ventIndex
                    )
                )
                val stepCells = cellWidths.hotspotSpacingCells * ventSpacingShare(config.seed, ventIndex)
                // The plume's course over this step, in the middle of it.
                val heading = initialHeadingRadians + 2.0 * PI * plumeCourseTurns(
                    config.seed, hotspot, (travelledCells + stepCells / 2f) / plumeCourseCells
                )
                plumeShiftEast += plumeSpeedShare * stepCells * cos(heading).toFloat()
                plumeShiftSouth += plumeSpeedShare * stepCells * sin(heading).toFloat()
                // The plate's course over the same step: its present drift, turned by every
                // reorganization the step lies before.
                var plateHeading = presentHeadingRadians
                if (plateTurns) {
                    var reorganizationCellsAgo = latestReorganizationCells
                    var reorganization = 0
                    while (reorganizationCellsAgo < travelledCells + stepCells / 2f) {
                        plateHeading += plateTurnRadians(config.seed, plate.id, reorganization)
                        reorganizationCellsAgo += reorganizationCells
                        reorganization++
                    }
                }
                carriedEast += stepCells * cos(plateHeading).toFloat()
                carriedSouth += stepCells * sin(plateHeading).toFloat()
                ventIndex++
                travelledCells += stepCells
            }
            trails.add(HotspotTrail(plate.id, vents))
        }
        return trails
    }

    /**
     * A plume's buoyancy flux against the strongest on the planet, between [WEAKEST_PLUME_FLUX_SHARE]
     * and 1, drawn from the world's seed and the hotspot's place in the draw.
     *
     * Sleep (*Hotspots and mantle plumes: some phenomenology*, J. Geophys. Res. 95, 1990) estimates
     * the fluxes of 37 hotspots: they span a factor of twenty, Hawaii's 8.7 Mg/s the largest, and
     * total 50 Mg/s, a mean of 1.35, 0.155 of Hawaii's. A density falling as the inverse square of
     * the flux over that factor of twenty has both: its mean is ln 20 / 19, 0.158. Drawn by inverting
     * its distribution, `1 / (20 - 19u)` for a uniform `u`. Most plumes are weak, and most chains
     * are small, mostly drowned seamounts beside the occasional Hawaii.
     */
    private fun plumeFluxShare(seed: Long, hotspot: Int): Double {
        val unit = unitDraw(seed xor PLUME_FLUX_SALT, hotspot.toLong())
        val strongestOverWeakest = 1.0 / WEAKEST_PLUME_FLUX_SHARE
        return 1.0 / (strongestOverWeakest - (strongestOverWeakest - 1.0) * unit)
    }

    /**
     * The [hotspot]th plume's course against the heading drawn for it, in whole turns, once its
     * plate has travelled [coursesTravelled] of [PLUME_COURSE_KM].
     *
     * A smooth wander along the plate's travel and nothing else: a knot at every whole course, each
     * drawn uniformly within half a turn either way, eased between by smoothstep so the course
     * bends rather than kinks. Half a turn either way, because a plume's course is free in every
     * direction, so over one course it may come to head anywhere. In courses of travel, a length on
     * the ground, and drawn per plume from the seed, so no two plumes wander alike and no map
     * lattice is read.
     */
    private fun plumeCourseTurns(seed: Long, hotspot: Int, coursesTravelled: Float): Double {
        val knot = floor(coursesTravelled).toInt()
        val along = (coursesTravelled - knot).toDouble()
        val eased = along * along * (3.0 - 2.0 * along)
        fun knotTurns(index: Int): Double =
            unitDraw(seed xor PLUME_COURSE_SALT, hotspot.toLong() * KNOTS_PER_PLUME + index) - 0.5
        return knotTurns(knot) + (knotTurns(knot + 1) - knotTurns(knot)) * eased
    }

    /**
     * How far [plate] turned at the [reorganization]th of the planet's reorganizations counted back
     * from the present, in radians, positive clockwise on the map: uniform up to
     * [LARGEST_PLATE_TURN_DEGREES] either way.
     *
     * Earth gives one plate's turn well measured, the Pacific's, and nothing like a distribution of
     * them, so the draw takes it as the largest and lets every smaller turn be as likely.
     */
    private fun plateTurnRadians(seed: Long, plate: Int, reorganization: Int): Double {
        val unit = unitDraw(seed xor PLATE_TURN_SALT, plate.toLong() * TURNS_PER_PLATE + reorganization)
        return (2.0 * unit - 1.0) * LARGEST_PLATE_TURN_DEGREES * PI / 180.0
    }

    /**
     * How far the [ventIndex]th volcano stands from the next older one, as a share of
     * [TectonicsConfig.hotspotSpacingKm]: uniform over the 40 to 60 km the USGS gives between the
     * Hawaiian volcanoes along each of their two lines, 0.8 to 1.2 of the 50 km middle. The spacing is
     * the plate's bend under each new load (ten Brink 1991), which varies with the load and the
     * plate; one spacing for every step drew a chain as evenly dashed as a ruled line.
     */
    private fun ventSpacingShare(seed: Long, ventIndex: Int): Float =
        (1.0 - VENT_SPACING_SPREAD + 2.0 * VENT_SPACING_SPREAD *
            unitDraw(seed xor VENT_SPACING_SALT, ventIndex.toLong())).toFloat()

    /** A uniform draw in 0..1 from [seed] and [index], by [seedHash]'s mix: no shared [Random]. */
    private fun unitDraw(seed: Long, index: Long): Double {
        val bits = seedHash(seed, index)
        return ((bits ushr 40) and 0xFFFFFF).toDouble() / 0xFFFFFF.toDouble()
    }

    /**
     * How fast a plume drifts in the mantle against how fast its plate moves over it: 0.23.
     *
     * Tarduno et al. (*The Emperor Seamounts: southward motion of the Hawaiian hotspot plume in
     * Earth's mantle*, Science 301, 2003) date and place the Emperor seamounts' lavas by their
     * magnetization and find the Hawaiian plume moving south at more than 40 mm a year from 81 to 47
     * million years ago, and about still since. Over the chain's 81 million years that is
     * 40 × 34 / 81, 17 mm a year, against the Pacific plate's 72 over the hotspot (USGS; see
     * [TectonicsConfig.hotspotChainLengthKm]): 0.23.
     */
    internal const val PLUME_SPEED_SHARE_OF_PLATE = 0.23f

    /**
     * How far a plate travels while its plume holds one course, in kilometers: 2,450, the Hawaiian
     * plume's 34 million years of southward drift, 81 to 47 million years ago (Tarduno et al. 2003,
     * see [PLUME_SPEED_SHARE_OF_PLATE]), at the Pacific plate's 72 mm a year.
     */
    internal const val PLUME_COURSE_KM = 2_450.0

    /**
     * The largest turn a plate makes at a reorganization, in degrees: 60, the Hawaiian-Emperor bend.
     * Torsvik, Doubrovine, Steinberger, Gaina, Spakman and Domeier (*Pacific plate motion change
     * caused the Hawaiian-Emperor Bend*, Nature Communications 8, 2017) show the plume's southward
     * drift moved where the Emperor seamounts stand but cannot make the bend: the Pacific plate
     * turned, about 47 million years ago, from nearly north, along the Emperor chain, to north-west,
     * along the Hawaiian.
     */
    private const val LARGEST_PLATE_TURN_DEGREES = 60.0

    /**
     * How far a plate travels between the planet's reorganizations, in kilometers: 3,960, 55 million
     * years at the Pacific plate's 72 mm a year. Matthews, Seton and Müller (*A global-scale plate
     * reorganization event at 105-100 Ma*, EPSL 355-356, 2012) date the reorganization that bent
     * Earth's largest set of fracture zones to 105 to 100 million years ago, and Torsvik et al. 2017
     * the Pacific's turn at the Hawaiian-Emperor bend to about 47 (see [LARGEST_PLATE_TURN_DEGREES]):
     * 55 million years apart.
     */
    private const val REORGANIZATION_INTERVAL_KM = 3_960.0

    /** More reorganizations than any trail can reach back over, so each plate's turns draw apart. */
    private const val TURNS_PER_PLATE = 1_000L

    /** More course knots than any trail can reach, so each plume's knots draw apart. */
    private const val KNOTS_PER_PLUME = 1_000L

    /** The weakest plume's flux against the strongest's: Sleep's factor of twenty. See [plumeFluxShare]. */
    private const val WEAKEST_PLUME_FLUX_SHARE = 1.0 / 20.0

    /** Half the spread of a volcano's spacing about its mean, as a share of it. See [ventSpacingShare]. */
    private const val VENT_SPACING_SPREAD = 0.2

    /** Keeps each per-hotspot and per-vent draw apart from the others that hash the same seed. */
    private const val PLUME_HEADING_SALT = 0x4EAD1A6L
    private const val PLUME_FLUX_SALT = 0xF10C5L
    private const val PLUME_COURSE_SALT = 0xC0A25EL
    private const val PLATE_TURN_SALT = 0x7E2A1L
    private const val REORGANIZATION_SALT = 0x2E0A6L
    private const val VENT_SPACING_SALT = 0x5BAC1A6L

    /**
     * One seamount: a smooth cone of [radius] cell widths on the ground, wrapping in x and clipped
     * in y.
     *
     * Round on the ground, so [radius] columns across and twice as many rows down on this map's
     * cells; stamped round in cells, every cone was an ellipse twice as long east-west as
     * north-south. The falloff is true Euclidean distance on the ground, so the eight-sided look a
     * cone can have is not the metric's doing. It is rasterization: a stamp four or five cells
     * across has too little grid to draw a circle, and the bearings a grid hits exactly come out
     * measurably longer than the bearings between them however continuous the formula behind them
     * is.
     *
     * [detail] answers that by supersampling each cell, which is cheap when the whole stamp is a
     * few cells wide, and while there breaks the remaining perfect symmetry with a few
     * low-amplitude harmonics of the rim radius, seeded from the world seed and this vent's own
     * index so that no two cones match without a shared [Random] or a hash order. Off reproduces
     * the single-sample, unmodulated stamp the guard measures its "before" against. See
     * docs/DESIGN_LEDGER.md, E3, for the eight-fold amplitudes at each grid.
     */
    internal fun stampSeamount(
        uplift: FloatField,
        cellHeightInCellWidths: Float,
        plateId: IntArray,
        plate: Int,
        ventX: Float,
        ventY: Float,
        radius: Float,
        amplitude: Float,
        seed: Long,
        ventIndex: Int,
        detail: Boolean
    ) {
        if (amplitude <= 0f) return
        val cellsAcross = uplift.width
        val cellsDown = uplift.height
        // Out to the furthest the rim's harmonics can push it, so no cone is cut off along a row or
        // a column by its own box.
        val widestRimCellWidths = radius * (1f + RIM_HARMONICS * (RIM_HARMONIC_MIN + RIM_HARMONIC_SPAN))
        val boundingColumns = widestRimCellWidths.toInt() + 1
        val boundingRows = (widestRimCellWidths / cellHeightInCellWidths).toInt() + 1

        val harmonics = if (detail) rimHarmonics(seedHash(seed, ventIndex.toLong())) else null
        // Sub-cell samples per axis, so a stamp only a few cells across is area-averaged rather
        // than point-sampled — the fix for the eight-fold artefact described above. Off (the old
        // behaviour) samples once, at the cell's own centre.
        val subSamplesPerAxis = if (detail) 5 else 1
        val subStep = 1f / subSamplesPerAxis
        val subOffset = (subStep - 1f) / 2f
        val subSampleCount = (subSamplesPerAxis * subSamplesPerAxis).toFloat()

        for (row in (ventY.toInt() - boundingRows)..(ventY.toInt() + boundingRows)) {
            if (row < 0 || row >= cellsDown) continue
            for (column in (ventX.toInt() - boundingColumns)..(ventX.toInt() + boundingColumns)) {
                var wrappedColumn = column % cellsAcross
                if (wrappedColumn < 0) wrappedColumn += cellsAcross
                val cell = row * cellsAcross + wrappedColumn
                if (plateId[cell] != plate) continue

                var sum = 0f
                for (subRow in 0 until subSamplesPerAxis) {
                    for (subColumn in 0 until subSamplesPerAxis) {
                        val sampleX = column + subOffset + subColumn * subStep
                        val sampleY = row + subOffset + subRow * subStep
                        var offsetX = sampleX - ventX
                        if (offsetX > cellsAcross / 2f) offsetX -= cellsAcross
                        if (offsetX < -cellsAcross / 2f) offsetX += cellsAcross
                        val offsetY = (sampleY - ventY) * cellHeightInCellWidths
                        val distance = sqrt(offsetX * offsetX + offsetY * offsetY)
                        val rim = if (harmonics == null) {
                            radius
                        } else {
                            radius * (1f + rimModulation(harmonics, offsetX, offsetY))
                        }
                        if (distance >= rim) continue
                        val inward = 1f - distance / rim
                        sum += inward * inward * (3f - 2f * inward)
                    }
                }
                if (sum <= 0f) continue
                uplift.data[cell] += amplitude * (sum / subSampleCount)
            }
        }
    }

    /**
     * A deterministic 64-bit mix of a world seed and a vent's index — splitmix64's finalizer,
     * applied to their combination. No shared [Random], no [HashMap] order: the same (seed,
     * ventIndex) pair always mixes to the same value, on every platform.
     */
    private fun seedHash(seed: Long, ventIndex: Long): Long {
        val gamma = 0x9E3779B97F4A7C15UL.toLong()
        var z = (seed xor (ventIndex * gamma)) + gamma
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return z xor (z ushr 31)
    }

    /**
     * How large the [ventIndex]th volcano of a hotspot chain is against the largest a chain builds,
     * as a share of its radius and height, between [SMALLEST_VOLCANO_VOLUME_SHARE]'s cube root and 1.
     *
     * A chain is a line of separate volcanoes of very different sizes, not a ridge of one cone
     * repeated: the Island of Hawaii is 213,000 km³ in seven volcanoes (Robinson and Eakins,
     * *Calculated volumes of individual shield volcanoes at the young end of the Hawaiian Ridge*,
     * J. Volcanol. Geotherm. Res. 151, 2006), of which Mauna Loa alone is some 75,000 (USGS), so the
     * other six average about a third of it, and a volcano's volume is drawn uniformly in its
     * logarithm over the decade below Mauna Loa's. A cone of one shape has its radius and height
     * as the cube root of its volume. Drawn per volcano, from the world's seed and the vent's own
     * index, so the chain is beaded the way its volcanoes are; with every cone the size of the
     * largest, [TectonicsConfig.hotspotSpacingKm] apart, the chain was a ridge of one width from
     * end to end, a ruled line across the ocean (docs/CONVENTIONS.md, rule 13).
     */
    private fun volcanoSizeShare(seed: Long, ventIndex: Int): Float {
        val unit = unitDraw(seed xor VOLCANO_SIZE_SALT, ventIndex.toLong())
        val volumeShare = exp(unit * ln(SMALLEST_VOLCANO_VOLUME_SHARE))
        return volumeShare.pow(1.0 / 3.0).toFloat()
    }

    /**
     * A hotspot chain's smallest volcano against its largest, by volume: a tenth, the decade below
     * Mauna Loa over which the chain's volcanoes are drawn. See [volcanoSizeShare].
     */
    private const val SMALLEST_VOLCANO_VOLUME_SHARE = 0.1

    /** Keeps a volcano's size draw apart from its rim's, which hashes the same seed and index. */
    private const val VOLCANO_SIZE_SALT = 0x5EAB0A7E5L

    /**
     * A handful of low-order harmonics of the rim radius, amplitude and phase both drawn from
     * [hash]. Frequencies 2, 3 and 5 are chosen to stay well clear of 8: the guard reads the rim
     * at sixteen bearings, so a component at exactly the eighth harmonic is indistinguishable from
     * the faceting it exists to catch, and this modulation must never be mistaken for it.
     */
    private fun rimHarmonics(hash: Long): FloatArray {
        val frequencies = intArrayOf(2, 3, 5)
        val amplitudeAndPhase = FloatArray(frequencies.size * 2)
        var bits = hash
        for (index in frequencies.indices) {
            bits = bits * 6364136223846793005L + 1442695040888963407L
            val amplitudeBits = ((bits ushr 40) and 0xFFFF).toFloat() / 0xFFFF.toFloat()
            bits = bits * 6364136223846793005L + 1442695040888963407L
            val phaseBits = ((bits ushr 40) and 0xFFFF).toFloat() / 0xFFFF.toFloat()
            // Individually visible, together never enough to push a cone's overall roundness
            // past what the guard treats as faceted.
            amplitudeAndPhase[index * 2] =
                RIM_HARMONIC_MIN + RIM_HARMONIC_SPAN * amplitudeBits
            amplitudeAndPhase[index * 2 + 1] = phaseBits * (2f * PI.toFloat())
        }
        return amplitudeAndPhase
    }

    /** The fractional change to a cone's rim radius at the bearing of ([dx], [dy]) from its vent. */
    private fun rimModulation(harmonics: FloatArray, offsetX: Float, offsetY: Float): Float {
        val frequencies = intArrayOf(2, 3, 5)
        val bearing = atan2(offsetY, offsetX)
        var modulation = 0f
        for (index in frequencies.indices) {
            val amplitude = harmonics[index * 2]
            val phase = harmonics[index * 2 + 1]
            modulation += amplitude * cos(frequencies[index] * bearing + phase)
        }
        return modulation
    }

    /**
     * What [segmentRifts] made of the present epoch's rifts, one entry per rift boundary cell, for
     * the guards that ask whether a rift is the same rift at every grid, and for `:cartography`'s
     * geometry guard, which reads the sills between half-grabens; public for that module's tests
     * and for nothing else. Nothing in the stage reads it.
     */
    class RiftSegmentReport {
        val cell = ArrayList<Int>()
        /** The pair's two plate ids, lower first. */
        val lowId = ArrayList<Int>()
        val highId = ArrayList<Int>()
        /** Which of the pair's separate rifts the cell lies on. */
        val chain = ArrayList<Int>()
        /** Which half-graben of that rift, counted along it. */
        val ordinal = ArrayList<Int>()
        val depthFactor = ArrayList<Float>()
        val footwallOnLow = ArrayList<Boolean>()
        /** How far along the rift the cell lies, in kilometers. */
        val alongKm = ArrayList<Float>()
        /** How far along the rift the nearer end of the cell's own half-graben lies, in kilometers. */
        val toJoinKm = ArrayList<Float>()

        internal fun add(
            cell: Int, lowId: Int, highId: Int, chain: Int, ordinal: Int, depthFactor: Float,
            footwallOnLow: Boolean, alongKm: Float, toJoinKm: Float
        ) {
            this.cell.add(cell); this.lowId.add(lowId); this.highId.add(highId); this.chain.add(chain)
            this.ordinal.add(ordinal); this.depthFactor.add(depthFactor)
            this.footwallOnLow.add(footwallOnLow); this.alongKm.add(alongKm); this.toJoinKm.add(toJoinKm)
        }
    }

    /** [RiftSegmentReport] for [config]'s present epoch, without stamping anything. */
    fun presentRiftSegments(config: WorldGenConfig): RiftSegmentReport = presentRiftSegmentsWithout(config, emptySet())

    /**
     * The same, with the boundary cells in [withoutCells] taken away first: how a guard cuts a
     * rift into separate stretches, or takes one away, where the plates would take a whole world
     * to arrange it.
     */
    internal fun presentRiftSegmentsWithout(config: WorldGenConfig, withoutCells: Set<Int>): RiftSegmentReport {
        val history = TectonicHistory.run(config)
        val crust = CrustMap.read(config, history)
        val plates = presentPlates(config, history)
        val share = FloatField(config.width, config.height, crust.continentalShare)
        val boundaries = classifyPresentBoundaries(config, crust.plateId, plates, history, share).toMutableMap()
        withoutCells.forEach { boundaries.remove(it) }
        val report = RiftSegmentReport()
        segmentRifts(config, boundaries, plates, report)
        return report
    }

    /**
     * The history's present plates as the map reads them: each plate's middle on the map, its drift
     * there, whether it is mostly continental, and its rotation as an Euler pole and a rate.
     *
     * The middle is the mean of the plate's points on the history's sphere, so it is the same place
     * at every map grid, which is what [segmentRifts] anchors a rift's half-grabens to.
     */
    internal fun presentPlates(config: WorldGenConfig, history: CrustHistory): List<Plate> {
        val sphere = history.sphere
        val count = history.plateRotations.size
        val sumX = DoubleArray(count)
        val sumY = DoubleArray(count)
        val sumZ = DoubleArray(count)
        val cells = IntArray(count)
        val continent = IntArray(count)
        for (point in 0 until sphere.cellCount) {
            val plate = history.plateOf[point]
            sumX[plate] += sphere.x[point]; sumY[plate] += sphere.y[point]; sumZ[plate] += sphere.z[point]
            cells[plate]++
            if (history.continental[point]) continent[plate]++
        }
        return List(count) { id ->
            var cx = sumX[id]; var cy = sumY[id]; var cz = sumZ[id]
            val length = sqrt(cx * cx + cy * cy + cz * cz)
            if (length > 0.0) { cx /= length; cy /= length; cz /= length } else { cx = 1.0; cy = 0.0; cz = 0.0 }
            val latitude = kotlin.math.asin(cz.coerceIn(-1.0, 1.0))
            var longitude = atan2(cy, cx)
            if (longitude < 0.0) longitude += 2.0 * PI
            val omega = history.plateRotations[id]
            // The plate's velocity at its middle, east and north, from `omega x r`.
            val vx = omega[1] * cz - omega[2] * cy
            val vy = omega[2] * cx - omega[0] * cz
            val vz = omega[0] * cy - omega[1] * cx
            val east = -sin(longitude) * vx + cos(longitude) * vy
            val north = -sin(latitude) * cos(longitude) * vx - sin(latitude) * sin(longitude) * vy + cos(latitude) * vz
            val speed = sqrt(east * east + north * north)
            val rate = sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2])
            val poleLatitude = if (rate > 0.0) kotlin.math.asin((omega[2] / rate).coerceIn(-1.0, 1.0)) else PI / 2
            val poleLongitude = if (rate > 0.0) atan2(omega[1], omega[0]) else 0.0
            Plate(
                id = id,
                seedX = (longitude / (2.0 * PI) * config.width).toInt().coerceIn(0, config.width - 1),
                seedY = ((0.5 - latitude / PI) * config.height).toInt().coerceIn(0, config.height - 1),
                driftX = if (speed > 0.0) (east / speed).toFloat() else 0f,
                driftY = if (speed > 0.0) (-north / speed).toFloat() else 0f,
                type = if (continent[id] > CRATONIC_PLATE_SHARE * cells[id]) PlateType.CONTINENTAL else PlateType.OCEANIC,
                eulerPoleLatitudeDegrees = (poleLatitude * DEGREES_PER_RADIAN).toFloat(),
                eulerPoleLongitudeDegrees = (poleLongitude * DEGREES_PER_RADIAN).toFloat(),
                rotationDegreesPerMyr = (rate * DEGREES_PER_RADIAN).toFloat()
            )
        }
    }

    /**
     * The present boundaries on the map, each map cell beside another plate given the class the
     * history read there from the two plates' relative motion: Bird 2003's 20 degrees for a
     * transform, the crusts either side for the rest (TectonicHistory.classifyBoundaries).
     *
     * A boundary cell takes the class of the nearest of the history's boundary points between the
     * same two plates within [BOUNDARY_SEARCH_SPACINGS] of the history's spacing; the map's boundary
     * and the history's are the same line, read on two grids. Cells of one pair and one class share
     * one [PairInteraction]: its strength is the closing rate over their stretch against the speed
     * of a plate a slab pulls (`TectonicsConfig.slabPulledPlateSpeedMmPerYear`), its overriding
     * plate the one the history kept on top there.
     */
    private fun classifyPresentBoundaries(
        config: WorldGenConfig,
        plateId: IntArray,
        plates: List<Plate>,
        history: CrustHistory,
        continentalShare: FloatField
    ): Map<Int, Boundary> {
        val width = config.width
        val height = config.height
        val sphere = history.sphere
        val reach = BOUNDARY_SEARCH_SPACINGS * sphere.spacingRadians
        val otherOf = IntArray(width * height) { -1 }
        val classOf = IntArray(width * height) { -1 }
        val overridingOf = IntArray(width * height) { -1 }
        val closingOf = FloatArray(width * height)
        parallelChunks(0, height) { fromRow, toRow ->
            for (row in fromRow until toRow) {
                val latitude = PI / 2 - (row + 0.5) / height * PI
                for (column in 0 until width) {
                    val cell = row * width + column
                    val plate = plateId[cell]
                    var other = -1
                    for (step in 0 until 4) {
                        val neighborRow = row + NEIGHBOR_ROW_STEPS[step]
                        if (neighborRow < 0 || neighborRow >= height) continue
                        val neighborColumn = (column + NEIGHBOR_COLUMN_STEPS[step]).mod(width)
                        val candidate = plateId[neighborRow * width + neighborColumn]
                        if (candidate != plate) {
                            other = candidate
                            break
                        }
                    }
                    if (other < 0) continue
                    otherOf[cell] = other
                    val longitude = (column + 0.5) / width * 2.0 * PI
                    val px = cos(latitude) * cos(longitude)
                    val py = cos(latitude) * sin(longitude)
                    val pz = sin(latitude)
                    var bestCosine = -2.0
                    sphere.forEachWithin(px, py, pz, reach) { point, cosine ->
                        val pointClass = history.boundaryClass[point]
                        if (pointClass < 0 || cosine <= bestCosine) return@forEachWithin
                        val a = history.plateOf[point]
                        val b = history.boundaryOtherPlate[point]
                        if ((a == plate && b == other) || (a == other && b == plate)) {
                            bestCosine = cosine
                            classOf[cell] = pointClass
                            overridingOf[cell] = history.boundaryOverridingPlate[point]
                            closingOf[cell] = history.boundaryClosingMmPerYear[point]
                        }
                    }
                    if (classOf[cell] < 0) classOf[cell] = BoundaryClass.TRANSFORM_FAULT.ordinal
                }
            }
        }
        // One interaction per pair and class, in ascending order of the pair's key.
        class Stretch {
            var closingSum = 0.0
            var cells = 0
            val overridingVotes = HashMap<Int, Int>()
        }
        val stretches = HashMap<Long, Stretch>()
        for (cell in 0 until width * height) {
            val other = otherOf[cell]
            if (other < 0) continue
            val low = minOf(plateId[cell], other)
            val high = maxOf(plateId[cell], other)
            val key = (low.toLong() * plates.size + high) * BOUNDARY_CLASSES + classOf[cell]
            val stretch = stretches.getOrPut(key) { Stretch() }
            stretch.closingSum += abs(closingOf[cell]).toDouble()
            stretch.cells++
            if (overridingOf[cell] >= 0) stretch.overridingVotes[overridingOf[cell]] = (stretch.overridingVotes[overridingOf[cell]] ?: 0) + 1
        }
        val referenceSpeed = config.tectonics.slabPulledPlateSpeedMmPerYear
        val interactions = HashMap<Long, PairInteraction>()
        for (key in stretches.keys.sorted()) {
            val stretch = stretches.getValue(key)
            val pairClass = BoundaryClass.entries[(key % BOUNDARY_CLASSES).toInt()]
            val pair = key / BOUNDARY_CLASSES
            val low = (pair / plates.size).toInt()
            val high = (pair % plates.size).toInt()
            val overriding = stretch.overridingVotes.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key ?: low
            val type = when (pairClass) {
                BoundaryClass.ANDEAN_MARGIN, BoundaryClass.COLLISION_PLATEAU, BoundaryClass.ISLAND_ARC -> BoundaryType.CONVERGENT
                BoundaryClass.OCEAN_RIDGE, BoundaryClass.CONTINENTAL_RIFT -> BoundaryType.DIVERGENT
                BoundaryClass.TRANSFORM_FAULT -> BoundaryType.TRANSFORM
            }
            interactions[key] = PairInteraction(
                type = type,
                strength = (stretch.closingSum / stretch.cells / referenceSpeed).toFloat().coerceIn(MIN_BOUNDARY_STRENGTH, 1f),
                continentalCollision = pairClass == BoundaryClass.COLLISION_PLATEAU,
                pairClass = pairClass,
                overridingId = overriding,
                lowId = low,
                highId = high
            )
        }
        val boundaries = HashMap<Int, Boundary>()
        for (cell in 0 until width * height) {
            val other = otherOf[cell]
            if (other < 0) continue
            val low = minOf(plateId[cell], other)
            val high = maxOf(plateId[cell], other)
            val interaction = interactions.getValue((low.toLong() * plates.size + high) * BOUNDARY_CLASSES + classOf[cell])
            boundaries[cell] = Boundary(
                interaction,
                oceanicSide = continentalShare.data[cell] < 0.5f,
                overridingSide = plateId[cell] == interaction.overridingId
            )
        }
        return boundaries
    }

    /**
     * The history's mean half-spreading rate over its ridges at the present, km per Myr: reported
     * where the stage used to solve a rate for its floor's ages, which the history now makes.
     */
    private fun meanHalfSpreadingRateKmPerMyr(history: CrustHistory): Double {
        var sum = 0.0
        var count = 0
        for (point in 0 until history.sphere.cellCount) {
            if (history.boundaryClass[point] != BoundaryClass.OCEAN_RIDGE.ordinal) continue
            sum += -history.boundaryClosingMmPerYear[point] / 2.0
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** A map cell's four neighbors, east, west, south and north, in the order they are asked. */
    private val NEIGHBOR_COLUMN_STEPS = intArrayOf(1, -1, 0, 0)
    private val NEIGHBOR_ROW_STEPS = intArrayOf(0, 0, 1, -1)

    /**
     * How far from a map boundary cell the history's boundary between the same plates is sought, in
     * the history's spacings: the map's plate boundary is read through the map pass's kernel and
     * warp, which can move it a spacing from the history's points, and three holds both.
     */
    private const val BOUNDARY_SEARCH_SPACINGS = 3.0

    /** How many [BoundaryClass]es there are, the stride of a stretch's key. */
    private val BOUNDARY_CLASSES = BoundaryClass.entries.size.toLong()

    /** A plate more than a quarter continental is a continent's, as Zahirovic et al. sort plates. */
    private const val CRATONIC_PLATE_SHARE = 0.25

    private const val DEGREES_PER_RADIAN = 180.0 / PI

    /**
     * Breaks every continental rift into half-grabens along its own length.
     *
     * A rift is not a trough of constant depth. It is a chain of half-grabens, each hanging from a
     * border fault on one flank and hinged on the other, with the polarity flipping from one to the
     * next and an accommodation zone between them where the floor rises back toward the hinge. The
     * sea then enters only the segments that have subsided below it, which is why the Red Sea is a
     * string of deeps and why no rift on Earth is one trough of constant depth for a thousand
     * kilometers. Whether the chain holds closed basins or an axial river is an outcome, of the
     * fill, the saddles' heights and the climate, and both happen on Earth: Tanganyika, Malawi,
     * Turkana and the Dead Sea stay closed, while the Rhine runs through its graben, the Rio Grande
     * down its rift and the Jordan into the Dead Sea. Nothing here shapes a rift to prevent either. How long a half-graben is and how long the
     * zone between two of them is are Earth's figures, in kilometers, in `TectonicsConfig`.
     *
     * **A rift is the same rift at every grid.** Everything that decides a half-graben is a
     * property of the ground, never of the grid that draws it:
     *
     *  - Its name is the pair's two plate ids and its place on the ground; never which of the
     *    pair's separate stretches it lies on or how many there are, which is topology: a third
     *    plate pinching the boundary, or a gap just over the joining threshold, moves with the grid.
     *  - Its place is a distance in kilometers along the rift's own course from an anchor that does
     *    not move with the grid: where the rift crosses the bisector of its two plates' middles, read
     *    off the history's sphere and so the same at every grid, or the end of the rift nearer it
     *    when the rift does not cross it. The course is measured along a
     *    centerline drawn through stretches [CENTERLINE_STEP_KM] long, so it follows the same bends
     *    at every grid; see [arcAlongRun].
     *  - The joins fall at a sequence of lengths drawn one per half-graben from a stream keyed by
     *    the pair and the half-graben's own place in the sequence, counted both ways from the
     *    seeds' midpoint on the bisector coordinate every stretch of the pair is measured in. Its
     *    depth, its shoulders, which flank its border fault stands on and the relay ramp at its
     *    start are drawn from the same key, so none of them depends on how many half-grabens a grid
     *    happens to cut the rift into, nor on which other stretches the pair has.
     *  - Each join is a relay ramp, oblique and curved across the trough; see [riftSegmentAt].
     *  - A rift the raster breaks into separate runs of cells, where a third plate pinches it for a
     *    cell or two, is joined back into one course across any gap narrower than the trough's own
     *    half-width, so a split run draws the same joins on the ground as a whole one.
     *
     * The key used to be the lowest cell index of each run, which is a different number at every
     * grid; see docs/DESIGN_LEDGER.md, L1, for what that did to the lakes.
     *
     * Determinism: the pairs, the runs and the ties in the chaining are all taken in ascending
     * order, never in the hash order of the boundary map, and every draw is a splitmix hash.
     */
    private fun segmentRifts(
        config: WorldGenConfig,
        boundaries: Map<Int, Boundary>,
        plates: List<Plate>,
        report: RiftSegmentReport? = null
    ) {
        val tectonics = config.tectonics
        if (!tectonics.riftSegmentation) return
        val cellWidthKm = config.cellWidthKm

        // Every rift cell under its pair's name, cells in ascending order.
        val cellsOfPair = HashMap<Int, ArrayList<Int>>()
        boundaries.keys.sorted().forEach { cell ->
            val interaction = boundaries.getValue(cell).interaction
            if (interaction.pairClass != BoundaryClass.CONTINENTAL_RIFT) return@forEach
            cellsOfPair.getOrPut(interaction.lowId * plates.size + interaction.highId) { ArrayList() }
                .add(cell)
        }
        if (cellsOfPair.isEmpty()) return

        // Earth's lengths, floored at the least the grid can draw a basin and a taper over: two cells
        // and one, which bind only on a grid coarser than 200 rows of square cells.
        val shortestKm = maxOf(tectonics.riftSegmentMinKm, MIN_SEGMENT_CELLS * cellWidthKm)
        val longestKm = maxOf(tectonics.riftSegmentMaxKm, shortestKm)
        val accommodationKm = maxOf(tectonics.riftAccommodationKm, MIN_ACCOMMODATION_CELLS * cellWidthKm)

        for (pairKey in cellsOfPair.keys.sorted()) {
            val pairCells = cellsOfPair.getValue(pairKey)
            val interaction = boundaries.getValue(pairCells[0]).interaction
            val axis = BisectorAxis(config, plates[interaction.lowId], plates[interaction.highId])
            fun bisectorKm(cell: Int): Double {
                val column = cell % config.width
                val row = cell / config.width
                return axis.alongKm((column + 0.5) * cellWidthKm, (row + 0.5) * config.cellHeightKm)
            }

            val courses = riftCourses(config, pairCells).map { course ->
                anchoredCourse(course, course.cells.map { bisectorKm(it) })
            }.sortedBy { it.bisectorStartKm }

            courses.forEachIndexed { courseIndex, course ->
                fun draw(ordinal: Int, salt: Long): Float =
                    riftDraw(config.seed, pairKey, ordinal, salt)
                fun lengthKm(ordinal: Int): Double =
                    shortestKm + (longestKm - shortestKm) * draw(ordinal, SALT_LENGTH)

                // The joins round the course: half-graben 0 holds the anchor, somewhere along it,
                // and the rest follow from it both ways.
                val firstKm = course.alongKm.minOrNull() ?: return@forEachIndexed
                val lastKm = course.alongKm.maxOrNull() ?: return@forEachIndexed
                var ordinal = 0
                var startKm = -draw(0, SALT_PHASE) * lengthKm(0)
                while (startKm > firstKm) {
                    ordinal--
                    startKm -= lengthKm(ordinal)
                }
                while (startKm + lengthKm(ordinal) <= firstKm) {
                    startKm += lengthKm(ordinal)
                    ordinal++
                }
                val joinsKm = arrayListOf(firstKm)
                val ordinals = ArrayList<Int>()
                var endKm = startKm + lengthKm(ordinal)
                while (true) {
                    ordinals.add(ordinal)
                    if (endKm >= lastKm) {
                        joinsKm.add(lastKm)
                        break
                    }
                    joinsKm.add(endKm)
                    ordinal++
                    endKm += lengthKm(ordinal)
                }
                // A sliver the rift's end leaves of a half-graben is not one; it joins its neighbor.
                if (ordinals.size > 1 && joinsKm[1] - joinsKm[0] < shortestKm / 2) {
                    joinsKm.removeAt(1)
                    ordinals.removeAt(0)
                }
                if (ordinals.size > 1 && joinsKm[joinsKm.size - 1] - joinsKm[joinsKm.size - 2] < shortestKm / 2) {
                    joinsKm.removeAt(joinsKm.size - 2)
                    ordinals.removeAt(ordinals.size - 1)
                }
                // Which flank the even half-grabens hang from; the odd ones hang from the other.
                val evenFootwallOnLow = draw(0, SALT_POLARITY) < 0.5f
                val floorHalfWidthKm = tectonics.riftWidthKm * tectonics.riftFloorShare
                val layout = RiftLayout(
                    joinsKm = joinsKm.toDoubleArray(),
                    ordinals = ordinals.toIntArray(),
                    depthFactor = FloatArray(ordinals.size) {
                        1f + tectonics.riftSegmentDepthVariation * (2f * draw(ordinals[it], SALT_DEPTH) - 1f)
                    },
                    // One draw for both the height and the width of the segment's shoulders, not
                    // two. Flexural uplift scales with the throw on the fault, so the footwall of a
                    // bigger half-graben stands both higher and broader, and two independent draws
                    // let a segment come out much taller than it is wide, which is a knife-edge
                    // ridge. Where such a ridge crosses shallow sea it clears the surface as a strip
                    // of land a couple of cells wide with a strait either side, the exact failure
                    // `RibbonLandTest` exists to catch (docs/DESIGN_LEDGER.md, E4).
                    shoulderFactor = FloatArray(ordinals.size) {
                        (1f + tectonics.riftSegmentShoulderVariation * (2f * draw(ordinals[it], SALT_SHOULDER) - 1f))
                            .coerceAtLeast(MIN_SHOULDER_FACTOR)
                    },
                    footwallOnLow = BooleanArray(ordinals.size) { (ordinals[it].mod(2) == 0) == evenFootwallOnLow },
                    // Each interior join's relay, keyed by the half-graben after it.
                    relayOverlapHalfKm = DoubleArray(joinsKm.size) { join ->
                        if (join == 0 || join == joinsKm.size - 1) 0.0
                        else (RELAY_OVERLAP_MIN_KM + (RELAY_OVERLAP_MAX_KM - RELAY_OVERLAP_MIN_KM) *
                            draw(ordinals[join], SALT_RELAY_OVERLAP)) / 2
                    },
                    relayRampKm = DoubleArray(joinsKm.size) { join ->
                        if (join == 0 || join == joinsKm.size - 1) 1.0
                        else floorHalfWidthKm * (RELAY_RAMP_MIN_SHARE +
                            (RELAY_RAMP_MAX_SHARE - RELAY_RAMP_MIN_SHARE) * draw(ordinals[join], SALT_RELAY_RAMP))
                    },
                    relayBowKm = DoubleArray(joinsKm.size) { join ->
                        if (join == 0 || join == joinsKm.size - 1) 0.0
                        else (RELAY_OVERLAP_MIN_KM + (RELAY_OVERLAP_MAX_KM - RELAY_OVERLAP_MIN_KM) *
                            draw(ordinals[join], SALT_RELAY_OVERLAP)) * RELAY_BOW_SHARE_OF_OVERLAP *
                            (2 * draw(ordinals[join], SALT_RELAY_BOW) - 1)
                    },
                    accommodationKm = accommodationKm
                )

                val centerline = centerlineOf(config, course)

                course.cells.forEachIndexed { index, cell ->
                    val alongKm = course.alongKm[index]
                    val place = RiftPlace(layout, alongKm, centerline)
                    boundaries.getValue(cell).segment = place
                    if (report != null) {
                        var segment = 0
                        while (segment < ordinals.size - 1 && alongKm >= joinsKm[segment + 1]) segment++
                        val toJoinKm = minOf(alongKm - joinsKm[segment], joinsKm[segment + 1] - alongKm)
                            .coerceAtLeast(0.0)
                        report.add(
                            cell, interaction.lowId, interaction.highId, courseIndex, ordinals[segment],
                            layout.depthFactor[segment], layout.footwallOnLow[segment], alongKm.toFloat(),
                            toJoinKm.toFloat()
                        )
                    }
                }
            }
        }
    }

    /**
     * The half-graben a cell of a rift's corridor stands in, [alongKm] along the rift and
     * [acrossKm] from its axis on the pair's low-id plate or the other.
     *
     * A join between two half-grabens is not a line square to the rift: the two border faults
     * overlap along strike and hand the extension from one to the other across an oblique, curved
     * relay ramp (Rosendahl 1987's accommodation zones; Morley and others 1990 on transfer zones).
     * So each join moves with the cell: on the flank of the half-graben before it, where that
     * half-graben's fault runs on past the join, it lies further along; on the other flank, where
     * the next fault has already begun, it lies back; the swing runs from nothing at the axis to the
     * whole overlap over the ramp's width, along a smoothstep, bowed to one side halfway across, and
     * set wandering by a noise on the ground, so the crest is oblique, curved and ragged. Each join stays
     * short of its neighbors by the accommodation zone's half-length, so the half-grabens keep
     * their order and none is squeezed to nothing.
     *
     * Through the accommodation zone the half-graben's depth and shoulders blend half-way to its
     * neighbor's at the join, where the taper reaches nothing, so neither the floor nor the
     * shoulders step at a join. [crestNoise] is the relay noise at the cell, about -1..1.
     */
    private fun riftSegmentAt(
        layout: RiftLayout,
        alongKm: Double,
        onLowIdPlate: Boolean,
        acrossKm: Double,
        crestNoise: Float
    ): RiftSegment {
        val joins = layout.joinsKm
        val last = joins.size - 1
        fun joinHere(join: Int): Double {
            if (join <= 0 || join >= last) return joins[join.coerceIn(0, last)]
            val swing = (acrossKm / layout.relayRampKm[join]).coerceIn(0.0, 1.0)
            val smooth = swing * swing * (3 - 2 * swing)
            val beforeFaultsHere = onLowIdPlate == layout.footwallOnLow[join - 1]
            val leanKm = (if (beforeFaultsHere) 1 else -1) * layout.relayOverlapHalfKm[join] * smooth +
                layout.relayBowKm[join] * 4 * swing * (1 - swing)
            // Held softly short of the neighbors' joins by the accommodation zone's half-length, so
            // no half-graben is squeezed to nothing where two relays lean toward each other: a hard
            // stop there drew a corner, and a half-graben of no length a step. The lean has half
            // the room and the wander the other half, added after the lean is held: a wander held
            // with it went flat wherever the lean filled the room, and the crest ran straight.
            val roomKm = (minOf(joins[join] - joins[join - 1], joins[join + 1] - joins[join]) / 2 -
                layout.accommodationKm / 2).coerceAtLeast(0.0)
            if (roomKm <= 0.0) return joins[join]
            val leanRoomKm = roomKm / 2
            val wanderKm = minOf(layout.accommodationKm * RELAY_CREST_SHARE_OF_ZONE, roomKm / 2) * crestNoise * swing
            return joins[join] + leanRoomKm * tanh(leanKm / leanRoomKm) + wanderKm
        }
        var segment = 0
        while (segment < last - 1 && alongKm >= joins[segment + 1]) segment++
        while (segment > 0 && alongKm < joinHere(segment)) segment--
        while (segment < last - 1 && alongKm >= joinHere(segment + 1)) segment++
        val fromKm = joinHere(segment)
        val toKm = joinHere(segment + 1)
        val toJoinKm = minOf(alongKm - fromKm, toKm - alongKm).coerceAtLeast(0.0)
        val intoSegment = (toJoinKm / layout.accommodationKm).coerceIn(0.0, 1.0).toFloat()
        val taper = intoSegment * intoSegment * (3f - 2f * intoSegment)
        // The neighbor across the nearer join, where there is one, and how much of it this cell takes.
        val nearerJoinIsBefore = alongKm - fromKm < toKm - alongKm
        val neighbor = when {
            nearerJoinIsBefore && segment > 0 -> segment - 1
            !nearerJoinIsBefore && segment < last - 1 -> segment + 1
            else -> segment
        }
        val towardNeighbor = 0.5f * (1f - taper)
        fun blend(values: FloatArray) = values[segment] + (values[neighbor] - values[segment]) * towardNeighbor
        val shoulder = blend(layout.shoulderFactor)
        return RiftSegment(
            depthFactor = blend(layout.depthFactor),
            shoulderFactor = shoulder,
            widthFactor = shoulder,
            footwallOnLow = layout.footwallOnLow[segment],
            taper = taper
        )
    }

    /**
     * [course]'s centerline: its cells' mean place in each stretch of [CENTERLINE_STEP_KM] along it,
     * in order, eastings taken the short way round from the first cell's.
     */
    private fun centerlineOf(config: WorldGenConfig, course: AnchoredCourse): RiftCenterline {
        val worldWidthKm = config.width * config.cellWidthKm
        val referenceEastKm = (course.cells[0] % config.width + 0.5) * config.cellWidthKm
        val bins = HashMap<Long, DoubleArray>()
        course.cells.forEachIndexed { index, cell ->
            val sums = bins.getOrPut(floor(course.alongKm[index] / CENTERLINE_STEP_KM).toLong()) { DoubleArray(4) }
            var eastKm = (cell % config.width + 0.5) * config.cellWidthKm - referenceEastKm
            if (eastKm > worldWidthKm / 2) eastKm -= worldWidthKm
            if (eastKm < -worldWidthKm / 2) eastKm += worldWidthKm
            sums[0] += referenceEastKm + eastKm
            sums[1] += (cell / config.width + 0.5) * config.cellHeightKm
            sums[2] += course.alongKm[index]
            sums[3] += 1.0
        }
        val means = bins.keys.sorted().map { bins.getValue(it) }
        return RiftCenterline(
            DoubleArray(means.size) { means[it][0] / means[it][3] },
            DoubleArray(means.size) { means[it][1] / means[it][3] },
            DoubleArray(means.size) { means[it][2] / means[it][3] },
            worldWidthKm
        )
    }

    /**
     * [RiftCenterline.alongKmAt]: halvings of a stretch to find where a line across it passes
     * through a point: twenty-four, a stretch of [CENTERLINE_STEP_KM] to under a centimeter.
     */
    private const val CENTERLINE_BISECTIONS = 24

    /** [RiftCenterline]: the shortest stretch it divides by, so two vertices in one place never divide by nothing. */
    private const val MIN_STEP_KM = 1e-9

    /**
     * [riftSegmentAt]: the two border faults' overlap along strike at a join, km, the least and the
     * most: tens of kilometers, as the East African relays overlap (Morley and others 1990), held
     * under the 60 km of the shortest half-graben so a join never passes its neighbor's.
     */
    private const val RELAY_OVERLAP_MIN_KM = 20.0
    private const val RELAY_OVERLAP_MAX_KM = 50.0

    /**
     * [riftSegmentAt]: over how much of the trough's flat floor, from the axis out, the crest swings
     * through its whole overlap: a half to one and a half of the floor's half-width, so some relays
     * cross the floor in a short ramp and some lean across the whole of it.
     */
    private const val RELAY_RAMP_MIN_SHARE = 0.5
    private const val RELAY_RAMP_MAX_SHARE = 1.5

    /**
     * [riftSegmentAt]: how far the crest bows off its swing halfway across, as a share of the
     * relay's overlap, either way: a quarter, so the bow is half the swing and its steepest lean,
     * four times the bow over the shortest ramp, stays near one in one; any steeper and the crest
     * folds into a hook whose level lines double back.
     */
    private const val RELAY_BOW_SHARE_OF_OVERLAP = 0.25

    /**
     * [riftSegmentAt]'s crest noise: how far the crest wanders, as a share of the accommodation
     * zone's half-length, 12.5 km either way; its longest wavelength, 100 km, and its octaves, two,
     * down to 50 km. The wander's steepest lean is two pi times its reach over its wavelength,
     * near one in one on the shorter octave: ragged on the ground, and never a hook.
     */
    private const val RELAY_CREST_SHARE_OF_ZONE = 1.0
    private const val RELAY_CREST_WAVELENGTH_KM = 200.0
    private const val RELAY_CREST_OCTAVES = 2

    /** [segmentRifts]: the least a half-graben can be long on any grid, in cells. */
    private const val MIN_SEGMENT_CELLS = 2.0

    /** [segmentRifts]: the least an accommodation zone's half-length can be on any grid, in cells. */
    private const val MIN_ACCOMMODATION_CELLS = 1.0

    /**
     * [segmentRifts]: the smallest a segment's shoulders can be drawn, as a factor on their nominal
     * height and width, so a draw at the bottom of a wide variation never flattens a flank to
     * nothing.
     */
    private const val MIN_SHOULDER_FACTOR = 0.2f

    /** [riftDraw]'s salts: one stream per property of a half-graben. */
    private const val SALT_LENGTH = 1L
    private const val SALT_PHASE = 2L
    private const val SALT_DEPTH = 3L
    private const val SALT_SHOULDER = 4L
    private const val SALT_POLARITY = 5L
    private const val SALT_RELAY_OVERLAP = 6L
    private const val SALT_RELAY_RAMP = 7L
    private const val SALT_RELAY_BOW = 8L

    /**
     * A number in 0..1 for one property of one half-graben, keyed by the world's seed, the pair
     * of plates, the half-graben's place in the pair's one sequence and the property. Nothing about
     * the grid goes into it, and nothing about how many separate stretches the pair's boundary
     * breaks into or where they rank: every stretch of a pair reads its half-grabens off the one
     * sequence laid along the pair's bisector coordinate, each at the ordinals its own place covers.
     */
    private fun riftDraw(seed: Long, pairKey: Int, ordinal: Int, salt: Long): Float {
        val key = (pairKey.toLong() * RIFT_ORDINAL_STRIDE + ordinal) * RIFT_SALT_STRIDE + salt
        val bits = seedHash(seed xor RIFT_DRAW_SALT, key)
        return ((bits ushr 40) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
    }

    /** Strides that keep [riftDraw]'s keys apart: more half-grabens and salts than any rift has. */
    private const val RIFT_ORDINAL_STRIDE = 1L shl 20
    private const val RIFT_SALT_STRIDE = 16L

    /** Keeps [riftDraw]'s stream apart from the vents' and every other [seedHash] caller's. */
    private const val RIFT_DRAW_SALT = 0x3c6ef372_fe94f82bL

    /**
     * The line two plates' boundary would be without the warp, the perpendicular bisector of their
     * seeds on the ground, and how far along it a point lies from the seeds' midpoint.
     *
     * A boundary cell's warped source lies on this line to within a cell, whichever grid drew it,
     * so the distance along it is a coordinate on the rift that the grid does not move: what
     * [segmentRifts] anchors a rift's joins to and orders its separate stretches by. Positive
     * along the seed axis from the lower id to the higher, turned a quarter turn.
     */
    private class BisectorAxis(config: WorldGenConfig, low: Plate, high: Plate) {
        private val worldWidthKm = config.width * config.cellWidthKm
        private val midEastKm: Double
        private val midSouthKm: Double
        private val alongEast: Double
        private val alongSouth: Double

        init {
            val lowEastKm = (low.seedX + 0.5) * config.cellWidthKm
            val lowSouthKm = (low.seedY + 0.5) * config.cellHeightKm
            val acrossKm = shortestEastKm((high.seedX + 0.5) * config.cellWidthKm - lowEastKm)
            val downKm = (high.seedY + 0.5) * config.cellHeightKm - lowSouthKm
            midEastKm = lowEastKm + acrossKm / 2
            midSouthKm = lowSouthKm + downKm / 2
            val separationKm = sqrt(acrossKm * acrossKm + downKm * downKm).coerceAtLeast(1e-9)
            alongEast = -downKm / separationKm
            alongSouth = acrossKm / separationKm
        }

        private fun shortestEastKm(eastKm: Double): Double = when {
            eastKm > worldWidthKm / 2 -> eastKm - worldWidthKm
            eastKm < -worldWidthKm / 2 -> eastKm + worldWidthKm
            else -> eastKm
        }

        fun alongKm(eastKm: Double, southKm: Double): Double =
            shortestEastKm(eastKm - midEastKm) * alongEast + (southKm - midSouthKm) * alongSouth
    }

    /** One stretch of a rift as a course: its cells, and each one's distance along it from one end, in km. */
    private class RiftCourse(val cells: IntArray, val alongKm: DoubleArray)

    /**
     * A course set on the ground: the same cells, measured from the anchor [segmentRifts] describes
     * and in the direction the bisector coordinate grows, and where the course starts on the
     * bisector, which orders a pair's separate stretches.
     */
    private class AnchoredCourse(val cells: IntArray, val alongKm: DoubleArray, val bisectorStartKm: Double)

    /**
     * Turns [course] so that it runs the way the bisector coordinate grows, and measures it from
     * the anchor: the first place along it where the coordinate reaches zero, the seeds' midpoint,
     * or the end nearer zero where the course never does. [bisectorKm] is each cell's coordinate.
     *
     * Beyond an end the distance continues as the coordinate does, so a course whose end moves by a
     * cell between grids moves its anchor by the same cell and nothing more.
     */
    private fun anchoredCourse(course: RiftCourse, bisectorKm: List<Double>): AnchoredCourse {
        val count = course.cells.size
        val order = (0 until count).sortedWith(compareBy({ course.alongKm[it] }, { course.cells[it] }))
        val startsLow = bisectorKm[order.first()] <= bisectorKm[order.last()]
        val lengthKm = course.alongKm[order.last()]
        val along = DoubleArray(count) { if (startsLow) course.alongKm[it] else lengthKm - course.alongKm[it] }
        val walk = if (startsLow) order else order.reversed()
        val lowKm = bisectorKm[walk.first()]
        val highKm = bisectorKm[walk.last()]
        val anchorAlongKm: Double
        val anchorBisectorKm: Double
        when {
            lowKm >= 0.0 -> { anchorAlongKm = along[walk.first()]; anchorBisectorKm = lowKm }
            highKm <= 0.0 -> { anchorAlongKm = along[walk.last()]; anchorBisectorKm = highKm }
            else -> {
                val crossing = walk.indexOfFirst { bisectorKm[it] >= 0.0 }.coerceAtLeast(1)
                val before = walk[crossing - 1]
                val after = walk[crossing]
                val span = bisectorKm[after] - bisectorKm[before]
                val share = if (span > 0.0) ((0.0 - bisectorKm[before]) / span).coerceIn(0.0, 1.0) else 0.0
                anchorAlongKm = along[before] + share * (along[after] - along[before])
                anchorBisectorKm = 0.0
            }
        }
        return AnchoredCourse(
            course.cells,
            DoubleArray(count) { along[it] - anchorAlongKm + anchorBisectorKm },
            lowKm
        )
    }

    /**
     * A pair's rift cells as courses: each connected run of them measured along its centerline
     * ([arcAlongRun]), and runs whose ends lie within the trough's half-width of each other joined
     * end to end, the gap counted as the straight line across it.
     *
     * A run breaks where a third plate pinches the boundary for a cell or two, which a finer or a
     * coarser grid may not do; within the trough's half-width the two pieces lie in one trough on
     * the ground, so they are one rift. Links are made shortest first, ties to the lower cell
     * index, each end taking at most one and never closing a ring.
     */
    private fun riftCourses(config: WorldGenConfig, pairCells: List<Int>): List<RiftCourse> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellWidthKm = config.cellWidthKm
        val runOf = HashMap<Int, Int>(pairCells.size * 2)
        pairCells.forEach { runOf[it] = UNCLAIMED_RUN }
        val runs = ArrayList<IntArray>()
        val queue = ArrayList<Int>()
        for (start in pairCells) {
            if (runOf.getValue(start) != UNCLAIMED_RUN) continue
            val run = runs.size
            queue.clear()
            queue.add(start)
            runOf[start] = run
            var head = 0
            while (head < queue.size) {
                val cell = queue[head++]
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowStep in -1..1) {
                    val neighborRow = row + rowStep
                    if (neighborRow < 0 || neighborRow >= cellsDown) continue
                    for (columnStep in -1..1) {
                        if (columnStep == 0 && rowStep == 0) continue
                        val neighbor = neighborRow * cellsAcross + (column + columnStep).mod(cellsAcross)
                        if (runOf[neighbor] != UNCLAIMED_RUN) continue
                        runOf[neighbor] = run
                        queue.add(neighbor)
                    }
                }
            }
            runs.add(queue.toIntArray())
        }
        val arcs = runs.map { arcAlongRun(config, it) }
        // Run r's two ends are 2r (where its arc starts) and 2r + 1 (where it ends).
        val endCell = IntArray(runs.size * 2) { end ->
            if (end % 2 == 0) arcs[end / 2].firstEndCell else arcs[end / 2].lastEndCell
        }
        val linkedTo = IntArray(runs.size * 2) { NO_LINK }
        val gapKm = DoubleArray(runs.size * 2)
        if (runs.size > 1) {
            val maxGapKm = config.tectonics.riftWidthKm
            class Link(val km: Double, val from: Int, val to: Int)
            val links = ArrayList<Link>()
            for (from in endCell.indices) for (to in from + 1 until endCell.size) {
                if (from / 2 == to / 2) continue
                val km = groundKm(config, endCell[from], endCell[to])
                if (km <= maxGapKm) links.add(Link(km, from, to))
            }
            links.sortWith(compareBy({ it.km }, { endCell[it.from] }, { endCell[it.to] }))
            val group = IntArray(runs.size) { it }
            fun root(run: Int): Int {
                var at = run
                while (group[at] != at) at = group[at]
                return at
            }
            for (link in links) {
                if (linkedTo[link.from] != NO_LINK || linkedTo[link.to] != NO_LINK) continue
                val fromRoot = root(link.from / 2)
                val toRoot = root(link.to / 2)
                if (fromRoot == toRoot) continue
                group[fromRoot] = toRoot
                linkedTo[link.from] = link.to
                linkedTo[link.to] = link.from
                gapKm[link.from] = link.km
                gapKm[link.to] = link.km
            }
        }

        // Walk each chain from a free end, runs in index order, laying the arcs end to end.
        val placed = BooleanArray(runs.size)
        val courses = ArrayList<RiftCourse>()
        for (first in runs.indices) {
            if (placed[first]) continue
            val entryEnd = when {
                linkedTo[2 * first] == NO_LINK -> 2 * first
                linkedTo[2 * first + 1] == NO_LINK -> 2 * first + 1
                else -> continue
            }
            val cells = ArrayList<Int>()
            val along = ArrayList<Double>()
            var offsetKm = 0.0
            var enter = entryEnd
            while (true) {
                val run = enter / 2
                placed[run] = true
                val forward = enter % 2 == 0
                val arc = arcs[run]
                val runLengthKm = arc.lengthCellWidths * cellWidthKm
                runs[run].forEachIndexed { index, cell ->
                    val fromStartKm = arc.arcLengthCellWidths[index] * cellWidthKm
                    cells.add(cell)
                    along.add(offsetKm + if (forward) fromStartKm else runLengthKm - fromStartKm)
                }
                offsetKm += runLengthKm
                val leave = if (forward) 2 * run + 1 else 2 * run
                val next = linkedTo[leave]
                if (next == NO_LINK) break
                offsetKm += gapKm[leave]
                enter = next
            }
            courses.add(RiftCourse(cells.toIntArray(), along.toDoubleArray()))
        }
        return courses
    }

    /** [riftCourses]: a rift cell not yet gathered into a run. */
    private const val UNCLAIMED_RUN = -1

    /** [riftCourses]: a run end joined to no other. */
    private const val NO_LINK = -1

    /** The straight line between two cells' centers on the ground, the short way round, in km. */
    private fun groundKm(config: WorldGenConfig, from: Int, to: Int): Double {
        var columns = to % config.width - from % config.width
        if (columns > config.width / 2) columns -= config.width
        if (columns < -config.width / 2) columns += config.width
        val acrossKm = columns * config.cellWidthKm
        val downKm = (to / config.width - from / config.width) * config.cellHeightKm
        return sqrt(acrossKm * acrossKm + downKm * downKm)
    }

    /** How far along one run of boundary cells each of its cells lies, from one end of it. */
    internal class RunArc(
        /** One entry per cell of the run, in the order the run was handed in. */
        val arcLengthCellWidths: FloatArray,
        /** From the end the arc is measured from to the far end. */
        val lengthCellWidths: Float,
        /** The cell at the end the arc is measured from. */
        val firstEndCell: Int,
        /** The cell at the far end. */
        val lastEndCell: Int
    )

    /**
     * The arc length along a connected run of boundary cells, on the ground and in cell widths,
     * from one of its ends.
     *
     * The ends are found by the usual double sweep — the farthest cell from the run's first, then
     * the farthest from that, ties broken by the lower cell index — over a walk from cell to cell
     * whose steps are their length on the ground: a whole cell width along a row, half of one down
     * a column on this map's cells, the hypotenuse on a diagonal. That walk orders the run but does
     * not measure it, because a staircase of axis and diagonal steps overstates the line it
     * approximates, by up to eighteen percent on cells half as tall as they are wide. So the length
     * is the length of the run's centreline: the cells are gathered into stretches
     * [CENTERLINE_STEP_KM] long by their walked distance, each stretch stands at the mean
     * of its cells on the ground, and the arc is measured along the line from one end through those
     * means to the other, each cell taking its place on it by its walked distance. A straight run
     * measures its own length to the width of its cells; a meander is followed at the scale of a
     * stretch. Counted in hops, as before, a north-south rift was twice its length on the ground and
     * a diagonal one 1.4 times.
     *
     * Internal so `TectonicGroundTest` can walk a straight run it lays at any bearing.
     */
    internal fun arcAlongRun(config: WorldGenConfig, runCells: IntArray): RunArc {
        val stretchCellWidths = config.cellsFor(CENTERLINE_STEP_KM).toDouble()
        val cellsAcross = config.width
        val cellsDown = config.height
        val steps = config.groundSteps
        val rowScale = config.cellHeightInCellWidths
        val count = runCells.size
        val localIndex = HashMap<Int, Int>(count * 2)
        runCells.forEachIndexed { index, cell -> localIndex[cell] = index }
        val walked = FloatArray(count)
        val heap = LongMinHeap(count)

        // A shortest walk over the run from [source], a local index, with each step its length on
        // the ground; leaves the distances in `walked` and returns the farthest cell's local index.
        fun sweep(source: Int): Int {
            walked.fill(Float.MAX_VALUE)
            walked[source] = 0f
            heap.push(walkKey(0f, source))
            while (!heap.isEmpty()) {
                val key = heap.pop()
                val local = (key and 0xFFFFFFFFL).toInt()
                val here = Float.fromBits((key ushr 32).toInt())
                if (here > walked[local]) continue
                val cell = runCells[local]
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowStep in -1..1) {
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnStep in -1..1) {
                        if (columnStep == 0 && rowStep == 0) continue
                        var neighbourColumn = (column + columnStep) % cellsAcross
                        if (neighbourColumn < 0) neighbourColumn += cellsAcross
                        val neighbourLocal =
                            localIndex[neighbourRow * cellsAcross + neighbourColumn] ?: continue
                        val there = here + steps.of(columnStep, rowStep)
                        if (there < walked[neighbourLocal]) {
                            walked[neighbourLocal] = there
                            heap.push(walkKey(there, neighbourLocal))
                        }
                    }
                }
            }
            var farthest = source
            for (local in 0 until count) {
                val distance = walked[local]
                if (distance > walked[farthest] ||
                    (distance == walked[farthest] && runCells[local] < runCells[farthest])
                ) {
                    farthest = local
                }
            }
            return farthest
        }

        val first = sweep(0)
        val last = sweep(first)
        val originColumn = runCells[first] % cellsAcross
        val originRow = runCells[first] / cellsAcross

        // Where each cell stands on the ground from the end the arc starts at, in cell widths.
        fun eastOf(local: Int): Double {
            var columns = runCells[local] % cellsAcross - originColumn
            if (columns > cellsAcross / 2) columns -= cellsAcross
            if (columns < -cellsAcross / 2) columns += cellsAcross
            return columns.toDouble()
        }
        fun southOf(local: Int): Double = (runCells[local] / cellsAcross - originRow) * rowScale

        // The stretches, by walked distance, and the mean of each on the ground.
        val lastWalked = walked[last].toDouble()
        val stretches = (lastWalked / stretchCellWidths).toInt() + 1
        val sumEast = DoubleArray(stretches)
        val sumSouth = DoubleArray(stretches)
        val sumWalked = DoubleArray(stretches)
        val members = IntArray(stretches)
        for (local in 0 until count) {
            val stretch = (walked[local] / stretchCellWidths).toInt().coerceIn(0, stretches - 1)
            sumEast[stretch] += eastOf(local)
            sumSouth[stretch] += southOf(local)
            sumWalked[stretch] += walked[local].toDouble()
            members[stretch]++
        }

        // The centreline: from the first end, through each stretch's mean, to the last end. Each
        // point carries the walked distance it stands for and the arc length along the line to it.
        val pointWalked = ArrayList<Double>()
        val pointArc = ArrayList<Double>()
        var previousEast = 0.0
        var previousSouth = 0.0
        var arc = 0.0
        fun addPoint(east: Double, south: Double, walkedHere: Double) {
            if (pointWalked.isNotEmpty() && walkedHere <= pointWalked.last()) return
            val stepEast = east - previousEast
            val stepSouth = south - previousSouth
            arc += sqrt(stepEast * stepEast + stepSouth * stepSouth)
            pointWalked.add(walkedHere)
            pointArc.add(arc)
            previousEast = east
            previousSouth = south
        }
        pointWalked.add(0.0)
        pointArc.add(0.0)
        for (stretch in 0 until stretches) {
            if (members[stretch] == 0) continue
            addPoint(
                sumEast[stretch] / members[stretch],
                sumSouth[stretch] / members[stretch],
                sumWalked[stretch] / members[stretch]
            )
        }
        addPoint(eastOf(last), southOf(last), lastWalked)

        // Each cell's place on the centreline, interpolated by its walked distance.
        val arcLengths = FloatArray(count) { local ->
            val distance = walked[local].toDouble()
            var point = 1
            while (point < pointWalked.size - 1 && pointWalked[point] < distance) point++
            if (point >= pointWalked.size) {
                pointArc.last().toFloat()
            } else {
                val from = pointWalked[point - 1]
                val to = pointWalked[point]
                val along = if (to > from) ((distance - from) / (to - from)).coerceIn(0.0, 1.0) else 1.0
                (pointArc[point - 1] + along * (pointArc[point] - pointArc[point - 1])).toFloat()
            }
        }
        return RunArc(arcLengths, pointArc.last().toFloat(), runCells[first], runCells[last])
    }

    /** A walked distance and a local index packed so a heap of them pops the nearest first. */
    private fun walkKey(distanceCellWidths: Float, local: Int): Long =
        (distanceCellWidths.toRawBits().toLong() shl 32) or local.toLong()

    /**
     * How long a stretch of a run [arcAlongRun] draws its centerline through, in kilometers: 93.75,
     * the four cell widths it was set as on the 512 by 512 grid's 23.4 km cells.
     *
     * Long enough that a stretch's mean lies on the run's axis whatever staircase the cells make,
     * which is four cells or more from 256 rows up; short against the bends of the boundary warp,
     * whose finest octave is 2,000 km over eight. A length on the ground rather than a count of
     * cells, because the arc is what a rift's joins are placed along and it has to be the same arc
     * at every grid: counted in cells the centerline cut the bends a coarse grid's stretches were too
     * long for and followed them on a fine one, and the same rift measured 1.6% shorter at 256 rows
     * than at 1,024, which puts a far join a half-graben out (docs/DESIGN_LEDGER.md, L1).
     */
    private const val CENTERLINE_STEP_KM = 93.75

    /**
     * Sharp-crested profile, for features that really are narrow: trenches, rifts, ridges at
     * spreading centres.
     */
    private fun sharpFalloff(distance: Float, range: Float): Float {
        if (distance >= range) return 0f
        return (1f - distance / range).pow(SHARP_FALLOFF_EXPONENT)
    }

    /**
     * Broad, flat-crested profile, for mountain belts.
     *
     * The sharp profile peaks exactly on the boundary and falls away fastest right at the crest,
     * which builds a knife-edge wall along the suture. Nothing on Earth looks like that: an orogen
     * is hundreds of kilometres across, with the high ground spread over a wide axis and foothills
     * grading into the forelands. Worse, where such a wall crosses a submerged region only its
     * crest clears sea level, leaving a ruler-straight strip of land with a strait either side.
     *
     * This is 1 - smoothstep: flat at the crest, flat at the toe, steepest in between, so the belt
     * has a broad high axis and a gradual outer slope.
     */
    private fun beltFalloff(distance: Float, range: Float): Float {
        if (distance >= range) return 0f
        val u = distance / range
        return (1f - u) * (1f - u) * (1f + 2f * u)
    }

    /**
     * Flat-topped plateau: dead flat across the inner [flatShare] of the half-width, then
     * [beltFalloff]'s shoulder out to nothing.
     *
     * A continental collision does not build a ridge, it thickens the crust over a wide area — the
     * Tibetan plateau is a plain at five kilometres, with its ranges around the rim. Even
     * [beltFalloff], broad as it is, still peaks on the suture and falls away from it everywhere;
     * this is what makes the difference between a very wide mountain and a plateau.
     */
    private fun plateauFalloff(distance: Float, range: Float, flatShare: Float): Float {
        if (distance >= range) return 0f
        val flat = range * flatShare.coerceIn(0f, 0.95f)
        if (distance <= flat) return 1f
        val u = (distance - flat) / (range - flat)
        return (1f - u) * (1f - u) * (1f + 2f * u)
    }

    /**
     * A ridge whose crest stands [offset] cells from the boundary rather than on it, [halfWidth]
     * either side of its own axis.
     *
     * This is what makes a margin asymmetric in the way that matters. A subducting slab melts once
     * it is deep enough, not where it goes under, so the volcanoes sit a fixed distance behind the
     * trench — and nothing built out of distance-to-the-boundary alone, however shaped, can put a
     * crest anywhere but on the line itself.
     */
    private fun ridgeAt(distance: Float, offset: Float, halfWidth: Float): Float {
        if (halfWidth <= 0f) return 0f
        val u = abs(distance - offset) / halfWidth
        if (u >= 1f) return 0f
        return (1f - u) * (1f - u) * (1f + 2f * u)
    }

    /**
     * Along-strike modulation for a volcanic arc: sampled fine and raised to a power, so the arc
     * is a row of separate cones rather than a continuous wall of the same height.
     */
    private fun volcanicChain(
        noise: PerlinNoise,
        lattice: GroundLattice,
        column: Int,
        row: Int
    ): Float {
        val alongStrike = 0.5f + 0.5f * noise.fbm(
            lattice.x(column),
            lattice.y(row),
            2,
            lattice.period,
            lattice.period
        )
        return alongStrike.coerceIn(0f, 1f).pow(ARC_CHAIN_EXPONENT) * ARC_CHAIN_PEAK
    }

    /**
     * The along-strike swell of a belt's width, as a factor on its nominal half-width: the noise
     * runs it between these two.
     *
     * A belt that keeps one width for its whole length reads as drawn on even once its height
     * varies. [WIDTH_SWELL_WAVELENGTH_KM] is the swell's wavelength along the belt, 1,714.3 km,
     * the 7 cycles round the 12,000 km world it was set as: slow enough that neighbouring cells
     * agree and the belt stays continuous rather than dissolving into blotches, and several
     * swells along a belt the length of the Andes.
     */
    private const val WIDTH_SWELL_MIN = 0.55f
    private const val WIDTH_SWELL_SPAN = 0.85f
    private const val WIDTH_SWELL_WAVELENGTH_KM = 1_714.3

    /**
     * [WIDTH_SWELL_MIN] plus [WIDTH_SWELL_SPAN], written out rather than added.
     *
     * Only [stampEpoch]'s reach test reads it, and the sum of the two floats rounds a hair above
     * the literal, which would move the test's bound and with it a bit of the world.
     */
    private const val MAX_WIDTH_SCALE = 1.4f

    /**
     * The finer jitter of a belt's rim, on the same terms, at a wavelength of 705.9 km: the 17
     * cycles round the 12,000 km world it was set as. See `edgeJitter` in [stampEpoch].
     */
    private const val EDGE_JITTER_MIN = 0.72f
    private const val EDGE_JITTER_SPAN = 0.56f
    private const val EDGE_JITTER_WAVELENGTH_KM = 705.9

    /** [EDGE_JITTER_MIN] plus [EDGE_JITTER_SPAN], written out for [MAX_WIDTH_SCALE]'s reason. */
    private const val MAX_EDGE_JITTER = 1.28f

    /**
     * How much of a belt's half-width the sharp-crested features use.
     *
     * A trench, a spreading ridge and a transform scarp are narrow next to the belt a collision
     * raises, and they share [TectonicsConfig.boundaryFalloffKm] rather than carrying a knob
     * apiece.
     */
    private const val NARROW_SHARE_OF_BELT = 0.45f

    /** The exponent that gives [sharpFalloff] its peak on the boundary and its fast decay. */
    private const val SHARP_FALLOFF_EXPONENT = 1.6f

    /** Amplitude of one seeded rim harmonic, as a fraction of a seamount's radius. */
    private const val RIM_HARMONIC_MIN = 0.015f
    private const val RIM_HARMONIC_SPAN = 0.03f

    /** How many harmonics a rim carries: the second, third and fifth. See [rimHarmonics]. */
    private const val RIM_HARMONICS = 3

    /**
     * The seamounts' size noise's wavelength on the ground, in kilometers: 1,333.3 km, the 9
     * cycles round the 12,000 km world it was set as, so neighbouring vents in a chain,
     * [TectonicsConfig.hotspotSpacingKm] apart, are sized alike and chains a plate apart are not;
     * one volcano against the next differs by [volcanoSizeShare] on top of it.
     */
    private const val SEAMOUNT_SIZE_WAVELENGTH_KM = 1_333.3

    /**
     * The along-strike modulation of a volcanic arc: a wavelength of [ARC_CHAIN_WAVELENGTH_KM],
     * 461.5 km, the 26 cycles round the 12,000 km world it was set as, raised to
     * [ARC_CHAIN_EXPONENT] and scaled to [ARC_CHAIN_PEAK].
     *
     * Sampled fine and raised to a power, so the arc is a row of separate cones rather than a
     * continuous wall of one height. The peak above 1 is what lets the tallest cones in a chain
     * stand above the arc's nominal crest.
     */
    private const val ARC_CHAIN_WAVELENGTH_KM = 461.5
    private const val ARC_CHAIN_EXPONENT = 2.5f
    private const val ARC_CHAIN_PEAK = 1.6f

    /**
     * The least convergence a boundary is given credit for, so that a nearly transform pair still
     * leaves a trace rather than vanishing where its relief would round to nothing.
     */
    private const val MIN_BOUNDARY_STRENGTH = 0.12f

    /**
     * The per-cell roughness that breaks a belt's crest into peaks, between these two, at a
     * longest wavelength of [ROUGHNESS_WAVELENGTH_KM], 1,000 km, the 12 cycles round the
     * 12,000 km world it was set as. Centered a little below 1 so it takes as much off a crest as
     * it adds.
     */
    private const val ROUGHNESS_MIN = 0.75f
    private const val ROUGHNESS_SPAN = 0.5f
    private const val ROUGHNESS_WAVELENGTH_KM = 1_000.0

    /** How much of the full ridge roughness a plateau feels: a plain at altitude, not a range. */
    private const val PLATEAU_ROUGHNESS_DAMPING = 0.7f

    /**
     * The exponent that makes an island arc sag between its volcanoes.
     *
     * Harder than the 1.6 a mountain belt gets: an arc is built on oceanic crust and is meant to
     * stay mostly under water, so only its swells should clear the surface.
     */
    private const val ARC_SAG_EXPONENT = 3f

    /**
     * What the overriding plate's belt keeps of [TectonicsConfig.mountainHeight] where the crusts
     * are mixed, in the one-profile control. Only [TectonicsConfig.crustPairProfiles] off reads it.
     */
    private const val OVERRIDING_BELT_SHARE = 0.8f

    /** For [localSpread], which squares an altitude and so must not do it in metres. */
    private const val METRES_PER_KILOMETRE = 1_000f
}
