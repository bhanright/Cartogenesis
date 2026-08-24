package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt

/**
 * Cutting valleys with running water.
 *
 * Thermal erosion answers a question about rock — how steeply it can stand before it fails — and
 * gives mountains their flanks. It does not answer the question about water, which is why rivers
 * were finding their way down terrain their own flow had never shaped: they ran in whatever
 * hollows the noise happened to leave rather than in valleys they had cut.
 *
 * This is stream-power incision, the standard of landscape-evolution models: a cell lowers in
 * proportion to the square root of the area draining through it times the slope it sits on. The
 * feedback is the point. A channel that cuts down gathers more water next round, which cuts it
 * deeper still, and the divides between channels sharpen as the channels fall away from them. That
 * is where V-shaped valleys, dendritic drainage and ridge lines come from, and none of them can be
 * had by smoothing.
 *
 * Two honest simplifications. Rainfall is uniform, because the climate depends on the terrain that
 * has not been shaped yet, and beginning that circle somewhere means beginning it with flat rain.
 * And nothing is deposited: material removed leaves the model rather than piling up downstream,
 * which is the detachment-limited case. The thermal sweeps that ran before this are what put
 * material back onto the flanks.
 */
internal object HydraulicErosion {

    /**
     * @param provisionalSeaLevel the fraction of the world that will end up under water. Erosion
     *   runs before the sea level is chosen, but water needs somewhere to go, so it works to the
     *   level the sea *will* take.
     * @param relax a few thermal sweeps, run after every round.
     *
     *   Not decoration, and not merely for looks. Incision on its own cuts a slot one cell wide,
     *   whose walls stand at whatever angle the arithmetic leaves them -- and a slot one cell wide
     *   is twice as steep on a grid twice as fine, so the world stops being the same world at
     *   different resolutions. Letting the walls fail between rounds caps them at the critical
     *   slope, which is a property of the map rather than the grid. It is also what actually
     *   happens: valley sides are worn back by mass wasting as fast as the river cuts down, which
     *   is why a valley is a V and not a slot.
     */
    suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        provisionalSeaLevel: Float,
        relax: suspend (FloatField) -> FloatField
    ): FloatField {
        val cfg = config.erosion
        if (cfg.hydraulicRounds <= 0 || cfg.erodibility <= 0f) return height

        val w = config.width
        val h = config.height
        var working = height.copy()

        repeat(cfg.hydraulicRounds) {
            // The shoreline moves as the land wears down, so it is found again each round rather
            // than fixed once. This is the same percentile the sea level stage will use.
            val sea = SeaLevelStage.apply(working, provisionalSeaLevel)
            if (sea.landCellCount == 0) return working

            val filled = FlowRouting.fillDepressions(w, h, sea.isLand, sea.relativeElevation)
            val directions = FlowRouting.flowDirections(w, h, sea.isLand, sea.relativeElevation, filled)
            // Uniform rain: every land cell contributes the same, so accumulation is simply the
            // number of cells upstream.
            val area = FlowRouting.accumulate(
                w, h, sea.isLand, filled, directions, sea.landCellCount
            ) { 1f }

            val land = sea.landCellCount.toFloat()
            for (i in 0 until w * h) {
                if (!sea.isLand[i]) continue
                val target = directions[i]
                if (target < 0) continue

                // Both terms are held against the map rather than the grid, so a finer grid cuts
                // the same valleys rather than deeper ones: area as a share of all land, slope as
                // a rise over a fraction of the map's width.
                val drop = filled.data[i] - if (sea.isLand[target]) {
                    filled.data[target]
                } else {
                    sea.relativeElevation.data[target]
                }
                if (drop <= 0f) continue

                val distance = if (isDiagonal(i, target, w)) 1.41421356f else 1f
                val slope = drop / distance * w
                val share = area.data[i] / land

                val incision = cfg.erodibility * sqrt(share) * slope
                // Two limits, and both matter.
                //
                // Never cut below what this cell drains into, or the channel digs a hole for the
                // next round's flood-fill to undo, and the two fight each other round after round.
                //
                // And never cut below the sea, which is the base level every river grades to. A
                // river reaching the coast stops cutting because there is nothing left to fall.
                // Without that limit the last cells before the shore incise hardest -- they have
                // the whole catchment behind them and open water in front -- and the coastline
                // shreds into drowned valleys and islands.
                val aboveSea = sea.relativeElevation.data[i].coerceAtLeast(0f)
                working.data[i] -= minOf(incision, drop * 0.5f, aboveSea)
            }

            working = relax(working)
        }
        return working
    }

    /** Neighbours differ by one row *and* one column only when the step was diagonal. */
    private fun isDiagonal(from: Int, to: Int, width: Int): Boolean =
        (from / width != to / width) && (from % width != to % width)
}
