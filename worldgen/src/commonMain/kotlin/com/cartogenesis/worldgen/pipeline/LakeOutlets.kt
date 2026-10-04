package com.cartogenesis.worldgen.pipeline

/**
 * What leaves each lake the hydraulic rounds route through: what its catchment sends it, plus the
 * rain on its own surface, less what that surface evaporates.
 *
 * A round fills every hollow of the bed and routes the water across the fill, so the discharge the
 * accumulation hands a lake's outlet is everything its catchment collected. A lake does not pass
 * that on. Open water takes all the rain that falls on it rather than the runoff share a slope
 * sheds, and loses what the air can evaporate off it, so its outflow is its surplus, and a lake in a
 * dry climate whose surface evaporates more than it is sent lets nothing out at all: the Caspian,
 * the Great Salt Lake, the Dead Sea. The outlet is then cut by the same implicit pass that cuts every
 * channel, with that surplus as its discharge, so a wet basin spills and cuts its lip down and a dry
 * one keeps its sill, which is what the explicit notch the rounds once ran could not tell apart
 * (docs/DESIGN_LEDGER.md, E1 and E1b).
 *
 * **One geometry.** A lake's wet area is read off the same hypsometry its storage and its fans are
 * ([GroundClosure.floodedShare], the slope of [GroundClosure.fillForBedRise]): a cell under water
 * by `h` over its bed is wet over its bed's share and over as much of its interfluves as stand
 * below the water, all of it once the water is twice their relief deep. A lake is the cells the
 * fill stands over by more than the pond depth, the same cells the incision treats as standing
 * water.
 *
 * **In the discharge's own units.** The rounds route a rainfall weight, rainfall over its mean on
 * the land, which stands in for runoff at Earth's share of rainfall
 * ([GroundClosure.RUNOFF_SHARE_OF_RAINFALL]); so a lake's gain in millimetres over a cell is
 * divided by that share of the land's mean rainfall to be counted in the weight. Evaporation is
 * Thornthwaite's potential evaporation off the provisional climate ([HydraulicErosion.Weather]).
 *
 * Holds its scratch arrays for the stage, one entry per cell; nothing in it is kept between rounds.
 */
internal class LakeOutlets(cellCount: Int) {

    /** Which lake each cell stands under this round, -1 where none. */
    private val lakeOf = IntArray(cellCount)

    /** Each land cell's place in the round's drainage order. */
    private val rankOf = IntArray(cellCount)

    /** What the round's balance did, for the round's tally and the guards. */
    class Balance(
        /** Lakes the round routed through. */
        val lakes: Int,
        /** Of those, how many evaporated everything they were sent and let nothing out. */
        val closed: Int
    )

    /**
     * Takes each lake's evaporation out of [discharge], in place, at the cells the lake's water
     * leaves by and on every cell below them to the sea.
     *
     * [relative] is the bed and [filled] its fill, both shoreline-relative with [landRange] of the
     * height field as their unit; [pondDepth] is in the same unit. [directions] and [order] are the
     * round's routing and drainage order (sources first). [discharge] is the round's accumulated
     * rainfall weight. [rainfallMm] is the floored rainfall the weight was normalised from,
     * [landMeanRainfallMm] its mean over the land, and [evaporationMm] the potential evaporation,
     * both per cell in millimetres a year. [cells] and [ground] give each cell's hypsometry.
     *
     * Lakes are taken upstream first, in the order of their first exit in the drainage order: a
     * lake's water can only reach a lake whose exits come later, so each is balanced on an inflow
     * every lake above it has already settled. A lake whose balance is negative lets nothing out.
     */
    fun spendEvaporation(
        cellsAcross: Int,
        cellsDown: Int,
        isLand: BooleanArray,
        relative: FloatArray,
        filled: FloatArray,
        directions: IntArray,
        order: IntArray,
        discharge: FloatArray,
        rainfallMm: FloatArray,
        evaporationMm: FloatArray,
        landMeanRainfallMm: Double,
        pondDepth: Float,
        landRange: Float,
        cells: GroundCells,
        ground: FloatArray
    ): Balance {
        val cellCount = cellsAcross * cellsDown
        lakeOf.fill(-1)
        for (rank in order.indices) rankOf[order[rank]] = rank
        val runoffShare = GroundClosure.RUNOFF_SHARE_OF_RAINFALL
        val millimetreCellsPerWeight = runoffShare * landMeanRainfallMm
        if (millimetreCellsPerWeight <= 0.0) return Balance(0, 0)

        fun underWater(cell: Int): Boolean = isLand[cell] && filled[cell] - relative[cell] > pondDepth

        // Each lake's gain over the round, in the discharge's units, and the cells it leaves by,
        // gathered by one flood of each connected body of standing water.
        val gain = ArrayList<Double>()
        val exitStart = ArrayList<Int>()
        val exits = ArrayList<Int>()
        val firstExitRank = ArrayList<Int>()
        var stack = IntArray(256)
        for (start in 0 until cellCount) {
            if (lakeOf[start] >= 0 || !underWater(start)) continue
            val lake = gain.size
            exitStart.add(exits.size)
            var lakeGain = 0.0
            var earliestExit = Int.MAX_VALUE
            var top = 0
            stack[top++] = start
            lakeOf[start] = lake
            while (top > 0) {
                val cell = stack[--top]
                val depth = (filled[cell] - relative[cell]).toDouble() * landRange
                val wet = cells.floodedShare(cell, depth, ground)
                // Open water keeps all its rain, not the runoff share a slope sheds, and loses what
                // the air takes off it.
                val surplusMm = (1.0 - runoffShare) * rainfallMm[cell] - evaporationMm[cell]
                lakeGain += wet * surplusMm / millimetreCellsPerWeight
                val receiver = directions[cell]
                if (receiver < 0 || !underWater(receiver)) {
                    exits.add(cell)
                    if (rankOf[cell] < earliestExit) earliestExit = rankOf[cell]
                }
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbour ->
                    if (lakeOf[neighbour] < 0 && underWater(neighbour)) {
                        lakeOf[neighbour] = lake
                        if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[top++] = neighbour
                    }
                }
            }
            gain.add(lakeGain)
            firstExitRank.add(earliestExit)
        }
        val lakeCount = gain.size
        exitStart.add(exits.size)

        // Upstream first. Ties cannot happen, every exit being one cell of one lake.
        val upstreamFirst = (0 until lakeCount).sortedBy { firstExitRank[it] }
        var closed = 0
        for (lake in upstreamFirst) {
            var sent = 0.0
            for (index in exitStart[lake] until exitStart[lake + 1]) sent += discharge[exits[index]].toDouble()
            val leaving = (sent + gain[lake]).coerceAtLeast(0.0)
            if (leaving <= 0.0) closed++
            for (index in exitStart[lake] until exitStart[lake + 1]) {
                val exit = exits[index]
                // Each exit keeps its share of what the lake sends, or, where nothing reached any
                // of them, the first takes whatever the lake's own rain leaves over.
                val kept = when {
                    sent > 0.0 -> leaving * discharge[exit] / sent
                    index == exitStart[lake] -> leaving
                    else -> 0.0
                }
                val change = (kept - discharge[exit]).toFloat()
                if (change == 0f) continue
                var cell = exit
                while (cell >= 0 && isLand[cell]) {
                    discharge[cell] = (discharge[cell] + change).coerceAtLeast(0f)
                    cell = directions[cell]
                }
            }
        }
        return Balance(lakeCount, closed)
    }
}
