package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.DistanceTransform
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
    val height: FloatField
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
        /** This segment's shoulder half-width, as a factor on `riftShoulderWidth`. */
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

    fun generate(config: WorldGenConfig, terrain: TerrainResult): PlateResult {
        val w = config.width
        val h = config.height
        val cfg = config.tectonics
        val rnd = Random(config.seed * 7919 + 13)

        val plates = createPlates(cfg, w, h, rnd)
        val plateId = assignPlates(config, plates)
        val boundaries = classifyBoundaries(w, h, plateId, plates)
        segmentRifts(config, boundaries)

        val dist = FloatArray(w * h) { DistanceTransform.INFINITE }
        val label = IntArray(w * h) { -1 }
        boundaries.keys.forEach { cell ->
            dist[cell] = 0f
            label[cell] = cell
        }
        val hasBoundaries = boundaries.isNotEmpty()
        if (hasBoundaries) DistanceTransform.run(w, h, dist, label)

        val plateBase = FloatField(w, h)
        for (i in plateBase.data.indices) {
            val plate = plates[plateId[i]]
            plateBase.data[i] =
                if (plate.type == PlateType.CONTINENTAL) cfg.plateElevationBias / 2f
                else -cfg.plateElevationBias / 2f
        }
        // Softens the step between plate interiors so ocean basins shelve into continents.
        BoxBlur.apply(plateBase, radius = (cfg.boundaryFalloff / 3f).roundToInt().coerceAtLeast(1))

        val ridgeNoise = PerlinNoise(config.seed * 104729 + 5)
        val rangeNoise = PerlinNoise(config.seed * 104729 + 911)
        val widthNoise = PerlinNoise(config.seed * 104729 + 1733)
        val arcNoise = PerlinNoise(config.seed * 104729 + 2477)
        val uplift = FloatField(w, h)
        val nearestType = IntArray(w * h) { -1 }
        val nearestClass = IntArray(w * h) { -1 }

        if (hasBoundaries) {
            val range = cfg.boundaryFalloff
            // The farthest any profile below reaches from its boundary, so a cell out in a plate
            // interior can be skipped before any of the noise is sampled. The widest is whichever
            // of the belts is broadest once the along-strike width swell is at its maximum.
            val widest = MAX_WIDTH_SCALE * MAX_EDGE_JITTER
            val maxReach = maxOf(
                range * MAX_WIDTH_SCALE,
                cfg.andeanWidth * widest,
                cfg.collisionWidth * widest,
                cfg.arcOffset + cfg.arcWidth,
                cfg.islandArcOffset + cfg.islandArcWidth * widest,
                cfg.riftShoulderOffset +
                    cfg.riftShoulderWidth * (1f + cfg.riftSegmentShoulderVariation)
            )
            // Each cell writes only its own uplift entry, and the roughness comes from position rather than a running RNG, so this splits cleanly across cores.
            parallelChunks(0, h) { startY, endY ->
                for (y in startY until endY) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val boundary = boundaries[label[i]] ?: continue
                        val interaction = boundary.interaction
                        nearestType[i] = interaction.type.ordinal
                        nearestClass[i] = interaction.pairClass.ordinal

                        val d = dist[i]
                        if (d >= maxReach) continue
                        // A belt that keeps the same width for its whole length reads as drawn on
                        // even once its height varies, so the width swells and pinches too. The
                        // noise is sampled on position, so neighbouring cells agree and the belt
                        // stays continuous rather than dissolving into blotches.
                        val widthScale = 0.55f + 0.85f *
                            (0.5f + 0.5f * widthNoise.fbm(x * 7f / w, y * 7f / h, 3, 7, 7))
                        val narrow = falloff(d, range * 0.45f)

                        // Breaks up the otherwise uniform ridge profile into distinct peaks.
                        val roughness =
                            0.75f + 0.5f * ridgeNoise.fbm(x * 12f / w, y * 12f / h, 4, 12, 12)

                        // Slow variation along the length of a belt. Without it, every convergent
                        // boundary rises to the same height for its entire run — which is the one
                        // thing that makes plate edges read as drawn on rather than grown. Real
                        // ranges swell, sag, and break into separate massifs.
                        val amount = cfg.rangeVariation.coerceIn(0f, 1f)
                        val period = cfg.rangeVariationScale.toInt().coerceAtLeast(1)
                        val swell = rangeNoise.fbm(
                            x * cfg.rangeVariationScale / w,
                            y * cfg.rangeVariationScale / h,
                            3,
                            period,
                            period
                        )
                        // Raised to a power so the belt spends more of its length low and rises
                        // into discrete massifs, rather than undulating gently about its mean. A
                        // belt that only ever sags to a third of its height stays a continuous
                        // wall; one that sags near to nothing becomes a chain with gaps in it.
                        val swollen = (0.5f + 0.5f * swell).coerceIn(0f, 1f).pow(1.6f)
                        val alongRange =
                            ((1f - amount) + amount * swollen * 1.9f).coerceIn(0f, 1.9f)

                        val strength = interaction.strength
                        if (!cfg.crustPairProfiles) {
                            // The pre-B2 generator: one belt profile for every convergent pair,
                            // whatever the crusts. Kept so `BoundaryPairTest` can measure the
                            // world this chunk replaced rather than take its word for it.
                            val wide = beltFalloff(d, range * widthScale)
                            if (wide <= 0f && narrow <= 0f) continue
                            uplift.data[i] += when (interaction.type) {
                                BoundaryType.CONVERGENT ->
                                    if (interaction.continentalCollision) {
                                        cfg.mountainHeight * strength * wide * roughness * alongRange
                                    } else if (boundary.oceanicSide) {
                                        -cfg.trenchDepth * strength * narrow
                                    } else {
                                        cfg.mountainHeight * 0.8f * strength * wide * roughness * alongRange
                                    }

                                BoundaryType.DIVERGENT ->
                                    if (boundary.oceanicSide) {
                                        cfg.mountainHeight * 0.22f * strength * narrow
                                    } else {
                                        -cfg.mountainHeight * 0.3f * strength * narrow
                                    }

                                BoundaryType.TRANSFORM ->
                                    cfg.mountainHeight * 0.12f * strength * narrow *
                                        (roughness - 0.75f)
                            }
                            continue
                        }

                        // A plateau that sagged to nothing between massifs would be a chain again,
                        // so it feels only a fraction of the along-strike variation; an island arc
                        // wants the opposite, sagging hard so that only its swells clear the water.
                        val plateauAmount = (amount * cfg.plateauAlongVariation).coerceIn(0f, 1f)
                        val alongPlateau =
                            ((1f - plateauAmount) + plateauAmount * swollen * 1.9f).coerceIn(0f, 1.9f)
                        val sagged = (0.5f + 0.5f * swell).coerceIn(0f, 1f).pow(3f)
                        val alongArc = ((1f - amount) + amount * sagged * 1.9f).coerceIn(0f, 1.9f)

                        // A second, finer swell of the width, on top of `widthScale`.
                        //
                        // The distance transform is a chamfer approximation, so its contours are
                        // octagons rather than circles. At the old belt width nobody could see
                        // that; a collision plateau is more than twice as wide, and its edge came
                        // out as a visible faceted polygon — `widthScale` varies too slowly to
                        // break up an outline that size. This is sampled three times finer, so the
                        // rim meanders within itself and the octagon disappears.
                        val edgeJitter = 0.72f + 0.56f *
                            (0.5f + 0.5f * widthNoise.fbm(x * 17f / w, y * 17f / h, 3, 17, 17))
                                .coerceIn(0f, 1f)

                        uplift.data[i] += when (interaction.pairClass) {
                            // Oceanic under continental. The trench is the subducting plate's and
                            // the range the overriding one's, so the two sides of the same
                            // boundary get quite different ground — which is the asymmetry a
                            // symmetric distance profile could not express.
                            BoundaryClass.ANDEAN_MARGIN ->
                                if (!boundary.overridingSide) {
                                    -cfg.trenchDepth * strength * narrow
                                } else {
                                    cfg.andeanHeight * strength *
                                        beltFalloff(d, cfg.andeanWidth * widthScale * edgeJitter) *
                                        roughness * alongRange +
                                        cfg.arcHeight * strength *
                                        ridgeAt(d, cfg.arcOffset, cfg.arcWidth) *
                                        volcanicChain(arcNoise, x, y, w, h) * alongRange
                                }

                            // Continental against continental. Neither side will go under, so the
                            // crust thickens over a wide area instead of piling onto a line: broad,
                            // high, flat-topped and very nearly symmetric.
                            BoundaryClass.COLLISION_PLATEAU -> {
                                val plateauWidth = cfg.collisionWidth * widthScale * edgeJitter
                                val rimShare = cfg.plateauRimShare.coerceIn(0.1f, 0.95f)
                                cfg.collisionHeight * strength *
                                    plateauFalloff(d, plateauWidth, cfg.plateauFlatShare) *
                                    // Damped roughness: a plateau is a plain at altitude, and the
                                    // full ridge noise would make it a mountain range again.
                                    (1f + (roughness - 1f) * 0.7f) * alongPlateau +
                                    // The rim ranges, which stand on the edge rather than in it.
                                    cfg.plateauRimHeight * strength *
                                    ridgeAt(
                                        d,
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
                                -cfg.trenchDepth * strength * narrow +
                                    if (boundary.overridingSide) {
                                        cfg.islandArcHeight * strength *
                                            ridgeAt(
                                                d,
                                                cfg.islandArcOffset,
                                                cfg.islandArcWidth * widthScale * edgeJitter
                                            ) * roughness * alongArc
                                    } else {
                                        0f
                                    }

                            BoundaryClass.OCEAN_RIDGE ->
                                cfg.mountainHeight * 0.22f * strength * narrow

                            // Continental crust being pulled apart: the floor drops between two
                            // rebounding shoulders. Where such a pair has ocean on one side, that
                            // side is a spreading ridge as before — the rift is what the
                            // continental crust does.
                            BoundaryClass.CONTINENTAL_RIFT ->
                                if (boundary.oceanicSide) {
                                    cfg.mountainHeight * 0.22f * strength * narrow
                                } else {
                                    val segment = boundary.segment
                                    if (segment == null) {
                                        // Unsegmented: the pre-E4 rift, one trough of constant
                                        // depth between two shoulders of constant height for the
                                        // whole run of the boundary. Kept so
                                        // `RiftSegmentationTest` can measure the world this chunk
                                        // replaced rather than take its word for it.
                                        -cfg.riftDepth * strength *
                                            plateauFalloff(d, cfg.riftWidth, cfg.riftFloorShare) +
                                            cfg.riftShoulderHeight * strength *
                                            ridgeAt(
                                                d, cfg.riftShoulderOffset, cfg.riftShoulderWidth
                                            ) * roughness * alongRange
                                    } else {
                                        // Which flank of the half-graben this cell is on. The
                                        // distance transform gives distance and not side, so the
                                        // side comes from the cell's own plate against the pair's
                                        // lower id — a total order, never a hash order. A cell on
                                        // some third plate near a triple junction falls to the
                                        // hinge side, which is the quieter of the two.
                                        val onLow = plateId[i] == interaction.lowId
                                        val footwall = onLow == segment.footwallOnLow

                                        // A half-graben is a wedge: the floor hangs from the fault
                                        // under the footwall and rises across to the hinge. The
                                        // signed across-strike coordinate saturates at the edge of
                                        // the flat floor, so the tilt is spent inside the trough
                                        // rather than out on the shoulder slope.
                                        val flatHalf =
                                            (cfg.riftWidth * cfg.riftFloorShare).coerceAtLeast(1f)
                                        val across = (d / flatHalf).coerceAtMost(1f) *
                                            (if (footwall) 1f else -1f)
                                        val hinge = cfg.riftHingeFloorShare.coerceIn(0f, 1f)
                                        val wedge = hinge + (1f - hinge) * (0.5f + 0.5f * across)
                                        // Symmetric again through the accommodation zone, so two
                                        // segments of opposite polarity meet without a step.
                                        val tilt = 1f + (wedge - 1f) * segment.taper

                                        val floor = -cfg.riftDepth * segment.depthFactor *
                                            segment.taper * tilt * strength *
                                            plateauFalloff(d, cfg.riftWidth, cfg.riftFloorShare)

                                        // High footwall on one flank, low hinge on the other —
                                        // and both fade to their mean at the join, as the trough
                                        // does.
                                        val flank = if (footwall) {
                                            1f
                                        } else {
                                            cfg.riftHingeShoulderShare.coerceAtLeast(0f)
                                        }
                                        val shoulder = cfg.riftShoulderHeight *
                                            segment.shoulderFactor *
                                            (1f + (flank - 1f) * segment.taper) * strength *
                                            ridgeAt(
                                                d,
                                                cfg.riftShoulderOffset,
                                                cfg.riftShoulderWidth * segment.widthFactor
                                            ) * roughness * alongRange

                                        // The accommodation zone itself: ground that rises between
                                        // two half-grabens, which is where the sill and the land
                                        // bridge between two gulfs come from.
                                        //
                                        // Its height is what E1's outlet notch has to saw through
                                        // where a rift stands on land, so it is deliberately
                                        // modest: a sill high enough to dam a half-graben for
                                        // twelve rounds of erosion leaves a lake the notch cannot
                                        // drain, which on seed 43 came out nearly twice the
                                        // Caspian's share of the map.
                                        val sill = cfg.riftSillHeight * strength *
                                            (1f - segment.taper) *
                                            beltFalloff(d, cfg.riftShoulderOffset)

                                        floor + shoulder + sill
                                    }
                                }

                            BoundaryClass.TRANSFORM_FAULT ->
                                cfg.mountainHeight * 0.12f * strength * narrow *
                                    (roughness - 0.75f)
                        }
                    }
                }
            }
        }

        stampHotspotChains(config, plates, plateId, uplift)

        val weight = cfg.tectonicWeight.coerceIn(0f, 1f)
        val result = FloatField(w, h)

        // Fine relief, an order of magnitude below anything the eye picks out of the shading. Both
        // the blurred plate base and the uplift falloff are very smooth, which leaves some plains
        // locally planar; D8 routing over a plane sends every cell the same way, so rivers there
        // come out as straight parallel lines that never join. This gives the water something to
        // converge on.
        val detailNoise = PerlinNoise(config.seed * 7919 + 13)
        val detailFrequency = cfg.detailFrequency.toFloat()

        // Position-derived detail noise; every cell writes its own index.
        parallelChunks(0, h) { startY, endY ->
            for (y in startY until endY) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val base = terrain.height.data[i] * (1f - weight) +
                        (0.5f + plateBase.data[i]) * weight
                    val detail = cfg.detailAmplitude * detailNoise.fbm(
                        x * detailFrequency / w,
                        y * detailFrequency / h,
                        4,
                        cfg.detailFrequency,
                        cfg.detailFrequency
                    )
                    result.data[i] = base + uplift.data[i] + detail
                }
            }
        }
        result.normalize()

        return PlateResult(
            plates, plateId, FloatField(w, h, dist), nearestType, nearestClass, result
        )
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
        val cfg = config.tectonics
        if (cfg.hotspotPlateFraction <= 0f || cfg.hotspotHeight == 0f) return
        if (cfg.hotspotRadius <= 0f || cfg.hotspotSpacing <= 0f) return

        val w = uplift.width
        val h = uplift.height
        val rnd = Random(config.seed * 31337 + 7)
        val sizeNoise = PerlinNoise(config.seed * 104729 + 4441)
        var ventIndex = 0

        plates.forEach { plate ->
            val roll = rnd.nextFloat()
            // Offset from the plate's own seed point, not a free point on the map. A hotspot
            // placed anywhere at all lands on some other plate nineteen times in twenty, and the
            // clip to the carrying plate in [stampSeamount] then erases the whole chain — which is
            // exactly what the first version of this did, on every seed tried.
            val spread = cfg.hotspotChainLength * 0.3f
            val originX = plate.seedX + (rnd.nextFloat() - 0.5f) * spread
            val originY = plate.seedY + (rnd.nextFloat() - 0.5f) * spread
            if (plate.type != PlateType.OCEANIC) return@forEach
            if (roll >= cfg.hotspotPlateFraction) return@forEach

            var travelled = 0f
            while (travelled <= cfg.hotspotChainLength) {
                // The hotspot stays put and the plate slides over it, so the volcano it built a
                // while ago has since been carried a while along the drift vector. Older means
                // further along, and lower: the crust cools and the seamount subsides with it.
                val cx = originX + plate.driftX * travelled
                val cy = originY + plate.driftY * travelled
                val age = travelled / cfg.hotspotChainLength
                val jitter = 0.6f + 0.8f *
                    (0.5f + 0.5f * sizeNoise.fbm(cx * 9f / w, cy * 9f / h, 2, 9, 9))
                        .coerceIn(0f, 1f)
                stampSeamount(
                    uplift, plateId, plate.id, cx, cy,
                    cfg.hotspotRadius, cfg.hotspotHeight * (1f - age) * (1f - age) * jitter,
                    config.seed, ventIndex, cfg.hotspotConeDetail
                )
                ventIndex++
                travelled += cfg.hotspotSpacing
            }
        }
    }

    /**
     * One seamount: a smooth cone of [radius] cells, wrapping in x and clipped in y.
     *
     * The falloff itself was always true Euclidean distance (`sqrt(dx*dx + dy*dy)`), never the
     * chamfer approximation [DistanceTransform] uses elsewhere in this file for plate assignment
     * and boundary distance — that was checked directly, by walking sixteen bearings out from a
     * vent with the old single-sample-per-cell code and computing the eight-fold component of the
     * resulting radius curve, and it measures near zero (relative amplitude ~0.003) wherever a
     * cone is read at sub-cell precision. What actually fails the same measurement read the plain
     * way — one sample per cell, nearest neighbour, exactly how every other stage reads a
     * [FloatField] — is small-radius rasterization: a stamp only four or five cells across does not
     * have enough grid resolution to render a circle, and the compass and diagonal directions (the
     * only ones a square grid can hit exactly) come out measurably larger than the directions in
     * between, which is an eight-sided artefact regardless of how continuous the underlying formula
     * is. [detail] fixes that by supersampling each cell — cheap, since the whole stamp is only a
     * few cells across — and, while at it, breaks the perfect symmetry it would otherwise leave
     * behind with a few low-amplitude harmonics of the rim radius, seeded from the world seed and
     * this vent's own index so no two cones are the same without a shared [Random] or a hash order.
     * Off reproduces the old single-sample, unmodulated stamp, which is what the guard's "before"
     * numbers were measured against.
     */
    private fun stampSeamount(
        uplift: FloatField,
        plateId: IntArray,
        plate: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        amplitude: Float,
        seed: Long,
        ventIndex: Int,
        detail: Boolean
    ) {
        if (amplitude <= 0f) return
        val w = uplift.width
        val h = uplift.height
        val r = radius.toInt() + 1

        val harmonics = if (detail) rimHarmonics(seedHash(seed, ventIndex.toLong())) else null
        // Sub-cell samples per axis, so a stamp only a few cells across is area-averaged rather
        // than point-sampled — the fix for the eight-fold artefact described above. Off (the old
        // behaviour) samples once, at the cell's own centre.
        val subSamples = if (detail) 5 else 1
        val subStep = 1f / subSamples
        val subOffset = (subStep - 1f) / 2f
        val subTotal = (subSamples * subSamples).toFloat()

        for (yy in (cy.toInt() - r)..(cy.toInt() + r)) {
            if (yy < 0 || yy >= h) continue
            for (xx in (cx.toInt() - r)..(cx.toInt() + r)) {
                var wrapped = xx % w
                if (wrapped < 0) wrapped += w
                val i = yy * w + wrapped
                if (plateId[i] != plate) continue

                var sum = 0f
                for (sy in 0 until subSamples) {
                    for (sx in 0 until subSamples) {
                        val px = xx + subOffset + sx * subStep
                        val py = yy + subOffset + sy * subStep
                        var dx = px - cx
                        if (dx > w / 2f) dx -= w
                        if (dx < -w / 2f) dx += w
                        val dy = py - cy
                        val distance = sqrt(dx * dx + dy * dy)
                        val rim = if (harmonics == null) {
                            radius
                        } else {
                            radius * (1f + rimModulation(harmonics, dx, dy))
                        }
                        if (distance >= rim) continue
                        val u = 1f - distance / rim
                        sum += u * u * (3f - 2f * u)
                    }
                }
                if (sum <= 0f) continue
                uplift.data[i] += amplitude * (sum / subTotal)
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
        val out = FloatArray(frequencies.size * 2)
        var h = hash
        for (idx in frequencies.indices) {
            h = h * 6364136223846793005L + 1442695040888963407L
            val ampBits = ((h ushr 40) and 0xFFFF).toFloat() / 0xFFFF.toFloat()
            h = h * 6364136223846793005L + 1442695040888963407L
            val phaseBits = ((h ushr 40) and 0xFFFF).toFloat() / 0xFFFF.toFloat()
            // Amplitude in [0.015, 0.045]: individually visible, together never enough to push a
            // cone's overall roundness past what the guard treats as faceted.
            out[idx * 2] = 0.015f + 0.03f * ampBits
            out[idx * 2 + 1] = phaseBits * (2f * PI.toFloat())
        }
        return out
    }

    /** The fractional change to a cone's rim radius at the bearing of ([dx], [dy]) from its vent. */
    private fun rimModulation(harmonics: FloatArray, dx: Float, dy: Float): Float {
        val frequencies = intArrayOf(2, 3, 5)
        val theta = atan2(dy, dx)
        var m = 0f
        for (idx in frequencies.indices) {
            val amp = harmonics[idx * 2]
            val phase = harmonics[idx * 2 + 1]
            m += amp * cos(frequencies[idx] * theta + phase)
        }
        return m
    }

    private fun createPlates(
        cfg: TectonicsConfig,
        width: Int,
        height: Int,
        rnd: Random
    ): List<Plate> {
        val count = cfg.plateCount.coerceAtLeast(2)
        val oceanicCount = (count * cfg.oceanicFraction).roundToInt().coerceIn(0, count)
        val types = MutableList(count) { if (it < oceanicCount) PlateType.OCEANIC else PlateType.CONTINENTAL }
        types.shuffle(rnd)

        return List(count) { id ->
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            Plate(
                id = id,
                seedX = rnd.nextInt(width),
                // Keeps plate seeds off the very edge, so polar rows belong to a real plate interior.
                seedY = (height * 0.06f).toInt() + rnd.nextInt((height * 0.88f).toInt().coerceAtLeast(1)),
                driftX = cos(angle),
                driftY = sin(angle),
                type = types[id]
            )
        }
    }

    /**
     * Chamfer-Voronoi assignment, then a noise domain-warp so boundaries meander instead of
     * looking like straight Voronoi edges.
     */
    private fun assignPlates(config: WorldGenConfig, plates: List<Plate>): IntArray {
        val w = config.width
        val h = config.height

        val dist = FloatArray(w * h) { DistanceTransform.INFINITE }
        val raw = IntArray(w * h) { -1 }
        plates.forEach { plate ->
            val i = plate.seedY * w + plate.seedX
            dist[i] = 0f
            raw[i] = plate.id
        }
        DistanceTransform.run(w, h, dist, raw)

        val warpX = PerlinNoise(config.seed * 6151 + 3)
        val warpY = PerlinNoise(config.seed * 6151 + 9)
        val amplitude = config.tectonics.boundaryFalloff * 1.6f

        val warped = IntArray(w * h)
        // Reads `raw`, writes its own cell of `warped` — no overlap between rows.
        parallelChunks(0, h) { startY, endY ->
            for (y in startY until endY) {
                for (x in 0 until w) {
                    val dx = amplitude * warpX.fbm(x * 6f / w, y * 6f / h, 4, 6, 6)
                    val dy = amplitude * warpY.fbm(x * 6f / w, y * 6f / h, 4, 6, 6)
                    var sx = (x + dx).roundToInt() % w
                    if (sx < 0) sx += w
                    val sy = (y + dy).roundToInt().coerceIn(0, h - 1)
                    warped[y * w + x] = raw[sy * w + sx]
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

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val a = plates[plateId[i]]
                var other: Plate? = null

                for (n in neighbours) {
                    val ny = y + n[1]
                    if (ny < 0 || ny >= height) continue
                    var nx = (x + n[0]) % width
                    if (nx < 0) nx += width

                    val candidate = plates[plateId[ny * width + nx]]
                    if (candidate.id != a.id) {
                        other = candidate
                        break
                    }
                }

                val b = other ?: continue
                val key = if (a.id < b.id) a.id * plates.size + b.id else b.id * plates.size + a.id
                val interaction = interactions.getOrPut(key) {
                    interactionOf(plates[key / plates.size], plates[key % plates.size], width)
                }
                boundaries[i] = Boundary(
                    interaction,
                    oceanicSide = a.type == PlateType.OCEANIC,
                    overridingSide = a.id == interaction.overridingId
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
        val cfg = config.tectonics
        if (!cfg.riftSegmentation) return
        val w = config.width
        val h = config.height

        // Ascending index order: nothing below may depend on the iteration order of a hash map.
        val riftCells = boundaries.keys
            .filter { boundaries[it]!!.interaction.pairClass == BoundaryClass.CONTINENTAL_RIFT }
            .sorted()
        if (riftCells.isEmpty()) return

        // Which pair each rift cell belongs to, as an index into first-encounter order. Two
        // different rift pairs can touch at a triple junction and must not be walked as one rift.
        val pairs = ArrayList<PairInteraction>()
        val pairAt = IntArray(w * h) { NOT_RIFT }
        riftCells.forEach { cell ->
            val interaction = boundaries[cell]!!.interaction
            var index = -1
            for (k in pairs.indices) if (pairs[k] === interaction) { index = k; break }
            if (index < 0) {
                pairs.add(interaction)
                index = pairs.size - 1
            }
            pairAt[cell] = index
        }

        val arc = IntArray(w * h) { -1 }
        val run = ArrayList<Int>()
        val queue = ArrayList<Int>()

        // Breadth-first over one run's cells, from [source]; leaves the hop count in `arc` and
        // returns the farthest cell, ties broken by the lower index.
        fun sweep(source: Int, pair: Int): Int {
            run.forEach { arc[it] = -1 }
            queue.clear()
            arc[source] = 0
            queue.add(source)
            var head = 0
            var farthest = source
            while (head < queue.size) {
                val cell = queue[head++]
                val depth = arc[cell]
                if (depth > arc[farthest] || (depth == arc[farthest] && cell < farthest)) {
                    farthest = cell
                }
                val x = cell % w
                val y = cell / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        val n = ny * w + nx
                        if (pairAt[n] != pair || arc[n] >= 0) continue
                        arc[n] = depth + 1
                        queue.add(n)
                    }
                }
            }
            return farthest
        }

        val minLength = (cfg.riftSegmentMin * w).coerceAtLeast(2f)
        val maxLength = (cfg.riftSegmentMax * w).coerceAtLeast(minLength)
        val accommodation = (cfg.riftAccommodation * w).coerceAtLeast(1f)

        riftCells.forEach { start ->
            val pair = pairAt[start]
            if (pair < 0) return@forEach

            // Collect this connected run, then take its two passes. `start` is the run's lowest
            // index, since the cells are walked in ascending order and a run is claimed whole.
            run.clear()
            queue.clear()
            queue.add(start)
            arc[start] = 0
            var head = 0
            while (head < queue.size) {
                val cell = queue[head++]
                run.add(cell)
                val x = cell % w
                val y = cell / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        val n = ny * w + nx
                        if (pairAt[n] != pair || arc[n] >= 0) continue
                        arc[n] = 0
                        queue.add(n)
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
            val depth = ArrayList<Float>()
            val shoulder = ArrayList<Float>()
            val width = ArrayList<Float>()
            joins.add(0f)
            var cursor = 0f
            while (cursor < length) {
                cursor += minLength + (maxLength - minLength) * draw()
                joins.add(cursor.coerceAtMost(length))
                depth.add(1f + cfg.riftSegmentDepthVariation * (2f * draw() - 1f))
                // One draw for both the height and the width of the segment's shoulders, not two.
                // Flexural uplift scales with the throw on the fault, so the footwall of a bigger
                // half-graben stands both higher and broader — and, less prettily, two independent
                // draws let a segment come out at 1.4 times the height and 0.6 times the width,
                // which is a knife-edge ridge. Where such a ridge crosses shallow sea it clears
                // the surface as a strip of land a couple of cells wide with a strait either side,
                // the exact failure `RibbonLandTest` exists to catch: measured on seed 234475 at
                // 1024, that combination left a 706-cell ribbon on a rift shoulder that survived
                // erosion, and tying the two together removes it.
                val spread = cfg.riftSegmentShoulderVariation
                val size = (1f + spread * (2f * draw() - 1f)).coerceAtLeast(0.2f)
                shoulder.add(size)
                width.add(size)
            }
            if (joins.size < 2) {
                joins.add(length)
                depth.add(1f)
                shoulder.add(1f)
                width.add(1f)
            }
            // A sliver left over at the far end is not a half-graben; it joins its neighbour.
            val last = joins.size - 1
            if (joins.size > 2 && joins[last] - joins[last - 1] < minLength * 0.5f) {
                joins.removeAt(last - 1)
                depth.removeAt(depth.size - 1)
                shoulder.removeAt(shoulder.size - 1)
                width.removeAt(width.size - 1)
            }
            // Which flank the first segment hangs from; the rest alternate off it.
            val parity = ((bits ushr 17) and 1L) == 0L

            run.forEach { cell ->
                val s = arc[cell].toFloat()
                var k = 0
                while (k < joins.size - 2 && s >= joins[k + 1]) k++
                val toJoin = minOf(s - joins[k], joins[k + 1] - s).coerceAtLeast(0f)
                val u = (toJoin / accommodation).coerceIn(0f, 1f)
                boundaries[cell]!!.segment = RiftSegment(
                    depthFactor = depth[k],
                    shoulderFactor = shoulder[k],
                    widthFactor = width[k],
                    footwallOnLow = (k % 2 == 0) == parity,
                    taper = u * u * (3f - 2f * u)
                )
            }

            run.forEach {
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
    private fun interactionOf(a: Plate, b: Plate, width: Int): PairInteraction {
        var dx = (b.seedX - a.seedX).toFloat()
        // Shortest way round the cylinder, so plates either side of the seam behave sanely.
        if (dx > width / 2f) dx -= width
        if (dx < -width / 2f) dx += width
        val dy = (b.seedY - a.seedY).toFloat()

        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val nx = dx / length
        val ny = dy / length

        // Positive when the pair is closing.
        val convergence = ((a.driftX - b.driftX) * nx + (a.driftY - b.driftY) * ny) / 2f
        val type = when {
            convergence > 0.15f -> BoundaryType.CONVERGENT
            convergence < -0.15f -> BoundaryType.DIVERGENT
            else -> BoundaryType.TRANSFORM
        }
        val bothContinental = a.type == PlateType.CONTINENTAL && b.type == PlateType.CONTINENTAL
        val bothOceanic = a.type == PlateType.OCEANIC && b.type == PlateType.OCEANIC

        // Dense oceanic crust goes under buoyant continental crust, so where the pair is mixed the
        // continent overrides and there is nothing to choose. Where both are the same, the choice
        // is genuinely arbitrary and has to be made by something that cannot vary between cells or
        // between runs: the lower id, a total order on the pair.
        val overridingId = when {
            a.type == b.type -> if (a.id < b.id) a.id else b.id
            a.type == PlateType.CONTINENTAL -> a.id
            else -> b.id
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
            strength = abs(convergence).coerceIn(0.12f, 1f),
            continentalCollision = bothContinental,
            pairClass = pairClass,
            overridingId = overridingId,
            lowId = if (a.id < b.id) a.id else b.id
        )
    }

    /**
     * Sharp-crested profile, for features that really are narrow: trenches, rifts, ridges at
     * spreading centres.
     */
    private fun falloff(distance: Float, range: Float): Float {
        if (distance >= range) return 0f
        return (1f - distance / range).pow(1.6f)
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
    private fun volcanicChain(noise: PerlinNoise, x: Int, y: Int, w: Int, h: Int): Float {
        val n = 0.5f + 0.5f * noise.fbm(x * 26f / w, y * 26f / h, 2, 26, 26)
        return n.coerceIn(0f, 1f).pow(2.5f) * 1.6f
    }

    /** The largest value the along-strike width swell can take; see `widthScale` in [generate]. */
    private const val MAX_WIDTH_SCALE = 1.4f

    /** The largest value the finer width jitter can take; see `edgeJitter` in [generate]. */
    private const val MAX_EDGE_JITTER = 1.28f
}
