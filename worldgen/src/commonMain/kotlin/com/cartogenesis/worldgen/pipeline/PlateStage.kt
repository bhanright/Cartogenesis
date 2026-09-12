package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
    /** Distance in cells to the nearest plate boundary. */
    val boundaryDistance: FloatField,
    /** [BoundaryType] ordinal of the nearest boundary, per cell. */
    val nearestBoundaryType: IntArray,
    /**
     * [BoundaryClass] ordinal of the nearest boundary, per cell — the crust pair that built
     * whatever relief this cell carries, and what tells an Andean margin from a Tibetan plateau
     * without measuring either.
     */
    val nearestBoundaryClass: IntArray,
    /** Terrain height with tectonic uplift applied, normalized to 0..1. */
    val height: FloatField,
    /**
     * How long ago the crust under each cell was last built, from 0 (an active belt of the present
     * epoch) to 1 (cratonic ground no epoch in the history ever deformed).
     *
     * The bands are unambiguous by construction: with `historyEpochs` epochs, a cell whose most
     * recent orogeny was `n` epochs ago lands in `[n/K, (n+1)/K)`, and only untouched crust reads
     * exactly 1. That is what lets a consumer tell an Appalachian belt from an Alpine one without
     * measuring either, and it is why this is an *age* rather than an erodibility: what a shield,
     * a worn orogen and an active one should erode like is the lithology stage's question, not
     * this one's. See REALISM_PLAN.md, H1 and H3.
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
        val lowId: Int
    )

    /**
     * Where along a segmented rift one boundary cell sits, and what that segment is shaped like.
     *
     * Filled in by [segmentRifts] for the cells of a [BoundaryClass.CONTINENTAL_RIFT] boundary and
     * null everywhere else. Every cell in the corridor either side of the rift reads its segment
     * off its *nearest* boundary cell — the label the distance transform already carries — so the
     * whole width of a half-graben agrees about which one it is without a second walk.
     */
    private class RiftSegment(
        /** This segment's trough depth, as a factor on `riftDepth`. */
        val depthFactor: Float,
        /** This segment's shoulder height, as a factor on `riftShoulderHeight`. */
        val shoulderFactor: Float,
        /** This segment's shoulder half-width, as a factor on `riftShoulderWidthCells`. */
        val widthFactor: Float,
        /**
         * Whether the high footwall stands on the pair's [PairInteraction.lowId] plate. Alternates
         * from segment to segment, which is what makes the chain a chain rather than one trough.
         */
        val footwallOnLow: Boolean,
        /**
         * 0 at the centre of an accommodation zone, 1 well inside the segment. The trough's depth
         * and its asymmetry are both multiplied by it, so a half-graben dies out at each end into
         * a symmetric sill rather than meeting its neighbour's opposite polarity at a step.
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
        var segment: RiftSegment? = null
    )

    /**
     * The four noise fields every belt profile reads, built once and shared by every epoch.
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
    }

    fun generate(config: WorldGenConfig, terrain: TerrainResult): PlateResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val random = Random(config.seed * 7919 + 13)

        val plates = createPlates(tectonics, cellsAcross, cellsDown, random)

        // The history: [TectonicsConfig.historyEpochs] configurations of the same plates, stamped
        // oldest first so the modern belts lie over the worn ones rather than under them. Only the
        // present epoch's assignment, distances and classes leave this function; the past epochs
        // leave nothing behind but the ground they built and their mark on `crustAge`.
        val epochs = tectonics.historyEpochs.coerceAtLeast(1)
        val noise = BeltNoise(config.seed)
        val uplift = FloatField(cellsAcross, cellsDown)
        val crustAge = FloatField(cellsAcross, cellsDown)
        crustAge.data.fill(1f)

        var plateId = IntArray(0)
        var presentDistanceCells = FloatArray(0)
        var nearestBoundaryType = IntArray(0)
        var nearestBoundaryClass = IntArray(0)

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
                    displacedPlates(
                        plates,
                        tectonics.epochDriftCells * epochsAgo,
                        cellsAcross,
                        cellsDown
                    )
                }
            val epochPlateId = assignPlates(config, epochPlates)
            val boundaries = classifyBoundaries(cellsAcross, cellsDown, epochPlateId, epochPlates)
            // Segmentation is a live rift's structure. A failed one is a filled sag (see the
            // CONTINENTAL_RIFT arm of [stampEpoch]), so only the present epoch is walked — which
            // also keeps the present segments, and their guard's figures, exactly as they were.
            if (present) segmentRifts(config, boundaries)

            // Euclidean, by jump flooding: every belt profile below is a function of this distance,
            // so a metric whose contours are octagons hands its facets to the plateau rims and the
            // trench walls. See [JumpFloodDistance].
            val epochDistanceCells = FloatArray(cellsAcross * cellsDown) { JumpFloodDistance.INFINITE }
            val nearestBoundaryCell = IntArray(cellsAcross * cellsDown) { -1 }
            boundaries.keys.forEach { cell ->
                epochDistanceCells[cell] = 0f
                nearestBoundaryCell[cell] = cell
            }
            val hasBoundaries = boundaries.isNotEmpty()
            if (hasBoundaries) {
                JumpFloodDistance.run(
                    cellsAcross,
                    cellsDown,
                    epochDistanceCells,
                    nearestBoundaryCell
                )
            }

            if (present) {
                plateId = epochPlateId
                presentDistanceCells = epochDistanceCells
                nearestBoundaryType = IntArray(cellsAcross * cellsDown) { -1 }
                nearestBoundaryClass = IntArray(cellsAcross * cellsDown) { -1 }
            }
            if (!hasBoundaries) continue

            // Ageing. Every factor is exactly 1 on the present epoch and the blur is skipped
            // outright, so a one-epoch history is the arithmetic of the generator that had no
            // history at all, to the last bit — a multiply by 1f is the identity in IEEE-754 and
            // a `copy` that multiplies nothing is never taken.
            val ageHeightFactor = tectonics.beltAgeDecay.pow(epochsAgo)
            val epochTectonics =
                if (present) tectonics else widened(tectonics, tectonics.beltAgeWidening.pow(epochsAgo))
            val epochUplift = if (present) uplift else FloatField(cellsAcross, cellsDown)

            stampEpoch(
                config = config,
                tectonics = epochTectonics,
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
                nearestBoundaryClass = if (present) nearestBoundaryClass else null
            )

            if (!present) {
                // The profile rounds with age: what was stamped with a crest and a toe comes out
                // as a swell. Two passes rather than the usual three, because an old range should
                // read as rounded and not as a stain.
                BoxBlur.apply(
                    epochUplift,
                    radius = (tectonics.beltAgeBlurCells * epochsAgo).roundToInt(),
                    passes = 2
                )
                for (cell in uplift.data.indices) uplift.data[cell] += epochUplift.data[cell]
            }
        }

        val plateBase = FloatField(cellsAcross, cellsDown)
        for (cell in plateBase.data.indices) {
            val plate = plates[plateId[cell]]
            plateBase.data[cell] =
                if (plate.type == PlateType.CONTINENTAL) tectonics.plateElevationBias / 2f
                else -tectonics.plateElevationBias / 2f
        }
        // Softens the step between plate interiors so ocean basins shelve into continents.
        BoxBlur.apply(plateBase, radius = (tectonics.boundaryFalloffCells / 3f).roundToInt().coerceAtLeast(1))

        stampHotspotChains(config, plates, plateId, uplift)

        val tectonicWeight = tectonics.tectonicWeight.coerceIn(0f, 1f)
        val blended = FloatField(cellsAcross, cellsDown)

        // Fine relief, an order of magnitude below anything the eye picks out of the shading. Both
        // the blurred plate base and the uplift falloff are very smooth, which leaves some plains
        // locally planar; D8 routing over a plane sends every cell the same way, so rivers there
        // come out as straight parallel lines that never join. This gives the water something to
        // converge on.
        val detailNoise = PerlinNoise(config.seed * 7919 + 13)
        val detailCyclesAcrossMap = tectonics.detailFrequency.toFloat()

        // Position-derived detail noise; every cell writes its own index.
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val blendedBase = terrain.height.data[cell] * (1f - tectonicWeight) +
                        (0.5f + plateBase.data[cell]) * tectonicWeight
                    val detail = tectonics.detailAmplitude * detailNoise.fbm(
                        column * detailCyclesAcrossMap / cellsAcross,
                        row * detailCyclesAcrossMap / cellsDown,
                        4,
                        tectonics.detailFrequency,
                        tectonics.detailFrequency
                    )
                    blended.data[cell] = blendedBase + uplift.data[cell] + detail
                }
            }
        }
        blended.normalize()

        return PlateResult(
            plates = plates,
            plateId = plateId,
            boundaryDistance = FloatField(cellsAcross, cellsDown, presentDistanceCells),
            nearestBoundaryType = nearestBoundaryType,
            nearestBoundaryClass = nearestBoundaryClass,
            height = blended,
            crustAge = crustAge
        )
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
        /** Cells to the nearest boundary of that epoch. */
        val distanceCells: FloatArray,
        /** [BoundaryClass] ordinal of that nearest boundary, or -1 where there was none. */
        val nearestBoundaryClass: IntArray
    )

    internal fun epochBoundaries(config: WorldGenConfig, epochsAgo: Int): EpochBoundaries {
        val cellsAcross = config.width
        val cellsDown = config.height
        val tectonics = config.tectonics
        val plates = createPlates(tectonics, cellsAcross, cellsDown, Random(config.seed * 7919 + 13))
        val epochPlates =
            if (epochsAgo == 0) plates
            else displacedPlates(plates, tectonics.epochDriftCells * epochsAgo, cellsAcross, cellsDown)
        val plateId = assignPlates(config, epochPlates)
        val boundaries = classifyBoundaries(cellsAcross, cellsDown, plateId, epochPlates)

        val distanceCells = FloatArray(cellsAcross * cellsDown) { JumpFloodDistance.INFINITE }
        val nearestBoundaryCell = IntArray(cellsAcross * cellsDown) { -1 }
        boundaries.keys.forEach { cell ->
            distanceCells[cell] = 0f
            nearestBoundaryCell[cell] = cell
        }
        if (boundaries.isNotEmpty()) {
            JumpFloodDistance.run(cellsAcross, cellsDown, distanceCells, nearestBoundaryCell)
        }

        val nearestBoundaryClass = IntArray(cellsAcross * cellsDown) { -1 }
        for (cell in nearestBoundaryClass.indices) {
            val boundary = boundaries[nearestBoundaryCell[cell]] ?: continue
            nearestBoundaryClass[cell] = boundary.interaction.pairClass.ordinal
        }
        return EpochBoundaries(distanceCells, nearestBoundaryClass)
    }

    /**
     * Every plate seed carried back along minus its own drift, by [distanceCells].
     *
     * X wraps, because the world is a cylinder. Y clamps, because it is not: a plate whose drift
     * points at a pole was, far enough back, at the pole and no further, and a seed off the edge
     * of the grid has no Voronoi cell to own. Two seeds clamped onto the same cell is harmless —
     * the later id simply takes the cell and the earlier plate has no region in that epoch, which
     * is a plate that had not yet rifted away from its neighbour.
     */
    private fun displacedPlates(
        plates: List<Plate>,
        distanceCells: Float,
        width: Int,
        height: Int
    ): List<Plate> = plates.map { plate ->
        var wrappedSeedX = (plate.seedX - plate.driftX * distanceCells).roundToInt() % width
        if (wrappedSeedX < 0) wrappedSeedX += width
        val clampedSeedY = (plate.seedY - plate.driftY * distanceCells).roundToInt().coerceIn(0, height - 1)
        plate.copy(seedX = wrappedSeedX, seedY = clampedSeedY)
    }

    /**
     * Every belt half-width, offset and reach in [tectonics] multiplied by [factor] — the wider
     * of ageing.
     *
     * Only the lengths move. The heights are handled by one multiply at the point of stamping, and
     * the dimensionless shares (`plateauFlatShare`, `riftFloorShare`, the rim share) describe the
     * shape of a profile rather than its size, so a wider belt keeps the same proportions.
     */
    private fun widened(tectonics: TectonicsConfig, factor: Float): TectonicsConfig = tectonics.copy(
        boundaryFalloffCells = tectonics.boundaryFalloffCells * factor,
        andeanWidthCells = tectonics.andeanWidthCells * factor,
        arcOffsetCells = tectonics.arcOffsetCells * factor,
        arcWidthCells = tectonics.arcWidthCells * factor,
        collisionWidthCells = tectonics.collisionWidthCells * factor,
        islandArcOffsetCells = tectonics.islandArcOffsetCells * factor,
        islandArcWidthCells = tectonics.islandArcWidthCells * factor,
        riftWidthCells = tectonics.riftWidthCells * factor,
        riftShoulderOffsetCells = tectonics.riftShoulderOffsetCells * factor,
        riftShoulderWidthCells = tectonics.riftShoulderWidthCells * factor
    )

    /**
     * One epoch's belts, stamped into [uplift] and recorded in [crustAge].
     *
     * This is the per-cell pass that used to be the body of [generate], unchanged except for two
     * things: what it writes is multiplied by [ageHeightFactor], and every cell it touches has
     * its crust age pulled down to this epoch's band. [tectonics] is the epoch's own widened copy,
     * so
     * every profile below reads the aged width without knowing that it has been aged.
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
        tectonics: TectonicsConfig,
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
        nearestBoundaryClass: IntArray?
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val ridgeNoise = noise.ridge
        val rangeNoise = noise.range
        val widthNoise = noise.width
        val arcNoise = noise.arc
        val past = epochsAgo > 0
        val ageBandBase = epochsAgo.toFloat() / epochs
        val ageBandSpan = 1f / epochs

        run {
            val beltReachCells = tectonics.boundaryFalloffCells
            // The farthest any profile below reaches from its boundary, so a cell out in a plate
            // interior can be skipped before any of the noise is sampled. The widest is whichever
            // of the belts is broadest once the along-strike width swell is at its maximum.
            val widestScale = MAX_WIDTH_SCALE * MAX_EDGE_JITTER
            val maxReachCells = maxOf(
                beltReachCells * MAX_WIDTH_SCALE,
                tectonics.andeanWidthCells * widestScale,
                tectonics.collisionWidthCells * widestScale,
                tectonics.arcOffsetCells + tectonics.arcWidthCells,
                tectonics.islandArcOffsetCells + tectonics.islandArcWidthCells * widestScale,
                tectonics.riftShoulderOffsetCells +
                    tectonics.riftShoulderWidthCells * (1f + tectonics.riftSegmentShoulderVariation)
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
                                column * WIDTH_SWELL_CYCLES / cellsAcross,
                                row * WIDTH_SWELL_CYCLES / cellsDown,
                                3,
                                WIDTH_SWELL_CYCLES.toInt(),
                                WIDTH_SWELL_CYCLES.toInt()
                            ))
                        val narrow = sharpFalloff(
                            distanceFromBoundary,
                            beltReachCells * NARROW_SHARE_OF_BELT
                        )

                        // Breaks up the otherwise uniform ridge profile into distinct peaks.
                        val roughness = ROUGHNESS_MIN + ROUGHNESS_SPAN * ridgeNoise.fbm(
                            column * ROUGHNESS_CYCLES / cellsAcross,
                            row * ROUGHNESS_CYCLES / cellsDown,
                            4,
                            ROUGHNESS_CYCLES.toInt(),
                            ROUGHNESS_CYCLES.toInt()
                        )

                        // Slow variation along the length of a belt. Without it, every convergent
                        // boundary rises to the same height for its entire run — which is the one
                        // thing that makes plate edges read as drawn on rather than grown. Real
                        // ranges swell, sag, and break into separate massifs.
                        val alongStrikeAmount = tectonics.rangeVariation.coerceIn(0f, 1f)
                        val swellCycles = tectonics.rangeVariationCycles.toInt().coerceAtLeast(1)
                        val swell = rangeNoise.fbm(
                            column * tectonics.rangeVariationCycles / cellsAcross,
                            row * tectonics.rangeVariationCycles / cellsDown,
                            3,
                            swellCycles,
                            swellCycles
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
                            // for it. See REALISM_PLAN.md, B2.
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
                        // REALISM_PLAN.md, G4.
                        val edgeJitter = EDGE_JITTER_MIN + EDGE_JITTER_SPAN *
                            (0.5f + 0.5f * widthNoise.fbm(
                                column * EDGE_JITTER_CYCLES / cellsAcross,
                                row * EDGE_JITTER_CYCLES / cellsDown,
                                3,
                                EDGE_JITTER_CYCLES.toInt(),
                                EDGE_JITTER_CYCLES.toInt()
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
                                            tectonics.andeanWidthCells * widthScale * edgeJitter
                                        ) * roughness * alongRange +
                                        tectonics.arcHeight * strength *
                                        ridgeAt(
                                            distanceFromBoundary,
                                            tectonics.arcOffsetCells,
                                            tectonics.arcWidthCells
                                        ) *
                                        volcanicChain(
                                            arcNoise, column, row, cellsAcross, cellsDown
                                        ) * alongRange
                                }

                            // Continental against continental. Neither side will go under, so the
                            // crust thickens over a wide area instead of piling onto a line: broad,
                            // high, flat-topped and very nearly symmetric.
                            BoundaryClass.COLLISION_PLATEAU -> {
                                val plateauWidth = tectonics.collisionWidthCells * widthScale * edgeJitter
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
                                                tectonics.islandArcOffsetCells,
                                                tectonics.islandArcWidthCells * widthScale * edgeJitter
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
                                    // down the axis, not a chain of lakes. See REALISM_PLAN.md,
                                    // H5, for the lake this left at 2048 before it was added.
                                    -tectonics.riftDepth * tectonics.failedRiftFill * strength *
                                        roughness * alongRange *
                                        plateauFalloff(
                                            distanceFromBoundary,
                                            tectonics.riftWidthCells,
                                            tectonics.riftFloorShare
                                        ) +
                                        tectonics.riftShoulderHeight *
                                        tectonics.failedRiftShoulder *
                                        strength * ridgeAt(
                                            distanceFromBoundary,
                                            tectonics.riftShoulderOffsetCells,
                                            tectonics.riftShoulderWidthCells
                                        ) * roughness * alongRange
                                } else {
                                    val segment = boundary.segment
                                    if (segment == null) {
                                        // Unsegmented: the rift before segmentation, one trough
                                        // of constant depth between two shoulders of constant
                                        // height for the whole run of the boundary. Kept so
                                        // `RiftSegmentationTest` can measure the world that was
                                        // replaced rather than take its word for it. See
                                        // REALISM_PLAN.md, E4.
                                        -tectonics.riftDepth * strength *
                                            plateauFalloff(
                                                distanceFromBoundary,
                                                tectonics.riftWidthCells,
                                                tectonics.riftFloorShare
                                            ) +
                                            tectonics.riftShoulderHeight * strength *
                                            ridgeAt(
                                                distanceFromBoundary,
                                                tectonics.riftShoulderOffsetCells,
                                                tectonics.riftShoulderWidthCells
                                            ) * roughness * alongRange
                                    } else {
                                        // Which flank of the half-graben this cell is on. The
                                        // distance transform gives distance and not side, so the
                                        // side comes from the cell's own plate against the pair's
                                        // lower id — a total order, never a hash order. A cell on
                                        // some third plate near a triple junction falls to the
                                        // hinge side, which is the quieter of the two.
                                        val onLowIdPlate = plateId[cell] == interaction.lowId
                                        val footwall = onLowIdPlate == segment.footwallOnLow

                                        // A half-graben is a wedge: the floor hangs from the fault
                                        // under the footwall and rises across to the hinge. The
                                        // signed across-strike coordinate saturates at the edge of
                                        // the flat floor, so the tilt is spent inside the trough
                                        // rather than out on the shoulder slope.
                                        val flatFloorHalfWidthCells =
                                            (tectonics.riftWidthCells * tectonics.riftFloorShare)
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
                                                tectonics.riftWidthCells,
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
                                                tectonics.riftShoulderOffsetCells,
                                                tectonics.riftShoulderWidthCells * segment.widthFactor
                                            ) * roughness * alongRange

                                        // The accommodation zone itself: ground that rises between
                                        // two half-grabens, which is where the sill and the land
                                        // bridge between two gulfs come from.
                                        //
                                        // Its height is what the outlet notch has to saw through
                                        // where a rift stands on land, so it is deliberately
                                        // modest: a sill high enough to dam a half-graben for
                                        // twelve rounds of erosion leaves a lake the notch cannot
                                        // drain. See REALISM_PLAN.md, E4, for what that measured.
                                        val sill = tectonics.riftSillHeight * strength *
                                            (1f - segment.taper) *
                                            beltFalloff(
                                                distanceFromBoundary,
                                                tectonics.riftShoulderOffsetCells
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
                    }
                }
            }
        }
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
        if (tectonics.hotspotPlateFraction <= 0f || tectonics.hotspotHeight == 0f) return
        if (tectonics.hotspotRadiusCells <= 0f || tectonics.hotspotSpacingCells <= 0f) return

        val cellsAcross = uplift.width
        val cellsDown = uplift.height
        val random = Random(config.seed * 31337 + 7)
        val sizeNoise = PerlinNoise(config.seed * 104729 + 4441)
        var ventIndex = 0

        plates.forEach { plate ->
            val roll = random.nextFloat()
            // Offset from the plate's own seed point, not a free point on the map. A hotspot
            // placed anywhere at all lands on some other plate nineteen times in twenty, and the
            // clip to the carrying plate in [stampSeamount] then erases the whole chain — which is
            // exactly what the first version of this did, on every seed tried.
            val spreadCells = tectonics.hotspotChainLengthCells * 0.3f
            val originX = plate.seedX + (random.nextFloat() - 0.5f) * spreadCells
            val originY = plate.seedY + (random.nextFloat() - 0.5f) * spreadCells
            if (plate.type != PlateType.OCEANIC) return@forEach
            if (roll >= tectonics.hotspotPlateFraction) return@forEach

            var travelledCells = 0f
            while (travelledCells <= tectonics.hotspotChainLengthCells) {
                // The hotspot stays put and the plate slides over it, so the volcano it built a
                // while ago has since been carried a while along the drift vector. Older means
                // further along, and lower: the crust cools and the seamount subsides with it.
                val ventX = originX + plate.driftX * travelledCells
                val ventY = originY + plate.driftY * travelledCells
                val ageAlongChain = travelledCells / tectonics.hotspotChainLengthCells
                val sizeJitter = 0.6f + 0.8f *
                    (0.5f + 0.5f * sizeNoise.fbm(ventX * 9f / cellsAcross, ventY * 9f / cellsDown, 2, 9, 9))
                        .coerceIn(0f, 1f)
                stampSeamount(
                    uplift = uplift,
                    plateId = plateId,
                    plate = plate.id,
                    ventX = ventX,
                    ventY = ventY,
                    radius = tectonics.hotspotRadiusCells,
                    // The crust cools and the seamount subsides with it, as the square of age.
                    amplitude = tectonics.hotspotHeight *
                        (1f - ageAlongChain) * (1f - ageAlongChain) * sizeJitter,
                    seed = config.seed,
                    ventIndex = ventIndex,
                    detail = tectonics.hotspotConeDetail
                )
                ventIndex++
                travelledCells += tectonics.hotspotSpacingCells
            }
        }
    }

    /**
     * One seamount: a smooth cone of [radius] cells, wrapping in x and clipped in y.
     *
     * The falloff is true Euclidean distance and always was, never the chamfer approximation
     * [DistanceTransform] still uses in this file for plate assignment, so the eight-sided look a
     * cone can have is not the metric's doing. It is rasterization: a stamp four or five cells
     * across has too little grid to draw a circle, and the four compass and four diagonal
     * bearings — the only ones a square grid hits exactly — come out measurably longer than the
     * bearings between them however continuous the formula behind them is.
     *
     * [detail] answers that by supersampling each cell, which is cheap when the whole stamp is a
     * few cells wide, and while there breaks the remaining perfect symmetry with a few
     * low-amplitude harmonics of the rim radius, seeded from the world seed and this vent's own
     * index so that no two cones match without a shared [Random] or a hash order. Off reproduces
     * the single-sample, unmodulated stamp the guard measures its "before" against. See
     * REALISM_PLAN.md, E3, for the eight-fold amplitudes at each grid.
     */
    private fun stampSeamount(
        uplift: FloatField,
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
        val boundingRadiusCells = radius.toInt() + 1

        val harmonics = if (detail) rimHarmonics(seedHash(seed, ventIndex.toLong())) else null
        // Sub-cell samples per axis, so a stamp only a few cells across is area-averaged rather
        // than point-sampled — the fix for the eight-fold artefact described above. Off (the old
        // behaviour) samples once, at the cell's own centre.
        val subSamplesPerAxis = if (detail) 5 else 1
        val subStep = 1f / subSamplesPerAxis
        val subOffset = (subStep - 1f) / 2f
        val subSampleCount = (subSamplesPerAxis * subSamplesPerAxis).toFloat()

        for (row in (ventY.toInt() - boundingRadiusCells)..(ventY.toInt() + boundingRadiusCells)) {
            if (row < 0 || row >= cellsDown) continue
            for (column in (ventX.toInt() - boundingRadiusCells)..(ventX.toInt() + boundingRadiusCells)) {
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
                        val offsetY = sampleY - ventY
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

    private fun createPlates(
        tectonics: TectonicsConfig,
        width: Int,
        height: Int,
        random: Random
    ): List<Plate> {
        val plateCount = tectonics.plateCount.coerceAtLeast(2)
        val oceanicCount = (plateCount * tectonics.oceanicFraction).roundToInt().coerceIn(0, plateCount)
        val types = MutableList(plateCount) {
            if (it < oceanicCount) PlateType.OCEANIC else PlateType.CONTINENTAL
        }
        types.shuffle(random)

        return List(plateCount) { id ->
            val angle = random.nextFloat() * 2f * PI.toFloat()
            Plate(
                id = id,
                seedX = random.nextInt(width),
                // Keeps plate seeds off the very edge, so polar rows belong to a real plate
                // interior rather than to a seed sitting on the rim of the grid.
                seedY = (height * SEED_POLE_MARGIN_SHARE).toInt() +
                    random.nextInt((height * SEED_LATITUDE_SPAN).toInt().coerceAtLeast(1)),
                driftX = cos(angle),
                driftY = sin(angle),
                type = types[id]
            )
        }
    }

    /**
     * Chamfer-Voronoi assignment, then a noise domain-warp so boundaries meander instead of
     * looking like straight Voronoi edges.
     *
     * Deliberately still the chamfer transform, where every consumer that reads a *distance* has
     * moved to [JumpFloodDistance]. Nothing here reads the distance: only the label survives, and
     * what the label decides is a partition of the map into plates — which cell belongs to which
     * seed.
     * There is no contour to facet, so the octagonal metric costs nothing visible; and since a
     * Euclidean Voronoi would draw its cell walls in slightly different places, switching it would
     * redraw every plate of every world ever generated for no gain anybody could see. Left alone
     * on purpose.
     */
    private fun assignPlates(config: WorldGenConfig, plates: List<Plate>): IntArray {
        val cellsAcross = config.width
        val cellsDown = config.height

        val distanceToSeed = FloatArray(cellsAcross * cellsDown) { DistanceTransform.INFINITE }
        val nearestSeedPlate = IntArray(cellsAcross * cellsDown) { -1 }
        plates.forEach { plate ->
            val cell = plate.seedY * cellsAcross + plate.seedX
            distanceToSeed[cell] = 0f
            nearestSeedPlate[cell] = plate.id
        }
        DistanceTransform.run(cellsAcross, cellsDown, distanceToSeed, nearestSeedPlate)

        val warpX = PerlinNoise(config.seed * 6151 + 3)
        val warpY = PerlinNoise(config.seed * 6151 + 9)
        val warpAmplitudeCells =
            config.tectonics.boundaryFalloffCells * WARP_AMPLITUDE_IN_BELT_WIDTHS

        val warped = IntArray(cellsAcross * cellsDown)
        // Reads `raw`, writes its own cell of `warped` — no overlap between rows.
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val warpAcross = warpAmplitudeCells * warpX.fbm(
                        column * WARP_CYCLES / cellsAcross,
                        row * WARP_CYCLES / cellsDown,
                        4,
                        WARP_CYCLES.toInt(),
                        WARP_CYCLES.toInt()
                    )
                    val warpDown = warpAmplitudeCells * warpY.fbm(
                        column * WARP_CYCLES / cellsAcross,
                        row * WARP_CYCLES / cellsDown,
                        4,
                        WARP_CYCLES.toInt(),
                        WARP_CYCLES.toInt()
                    )
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

    private fun classifyBoundaries(
        width: Int,
        height: Int,
        plateId: IntArray,
        plates: List<Plate>
    ): Map<Int, Boundary> {
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
                    interactionOf(plates[key / plates.size], plates[key % plates.size], width)
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
     * Breaks every continental rift into half-grabens along its own length.
     *
     * A rift is not a canal. It is a chain of asymmetric basins fifty to a hundred and fifty
     * kilometres long, each hanging from a fault on one flank and hinged on the other, with the
     * polarity flipping from one to the next and an accommodation zone between them where the
     * floor rises back toward the hinge. The sea then enters only the segments that have subsided
     * below it, which is why the Red Sea is a string of deeps, why Tanganyika and Baikal are
     * strings of deeps on land, and why no rift on Earth is one trough of constant depth for a
     * thousand kilometres.
     *
     * The along-strike coordinate is a walk, not a projection: a rift meanders, so distance along
     * any straight axis is not distance along the rift. Each connected run of a pair's boundary
     * cells is traversed breadth-first from one of its ends — found by the usual double sweep,
     * farthest cell from an arbitrary start, then farthest from that — and the resulting geodesic
     * distance in cells is the arc length every cell of the run is cut by.
     *
     * Determinism, in the terms rule 4 of the plan asks for: the cells are taken in ascending
     * index order (never in the hash order of the boundary map), each connected run is keyed by
     * its own lowest cell index, every tie in the double sweep is broken by the lower index, and
     * the per-segment draws come from a splitmix-seeded linear congruential stream rather than
     * from a shared [Random]. Segment lengths are fractions of the map's width, so the same rift
     * breaks into the same segments at 512 and at 2048.
     */
    private fun segmentRifts(config: WorldGenConfig, boundaries: Map<Int, Boundary>) {
        val tectonics = config.tectonics
        if (!tectonics.riftSegmentation) return
        val cellsAcross = config.width
        val cellsDown = config.height

        // Ascending index order: nothing below may depend on the iteration order of a hash map.
        val riftCells = boundaries.keys
            .filter { boundaries[it]!!.interaction.pairClass == BoundaryClass.CONTINENTAL_RIFT }
            .sorted()
        if (riftCells.isEmpty()) return

        // Which pair each rift cell belongs to, as an index into first-encounter order. Two
        // different rift pairs can touch at a triple junction and must not be walked as one rift.
        val pairs = ArrayList<PairInteraction>()
        val pairAt = IntArray(cellsAcross * cellsDown) { NOT_RIFT }
        riftCells.forEach { cell ->
            val interaction = boundaries[cell]!!.interaction
            var index = -1
            for (known in pairs.indices) {
                if (pairs[known] === interaction) { index = known; break }
            }
            if (index < 0) {
                pairs.add(interaction)
                index = pairs.size - 1
            }
            pairAt[cell] = index
        }

        val arc = IntArray(cellsAcross * cellsDown) { -1 }
        val runCells = ArrayList<Int>()
        val queue = ArrayList<Int>()

        // Breadth-first over one run's cells, from [source]; leaves the hop count in `arc` and
        // returns the farthest cell, ties broken by the lower index.
        fun sweep(source: Int, pair: Int): Int {
            runCells.forEach { arc[it] = -1 }
            queue.clear()
            arc[source] = 0
            queue.add(source)
            var head = 0
            var farthest = source
            while (head < queue.size) {
                val cell = queue[head++]
                val hops = arc[cell]
                if (hops > arc[farthest] || (hops == arc[farthest] && cell < farthest)) {
                    farthest = cell
                }
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowStep in -1..1) {
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnStep in -1..1) {
                        if (columnStep == 0 && rowStep == 0) continue
                        var neighbourColumn = (column + columnStep) % cellsAcross
                        if (neighbourColumn < 0) neighbourColumn += cellsAcross
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (pairAt[neighbour] != pair || arc[neighbour] >= 0) continue
                        arc[neighbour] = hops + 1
                        queue.add(neighbour)
                    }
                }
            }
            return farthest
        }

        val minSegmentCells = (tectonics.riftSegmentMin * cellsAcross).coerceAtLeast(2f)
        val maxSegmentCells = (tectonics.riftSegmentMax * cellsAcross).coerceAtLeast(minSegmentCells)
        val accommodationCells = (tectonics.riftAccommodation * cellsAcross).coerceAtLeast(1f)

        riftCells.forEach { start ->
            val pair = pairAt[start]
            if (pair < 0) return@forEach

            // Collect this connected run, then take its two passes. `start` is the run's lowest
            // index, since the cells are walked in ascending order and a run is claimed whole.
            runCells.clear()
            queue.clear()
            queue.add(start)
            arc[start] = 0
            var head = 0
            while (head < queue.size) {
                val cell = queue[head++]
                runCells.add(cell)
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowStep in -1..1) {
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnStep in -1..1) {
                        if (columnStep == 0 && rowStep == 0) continue
                        var neighbourColumn = (column + columnStep) % cellsAcross
                        if (neighbourColumn < 0) neighbourColumn += cellsAcross
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (pairAt[neighbour] != pair || arc[neighbour] >= 0) continue
                        arc[neighbour] = 0
                        queue.add(neighbour)
                    }
                }
            }

            val end = sweep(sweep(start, pair), pair)
            // `arc` now holds arc length from the far end of the run; `end` is its other end.
            val length = arc[end].toFloat()

            // Where the joins fall, and what each segment between them looks like.
            var bits = seedHash(config.seed, start.toLong() * 131L + pair.toLong())
            fun draw(): Float {
                bits = bits * 6364136223846793005L + 1442695040888963407L
                return ((bits ushr 40) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
            }

            val joins = ArrayList<Float>()
            val segmentDepthFactors = ArrayList<Float>()
            val segmentShoulderFactors = ArrayList<Float>()
            val segmentWidthFactors = ArrayList<Float>()
            joins.add(0f)
            var cursor = 0f
            while (cursor < length) {
                cursor += minSegmentCells + (maxSegmentCells - minSegmentCells) * draw()
                joins.add(cursor.coerceAtMost(length))
                segmentDepthFactors.add(1f + tectonics.riftSegmentDepthVariation * (2f * draw() - 1f))
                // One draw for both the height and the width of the segment's shoulders, not two.
                // Flexural uplift scales with the throw on the fault, so the footwall of a bigger
                // half-graben stands both higher and broader — and, less prettily, two independent
                // draws let a segment come out much taller than it is wide, which is a knife-edge
                // ridge. Where such a ridge crosses shallow sea it clears the surface as a strip
                // of land a couple of cells wide with a strait either side, the exact failure
                // `RibbonLandTest` exists to catch. See REALISM_PLAN.md, E4, for the ribbon that
                // measured.
                val shoulderVariation = tectonics.riftSegmentShoulderVariation
                val shoulderSize = (1f + shoulderVariation * (2f * draw() - 1f)).coerceAtLeast(0.2f)
                segmentShoulderFactors.add(shoulderSize)
                segmentWidthFactors.add(shoulderSize)
            }
            if (joins.size < 2) {
                joins.add(length)
                segmentDepthFactors.add(1f)
                segmentShoulderFactors.add(1f)
                segmentWidthFactors.add(1f)
            }
            // A sliver left over at the far end is not a half-graben; it joins its neighbour.
            val lastJoin = joins.size - 1
            if (joins.size > 2 && joins[lastJoin] - joins[lastJoin - 1] < minSegmentCells * 0.5f) {
                joins.removeAt(lastJoin - 1)
                segmentDepthFactors.removeAt(segmentDepthFactors.size - 1)
                segmentShoulderFactors.removeAt(segmentShoulderFactors.size - 1)
                segmentWidthFactors.removeAt(segmentWidthFactors.size - 1)
            }
            // Which flank the first segment hangs from; the rest alternate off it.
            val parity = ((bits ushr 17) and 1L) == 0L

            runCells.forEach { cell ->
                val arcLengthCells = arc[cell].toFloat()
                var segmentIndex = 0
                while (segmentIndex < joins.size - 2 &&
                    arcLengthCells >= joins[segmentIndex + 1]
                ) {
                    segmentIndex++
                }
                val toJoin = minOf(
                    arcLengthCells - joins[segmentIndex],
                    joins[segmentIndex + 1] - arcLengthCells
                ).coerceAtLeast(0f)
                val intoSegment = (toJoin / accommodationCells).coerceIn(0f, 1f)
                boundaries[cell]!!.segment = RiftSegment(
                    depthFactor = segmentDepthFactors[segmentIndex],
                    shoulderFactor = segmentShoulderFactors[segmentIndex],
                    widthFactor = segmentWidthFactors[segmentIndex],
                    footwallOnLow = (segmentIndex % 2 == 0) == parity,
                    taper = intoSegment * intoSegment * (3f - 2f * intoSegment)
                )
            }

            runCells.forEach {
                arc[it] = -1
                pairAt[it] = CLAIMED
            }
        }
    }

    /** [segmentRifts]: a cell that is not on a continental rift boundary at all. */
    private const val NOT_RIFT = -1

    /** [segmentRifts]: a rift cell whose run has already been walked. */
    private const val CLAIMED = -2

    /**
     * Projects the plates' relative motion onto the axis between their centres — the closest thing
     * to a boundary normal that holds for the whole shared edge.
     */
    private fun interactionOf(first: Plate, second: Plate, width: Int): PairInteraction {
        var acrossCells = (second.seedX - first.seedX).toFloat()
        // Shortest way round the cylinder, so plates either side of the seam behave sanely.
        if (acrossCells > width / 2f) acrossCells -= width
        if (acrossCells < -width / 2f) acrossCells += width
        val downCells = (second.seedY - first.seedY).toFloat()

        val separationCells = sqrt(acrossCells * acrossCells + downCells * downCells)
            .coerceAtLeast(MIN_SEED_SEPARATION_CELLS)
        val axisAcross = acrossCells / separationCells
        val axisDown = downCells / separationCells

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
            lowId = if (first.id < second.id) first.id else second.id
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
        column: Int,
        row: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): Float {
        val alongStrike = 0.5f + 0.5f * noise.fbm(
            column * ARC_CHAIN_CYCLES / cellsAcross,
            row * ARC_CHAIN_CYCLES / cellsDown,
            2,
            ARC_CHAIN_CYCLES.toInt(),
            ARC_CHAIN_CYCLES.toInt()
        )
        return alongStrike.coerceIn(0f, 1f).pow(ARC_CHAIN_EXPONENT) * ARC_CHAIN_PEAK
    }

    /**
     * The along-strike swell of a belt's width, as a factor on its nominal half-width: the noise
     * runs it between these two.
     *
     * A belt that keeps one width for its whole length reads as drawn on even once its height
     * varies. [WIDTH_SWELL_CYCLES] is how many times that swell repeats across the map — slow
     * enough that neighbouring cells agree and the belt stays continuous rather than dissolving
     * into blotches.
     */
    private const val WIDTH_SWELL_MIN = 0.55f
    private const val WIDTH_SWELL_SPAN = 0.85f
    private const val WIDTH_SWELL_CYCLES = 7f

    /**
     * [WIDTH_SWELL_MIN] plus [WIDTH_SWELL_SPAN], written out rather than added.
     *
     * Only [stampEpoch]'s reach test reads it, and the sum of the two floats rounds a hair above
     * the literal, which would move the test's bound and with it a bit of the world.
     */
    private const val MAX_WIDTH_SCALE = 1.4f

    /** The finer jitter of a belt's rim, on the same terms. See `edgeJitter` in [stampEpoch]. */
    private const val EDGE_JITTER_MIN = 0.72f
    private const val EDGE_JITTER_SPAN = 0.56f
    private const val EDGE_JITTER_CYCLES = 17f

    /** [EDGE_JITTER_MIN] plus [EDGE_JITTER_SPAN], written out for [MAX_WIDTH_SCALE]'s reason. */
    private const val MAX_EDGE_JITTER = 1.28f

    /**
     * How much of a belt's half-width the sharp-crested features use.
     *
     * A trench, a spreading ridge and a transform scarp are narrow next to the belt a collision
     * raises, and they share [TectonicsConfig.boundaryFalloffCells] rather than carrying a knob
     * apiece.
     */
    private const val NARROW_SHARE_OF_BELT = 0.45f

    /** The exponent that gives [sharpFalloff] its peak on the boundary and its fast decay. */
    private const val SHARP_FALLOFF_EXPONENT = 1.6f

    /** Amplitude of one seeded rim harmonic, as a fraction of a seamount's radius. */
    private const val RIM_HARMONIC_MIN = 0.015f
    private const val RIM_HARMONIC_SPAN = 0.03f

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
     * How far the domain warp may push a plate boundary, in belt half-widths, and how many times
     * its noise repeats across the map.
     *
     * Straight Voronoi edges are the one thing that makes a plate map look computed; the warp is
     * what makes a boundary meander. Kept comparable to the belt it carries, so the meander is of
     * the same scale as the mountains along it.
     */
    private const val WARP_AMPLITUDE_IN_BELT_WIDTHS = 1.6f
    private const val WARP_CYCLES = 6f

    /**
     * The along-strike modulation of a volcanic arc: [ARC_CHAIN_CYCLES] repeats across the map,
     * raised to [ARC_CHAIN_EXPONENT] and scaled to [ARC_CHAIN_PEAK].
     *
     * Sampled fine and raised to a power, so the arc is a row of separate cones rather than a
     * continuous wall of one height. The peak above 1 is what lets the tallest cones in a chain
     * stand above the arc's nominal crest.
     */
    private const val ARC_CHAIN_CYCLES = 26f
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
     * The per-cell roughness that breaks a belt's crest into peaks, between these two, repeating
     * [ROUGHNESS_CYCLES] times across the map. Centred a little below 1 so it takes as much off a
     * crest as it adds.
     */
    private const val ROUGHNESS_MIN = 0.75f
    private const val ROUGHNESS_SPAN = 0.5f
    private const val ROUGHNESS_CYCLES = 12f

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
}
