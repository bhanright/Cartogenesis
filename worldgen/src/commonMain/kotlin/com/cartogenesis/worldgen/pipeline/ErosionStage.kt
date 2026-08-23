package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt

data class ErosionResult(
    /** Height after erosion, in the same 0..1 range the uplift produced. */
    val height: FloatField
)

/**
 * Step 4: wear the terrain down.
 *
 * Uplift alone leaves mountains with the shape the falloff gave them, which is the shape no real
 * range has. Rock does not stand at an arbitrary angle: past a critical slope it fails and slides,
 * and the debris piles against the foot of the slope until the pile itself reaches that angle. So
 * a crest is lowered, an apron is built out around its base, and a knife edge becomes a ridge with
 * flanks — the difference between a wall and a mountain.
 *
 * This is thermal erosion (Musgrave et al.). Every pass, each cell hands a share of whatever
 * material sits above the critical slope to its lower neighbours, in proportion to how far below
 * that slope each of them is. It conserves mass: nothing is created, and what leaves a crest
 * arrives somewhere downhill. That matters for more than tidiness — the debris is what widens a
 * ridge's footprint, and a wider footprint is what stops a belt crossing shallow sea as a strip of
 * land one cell wide.
 *
 * What it deliberately does not do is carve valleys. That needs water routed over the terrain, and
 * routing has to happen after the depressions are filled, which is two stages further on. This
 * runs on the raw uplift, before sea level is chosen, because eroding the terrain changes which
 * elevation the sea-level percentile lands on.
 */
object ErosionStage {

    fun apply(config: WorldGenConfig, height: FloatField): ErosionResult {
        val cfg = config.erosion
        if (!cfg.enabled || cfg.passes <= 0) return ErosionResult(height)

        val w = config.width
        val h = config.height
        // Three grid-sized buffers and no more: at export resolutions each one is tens of
        // megabytes, and this stage runs while the rest of the pipeline is still holding its own.
        var read = height.data.copyOf()
        var write = FloatArray(w * h)
        // How much material each cell hands over per unit of excess it holds. Storing the ratio
        // rather than the total and the divisor saves a whole buffer, since the receiving pass
        // only ever needs the product.
        val giveRate = FloatArray(w * h)

        // The critical slope is held in elevation per unit of map width, not per cell, so the same
        // terrain wears to the same shape whatever grid it is computed on. Cells are treated as
        // square here, as they are everywhere else in the pipeline.
        val orthogonal = cfg.talus / w
        val diagonal = orthogonal * SQRT2

        repeat(cfg.passes) {
            val current = read
            parallelChunks(0, h) { startY, endY ->
                for (y in startY until endY) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val here = current[i]
                        var excess = 0f
                        var steepest = 0f

                        for (n in 0 until 8) {
                            val ny = y + NEIGHBOUR_DY[n]
                            if (ny < 0 || ny >= h) continue
                            val nx = (x + NEIGHBOUR_DX[n] + w) % w
                            val drop = here - current[ny * w + nx]
                            if (drop <= 0f) continue
                            if (drop > steepest) steepest = drop
                            val limit = if (n < 4) orthogonal else diagonal
                            if (drop > limit) excess += drop - limit
                        }

                        // Capped at half the steepest drop, or a cell could hand over more than it
                        // stands above its neighbour and invert the slope it was trying to relax.
                        giveRate[i] = if (excess <= 0f) 0f
                        else minOf(cfg.rate * excess, steepest * 0.5f) / excess
                    }
                }
            }

            val out = write
            parallelChunks(0, h) { startY, endY ->
                for (y in startY until endY) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val here = current[i]
                        var received = 0f
                        var given = 0f

                        for (n in 0 until 8) {
                            val ny = y + NEIGHBOUR_DY[n]
                            if (ny < 0 || ny >= h) continue
                            val nx = (x + NEIGHBOUR_DX[n] + w) % w
                            val j = ny * w + nx
                            val limit = if (n < 4) orthogonal else diagonal

                            val incoming = current[j] - here
                            if (incoming > limit) received += giveRate[j] * (incoming - limit)
                            else if (-incoming > limit) given += giveRate[i] * (-incoming - limit)
                        }

                        out[i] = here - given + received
                    }
                }
            }

            val swap = read
            read = write
            write = swap
        }

        return ErosionResult(FloatField(w, h, read))
    }

    private val SQRT2 = sqrt(2f)

    // Orthogonal neighbours first, so the loop can tell them from the diagonals by index alone.
    private val NEIGHBOUR_DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val NEIGHBOUR_DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
