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
import kotlin.math.exp
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
    /**
     * Terrain height with tectonic uplift applied, on the height field's absolute ruler: 0 is
     * `WorldScale.deepestOceanMetres` below the water and 1 is `highestLandMetres` above it, so
     * the waterline stands at `WorldScale.shorelineFieldLevel` whatever the world turned out like.
     *
     * Absolute since S2, where before it was renormalised to its own extremes. The difference is
     * the whole of what made "62% ocean" a statement about a histogram rather than about a planet:
     * with the field normalised, one tall massif pushed every other landform down the ramp, the
     * hypsometric curve came out as a single peak straddling the shoreline, and the same 120 m of
     * sea-level fall was a different level on every seed. See [Isostasy] and REALISM_PLAN.md, S2.
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
                    displacedPlates(
                        plates,
                        tectonics.epochDriftCells * epochsAgo,
                        cellsAcross,
                        cellsDown
                    )
                }
            val epochPlateId = if (present) drawn.plateId else assignPlates(config, epochPlates)
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
                nearestBoundaryClass = if (present) nearestBoundaryClass else null,
                // Only the present epoch's belts are still rising; that is what "old" means here.
                upliftRate = if (present) upliftRate else null
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

        // Which crust each cell is made of, blurred across the plate boundary. The blur is not
        // cosmetic: the band it makes is a continental margin, crust thinned on its way out to the
        // ocean floor, and it is what [Isostasy.Columns] turns into a shelf, a slope and a rise.
        val continentalShare = FloatField(cellsAcross, cellsDown)
        for (cell in continentalShare.data.indices) {
            continentalShare.data[cell] =
                if (plates[plateId[cell]].type == PlateType.CONTINENTAL) 1f else 0f
        }
        // Half the margin's width, because a box blur of radius r spreads a step over 2r.
        BoxBlur.apply(
            continentalShare,
            radius = config.wholeCellsFor(tectonics.crustMarginKm / 2.0)
        )
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
        val detailCyclesAcrossMap = tectonics.detailFrequency.toFloat()

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
                        column * detailCyclesAcrossMap / cellsAcross,
                        row * detailCyclesAcrossMap / cellsDown,
                        4,
                        tectonics.detailFrequency,
                        tectonics.detailFrequency
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
     * The cycles are counted across the map rather than in cells, as every other noise in this file
     * is, so a margin has the same shape at 512 and at 2048 with more of its octaves resolved.
     */
    private fun roughenMargins(config: WorldGenConfig, continentalShare: FloatField) {
        val roughness = config.tectonics.marginRoughness
        if (roughness <= 0f) return
        val cellsAcross = config.width
        val cellsDown = config.height
        val noise = PerlinNoise(config.seed * 104729 + 6199)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val share = continentalShare.data[cell]
                    val inTheBand = 4f * share * (1f - share)
                    if (inTheBand <= 0f) continue
                    val wander = noise.fbm(
                        column * MARGIN_CYCLES / cellsAcross,
                        row * MARGIN_CYCLES / cellsDown,
                        MARGIN_OCTAVES,
                        MARGIN_CYCLES.toInt(),
                        MARGIN_CYCLES.toInt()
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
        /** Cells to the nearest boundary of that epoch. */
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
            else displacedPlates(drawn.plates, tectonics.epochDriftCells * epochsAgo, cellsAcross, cellsDown)
        val plateId = if (epochsAgo == 0) drawn.plateId else assignPlates(config, epochPlates)
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
        nearestBoundaryClass: IntArray?,
        upliftRate: FloatField?
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

    /** The present epoch's plates and the cells they own. */
    internal class DrawnPlates(val plates: List<Plate>, val plateId: IntArray)

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
     * Determinism: every draw below is taken for every plate whether or not it is used, in id
     * order, and the shuffle runs on a list built in id order — never on a hash order.
     */
    private fun drawPlates(config: WorldGenConfig): DrawnPlates {
        val tectonics = config.tectonics
        val width = config.width
        val height = config.height
        val random = Random(config.seed * 7919 + 13)
        val plateCount = tectonics.plateCount.coerceAtLeast(2)

        // Seeds and drifts first, all oceanic for now: the assignment below reads neither the
        // types nor anything derived from them, so the partition is settled before the crusts are.
        val order = MutableList(plateCount) { it }
        order.shuffle(random)
        val drawnSeeds = List(plateCount) { id ->
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
                type = PlateType.OCEANIC
            )
        }
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
     * How many times the margin noise repeats across the map, and over how many octaves.
     *
     * Twenty-four cycles is a base wavelength of twenty-one cells at 512 and eighty-five at 2048 —
     * the scale of a coastal embayment — and six octaves carry it down to a third of a cell at 512.
     * The coastline's box count is taken over four, eight and sixteen cells, all of which sit
     * inside that range with power in them, which is the point. See [roughenMargins].
     */
    private const val MARGIN_CYCLES = 24f
    private const val MARGIN_OCTAVES = 6

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

    /** For [localSpread], which squares an altitude and so must not do it in metres. */
    private const val METRES_PER_KILOMETRE = 1_000f
}
