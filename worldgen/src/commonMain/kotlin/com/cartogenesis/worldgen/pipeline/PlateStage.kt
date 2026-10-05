package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.math.LongMinHeap
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

@Serializable
data class Plate(
    val id: Int,
    val seedX: Int,
    val seedY: Int,
    val driftX: Float,
    val driftY: Float,
    val type: PlateType
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
     * and the deep sea was a set of flat plate polygons. Continental cells carry an age too; it
     * simply does nothing there, because their altitude is their crust's business. See
     * [PlateStage.seafloorAgeOf], which is also where the rate comes from.
     */
    val seafloorAgeMyr: FloatField,
    val seafloorHalfSpreadingRateKmPerMyr: Double,
    /**
     * How much of each cell's crust is continental rather than oceanic, 0 to 1 — which is what
     * decides the altitude the cell floats at before anything is stamped on it.
     *
     * Not a label but a mixture, because a continental margin is a mixture: crust stretched and
     * thinned on its way out to the ocean floor. The field is 1 over a continental plate's
     * interior and 0 over an oceanic one, blurred across the boundary by the same radius the plate
     * base was blurred by before S2, and the band between is the shelf, the slope and the rise.
     * [Isostasy.Columns] turns it into metres.
     */
    val continentalShare: FloatField,
    /**
     * How fast the rock is still rising under each cell, in millimetres a year — zero everywhere
     * except on the belts of the *present* epoch.
     *
     * The other half of what retires H1's decay factors. An old belt is low because its uplift
     * stopped and erosion went on, so a past epoch's belt is stamped and then left alone while a
     * present one goes on being pushed up through every hydraulic round. See
     * [com.cartogenesis.worldgen.model.TectonicsConfig.collisionUpliftMmPerYear] and
     * `HydraulicErosion.apply`.
     */
    val upliftRateMmPerYear: FloatField,
    /**
     * How long ago the crust under each cell was last built, from 0 (an active belt of the present
     * epoch) to 1 (cratonic ground no epoch in the history ever deformed).
     *
     * The bands are unambiguous by construction: with `historyEpochs` epochs, a cell whose most
     * recent orogeny was `n` epochs ago lands in `[n/K, (n+1)/K)`, and only untouched crust reads
     * exactly 1. That is what lets a consumer tell an Appalachian belt from an Alpine one without
     * measuring either, and it is why this is an *age* rather than an erodibility: what a shield,
     * a worn orogen and an active one should erode like is the lithology stage's question, not
     * this one's. See docs/DESIGN_LEDGER.md, H1 and H3.
     */
    val crustAge: FloatField
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

    fun generate(config: WorldGenConfig, terrain: TerrainResult): PlateResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val cellWidths = BeltCellWidths.of(config)

        val drawn = drawPlates(config)
        val plates = drawn.plates

        // The history: [TectonicsConfig.historyEpochs] configurations of the same plates, stamped
        // oldest first so the modern belts lie over the worn ones rather than under them. Only the
        // present epoch's assignment, distances and classes leave this function; the past epochs
        // leave nothing behind but the ground they built and their mark on `crustAge`.
        val epochs = tectonics.historyEpochs.coerceAtLeast(1)
        val noise = BeltNoise(config.seed)
        val uplift = FloatField(cellsAcross, cellsDown)
        val crustAge = FloatField(cellsAcross, cellsDown)
        crustAge.data.fill(1f)
        val upliftRate = FloatField(cellsAcross, cellsDown)

        var plateId = IntArray(0)
        var presentDistanceCells = FloatArray(0)
        var nearestBoundaryType = IntArray(0)
        var nearestBoundaryClass = IntArray(0)
        // Where the present epoch's sea floor is being made, for [seafloorAgeMyr].
        var presentRidgeCells: List<Int> = emptyList()

        for (epoch in 0 until epochs) {
            val epochsAgo = epochs - 1 - epoch
            val present = epochsAgo == 0

            // Minus the drift, times the age: where these plates came from, not where they are
            // headed. The crusts themselves do not change — a continent does not become an ocean
            // floor between epochs — but which pairs meet, and how squarely, does.
            val epochPlates =
                if (present) {
                    plates
                } else {
                    displacedPlates(config, plates, cellWidths.epochDriftCells * epochsAgo)
                }
            val epochPlateId = if (present) drawn.plateId else assignPlates(config, epochPlates)
            val boundaries = classifyBoundaries(config, epochPlateId, epochPlates)
            // Segmentation is a live rift's structure. A failed one is a filled sag (see the
            // CONTINENTAL_RIFT arm of [stampEpoch]), so only the present epoch is walked — which
            // also keeps the present segments, and their guard's figures, exactly as they were.
            if (present) segmentRifts(config, boundaries, epochPlates)

            val epochDistance = boundaryDistance(config, boundaries.keys)
            val epochDistanceCells = epochDistance.distanceCellWidths
            val nearestBoundaryCell = epochDistance.nearestBoundaryCell
            val hasBoundaries = boundaries.isNotEmpty()

            if (present) {
                plateId = epochPlateId
                presentDistanceCells = epochDistanceCells
                nearestBoundaryType = IntArray(cellsAcross * cellsDown) { -1 }
                nearestBoundaryClass = IntArray(cellsAcross * cellsDown) { -1 }
                // In cell order, not the map's iteration order, so the age field below is the
                // same field however the boundary map happens to be walked.
                presentRidgeCells = boundaries.entries
                    .filter { it.value.interaction.pairClass == BoundaryClass.OCEAN_RIDGE }
                    .map { it.key }
                    .sorted()
            }
            if (!hasBoundaries) continue

            // Ageing, by the time since the belt stopped rising rather than by a factor per epoch.
            // A dead orogen decays exponentially toward its foreland, so an epoch of silence costs
            // it `exp(-epochLength / decayTime)` of its height and two epochs the square of that;
            // both figures are Earth's and both are in `TectonicsConfig`. Every factor is still
            // exactly 1 on the present epoch — `exp(0)` is 1.0 to the last bit — and the blur is
            // skipped outright, so a one-epoch history is the arithmetic of the generator that had
            // no history at all.
            val ageHeightFactor = beltAgeDecay(tectonics, epochsAgo)
            val epochCellWidths =
                if (present) cellWidths else cellWidths.widened(tectonics.beltAgeWidening.pow(epochsAgo))
            val epochUplift = if (present) uplift else FloatField(cellsAcross, cellsDown)

            stampEpoch(
                config = config,
                cellWidths = epochCellWidths,
                noise = noise,
                boundaries = boundaries,
                nearestBoundaryCell = nearestBoundaryCell,
                distanceCells = epochDistanceCells,
                plateId = epochPlateId,
                uplift = epochUplift,
                crustAge = crustAge,
                ageHeightFactor = ageHeightFactor,
                epochsAgo = epochsAgo,
                epochs = epochs,
                nearestBoundaryType = if (present) nearestBoundaryType else null,
                nearestBoundaryClass = if (present) nearestBoundaryClass else null,
                // Only the present epoch's belts are still rising; that is what "old" means here.
                upliftRate = if (present) upliftRate else null
            )

            if (!present) {
                roundWithAge(config, epochUplift, epochsAgo)
                for (cell in uplift.data.indices) uplift.data[cell] += epochUplift.data[cell]
            }
        }

        // Which crust each cell is made of, blurred across the plate boundary. The blur is not
        // cosmetic: the band it makes is a continental margin, crust thinned on its way out to the
        // ocean floor, and it is what [Isostasy.Columns] turns into a shelf, a slope and a rise.
        val continentalShare = FloatField(cellsAcross, cellsDown)
        for (cell in continentalShare.data.indices) {
            continentalShare.data[cell] =
                if (plates[plateId[cell]].type == PlateType.CONTINENTAL) 1f else 0f
        }
        blurAcrossTheMargin(config, continentalShare)
        roughenMargins(config, continentalShare)

        stampHotspotChains(config, plates, plateId, uplift)

        val scale = config.scale
        val columns = Isostasy.Columns(config.isostasy)
        val isostatic = config.isostasy.enabled
        val elevationLimit = Limit(tectonics.elevationLimitKneeMetres, scale.highestLandMetres)
        val beltReliefMetres = tectonics.beltReliefMetres
        val marginReliefMetres = tectonics.marginReliefStandardDeviationMetres
        val cratonReliefMetres = tectonics.cratonReliefStandardDeviationMetres
        val oceanicReliefMetres = tectonics.oceanicReliefStandardDeviationMetres
        val orogenReliefMetres = tectonics.orogenReliefStandardDeviationMetres
        val crustAgeReference = tectonics.crustAgeReference.coerceAtLeast(1e-6f)
        val height = FloatField(cellsAcross, cellsDown)

        // How old the sea floor is under each cell, which is what decides how deep it lies. See
        // [seafloorAgeOf].
        val seafloor = seafloorAgeOf(config, columns, continentalShare, presentRidgeCells)
        val seafloorAgeMyr = seafloor.ageMyr

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
        // craton. Both the crust's thickness and the relief it carries are read off it. With
        // isostasy off there is no crust to have a profile — one datum, one relief — which is the
        // world before S2 and the control this chunk's guards are shown to fail against.
        val interiorShare =
            if (isostatic) cratonInteriorShare(config, continentalShare)
            else FloatArray(cellsAcross * cellsDown)
        // Mass-neutral: the average continental column keeps the standard thickness, so the datum
        // stays where `IsostasyConfig.continentalFreeboardMetres` puts it.
        val meanInteriorShare = weightedMean(interiorShare, continentalShare.data)
        val cratonThickeningKm = if (isostatic) config.isostasy.cratonThickeningKm else 0f

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
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val share = continentalShare.data[cell]
                    // Where the crust floats: its own thickness plus the cratonic profile, which
                    // tilts a continent up in the middle without moving the average column.
                    val thickeningKm =
                        cratonThickeningKm * (interiorShare[cell] - meanInteriorShare)
                    datumMetres[cell] =
                        if (isostatic) {
                            columns.altitudeMetres(share, seafloorAgeMyr[cell], thickeningKm)
                        } else {
                            0f
                        }
                    // How hard the present epoch worked here, which is what makes an orogen
                    // rougher than the plain it stands on and leaves the foreland alone.
                    val orogenShare =
                        (uplift.data[cell] / crustAgeReference).coerceIn(0f, 1f)
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
                    shapeMetres[cell] = shapeNoise.data[cell] * reliefMetres +
                        (uplift.data[cell] + detail) * beltReliefMetres
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

        // Second pass: the texture, and the field.
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    // Ahnert's line where there is relief to spend it on, and a landscape that
                    // stays flat where there is not: see
                    // `TectonicsConfig.textureReliefThresholdMetres`.
                    val relief = localReliefMetres[cell]
                    val dissected = relief * relief / (relief + textureReliefThresholdMetres)
                    val textureMetres = textureNoise[cell] * dissected * textureShareOfRelief
                    height.data[cell] =
                        scale.fieldAtAltitude(
                            elevationLimit.applyTo(
                                datumMetres[cell] + shapeMetres[cell] + textureMetres
                            )
                        )
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
            seafloorAgeMyr = FloatField(cellsAcross, cellsDown, seafloor.ageMyr),
            seafloorHalfSpreadingRateKmPerMyr = seafloor.halfSpreadingRateKmPerMyr,
            upliftRateMmPerYear = upliftRate,
            crustAge = crustAge
        )
    }

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

    /**
     * How long ago the sea floor under each cell was made, and how fast the ridges that made it
     * are spreading.
     */
    internal class SeafloorAge(
        /** Millions of years, one entry per cell; the reference age where there is no ridge. */
        val ageMyr: FloatArray,
        /** Solved rather than declared — see [seafloorAgeOf]. Kilometres per million years. */
        val halfSpreadingRateKmPerMyr: Double,
        /** How many cells of the present epoch's boundaries are spreading ridges. */
        val ridgeCells: Int
    )

    /**
     * How long ago the sea floor under each cell was made, in millions of years.
     *
     * Ocean floor is a conveyor. It is made at a spreading ridge, carried away from it, cools and
     * contracts as it goes, and is destroyed at a trench — so its depth is a function of its age
     * and of very little else, which is the most robust relationship in marine geophysics (Parsons
     * & Sclater 1977). Distance to the nearest ridge over the spreading rate is that age, and it is
     * the field this generator was missing: without it every piece of sea floor was of one age, the
     * deep ocean stood at one level per plate, and the Voronoi partition showed straight through
     * the bathymetry as flat polygons meeting at triple junctions.
     *
     * Measured to the *present* epoch's spreading boundaries only. A ridge that closed three
     * hundred million years ago is not making floor now and the floor it made has been subducted;
     * what a past epoch leaves on this map is a belt, not a basin.
     *
     * **The rate is solved, not declared, and that is the interesting part.** Floor is destroyed as
     * fast as it is made, so the mean age of a planet's sea floor is its ocean's area over its
     * ridges' production — a world with less ridge for its ocean must spread faster, or its floor
     * would be older than the planet. This map has about half Earth's ridge for its ocean, because
     * fourteen plates on a cylinder put most of their boundaries between crusts that are not both
     * oceanic. So the rate this world spreads at is not an Earth figure to be copied; what *is* an
     * Earth figure, and what the map can be held to, is how deep the floor ends up
     * ([IsostasyConfig.oceanicMeanFloorMetres]). The rate is whatever puts the mean there, found by
     * bisection on a histogram of the distances, and it is reported rather than hidden — see
     * `IsostasyTest` and `TODO.md`, which record it running well above Earth's fastest ridge and
     * why.
     *
     * Where a world has no spreading ridge at all — one plate, or every boundary convergent —
     * every cell takes the age that floats at that same mean depth, which is the one-age ocean
     * S2's first pass drew and the control the depth-age guards are shown to fail against.
     */
    internal fun seafloorAgeOf(
        config: WorldGenConfig,
        columns: Isostasy.Columns,
        continentalShare: FloatField,
        ridgeCells: List<Int>
    ): SeafloorAge {
        val cellCount = config.width * config.height
        val isostasy = config.isostasy
        val targetDepthMetres = isostasy.oceanicMeanFloorMetres
        val referenceAge = columns.seafloorAgeAtDepth(targetDepthMetres)
        if (!isostasy.seafloorAge || ridgeCells.isEmpty()) {
            return SeafloorAge(FloatArray(cellCount) { referenceAge }, 0.0, ridgeCells.size)
        }

        // In cell widths and told how tall a row is, so that a ridge running east-west ages its
        // floor over the same kilometres as one running north-south. Counted in plain cells, the
        // age-depth curve would read the orientation of the ridge that made a piece of floor as if
        // it were the floor's age.
        val distanceCellWidths = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearestRidgeCell = IntArray(cellCount) { -1 }
        ridgeCells.forEach { cell ->
            distanceCellWidths[cell] = 0f
            nearestRidgeCell[cell] = cell
        }
        JumpFloodDistance.run(
            config.width, config.height, distanceCellWidths, nearestRidgeCell,
            config.cellHeightInCellWidths
        )

        val kilometresPerCellWidth = config.cellWidthKm
        val oldest = isostasy.oldestSeafloorAgeMyr

        // The distances of the cells the answer is about, in one histogram, so the bisection below
        // costs a few thousand operations rather than a few hundred million at 4096. Only the
        // cells that are more oceanic than continental count: a continental platform's altitude is
        // its crust's business and no age changes it, and letting it into the mean would make the
        // solved rate depend on how much of the map happens to be land.
        var longestKm = 0.0
        for (cell in 0 until cellCount) {
            if (continentalShare.data[cell] >= 0.5f) continue
            val cellWidths = distanceCellWidths[cell]
            if (cellWidths >= JumpFloodDistance.INFINITE) continue
            val kilometres = cellWidths * kilometresPerCellWidth
            if (kilometres > longestKm) longestKm = kilometres
        }
        if (longestKm <= 0.0) {
            return SeafloorAge(FloatArray(cellCount) { referenceAge }, 0.0, ridgeCells.size)
        }
        val bins = LongArray(DISTANCE_HISTOGRAM_BINS)
        var oceanicCells = 0L
        for (cell in 0 until cellCount) {
            if (continentalShare.data[cell] >= 0.5f) continue
            val cellWidths = distanceCellWidths[cell]
            if (cellWidths >= JumpFloodDistance.INFINITE) continue
            val bin =
                ((cellWidths * kilometresPerCellWidth / longestKm) * (DISTANCE_HISTOGRAM_BINS - 1))
                    .toInt().coerceIn(0, DISTANCE_HISTOGRAM_BINS - 1)
            bins[bin]++
            oceanicCells++
        }
        if (oceanicCells == 0L) {
            return SeafloorAge(FloatArray(cellCount) { referenceAge }, 0.0, ridgeCells.size)
        }

        fun meanDepthAt(rateKmPerMyr: Double): Double {
            var sum = 0.0
            for (bin in bins.indices) {
                if (bins[bin] == 0L) continue
                val kilometres = longestKm * bin / (DISTANCE_HISTOGRAM_BINS - 1)
                val age = (kilometres / rateKmPerMyr).toFloat().coerceAtMost(oldest)
                sum += -columns.seafloorDepthMetres(age).toDouble() * bins[bin]
            }
            return sum / oceanicCells
        }

        // Deeper the slower it spreads, so the mean depth falls monotonically with the rate and a
        // bisection cannot miss. The bounds are a millimetre a year and ten metres a year, which
        // bracket anything a plate partition can ask for; forty halvings settle the rate to a part
        // in ten thousand, which is far finer than the depth curve's own fit.
        var slow = SLOWEST_HALF_RATE_KM_PER_MYR
        var fast = FASTEST_HALF_RATE_KM_PER_MYR
        repeat(RATE_BISECTIONS) {
            val middle = 0.5 * (slow + fast)
            if (meanDepthAt(middle) > targetDepthMetres) slow = middle else fast = middle
        }
        val rate = 0.5 * (slow + fast)

        val ageMyr = FloatArray(cellCount) { cell ->
            val cellWidths = distanceCellWidths[cell]
            if (cellWidths >= JumpFloodDistance.INFINITE) referenceAge
            else (cellWidths * kilometresPerCellWidth / rate).toFloat().coerceAtMost(oldest)
        }
        return SeafloorAge(ageMyr, rate, ridgeCells.size)
    }

    /** Bins the distance histogram the spreading rate is solved on carries. */
    private const val DISTANCE_HISTOGRAM_BINS = 512

    /** The rate bisection's bracket, in kilometres per million years, and its depth. */
    private const val SLOWEST_HALF_RATE_KM_PER_MYR = 1.0
    private const val FASTEST_HALF_RATE_KM_PER_MYR = 10_000.0
    private const val RATE_BISECTIONS = 40

    /**
     * Breaks the crust boundary up, in place, so that a coastline standing on it is a coastline.
     *
     * The blurred share is a smooth ramp from one crust to the other, and a coastline is a contour
     * of it. A contour of a smooth ramp is a smooth curve — box-counted over three octaves it comes
     * out at dimension 1.04 to 1.10, which is Richardson's *smoothest* coast and not Britain's
     * 1.25. That was the first thing S2 broke and the first thing it had to put back: before
     * isostasy the coastline was a percentile of the terrain noise and inherited the noise's
     * fractal shape, and once the crust decides where the water stands the crust has to carry that
     * shape instead.
     *
     * Which it should. A rifted margin is not a smooth curve on Earth either: it is offset by
     * transform faults every few hundred kilometres, embayed where the rift arms failed, and
     * cut into banks and troughs by the sediment that has poured off it since. So the share gets a
     * few octaves of noise, and the noise is windowed by `4 s (1 - s)` — nothing at all where the
     * crust is one thing or the other, everything in the band between — which keeps a continental
     * interior at exactly the level its column floats at and lets the margin wander.
     *
     * The wavelength is a length on the ground rather than a count of cells, as every other noise
     * in this file is, so a margin has the same shape at any grid with more of its octaves
     * resolved and on a planet of any size, and on a lattice square on the ground, so it wanders
     * as far north-south as east-west.
     */
    private fun roughenMargins(config: WorldGenConfig, continentalShare: FloatField) {
        val roughness = config.tectonics.marginRoughness
        if (roughness <= 0f) return
        val cellsAcross = config.width
        val cellsDown = config.height
        val noise = PerlinNoise(config.seed * 104729 + 6199)
        val lattice = GroundLattice(config, MARGIN_WAVELENGTH_KM)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val share = continentalShare.data[cell]
                    val inTheBand = 4f * share * (1f - share)
                    if (inTheBand <= 0f) continue
                    val wander = noise.fbm(
                        lattice.x(column),
                        lattice.y(row),
                        MARGIN_OCTAVES,
                        lattice.period,
                        lattice.period
                    )
                    continentalShare.data[cell] =
                        (share + roughness * inTheBand * wander).coerceIn(0f, 1f)
                }
            }
        }
    }

    /**
     * The gravitational limit on elevation, as a curve rather than a clamp.
     *
     * Below [kneeMetres] a height passes through untouched; above it the excess is compressed
     * toward [ceilingMetres] by `span * (1 - exp(-excess / span))`, which approaches the ceiling
     * without ever reaching it. That last part is what makes it usable on a map: a clamp would
     * turn every crest above the bar into one dead-flat bench at exactly the bar, and a bench is
     * the kind of geometry this project treats as a defect. Here a crest that was 300 m above its
     * neighbour is still above it, by less.
     *
     * See [com.cartogenesis.worldgen.model.TectonicsConfig.elevationLimitKneeMetres] for where the
     * knee comes from.
     */
    internal class Limit(private val kneeMetres: Float, ceilingMetres: Float) {

        private val spanMetres = (ceilingMetres - kneeMetres).coerceAtLeast(1f)

        fun applyTo(metres: Float): Float {
            if (metres <= kneeMetres) return metres
            val excess = metres - kneeMetres
            return kneeMetres + spanMetres * (1f - exp(-excess / spanMetres))
        }

        /**
         * What share of a further metre of uplift survives at [metres] — 1 below the knee, 0 at
         * the ceiling, straight between.
         *
         * A taper rather than [applyTo] because the hydraulic rounds add uplift over and over, and
         * a curve applied twelve times is not the curve applied once. Tapering the increment
         * instead is stable however many rounds run, and it is the same statement: a range near
         * its gravitational limit does not go on rising.
         */
        fun upliftShareAt(metres: Float): Float =
            ((kneeMetres + spanMetres - metres) / spanMetres).coerceIn(0f, 1f)
    }

    /**
     * What a belt keeps of its height after [epochsAgo] epochs with no uplift under it.
     *
     * `exp(-t / tau)` with `t` the time since it stopped and `tau` the decay time of an unforced
     * orogen — both figures in `TectonicsConfig`, both Earth's, and their ratio is why this comes
     * out at 0.42 an epoch where H1's factor was 0.45. Exactly 1 for the present epoch.
     */
    internal fun beltAgeDecay(tectonics: TectonicsConfig, epochsAgo: Int): Float {
        if (epochsAgo <= 0) return 1f
        val decayTime = tectonics.orogenDecayTimeYears.coerceAtLeast(1.0)
        return exp(-epochsAgo * tectonics.epochLengthYears / decayTime).toFloat()
    }

    /**
     * Where one epoch's boundaries were, and what crust pair each cell's nearest one was — the two
     * fields [generate] keeps for the present epoch and throws away for the past ones.
     *
     * Exists for the guard. An old belt can only be shown to be lower and broader than a young one
     * if both are measured the same way, and the way `BoundaryPairTest` measures a belt is a mean
     * radial profile away from the boundary that built it; for a past epoch that boundary is gone
     * by the time the stage returns. Recomputing it costs one epoch's assignment and one distance
     * field, which is cheaper and far less misleading than adding a field to [PlateResult] that
     * nothing in the pipeline would ever read.
     */
    internal class EpochBoundaries(
        /** Cell widths on the ground to the nearest boundary of that epoch. */
        val distanceCells: FloatArray,
        /** [BoundaryClass] ordinal of that nearest boundary, or -1 where there was none. */
        val nearestBoundaryClass: IntArray
    )

    internal fun epochBoundaries(config: WorldGenConfig, epochsAgo: Int): EpochBoundaries {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val drawn = drawPlates(config)
        val epochPlates =
            if (epochsAgo == 0) drawn.plates
            else displacedPlates(config, drawn.plates, config.cellsFor(config.tectonics.epochDriftKm) * epochsAgo)
        val plateId = if (epochsAgo == 0) drawn.plateId else assignPlates(config, epochPlates)
        val boundaries = classifyBoundaries(config, plateId, epochPlates)

        val distance = boundaryDistance(config, boundaries.keys)
        val distanceCells = distance.distanceCellWidths
        val nearestBoundaryCell = distance.nearestBoundaryCell

        val nearestBoundaryClass = IntArray(cellsAcross * cellsDown) { -1 }
        for (cell in nearestBoundaryClass.indices) {
            val boundary = boundaries[nearestBoundaryCell[cell]] ?: continue
            nearestBoundaryClass[cell] = boundary.interaction.pairClass.ordinal
        }
        return EpochBoundaries(distanceCells, nearestBoundaryClass)
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
     * Blurs a map of which crust each cell is made of across the boundary between the two, in
     * place: the band this makes is the continental margin [Isostasy.Columns] turns into a shelf,
     * a slope and a rise.
     *
     * A Gaussian round on the ground, so a straight crust edge becomes the same ramp whichever way
     * it runs, and its spread is the one that makes the ramp's 10-90% width
     * `TectonicsConfig.crustMarginKm`: a step convolved with a Gaussian is an error function, which
     * climbs from a tenth to nine tenths over [TEN_TO_NINETY_IN_STANDARD_DEVIATIONS] standard
     * deviations. The box blur this replaced took one radius in cells for both axes, so its ramp was
     * 389 km wide east-west and 195 km north-south against the 300 declared, square in support
     * and faintly diagonal in shape. See docs/DESIGN_LEDGER.md, Fix 2.
     *
     * Internal so `TectonicGroundTest` can measure the margin's width on edges it lays where it
     * likes.
     */
    internal fun blurAcrossTheMargin(config: WorldGenConfig, crust: FloatField) {
        val spreadKm = config.tectonics.crustMarginKm / TEN_TO_NINETY_IN_STANDARD_DEVIATIONS
        GaussianBlur.apply(crust, spreadKm / config.cellWidthKm, spreadKm / config.cellHeightKm)
    }

    /**
     * How many standard deviations of a Gaussian an error-function ramp takes to climb from a tenth
     * to nine tenths: twice the normal quantile at 0.9, `2 * 1.2815515655446004`.
     */
    private const val TEN_TO_NINETY_IN_STANDARD_DEVIATIONS = 2.5631031310892008

    /**
     * Every plate seed carried back along minus its own drift, by [distanceCellWidths] of ground.
     *
     * A drift is a direction on the ground, drawn at a uniform angle, so the same distance is a
     * whole column per cell width east-west and two rows per cell width north-south on this map's
     * cells; carried back in cells both ways, a plate drifting north travelled half as far as one
     * drifting east.
     *
     * X wraps, because the world is a cylinder. Y clamps, because it is not: a plate whose drift
     * points at a pole was, far enough back, at the pole and no further, and a seed off the edge
     * of the grid has no Voronoi cell to own. Two seeds clamped onto the same cell is harmless —
     * the later id simply takes the cell and the earlier plate has no region in that epoch, which
     * is a plate that had not yet rifted away from its neighbour.
     */
    internal fun displacedPlates(
        config: WorldGenConfig,
        plates: List<Plate>,
        distanceCellWidths: Float
    ): List<Plate> = plates.map { plate ->
        val width = config.width
        val height = config.height
        val distanceRows = distanceCellWidths / config.cellHeightInCellWidths.toFloat()
        var wrappedSeedX = (plate.seedX - plate.driftX * distanceCellWidths).roundToInt() % width
        if (wrappedSeedX < 0) wrappedSeedX += width
        val clampedSeedY = (plate.seedY - plate.driftY * distanceRows).roundToInt().coerceIn(0, height - 1)
        plate.copy(seedX = wrappedSeedX, seedY = clampedSeedY)
    }

    /**
     * Rounds one past epoch's uplift, in place: what was stamped with a crest and a toe comes out as
     * a swell, the more so the older it is.
     *
     * Internal so `TectonicGroundTest` can measure the kernel's spread on the ground.
     */
    internal fun roundWithAge(config: WorldGenConfig, uplift: FloatField, epochsAgo: Int) {
        // A Gaussian round on the ground with the spread two box passes of the setting's radius
        // have east-west, `sqrt(2 r (r + 1) / 3)` cell widths: the same rounding the belts were
        // measured with along a row, and now as far down a column, where the square window in cells
        // rounded half as far on the ground and cut its support off in a rectangle.
        val radiusCellWidths = config.cellsFor(config.tectonics.beltAgeBlurKm).toDouble() * epochsAgo
        val spreadCellWidths = sqrt(2.0 * radiusCellWidths * (radiusCellWidths + 1.0) / 3.0)
        GaussianBlur.apply(uplift, spreadCellWidths, spreadCellWidths / config.cellHeightInCellWidths)
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
        val epochDriftCells: Float,
        val hotspotChainLengthCells: Float,
        val hotspotSpacingCells: Float,
        val hotspotRadiusCells: Float
    ) {
        /**
         * Every belt half-width, offset and reach multiplied by [factor] — the widening of ageing.
         *
         * Only the belts' lengths move. The heights are handled by one multiply at the point of
         * stamping, and the dimensionless shares (`plateauFlatShare`, `riftFloorShare`, the rim
         * share) describe the shape of a profile rather than its size, so a wider belt keeps the
         * same proportions. The drift, the ageing blur and the hotspots are not a belt's and stay.
         */
        fun widened(factor: Float): BeltCellWidths = copy(
            boundaryFalloffCells = boundaryFalloffCells * factor,
            andeanWidthCells = andeanWidthCells * factor,
            arcOffsetCells = arcOffsetCells * factor,
            arcWidthCells = arcWidthCells * factor,
            collisionWidthCells = collisionWidthCells * factor,
            islandArcOffsetCells = islandArcOffsetCells * factor,
            islandArcWidthCells = islandArcWidthCells * factor,
            riftWidthCells = riftWidthCells * factor,
            riftShoulderOffsetCells = riftShoulderOffsetCells * factor,
            riftShoulderWidthCells = riftShoulderWidthCells * factor
        )

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
                    epochDriftCells = config.cellsFor(tectonics.epochDriftKm),
                    hotspotChainLengthCells = config.cellsFor(tectonics.hotspotChainLengthKm),
                    hotspotSpacingCells = config.cellsFor(tectonics.hotspotSpacingKm),
                    hotspotRadiusCells = config.cellsFor(tectonics.hotspotRadiusKm)
                )
            }
        }
    }

    /**
     * One epoch's belts, stamped into [uplift] and recorded in [crustAge].
     *
     * This is the per-cell pass that used to be the body of [generate], unchanged except for two
     * things: what it writes is multiplied by [ageHeightFactor], and every cell it touches has
     * its crust age pulled down to this epoch's band. [cellWidths] is the epoch's own widened copy,
     * so every profile below reads the aged width without knowing that it has been aged.
     *
     * The age bands: with [epochs] epochs, an epoch [epochsAgo] back owns `[epochsAgo/K,
     * (epochsAgo+1)/K)`, a cell landing at the bottom of its band where the belt built it
     * outright and near the top where the belt barely reached. Untouched crust is left at 1. The
     * bands cannot overlap, so a later consumer can bucket the field without a threshold of its
     * own — see [PlateResult.crustAge].
     *
     * Rule 8 of the plan: this is per-cell arithmetic and so is the ageing blur, and both were
     * specified with a GPU path in mind. Measured first, as the spec asks — see
     * `TectonicHistoryTest.report the cost of a history`.
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
        crustAge: FloatField,
        ageHeightFactor: Float,
        epochsAgo: Int,
        epochs: Int,
        nearestBoundaryType: IntArray?,
        nearestBoundaryClass: IntArray?,
        upliftRate: FloatField?
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val ridgeNoise = noise.ridge
        val rangeNoise = noise.range
        val widthNoise = noise.width
        val arcNoise = noise.arc
        val past = epochsAgo > 0
        val ageBandBase = epochsAgo.toFloat() / epochs
        val ageBandSpan = 1f / epochs
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
                        nearestBoundaryType?.set(cell, interaction.type.ordinal)
                        nearestBoundaryClass?.set(cell, interaction.pairClass.ordinal)

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
                            uplift.data[cell] += upliftHere * ageHeightFactor
                            recordCrustAge(crustAge, cell, upliftHere, tectonics, ageBandBase, ageBandSpan)
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
                            BoundaryClass.ANDEAN_MARGIN ->
                                if (!boundary.overridingSide) {
                                    -tectonics.trenchDepth * strength * narrow
                                } else {
                                    tectonics.andeanHeight * strength *
                                        beltFalloff(
                                            distanceFromBoundary,
                                            cellWidths.andeanWidthCells * widthScale * edgeJitter
                                        ) * roughness * alongRange +
                                        tectonics.arcHeight * strength *
                                        ridgeAt(
                                            distanceFromBoundary,
                                            cellWidths.arcOffsetCells,
                                            cellWidths.arcWidthCells
                                        ) *
                                        volcanicChain(arcNoise, arcChainLattice, column, row) *
                                        alongRange
                                }

                            // Continental against continental. Neither side will go under, so the
                            // crust thickens over a wide area instead of piling onto a line: broad,
                            // high, flat-topped and very nearly symmetric.
                            BoundaryClass.COLLISION_PLATEAU -> {
                                val plateauWidth = cellWidths.collisionWidthCells * widthScale * edgeJitter
                                val rimShare = tectonics.plateauRimShare.coerceIn(0.1f, 0.95f)
                                tectonics.collisionHeight * strength *
                                    plateauFalloff(
                                        distanceFromBoundary,
                                        plateauWidth,
                                        tectonics.plateauFlatShare
                                    ) *
                                    // Damped roughness: a plateau is a plain at altitude, and the
                                    // full ridge noise would make it a mountain range again.
                                    (1f + (roughness - 1f) * PLATEAU_ROUGHNESS_DAMPING) *
                                    alongPlateau +
                                    // The rim ranges, which stand on the edge rather than in it.
                                    tectonics.plateauRimHeight * strength *
                                    ridgeAt(
                                        distanceFromBoundary,
                                        plateauWidth * rimShare,
                                        plateauWidth * (1f - rimShare)
                                    ) * roughness * alongRange
                            }

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

                            BoundaryClass.OCEAN_RIDGE ->
                                tectonics.mountainHeight * 0.22f * strength * narrow

                            // Continental crust being pulled apart: the floor drops between two
                            // rebounding shoulders. Where such a pair has ocean on one side, that
                            // side is a spreading ridge as before — the rift is what the
                            // continental crust does.
                            BoundaryClass.CONTINENTAL_RIFT ->
                                if (boundary.oceanicSide) {
                                    tectonics.mountainHeight * 0.22f * strength * narrow
                                } else if (past) {
                                    // A rift that opened in a past epoch and then stopped. It does
                                    // not stay a canyon: the fault dies, the flexural shoulders
                                    // relax, and the trough fills with its own erosion products
                                    // until what is left is a broad shallow sag — an aulacogen,
                                    // which is what the Benue trough, the Mississippi embayment
                                    // and the North Sea graben are. Unsegmented on purpose: the
                                    // half-grabens that made it a chain of deeps are exactly what
                                    // the sediment has buried.
                                    //
                                    // The floor varies along strike, by the same `roughness *
                                    // alongRange` every other belt on this map varies by, and that
                                    // is not decoration. Without it the sag is a spirit level for
                                    // the whole run of a boundary, and the outlet notch measures
                                    // the slope below a basin's lip to decide how hard the outflow
                                    // cuts: on a level floor that slope is zero, so the notch cuts
                                    // nothing however large the catchment and the trough holds a
                                    // lake for the life of the world. A varying floor gives it a
                                    // low end to drain to and sills to break it into reaches the
                                    // notch can finish. It is also what a filled sag looks like:
                                    // the Mississippi embayment and the Benue trough carry a river
                                    // down the axis, not a chain of lakes. See docs/DESIGN_LEDGER.md,
                                    // H5, for the lake this left at 2048 before it was added.
                                    -tectonics.riftDepth * tectonics.failedRiftFill * strength *
                                        roughness * alongRange *
                                        plateauFalloff(
                                            distanceFromBoundary,
                                            cellWidths.riftWidthCells,
                                            tectonics.riftFloorShare
                                        ) +
                                        tectonics.riftShoulderHeight *
                                        tectonics.failedRiftShoulder *
                                        strength * ridgeAt(
                                            distanceFromBoundary,
                                            cellWidths.riftShoulderOffsetCells,
                                            cellWidths.riftShoulderWidthCells
                                        ) * roughness * alongRange
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
                        uplift.data[cell] += upliftHere * ageHeightFactor
                        recordCrustAge(crustAge, cell, upliftHere, tectonics, ageBandBase, ageBandSpan)
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
        upliftRate: FloatField?,
        cell: Int,
        upliftHere: Float,
        pairClass: BoundaryClass,
        tectonics: TectonicsConfig
    ) {
        if (upliftRate == null || upliftHere <= 0f) return
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
     * Pulls one cell's crust age down to this epoch's band, in proportion to how hard the epoch
     * worked on it.
     *
     * A running minimum over the epochs, which is what makes the field the age of the *most
     * recent* event rather than of the first: the present epoch stamps last, so wherever a modern
     * belt overprints an old one the young age wins. A cell the epoch left untouched (a value of
     * exactly zero, which is what every cell outside every profile's reach gets) is not claimed at
     * all, so cratonic ground keeps the 1 it started with.
     */
    private fun recordCrustAge(
        crustAge: FloatField,
        cell: Int,
        upliftHere: Float,
        tectonics: TectonicsConfig,
        ageBandBase: Float,
        ageBandSpan: Float
    ) {
        if (upliftHere == 0f) return
        val influence = (abs(upliftHere) / tectonics.crustAgeReference).coerceAtMost(1f)
        val age = ageBandBase + (1f - influence) * ageBandSpan
        if (age < crustAge.data[cell]) crustAge.data[cell] = age
    }

    /**
     * Hotspots: a point fixed in the mantle that a plate drifts over, leaving its volcanoes behind
     * it as a line of seamounts that subside with age. Hawaii, the Emperor chain, Réunion.
     *
     * Nearly free, and it puts islands somewhere other than a plate boundary — which is otherwise
     * the only place this generator has anything to offer the open ocean.
     *
     * Restricted to oceanic plates, so what comes out is island chains in deep water rather than
     * volcanic fields inland, and clipped to the carrying plate, so a trail stops at the boundary
     * instead of running on across a neighbour that never passed over the hotspot.
     *
     * Determinism: the plates are walked in id order and all three draws are taken for every plate
     * whether or not it ends up carrying one, so the sequence does not depend on the outcome of any
     * test — and never on a hash order. Each stamped seamount also gets a running index, walked in
     * the same fixed order, which seeds its own rim modulation (see [stampSeamount]) — again never
     * from a hash order, and never from the [Random] shared by the placement draws above, so tuning
     * one does not reseed the other.
     */
    private fun stampHotspotChains(
        config: WorldGenConfig,
        plates: List<Plate>,
        plateId: IntArray,
        uplift: FloatField
    ) {
        val tectonics = config.tectonics
        val cellWidths = BeltCellWidths.of(config)
        if (tectonics.hotspotPlateFraction <= 0f || tectonics.hotspotHeight == 0f) return
        if (cellWidths.hotspotRadiusCells <= 0f || cellWidths.hotspotSpacingCells <= 0f) return

        val random = Random(config.seed * 31337 + 7)
        val sizeNoise = PerlinNoise(config.seed * 104729 + 4441)
        val sizeLattice = GroundLattice(config, SEAMOUNT_SIZE_WAVELENGTH_KM)
        // A chain's lengths are in cell widths of ground, so a step north or south of one is this
        // many rows.
        val rowsPerCellWidth = (1.0 / config.cellHeightInCellWidths).toFloat()
        var ventIndex = 0

        plates.forEach { plate ->
            val roll = random.nextFloat()
            // Offset from the plate's own seed point, not a free point on the map. A hotspot
            // placed anywhere at all lands on some other plate nineteen times in twenty, and the
            // clip to the carrying plate in [stampSeamount] then erases the whole chain — which is
            // exactly what the first version of this did, on every seed tried.
            val spreadCellWidths = cellWidths.hotspotChainLengthCells * 0.3f
            val originX = plate.seedX + (random.nextFloat() - 0.5f) * spreadCellWidths
            val originY = plate.seedY + (random.nextFloat() - 0.5f) * spreadCellWidths * rowsPerCellWidth
            if (plate.type != PlateType.OCEANIC) return@forEach
            if (roll >= tectonics.hotspotPlateFraction) return@forEach

            var travelledCells = 0f
            while (travelledCells <= cellWidths.hotspotChainLengthCells) {
                // The hotspot stays put and the plate slides over it, so the volcano it built a
                // while ago has since been carried a while along the drift vector. Older means
                // further along, and lower: the crust cools and the seamount subsides with it.
                val ventX = originX + plate.driftX * travelledCells
                val ventY = originY + plate.driftY * travelledCells * rowsPerCellWidth
                val ageAlongChain = travelledCells / cellWidths.hotspotChainLengthCells
                val sizeJitter = 0.6f + 0.8f * (0.5f + 0.5f * sizeNoise.fbm(
                    sizeLattice.x(ventX), sizeLattice.y(ventY), 2, sizeLattice.period, sizeLattice.period
                )).coerceIn(0f, 1f)
                stampSeamount(
                    uplift = uplift,
                    cellHeightInCellWidths = config.cellHeightInCellWidths.toFloat(),
                    plateId = plateId,
                    plate = plate.id,
                    ventX = ventX,
                    ventY = ventY,
                    radius = cellWidths.hotspotRadiusCells,
                    // The crust cools and the seamount subsides with it, as the square of age.
                    amplitude = tectonics.hotspotHeight *
                        (1f - ageAlongChain) * (1f - ageAlongChain) * sizeJitter,
                    seed = config.seed,
                    ventIndex = ventIndex,
                    detail = tectonics.hotspotConeDetail
                )
                ventIndex++
                travelledCells += cellWidths.hotspotSpacingCells
            }
        }
    }

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
    private fun stampSeamount(
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

    /** The present epoch's plates and the cells they own. */
    internal class DrawnPlates(val plates: List<Plate>, val plateId: IntArray)

    /**
     * The order the crusts are handed out in, where each plate's seed sits, and the stream both
     * came out of, so the caller can go on drawing from it.
     *
     * Separated from [drawPlates] so that a guard can ask where the seeds of a world land at a
     * grid it has no time to generate; nothing else about a plate is settled here.
     */
    internal class PlateSeedDraw(
        val shuffledOrder: MutableList<Int>,
        val seeds: List<Plate>,
        val random: Random
    )

    /**
     * Where each plate's seed sits: drawn on the reference grid the world is defined on, and put
     * on the working grid as a fraction of it.
     *
     * Drawn against the working grid instead — `nextInt(width)` and `nextInt(band)` — the column
     * happened to hold, because Kotlin's `nextInt` takes the generator's top bits when the bound
     * is a power of two and so lands at the same fraction of a 512-wide grid and of a 2048-wide
     * one. The row's bound is the height less the pole margins and never a power of two, so it
     * took the remainder of a division that changes with the bound: every plate sat at a different
     * latitude at every size, and an export was a different world from the preview it came from
     * (docs/DESIGN_LEDGER.md, F35).
     *
     * The repair is to stop asking the working grid where a seed may go. How many cells a world is
     * cut into says nothing about where its plates are, so the draw is made once against
     * [SEED_REFERENCE_CELLS] and the cell it names is then read as a fraction — which is what a
     * finer grid resolves rather than redefines. The draws themselves are unchanged, in the same
     * order and the same number, so the world the reference grid has always made is the world it
     * still makes, and every other grid is now that same world drawn finer.
     */
    internal fun drawPlateSeeds(config: WorldGenConfig): PlateSeedDraw {
        val width = config.width
        val height = config.height
        val random = Random(config.seed * 7919 + 13)
        val plateCount = config.tectonics.plateCount.coerceAtLeast(2)
        val shuffledOrder = MutableList(plateCount) { it }
        shuffledOrder.shuffle(random)
        val poleMarginRows = (SEED_REFERENCE_CELLS * SEED_POLE_MARGIN_SHARE).toInt()
        val latitudeBandRows =
            (SEED_REFERENCE_CELLS * SEED_LATITUDE_SPAN).toInt().coerceAtLeast(1)
        val seeds = List(plateCount) { id ->
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val columnOnReference = random.nextInt(SEED_REFERENCE_CELLS)
            // Keeps plate seeds off the very edge, so polar rows belong to a real plate interior
            // rather than to a seed sitting on the rim of the grid.
            val rowOnReference = poleMarginRows + random.nextInt(latitudeBandRows)
            Plate(
                id = id,
                seedX = onGrid(columnOnReference, width),
                seedY = onGrid(rowOnReference, height),
                driftX = cos(angle),
                driftY = sin(angle),
                type = PlateType.OCEANIC
            )
        }
        return PlateSeedDraw(shuffledOrder, seeds, random)
    }

    /**
     * The cell of a [cellsAcrossOrDown]-cell grid that holds the middle of reference cell
     * [cellOnReference].
     *
     * The middle and not the near edge, so that a grid twice as fine puts the seed in one of the
     * two cells the coarse one splits into rather than always in the first: a seed lands within
     * half a reference cell of where the reference grid put it, at any grid, and on the reference
     * grid itself it lands in exactly that cell.
     */
    private fun onGrid(cellOnReference: Int, cellsAcrossOrDown: Int): Int =
        ((cellOnReference + 0.5f) / SEED_REFERENCE_CELLS * cellsAcrossOrDown)
            .toInt().coerceIn(0, cellsAcrossOrDown - 1)

    /**
     * The plates, their drifts, which of them are continental, and which cells each owns.
     *
     * The crusts are chosen last and by *area*, which is what makes the ocean-coverage slider a
     * statement about the crust rather than about a histogram. `WorldGenConfig.seaLevel` asks for a
     * share of the world under water; `TectonicsConfig.continentalCrustSubmergedShare` says how
     * much of a continent stands under water anyway; between them they name a share of the map
     * that has to be continental crust, and plates are taken in a shuffled order until their
     * Voronoi cells add up to it.
     *
     * Taking them by area rather than by count is the point of doing it here rather than in the
     * draw. Fourteen Voronoi cells on a warped lattice are not the same size — the largest is
     * routinely three times the smallest — so "55% of the plates are oceanic" and "55% of the world
     * is oceanic crust" are different statements, and only the second one is what the slider
     * means. The order is shuffled from the world's own stream, so which plates end up continental
     * is still the seed's business and not the geometry's.
     *
     * Determinism: every draw, here and in [drawPlateSeeds], is taken for every plate whether or
     * not it is used, in id order, and the shuffle runs on a list built in id order — never on a
     * hash order.
     */
    private fun drawPlates(config: WorldGenConfig): DrawnPlates {
        val tectonics = config.tectonics
        val width = config.width
        val height = config.height
        val plateCount = tectonics.plateCount.coerceAtLeast(2)

        // Seeds and drifts first, all oceanic for now: the assignment below reads neither the
        // types nor anything derived from them, so the partition is settled before the crusts are.
        val draw = drawPlateSeeds(config)
        val order = draw.shuffledOrder
        val drawnSeeds = draw.seeds
        val random = draw.random
        val plateId = assignPlates(config, drawnSeeds)

        val cellsPerPlate = IntArray(plateCount)
        for (cell in plateId.indices) {
            val plate = plateId[cell]
            if (plate in 0 until plateCount) cellsPerPlate[plate]++
        }

        // What share of the map has to be continental crust for `1 - seaLevel` of it to stand
        // above water, given that some of every continent is drowned. See
        // `TectonicsConfig.continentalCrustSubmergedShare`.
        val landShare = (1f - config.seaLevel).coerceIn(0f, 1f)
        val dryShareOfContinent = (1f - tectonics.continentalCrustSubmergedShare).coerceIn(0.05f, 1f)
        val targetContinentalShare = (landShare / dryShareOfContinent).coerceIn(0f, 1f)

        // Which plates touch which, so the continents can be kept apart.
        val touches = Array(plateCount) { BooleanArray(plateCount) }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val here = plateId[row * width + column]
                val rightColumn = (column + 1) % width
                val right = plateId[row * width + rightColumn]
                if (right != here) {
                    touches[here][right] = true
                    touches[right][here] = true
                }
                if (row + 1 >= height) continue
                val below = plateId[(row + 1) * width + column]
                if (below != here) {
                    touches[here][below] = true
                    touches[below][here] = true
                }
            }
        }

        val cellCount = plateId.size.toFloat()
        val target = targetContinentalShare * cellCount
        val continental = BooleanArray(plateCount)
        var claimed = 0f

        // Continents are rafts, not a slab. Taken straight down the shuffled order, a random
        // 45% of fourteen Voronoi plates is almost always one connected mass — measured, every
        // standard seed came out with a single landmass holding 99.8% of its land, no
        // archipelago, a coastline of box dimension 1.05 where every chunk before S2 held 1.20,
        // and a flooded rift with no land bridge left in it. Earth is not like that: its
        // continental plates are separated by oceanic ones, which is what an ocean basin *is*.
        //
        // So the order is walked twice. The first pass takes only plates that touch nothing
        // already continental, which scatters the seeds of the continents across the map; the
        // second fills up to the target from what is left, which is what grows them. The result
        // is several separate landmasses with ocean between them, and their margins break into
        // islands the way a margin does.
        for (plate in order) {
            if (claimed >= target) break
            if ((0 until plateCount).any { continental[it] && touches[plate][it] }) continue
            continental[plate] = true
            claimed += cellsPerPlate[plate]
        }
        for (plate in order) {
            if (claimed >= target) break
            if (continental[plate]) continue
            continental[plate] = true
            claimed += cellsPerPlate[plate]
        }
        // The last plate taken usually overshoots. Give it back when doing so lands nearer the
        // target than keeping it, so the answer is the closest the plates can get rather than the
        // first one past the post.
        val lastTaken = order.lastOrNull { continental[it] }
        if (lastTaken != null) {
            val over = claimed - target
            if (over > cellsPerPlate[lastTaken] / 2f) continental[lastTaken] = false
        }

        val plates = drawnSeeds.map { plate ->
            if (continental[plate.id]) plate.copy(type = PlateType.CONTINENTAL) else plate
        }
        return DrawnPlates(plates, plateId)
    }

    /**
     * The nearest seed's plate at every cell, on the ground, then a noise domain-warp so boundaries
     * meander instead of looking like straight Voronoi edges.
     *
     * Still a warped partition, and rule 13 names a nearest-seed partition among the shapes it
     * rejects; what is measured of the boundaries it draws is in docs/DESIGN_LEDGER.md, Fix 2, and
     * reshaping them is its own item on the roadmap. What changed is the ruler: the partition's
     * metric is Euclid's on the ground, and the warp moves a boundary as far north-south as
     * east-west in kilometres, on a noise lattice square on the ground. Both were square in cells,
     * which on this map's cells drew every plate twice as wide as it is tall on the ground and
     * warped its boundaries twice as far and twice as tightly east-west.
     */
    private fun assignPlates(config: WorldGenConfig, plates: List<Plate>): IntArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val nearestSeedPlate = nearestSeedPartition(config, plates)

        val warp = PlateWarp(config)

        val warped = IntArray(cellsAcross * cellsDown)
        // Reads `raw`, writes its own cell of `warped` — no overlap between rows.
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val warpAcross = warp.acrossCellWidths(column, row)
                    val warpDown = warp.downRows(column, row)
                    var sourceColumn = (column + warpAcross).roundToInt() % cellsAcross
                    if (sourceColumn < 0) sourceColumn += cellsAcross
                    val sourceRow = (row + warpDown).roundToInt().coerceIn(0, cellsDown - 1)
                    warped[row * cellsAcross + column] =
                        nearestSeedPlate[sourceRow * cellsAcross + sourceColumn]
                }
            }
        }
        return warped
    }

    /**
     * Which plate's seed each cell is nearest on the ground, before the warp: Euclidean by jump
     * flooding and told how tall a row is. A chamfer walk over square cells did this before, which
     * is an octagonal metric in cells and, on this map's cells, one that measured north-south at
     * twice the ground.
     *
     * Internal so `TectonicGroundTest` can ask it about seeds it places where it likes.
     */
    internal fun nearestSeedPartition(config: WorldGenConfig, plates: List<Plate>): IntArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val distanceToSeed = FloatArray(cellsAcross * cellsDown) { JumpFloodDistance.INFINITE }
        val nearestSeedPlate = IntArray(cellsAcross * cellsDown) { -1 }
        plates.forEach { plate ->
            val cell = plate.seedY * cellsAcross + plate.seedX
            distanceToSeed[cell] = 0f
            nearestSeedPlate[cell] = plate.id
        }
        JumpFloodDistance.run(
            cellsAcross, cellsDown, distanceToSeed, nearestSeedPlate, config.cellHeightInCellWidths
        )
        return nearestSeedPlate
    }

    private fun classifyBoundaries(
        config: WorldGenConfig,
        plateId: IntArray,
        plates: List<Plate>
    ): Map<Int, Boundary> {
        val width = config.width
        val height = config.height
        val boundaries = HashMap<Int, Boundary>()
        val interactions = HashMap<Int, PairInteraction>()
        val neighbours = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )

        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                val plate = plates[plateId[cell]]
                var other: Plate? = null

                for (step in neighbours) {
                    val neighbourRow = row + step[1]
                    if (neighbourRow < 0 || neighbourRow >= height) continue
                    var neighbourColumn = (column + step[0]) % width
                    if (neighbourColumn < 0) neighbourColumn += width

                    val candidate = plates[plateId[neighbourRow * width + neighbourColumn]]
                    if (candidate.id != plate.id) {
                        other = candidate
                        break
                    }
                }

                val neighbourPlate = other ?: continue
                // The pair's key, always taken in ascending id order, so the two cells either
                // side of a boundary look up the same interaction.
                val key =
                    if (plate.id < neighbourPlate.id) plate.id * plates.size + neighbourPlate.id
                    else neighbourPlate.id * plates.size + plate.id
                val interaction = interactions.getOrPut(key) {
                    interactionOf(
                        plates[key / plates.size], plates[key % plates.size], width,
                        config.cellHeightInCellWidths
                    )
                }
                boundaries[cell] = Boundary(
                    interaction,
                    oceanicSide = plate.type == PlateType.OCEANIC,
                    overridingSide = plate.id == interaction.overridingId
                )
            }
        }
        return boundaries
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

    /** Each plate's crust in [config]'s world, by plate id, without stamping anything. */
    internal fun presentPlateTypes(config: WorldGenConfig): List<PlateType> =
        drawPlates(config).plates.map { it.type }

    /** [RiftSegmentReport] for [config]'s present epoch, without stamping anything. */
    fun presentRiftSegments(config: WorldGenConfig): RiftSegmentReport = presentRiftSegmentsWithout(config, emptySet())

    /**
     * The same, with the boundary cells in [withoutCells] taken away first: how a guard cuts a
     * rift into separate stretches, or takes one away, where the plates would take a whole world
     * to arrange it.
     */
    internal fun presentRiftSegmentsWithout(config: WorldGenConfig, withoutCells: Set<Int>): RiftSegmentReport {
        val drawn = drawPlates(config)
        val boundaries = classifyBoundaries(config, drawn.plateId, drawn.plates).toMutableMap()
        withoutCells.forEach { boundaries.remove(it) }
        val report = RiftSegmentReport()
        segmentRifts(config, boundaries, drawn.plates, report)
        return report
    }

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
     *    not move with the grid: where the rift crosses the midpoint of its two plates' seeds, read
     *    off the bisector of those seeds through the same warp that drew the boundary, or the end of
     *    the rift nearer it when the rift does not cross it. The course is measured along a
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
        val warp = PlateWarp(config)

        for (pairKey in cellsOfPair.keys.sorted()) {
            val pairCells = cellsOfPair.getValue(pairKey)
            val interaction = boundaries.getValue(pairCells[0]).interaction
            val axis = BisectorAxis(config, plates[interaction.lowId], plates[interaction.highId])
            fun bisectorKm(cell: Int): Double {
                val column = cell % config.width
                val row = cell / config.width
                val sourceEastKm = (column + 0.5 + warp.acrossCellWidths(column, row)) * cellWidthKm
                val sourceSouthKm =
                    ((row + 0.5 + warp.downRows(column, row)).coerceIn(0.0, config.height.toDouble())) *
                        config.cellHeightKm
                return axis.alongKm(sourceEastKm, sourceSouthKm)
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

    /**
     * The warp [assignPlates] bends the nearest-seed partition by: at each cell, how far away the
     * cell reads its plate from, across in cell widths and down in rows.
     */
    private class PlateWarp(config: WorldGenConfig) {
        private val warpX = PerlinNoise(config.seed * 6151 + 3)
        private val warpY = PerlinNoise(config.seed * 6151 + 9)
        private val lattice = GroundLattice(config, WARP_WAVELENGTH_KM)
        private val amplitudeCellWidths =
            config.cellsFor(config.tectonics.boundaryFalloffKm) * WARP_AMPLITUDE_IN_BELT_WIDTHS
        private val amplitudeRows = (amplitudeCellWidths / config.cellHeightInCellWidths).toFloat()

        fun acrossCellWidths(column: Int, row: Int): Float = amplitudeCellWidths *
            warpX.fbm(lattice.x(column), lattice.y(row), 4, lattice.period, lattice.period)

        fun downRows(column: Int, row: Int): Float = amplitudeRows *
            warpY.fbm(lattice.x(column), lattice.y(row), 4, lattice.period, lattice.period)
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
     * Projects the plates' relative motion onto the axis between their centres — the closest thing
     * to a boundary normal that holds for the whole shared edge.
     *
     * The axis is taken on the ground, a row counting [cellHeightInCellWidths] of a column, because
     * the drifts it is projected against are directions on the ground; taken in cells, a pair lying
     * north-south of each other was read at the wrong angle to every drift not along an axis.
     */
    private fun interactionOf(
        first: Plate,
        second: Plate,
        width: Int,
        cellHeightInCellWidths: Double
    ): PairInteraction {
        var acrossCells = (second.seedX - first.seedX).toFloat()
        // Shortest way round the cylinder, so plates either side of the seam behave sanely.
        if (acrossCells > width / 2f) acrossCells -= width
        if (acrossCells < -width / 2f) acrossCells += width
        val downCellWidths = (second.seedY - first.seedY) * cellHeightInCellWidths.toFloat()

        val separationCellWidths = sqrt(acrossCells * acrossCells + downCellWidths * downCellWidths)
            .coerceAtLeast(MIN_SEED_SEPARATION_CELLS)
        val axisAcross = acrossCells / separationCellWidths
        val axisDown = downCellWidths / separationCellWidths

        // Positive when the pair is closing.
        val convergence = (
            (first.driftX - second.driftX) * axisAcross +
                (first.driftY - second.driftY) * axisDown
            ) / 2f
        val type = when {
            convergence > CONVERGENCE_THRESHOLD -> BoundaryType.CONVERGENT
            convergence < -CONVERGENCE_THRESHOLD -> BoundaryType.DIVERGENT
            else -> BoundaryType.TRANSFORM
        }
        val bothContinental = first.type == PlateType.CONTINENTAL && second.type == PlateType.CONTINENTAL
        val bothOceanic = first.type == PlateType.OCEANIC && second.type == PlateType.OCEANIC

        // Dense oceanic crust goes under buoyant continental crust, so where the pair is mixed the
        // continent overrides and there is nothing to choose. Where both are the same, the choice
        // is genuinely arbitrary and has to be made by something that cannot vary between cells or
        // between runs: the lower id, a total order on the pair.
        val overridingId = when {
            first.type == second.type -> if (first.id < second.id) first.id else second.id
            first.type == PlateType.CONTINENTAL -> first.id
            else -> second.id
        }

        val pairClass = when (type) {
            BoundaryType.CONVERGENT -> when {
                bothContinental -> BoundaryClass.COLLISION_PLATEAU
                bothOceanic -> BoundaryClass.ISLAND_ARC
                else -> BoundaryClass.ANDEAN_MARGIN
            }
            BoundaryType.DIVERGENT ->
                if (bothOceanic) BoundaryClass.OCEAN_RIDGE else BoundaryClass.CONTINENTAL_RIFT
            BoundaryType.TRANSFORM -> BoundaryClass.TRANSFORM_FAULT
        }

        return PairInteraction(
            type = type,
            strength = abs(convergence).coerceIn(MIN_BOUNDARY_STRENGTH, 1f),
            continentalCollision = bothContinental,
            pairClass = pairClass,
            overridingId = overridingId,
            lowId = if (first.id < second.id) first.id else second.id,
            highId = if (first.id < second.id) second.id else first.id
        )
    }

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
     * The margin noise's longest wavelength on the ground, in kilometers, and its octaves.
     *
     * 500 km, the scale of a coastal embayment: the 24 cycles round the 12,000 km world it was set
     * as. Six octaves carry it down to 15.6 km, and the coastline's box count, taken over spans of
     * tens to a few hundred kilometers, sits inside that range with power in it, which is the
     * point. See [roughenMargins].
     */
    private const val MARGIN_WAVELENGTH_KM = 500.0
    private const val MARGIN_OCTAVES = 6

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
     * [TectonicsConfig.hotspotSpacingKm] apart, are sized alike and chains a plate apart are not.
     */
    private const val SEAMOUNT_SIZE_WAVELENGTH_KM = 1_333.3

    /**
     * How far a plate seed is kept from each pole, as a share of the grid's height, and the band
     * of rows left for it between the two margins.
     *
     * A seed on the very first or last row owns a Voronoi cell that is all edge and no interior,
     * so the polar rows would belong to a plate boundary rather than to a plate.
     */
    private const val SEED_POLE_MARGIN_SHARE = 0.06f
    private const val SEED_LATITUDE_SPAN = 0.88f

    /**
     * The grid a plate seed's position is drawn against, whatever grid the world is generated on:
     * a seed's place is drawn as one of 512 columns and 512 rows of the map and read as that
     * fraction of the map's width and height.
     *
     * A seed's place on the planet is a property of the seed, not of how finely the map is cut, so
     * it is settled here once and read as a fraction everywhere else — see [drawPlateSeeds]. 512
     * because it was `WorldGenConfig`'s default grid when the draw was settled, and the draws made
     * on it are the worlds every seed has named since; it is a fraction of the map and not a
     * length, so it holds on a planet of any size, and it is not the grid the application makes
     * worlds on, which is 1,024 rows (docs/DESIGN_LEDGER.md, G1).
     */
    private const val SEED_REFERENCE_CELLS = 512

    /**
     * How far the domain warp may push a plate boundary, in belt half-widths, and its noise's
     * longest wavelength on the ground, in kilometers.
     *
     * Straight Voronoi edges are the one thing that makes a plate map look computed; the warp is
     * what makes a boundary meander. Kept comparable to the belt it carries, so the meander is of
     * the same scale as the mountains along it. 2,000 km, the 6 cycles round the 12,000 km world
     * it was set as: a bend or two along a boundary the length of a plate. Counted in cycles it
     * stayed six on a world of Earth's size, where a bend is 6,679 km and the boundaries ran
     * straight (docs/DESIGN_LEDGER.md, K1).
     */
    private const val WARP_AMPLITUDE_IN_BELT_WIDTHS = 1.6f
    private const val WARP_WAVELENGTH_KM = 2_000.0

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
     * The convergence rate, as a share of a plate's own drift speed, above which a pair counts as
     * converging or diverging rather than sliding past one another.
     */
    private const val CONVERGENCE_THRESHOLD = 0.15f

    /**
     * The least convergence a boundary is given credit for, so that a nearly transform pair still
     * leaves a trace rather than vanishing where its relief would round to nothing.
     */
    private const val MIN_BOUNDARY_STRENGTH = 0.12f

    /** Guards the division when two plate seeds land on the same cell. */
    private const val MIN_SEED_SEPARATION_CELLS = 1e-4f

    /**
     * The per-cell roughness that breaks a belt's crest into peaks, between these two, at a
     * longest wavelength of [ROUGHNESS_WAVELENGTH_KM], 1,000 km, the 12 cycles round the
     * 12,000 km world it was set as. Centred a little below 1 so it takes as much off a crest as
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
