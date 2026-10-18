package com.cartogenesis.worldgen.pipeline

/**
 * The order a lap of the moisture march is marched in: which columns the boundary layer and the
 * free troposphere march in each slot of the lap ([MoistureMarch]'s "The exchange inside the lap").
 *
 * A layer's lap is two sweeps of [cellsAcross] columns, the eastward sweep's from the first column
 * and the westward sweep's from the last, cut into stages of [columnsPerStage] columns. The boundary
 * layer marches stage `s` of the eastward sweep in slot `s`, rests one slot, and marches stage `s` of
 * the westward sweep in slot `stagesPerSweep + 1 + s`. The free troposphere marches the same stages
 * one slot after. That gives the lap its guarantees, which `MarchScheduleTest` holds:
 * - in a slot the two layers stand on different columns, so what one writes the other does not
 *   read or write in the same slot;
 * - the free troposphere marches a cell after the boundary layer has, except a cell marched
 *   eastward by the free troposphere and westward by the boundary layer, which it marches first;
 * - the rest slot keeps the boundary layer's first westward stage off the free troposphere's last
 *   eastward one, which stand on the same columns.
 */
internal class MarchSchedule(val cellsAcross: Int, val columnsPerStage: Int) {

    init {
        require(cellsAcross > 0 && columnsPerStage > 0) { "a schedule of $cellsAcross columns in stages of $columnsPerStage" }
    }

    /** The stages of one sweep. */
    val stagesPerSweep: Int = (cellsAcross + columnsPerStage - 1) / columnsPerStage

    /** The stages of a lap: the eastward sweep's, then the westward sweep's. */
    val stageCount: Int = 2 * stagesPerSweep

    /** The slots of a lap: the free troposphere's last stage comes one slot after the boundary layer's. */
    val slotCount: Int = stageCount + 2

    /** The stage the layer marches in [slot], or -1 if it rests; the free troposphere is one slot behind. */
    fun stageAt(boundaryLayer: Boolean, slot: Int): Int {
        val own = slot - (if (boundaryLayer) 0 else 1)
        return when {
            own < 0 || own == stagesPerSweep -> -1
            own < stagesPerSweep -> own
            own - 1 < stageCount -> own - 1
            else -> -1
        }
    }

    /** Whether [stage] is of the eastward sweep. */
    fun isEastward(stage: Int): Boolean = stage < stagesPerSweep

    /**
     * The columns of [stage] in the order the sweep marches them: the eastward sweep's from the
     * first column up, the westward sweep's from the last column down. The first and the last of
     * the range, inclusive, in [firstColumn] and [lastColumn]; the step is `+1` or `-1`.
     */
    fun firstColumn(stage: Int): Int =
        if (isEastward(stage)) stage * columnsPerStage
        else cellsAcross - 1 - (stage - stagesPerSweep) * columnsPerStage

    fun lastColumn(stage: Int): Int =
        if (isEastward(stage)) minOf((stage + 1) * columnsPerStage, cellsAcross) - 1
        else maxOf(cellsAcross - (stage - stagesPerSweep + 1) * columnsPerStage, 0)
}
