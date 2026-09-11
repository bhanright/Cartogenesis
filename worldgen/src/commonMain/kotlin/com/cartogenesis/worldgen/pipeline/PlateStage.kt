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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class PlateType { OCEANIC, CONTINENTAL }

enum class BoundaryType { CONVERGENT, DIVERGENT, TRANSFORM }

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
        val continentalCollision: Boolean
    )

    private class Boundary(
        val interaction: PairInteraction,
        /** Whether this particular cell sits on the oceanic plate of the pair. */
        val oceanicSide: Boolean
    )

    fun generate(config: WorldGenConfig, terrain: TerrainResult): PlateResult {
        val w = config.width
        val h = config.height
        val cfg = config.tectonics
        val rnd = Random(config.seed * 7919 + 13)

        val plates = createPlates(cfg, w, h, rnd)
        val plateId = assignPlates(config, plates)
        val boundaries = classifyBoundaries(w, h, plateId, plates)

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
        val uplift = FloatField(w, h)
        val nearestType = IntArray(w * h) { -1 }

        if (hasBoundaries) {
            val range = cfg.boundaryFalloff
            // Each cell writes only its own uplift entry, and the roughness comes from position rather than a running RNG, so this splits cleanly across cores.
            parallelChunks(0, h) { startY, endY ->
                for (y in startY until endY) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val boundary = boundaries[label[i]] ?: continue
                        val interaction = boundary.interaction
                        nearestType[i] = interaction.type.ordinal

                        val d = dist[i]
                        // A belt that keeps the same width for its whole length reads as drawn on
                        // even once its height varies, so the width swells and pinches too. The
                        // noise is sampled on position, so neighbouring cells agree and the belt
                        // stays continuous rather than dissolving into blotches.
                        val widthScale = 0.55f + 0.85f *
                            (0.5f + 0.5f * widthNoise.fbm(x * 7f / w, y * 7f / h, 3, 7, 7))
                        val wide = beltFalloff(d, range * widthScale)
                        val narrow = falloff(d, range * 0.45f)
                        if (wide <= 0f && narrow <= 0f) continue

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

                        uplift.data[i] += when (interaction.type) {
                            BoundaryType.CONVERGENT ->
                                if (interaction.continentalCollision) {
                                    cfg.mountainHeight * interaction.strength * wide * roughness * alongRange
                                } else if (boundary.oceanicSide) {
                                    -cfg.trenchDepth * interaction.strength * narrow
                                } else {
                                    cfg.mountainHeight * 0.8f * interaction.strength * wide * roughness * alongRange
                                }

                            BoundaryType.DIVERGENT ->
                                if (boundary.oceanicSide) {
                                    cfg.mountainHeight * 0.22f * interaction.strength * narrow
                                } else {
                                    -cfg.mountainHeight * 0.3f * interaction.strength * narrow
                                }

                            BoundaryType.TRANSFORM ->
                                cfg.mountainHeight * 0.12f * interaction.strength * narrow *
                                    (roughness - 0.75f)
                        }
                    }
                }
            }
        }

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

        return PlateResult(plates, plateId, FloatField(w, h, dist), nearestType, result)
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
                boundaries[i] = Boundary(interaction, oceanicSide = a.type == PlateType.OCEANIC)
            }
        }
        return boundaries
    }

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
        return PairInteraction(
            type = type,
            strength = abs(convergence).coerceIn(0.12f, 1f),
            continentalCollision = a.type == PlateType.CONTINENTAL &&
                b.type == PlateType.CONTINENTAL
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
}
