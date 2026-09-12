package com.cartogenesis.worldgen.pipeline

import kotlin.math.sqrt

/**
 * How big a reach of river is against the largest river on the same map.
 *
 * Leopold and Maddock measured downstream hydraulic geometry on hundreds of gauging stations and
 * found that a channel's width goes as the square root of its discharge, its depth as the 0.4 power
 * and its velocity as the 0.1: nearly all of the growth of a river is width and depth, and width is
 * the half of that a map can show. Discharge here is [RiverResult.flowAccumulation], the runoff of
 * every cell upstream, so a channel widens where tributaries join it and where the rain that feeds
 * it is heavier, which is what a reader expects to see and what makes a trunk read as a trunk.
 *
 * The number this produces is a ratio and not a length. A length would have to be a length of
 * something — cells, which change size with the resolution, or pixels, which change with the export
 * — and neither belongs to the world. What belongs to the world is where this reach stands between
 * the smallest channel the map draws and the mouth of its biggest river, and that is the same
 * number at 512 and at 4096. The renderer turns it into a pen; see `RiverPen` in `:cartography`.
 *
 * Both ends of the scale are read off the network itself rather than from a threshold in the
 * settings, for two reasons. It is the honest normaliser — the question the ratio answers is "how
 * big is this against the biggest here", and the biggest here is a fact about the drawn network —
 * and it is recomputable, so a saved world whose rivers were sized by an older rule can be sized
 * again from the accumulation the save already carries, with no threshold to reconstruct.
 */
object RiverWidth {

    /**
     * Where [discharge] stands between [smallest] and [largest], on a square-root scale, in 0..1.
     *
     * Zero where the whole network carries the same water, which is a map with one river on it and
     * no scale to speak of; there is nothing to say about relative size then and every channel gets
     * the same hairline.
     */
    fun ratio(discharge: Float, smallest: Float, largest: Float): Float {
        val floor = sqrt(smallest)
        val span = sqrt(largest) - floor
        if (span <= 0f) return 0f
        return ((sqrt(discharge) - floor) / span).coerceIn(0f, 1f)
    }

    /**
     * The same rivers with [River.widthRatio] filled in from [accumulation].
     *
     * A cell with no accumulation of its own is the mouth, sitting in the sea, where the flow was
     * never routed: it keeps the ratio of the last cell on land, because a river does not narrow as
     * it arrives. The first cell of a river always has some, so there is always a ratio to carry.
     */
    fun sized(rivers: List<River>, accumulation: FloatArray): List<River> {
        if (rivers.isEmpty()) return rivers

        var smallest = Float.MAX_VALUE
        var largest = 0f
        for (river in rivers) {
            for (cell in river.cells) {
                val discharge = accumulation[cell]
                if (discharge <= 0f) continue
                if (discharge < smallest) smallest = discharge
                if (discharge > largest) largest = discharge
            }
        }
        if (largest <= 0f) return rivers.map { River(it.cells, FloatArray(it.cells.size)) }

        return rivers.map { river ->
            var carried = 0f
            val ratios = FloatArray(river.cells.size) { index ->
                val discharge = accumulation[river.cells[index]]
                if (discharge > 0f) carried = ratio(discharge, smallest, largest)
                carried
            }
            River(river.cells, ratios)
        }
    }
}
