package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ErosionResult(
    /** Height after erosion, one entry per cell, row-major, in the 0..1 range uplift produced. */
    val height: FloatField,
    /**
     * Whether an accelerator did the thermal sweeps, as opposed to looking at the job and
     * declining it, which is a normal outcome the processor then covers with the same answer.
     * The two are indistinguishable from the height alone, and the guard that asks whether the
     * card ran at all reads this rather than timing the two against each other on a shared
     * machine. Not saved: it is a fact about one run, not about the world.
     */
    val sweptOnDevice: Boolean = false
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
    private const val TILE_CELLS = 32

    /** Neighbours every cell trades material with: the four orthogonal ones, then the diagonals. */
    private const val NEIGHBOUR_COUNT = 8

    /** How many of those come first in the tables below: the neighbours along a row or a column. */
    private const val ORTHOGONAL_NEIGHBOURS = 4

    /**
     * The excess a cell may still hold and be called settled, as a share of the critical slope.
     *
     * Each sweep moves a fraction of the excess, so the excess decays geometrically and never
     * reaches zero — without a floor, ground that is done moving in any meaningful sense still
     * reports itself as active for ever and nothing can be skipped. A thousandth of the critical
     * slope is about a centimetre of fall per kilometre of ground.
     */
    private const val SETTLED_SHARE_OF_CRITICAL_SLOPE = 1e-3f

    /**
     * The most of the steepest drop away from a cell that the cell may hand over in one sweep.
     *
     * Half, or a cell could give a neighbour more than it stands above that neighbour and invert
     * the very slope it was relaxing.
     */
    private const val MAX_SHARE_OF_STEEPEST_DROP = 0.5f

    /**
     * The whole stage: the thermal sweeps, then the hydraulic rounds with a share of the sweeps
     * relaxing the terrain between each of them.
     *
     * [height] is the uplifted terrain in its own 0..1 units and is not modified; the result is a
     * separate field in the same units. [accelerator] is offered the sweeps and may decline them,
     * in which case they run on the CPU.
     */
    suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        upliftRateMmPerYear: FloatField? = null,
        accelerator: ErosionAccelerator? = null
    ): ErosionResult = apply(config, height, upliftRateMmPerYear, accelerator, onRound = null)

    /**
     * @param upliftRateMmPerYear how fast the rock is still rising under each cell, which the
     *   hydraulic rounds add to the terrain round by round; null runs the rounds without the
     *   tectonics, which is the control the uplift guards are measured against.
     * @param onRound handed each hydraulic round's mass budget as it closes, for the guard that
     *   checks the sediment bookkeeping adds up. Purely an observer — passing it changes nothing,
     *   except that the round's pit census is only counted when someone asked for it.
     * @param log handed which mechanism laid sediment on which cell, for the guards that ask what
     *   shape each of them makes. An observer on the same terms.
     * @param receiverClamp whether the incision is bounded below by the receiver's new elevation.
     *   Only ever false in `ReceiverClampTest`, which is where the guard is shown to fail without
     *   it; nothing outside the tests can reach this.
     */
    internal suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        upliftRateMmPerYear: FloatField?,
        accelerator: ErosionAccelerator?,
        onRound: ((RoundMass) -> Unit)?,
        log: DepositionLog? = null,
        receiverClamp: Boolean = true,
        /** See `HydraulicErosion.apply`: every routing pass's weight sum, for the guard. */
        weightSums: ((String, Double, Int) -> Unit)? = null,
        /**
         * Whether the rounds shield the incision with the plant cover.
         *
         * Only ever false in `ClimateFedErosionTest`, which is where the cover's own guard is shown
         * to fail without it. Kept off `WorldGenConfig` for the reason the receiver clamp is: the
         * shielding is not a taste, and a world generated with the rain but without the cover is
         * not a world anybody wants, only a control.
         */
        shieldCut: Boolean = true,
        /** See `HydraulicErosion.apply`: every cell's cut before the clamp, for the unit guards. */
        incisionWatch: IncisionWatch? = null
    ): ErosionResult {
        if (!config.erosion.enabled) return ErosionResult(height)

        // Rock first, then water. The flanks have to exist before anything can cut into them, and
        // the channels the water carves are the ones the river stage will later find and draw.
        val weathered = thermalErosion(config, height, accelerator)

        // A full thermal budget again, spread across the rounds, so material still moves one cell
        // per sweep however fine the grid. Halving it was tried and put back: walls left short of
        // the critical slope are steeper on a finer grid, and at half the budget the same world
        // came out 1.32 times steeper at 2048 than at 512 — the exact failure this relaxation
        // exists to prevent, and `ResolutionScalingTest`'s to catch.
        val erosion = config.erosion
        val sweepsPerRound =
            (sweepsFor(config) / erosion.hydraulicRounds.coerceAtLeast(1)).coerceAtLeast(1)

        return ErosionResult(
            HydraulicErosion.apply(
                config, weathered.height, config.seaLevel, upliftRateMmPerYear, onRound, log,
                receiverClamp, weightSums, shieldCut, incisionWatch
            ) { field ->
                thermalErosion(config, field, accelerator, sweepsPerRound).height
            },
            sweptOnDevice = weathered.sweptOnDevice
        )
    }

    /**
     * Slope-limited failure — the sweeps that give a mountain its flanks — wherever the world is
     * set to run them, which is the accelerator if it will take the job and the CPU otherwise.
     */
    private suspend fun thermalErosion(
        config: WorldGenConfig,
        height: FloatField,
        accelerator: ErosionAccelerator?,
        sweeps: Int = sweepsFor(config)
    ): ErosionResult {
        val erosion = config.erosion
        if (sweeps <= 0) return ErosionResult(height)

        // Asked before the batch rather than only inside it. A batch handed to the graphics card is
        // a single call that cannot be interrupted part-way, so the place to notice a stop is
        // before one is started; the round the reader interrupted finishes and frees its buffers,
        // and no further round begins.
        currentCoroutineContext().ensureActive()

        if (erosion.acceleration == Acceleration.GPU && accelerator != null) {
            // A null result means the accelerator looked at the job and declined it, which is a
            // normal outcome rather than a failure, so the CPU simply picks it up.
            val accelerated = accelerator.erode(
                config.width,
                config.height,
                height.data,
                thermalLimits(config),
                sweeps,
                erosion.rate
            )
            if (accelerated != null) {
                return ErosionResult(
                    FloatField(config.width, config.height, accelerated), sweptOnDevice = true
                )
            }
        }
        return thermalSweep(config, height, skipSettled = true, sweeps = sweeps)
    }

    /**
     * The thermal sweeps on the CPU, and nothing else — not the hydraulic rounds, which is what
     * [apply] adds on top.
     *
     * [height] is not modified; the result is a separate field in the same units. Named apart from
     * [apply] rather than overloading it: a caller that wanted the whole stage and reached this by
     * accident would silently lose the water.
     *
     * @param skipSettled leave the settled parts of the map alone instead of re-scanning them.
     *   Only ever false in the test that proves doing so changes nothing.
     */
    internal suspend fun thermalSweep(
        config: WorldGenConfig,
        height: FloatField,
        skipSettled: Boolean,
        sweeps: Int = sweepsFor(config)
    ): ErosionResult {
        val erosion = config.erosion
        if (!erosion.enabled || sweeps <= 0) return ErosionResult(height)

        val cellsAcross = config.width
        val cellsDown = config.height

        // Three grid-sized buffers and no more: at export resolutions each one is tens of
        // megabytes, and this stage runs while the rest of the pipeline is still holding its own.
        var heights = height.data.copyOf()
        var nextHeights = FloatArray(cellsAcross * cellsDown)
        // How much material each cell hands over per unit of excess it holds. Storing the ratio
        // rather than the total and the divisor saves a whole buffer, since the receiving pass
        // only ever needs the product.
        val giveRate = FloatArray(cellsAcross * cellsDown)

        // The critical slope arrives as a fall in metres per kilometre and is turned into a drop to
        // each kind of neighbour here, once, so the same terrain wears to the same shape whatever
        // grid it is computed on and whichever way it faces.
        val limits = thermalLimits(config)
        val limitByNeighbour = FloatArray(NEIGHBOUR_COUNT) { neighbour ->
            when {
                neighbour >= ORTHOGONAL_NEIGHBOURS -> limits.diagonal
                NEIGHBOUR_ROW_STEP[neighbour] != 0 -> limits.northSouth
                else -> limits.eastWest
            }
        }
        val settled = limits.eastWest * SETTLED_SHARE_OF_CRITICAL_SLOPE

        // Most of a map reaches the critical slope early and then never moves again — ocean floor,
        // plains, anything the uplift left gentle. Re-scanning all of it every sweep is what made
        // this the most expensive stage in the pipeline, so tiles that have gone quiet are skipped.
        //
        // This is exact rather than an approximation. A cell changes only if it holds material
        // above the critical slope or a neighbour does, and material moves one cell per sweep, so
        // a tile can only be disturbed by its immediate neighbours. Dilating the set of tiles that
        // still hold excess therefore covers every cell that can possibly change.
        val tilesAcross = (cellsAcross + TILE_CELLS - 1) / TILE_CELLS
        val tilesDown = (cellsDown + TILE_CELLS - 1) / TILE_CELLS
        val hasExcess = BooleanArray(tilesAcross * tilesDown)
        var canChange = BooleanArray(tilesAcross * tilesDown) { true }
        var canHoldExcess = BooleanArray(tilesAcross * tilesDown) { true }

        repeat(sweeps) {
            // One sweep is one walk of the grid, and the finest a stop can be answered at: the
            // sweeps that open the stage and the few that relax the field between hydraulic rounds
            // all pass through here, so a reader who presses Stop waits out at most one of them.
            currentCoroutineContext().ensureActive()

            val current = heights
            val scan = canHoldExcess

            parallelChunks(0, tilesDown) { startTile, endTile ->
                for (tileRow in startTile until endTile) {
                    for (tileColumn in 0 until tilesAcross) {
                        val tile = tileRow * tilesAcross + tileColumn
                        if (skipSettled && !scan[tile]) continue
                        var tileExcess = false

                        val rowEnd = minOf((tileRow + 1) * TILE_CELLS, cellsDown)
                        val columnEnd = minOf((tileColumn + 1) * TILE_CELLS, cellsAcross)
                        for (row in tileRow * TILE_CELLS until rowEnd) {
                            for (column in tileColumn * TILE_CELLS until columnEnd) {
                                val cell = row * cellsAcross + column
                                val here = current[cell]
                                var excess = 0f
                                var steepestDrop = 0f

                                for (neighbour in 0 until NEIGHBOUR_COUNT) {
                                    val neighbourRow = row + NEIGHBOUR_ROW_STEP[neighbour]
                                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                                    val neighbourColumn =
                                        (column + NEIGHBOUR_COLUMN_STEP[neighbour] + cellsAcross) %
                                            cellsAcross
                                    val drop =
                                        here - current[neighbourRow * cellsAcross + neighbourColumn]
                                    if (drop <= 0f) continue
                                    if (drop > steepestDrop) steepestDrop = drop
                                    val limit = limitByNeighbour[neighbour]
                                    if (drop > limit) excess += drop - limit
                                }

                                if (excess <= settled) {
                                    giveRate[cell] = 0f
                                } else {
                                    giveRate[cell] = minOf(
                                        erosion.rate * excess,
                                        steepestDrop * MAX_SHARE_OF_STEEPEST_DROP
                                    ) / excess
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
            canChange = dilate(hasExcess, tilesAcross, tilesDown)
            canHoldExcess = dilate(canChange, tilesAcross, tilesDown)

            val writeTo = nextHeights
            val changeable = canChange
            parallelChunks(0, tilesDown) { startTile, endTile ->
                for (tileRow in startTile until endTile) {
                    for (tileColumn in 0 until tilesAcross) {
                        val tile = tileRow * tilesAcross + tileColumn
                        val rowEnd = minOf((tileRow + 1) * TILE_CELLS, cellsDown)
                        val columnEnd = minOf((tileColumn + 1) * TILE_CELLS, cellsAcross)

                        if (skipSettled && !changeable[tile]) {
                            for (row in tileRow * TILE_CELLS until rowEnd) {
                                val rowStart = row * cellsAcross
                                current.copyInto(
                                    writeTo,
                                    rowStart + tileColumn * TILE_CELLS,
                                    rowStart + tileColumn * TILE_CELLS,
                                    rowStart + columnEnd
                                )
                            }
                            continue
                        }

                        for (row in tileRow * TILE_CELLS until rowEnd) {
                            for (column in tileColumn * TILE_CELLS until columnEnd) {
                                val cell = row * cellsAcross + column
                                val here = current[cell]
                                var received = 0f
                                var given = 0f

                                for (neighbour in 0 until NEIGHBOUR_COUNT) {
                                    val neighbourRow = row + NEIGHBOUR_ROW_STEP[neighbour]
                                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                                    val neighbourColumn =
                                        (column + NEIGHBOUR_COLUMN_STEP[neighbour] + cellsAcross) %
                                            cellsAcross
                                    val limit = limitByNeighbour[neighbour]
                                    val neighbourCell = neighbourRow * cellsAcross + neighbourColumn

                                    val incoming = current[neighbourCell] - here
                                    if (incoming > limit) {
                                        received += giveRate[neighbourCell] * (incoming - limit)
                                    } else if (-incoming > limit) {
                                        given += giveRate[cell] * (-incoming - limit)
                                    }
                                }

                                writeTo[cell] = here - given + received
                            }
                        }
                    }
                }
            }

            val previous = heights
            heights = nextHeights
            nextHeights = previous
        }

        return ErosionResult(FloatField(cellsAcross, cellsDown, heights))
    }

    /**
     * How many sweeps [ErosionConfig.debrisTravelKm] of debris travel comes to on this grid.
     *
     * Material moves at most one cell per sweep, so a distance on the ground is a count of sweeps
     * once the grid is known: eighty at 512 and three hundred and twenty at 2048, which is the
     * same apron either way.
     */
    internal fun sweepsFor(config: WorldGenConfig): Int =
        config.wholeCellsFor(config.erosion.debrisTravelKm, atLeast = 0)

    /**
     * The steepest drop one cell may hold toward each kind of neighbour, in the height field's own
     * units.
     *
     * [ErosionConfig.criticalFallMetresPerKm] is a gradient, so each is that gradient over the
     * step's length on the ground — a cell's width along a row, a row's height down a column, the
     * hypotenuse of the two on a diagonal — divided by what one unit of the height field is worth
     * in metres. The grid cancels out of the pair - a finer grid has narrower cells and so a
     * smaller drop - which is the whole point of writing the knob as a gradient.
     *
     * Public because an accelerator is handed the converted figures rather than the knob: a kernel
     * has no business knowing how wide the world is. See [ErosionAccelerator.erode].
     */
    fun thermalLimits(config: WorldGenConfig): ThermalLimits {
        val criticalPerKm = config.erosion.criticalFallMetresPerKm.toDouble() / config.scale.reliefSpanMetres
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        return ThermalLimits(
            eastWest = (criticalPerKm * cellWidthKm).toFloat(),
            northSouth = (criticalPerKm * cellHeightKm).toFloat(),
            diagonal = (criticalPerKm * sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)).toFloat()
        )
    }

    /** Grows a tile mask by one tile in every direction, wrapping in x as the world does. */
    private fun dilate(mask: BooleanArray, tilesAcross: Int, tilesDown: Int): BooleanArray {
        val grown = BooleanArray(mask.size)
        for (tileRow in 0 until tilesDown) {
            for (tileColumn in 0 until tilesAcross) {
                if (!mask[tileRow * tilesAcross + tileColumn]) continue
                for (rowStep in -1..1) {
                    val neighbourRow = tileRow + rowStep
                    if (neighbourRow < 0 || neighbourRow >= tilesDown) continue
                    for (columnStep in -1..1) {
                        val neighbourColumn =
                            (tileColumn + columnStep + tilesAcross) % tilesAcross
                        grown[neighbourRow * tilesAcross + neighbourColumn] = true
                    }
                }
            }
        }
        return grown
    }

    // Orthogonal neighbours first, so the loop can tell them from the diagonals by index alone.
    private val NEIGHBOUR_COLUMN_STEP = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val NEIGHBOUR_ROW_STEP = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
