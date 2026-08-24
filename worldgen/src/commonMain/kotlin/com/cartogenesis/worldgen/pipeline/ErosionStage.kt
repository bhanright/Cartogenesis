package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.Acceleration
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
 * This is thermal erosion (Musgrave et al.). Every sweep, each cell hands a share of whatever
 * material sits above the critical slope to its lower neighbours, in proportion to how far below
 * that slope each of them is. It conserves mass: nothing is created, and what leaves a crest
 * arrives somewhere downhill. That matters for more than tidiness — the debris is what widens a
 * ridge's footprint, and a wider footprint is what stops a belt crossing shallow sea as a strip of
 * land a few cells wide.
 *
 * What it deliberately does not do is carve valleys. That needs water routed over the terrain, and
 * routing has to happen after the depressions are filled, which is two stages further on. This
 * runs on the raw uplift, before sea level is chosen, because eroding the terrain changes which
 * elevation the sea-level percentile lands on.
 */
object ErosionStage {

    /**
     * Side of the activity tiles, in cells. Small enough that a settled ocean floor is skipped in
     * useful pieces, large enough that the bookkeeping stays a rounding error.
     */
    private const val TILE = 32

    suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        accelerator: ErosionAccelerator? = null
    ): ErosionResult {
        val cfg = config.erosion
        if (!cfg.enabled || cfg.passes <= 0) return ErosionResult(height)

        if (cfg.acceleration == Acceleration.GPU && accelerator != null) {
            // A null result means the accelerator looked at the job and declined it, which is a
            // normal outcome rather than a failure, so the CPU simply picks it up.
            val accelerated = accelerator.erode(
                config.width, config.height, height.data, cfg.talus, cfg.passes, cfg.rate
            )
            if (accelerated != null) {
                return ErosionResult(FloatField(config.width, config.height, accelerated))
            }
        }
        return apply(config, height, skipSettled = true)
    }

    /**
     * @param skipSettled leave the settled parts of the map alone instead of re-scanning them.
     *   Only ever false in the test that proves doing so changes nothing.
     */
    internal fun apply(
        config: WorldGenConfig,
        height: FloatField,
        skipSettled: Boolean
    ): ErosionResult {
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

        // Each sweep moves a fraction of the excess, so the excess decays geometrically and never
        // reaches zero — without a floor, ground that is done moving in any meaningful sense still
        // reports itself as active forever, and nothing can ever be skipped. A thousandth of the
        // critical slope is about five centimetres of rock against a six-kilometre range.
        val settled = orthogonal * 1e-3f

        // Most of a map reaches the critical slope early and then never moves again — ocean floor,
        // plains, anything the uplift left gentle. Re-scanning all of it every sweep is what made
        // this the most expensive stage in the pipeline, so tiles that have gone quiet are skipped.
        //
        // This is exact rather than an approximation. A cell changes only if it holds material
        // above the critical slope or a neighbour does, and material moves one cell per sweep, so
        // a tile can only be disturbed by its immediate neighbours. Dilating the set of tiles that
        // still hold excess therefore covers every cell that can possibly change.
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        val hasExcess = BooleanArray(tilesX * tilesY)
        var canChange = BooleanArray(tilesX * tilesY) { true }
        var canHoldExcess = BooleanArray(tilesX * tilesY) { true }

        repeat(cfg.passes) {
            val current = read
            val scan = canHoldExcess

            parallelChunks(0, tilesY) { startTile, endTile ->
                for (ty in startTile until endTile) {
                    for (tx in 0 until tilesX) {
                        val tile = ty * tilesX + tx
                        if (skipSettled && !scan[tile]) continue
                        var tileExcess = false

                        val y1 = minOf((ty + 1) * TILE, h)
                        val x1 = minOf((tx + 1) * TILE, w)
                        for (y in ty * TILE until y1) {
                            for (x in tx * TILE until x1) {
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

                                // Capped at half the steepest drop, or a cell could hand over more
                                // than it stands above its neighbour and invert the very slope it
                                // was trying to relax.
                                if (excess <= settled) {
                                    giveRate[i] = 0f
                                } else {
                                    giveRate[i] = minOf(cfg.rate * excess, steepest * 0.5f) / excess
                                    tileExcess = true
                                }
                            }
                        }
                        hasExcess[tile] = tileExcess
                    }
                }
            }

            // Cells change only in tiles holding excess or bordering one; and the sweep after this
            // has to look one tile wider still, because a tile next to a changed tile sees new
            // drops across its own edge.
            canChange = dilate(hasExcess, tilesX, tilesY)
            canHoldExcess = dilate(canChange, tilesX, tilesY)

            val out = write
            val change = canChange
            parallelChunks(0, tilesY) { startTile, endTile ->
                for (ty in startTile until endTile) {
                    for (tx in 0 until tilesX) {
                        val tile = ty * tilesX + tx
                        val y1 = minOf((ty + 1) * TILE, h)
                        val x1 = minOf((tx + 1) * TILE, w)

                        if (skipSettled && !change[tile]) {
                            for (y in ty * TILE until y1) {
                                val row = y * w
                                current.copyInto(out, row + tx * TILE, row + tx * TILE, row + x1)
                            }
                            continue
                        }

                        for (y in ty * TILE until y1) {
                            for (x in tx * TILE until x1) {
                                val i = y * w + x
                                val here = current[i]
                                var received = 0f
                                var given = 0f

                                for (n in 0 until 8) {
                                    val ny = y + NEIGHBOUR_DY[n]
                                    if (ny < 0 || ny >= h) continue
                                    val nx = (x + NEIGHBOUR_DX[n] + w) % w
                                    val limit = if (n < 4) orthogonal else diagonal

                                    val incoming = current[ny * w + nx] - here
                                    if (incoming > limit) {
                                        received += giveRate[ny * w + nx] * (incoming - limit)
                                    } else if (-incoming > limit) {
                                        given += giveRate[i] * (-incoming - limit)
                                    }
                                }

                                out[i] = here - given + received
                            }
                        }
                    }
                }
            }

            val swap = read
            read = write
            write = swap
        }

        return ErosionResult(FloatField(w, h, read))
    }

    /** Grows a tile mask by one tile in every direction, wrapping in x as the world does. */
    private fun dilate(mask: BooleanArray, tilesX: Int, tilesY: Int): BooleanArray {
        val grown = BooleanArray(mask.size)
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                if (!mask[ty * tilesX + tx]) continue
                for (dy in -1..1) {
                    val ny = ty + dy
                    if (ny < 0 || ny >= tilesY) continue
                    for (dx in -1..1) {
                        grown[ny * tilesX + ((tx + dx + tilesX) % tilesX)] = true
                    }
                }
            }
        }
        return grown
    }

    private val SQRT2 = sqrt(2f)

    // Orthogonal neighbours first, so the loop can tell them from the diagonals by index alone.
    private val NEIGHBOUR_DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val NEIGHBOUR_DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
