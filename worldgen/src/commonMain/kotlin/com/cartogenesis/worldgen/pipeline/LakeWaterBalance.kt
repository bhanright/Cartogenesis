package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.LakesConfig
import kotlin.math.pow

/**
 * How high the water actually stands in a basin the terrain never drains.
 *
 * Depression filling answers a routing question — raise the hollow until water can leave — and the
 * answer it gives is the basin's *spill* level, the brim of the bowl. Treating that as the lake
 * surface says every closed basin on the map is full to the rim, which is true of a Great Lake and
 * wildly false of the Caspian, the Aral, Chad, Eyre or the Great Salt Lake. Each of those sits in a
 * basin many times its own size, because a lake with no outlet loses water only by evaporating,
 * and the surface settles exactly where the catchment's inflow matches evaporation off the water:
 *
 *     inflow  =  runoffFraction x (rainfall over the catchment)
 *     loss    =  (potential evaporation - rainfall on the water) x lake area
 *
 * Area grows with level — that is the basin's hypsometry — so raising the surface raises the loss
 * while the inflow barely moves, and there is one level where the two meet. Below the spill, the
 * basin is endorheic: nothing leaves it, rivers end in it, and if even the first cell of water
 * cannot be sustained there is no lake at all, only a playa.
 *
 * Above the spill there is nothing to solve. The lake overflows, the surplus runs to the sea, and
 * the surface is the spill level exactly as before this existed — which is why wet country comes
 * out of this file bit for bit unchanged.
 */
internal object LakeWaterBalance {

    /**
     * Potential evaporation in millimetres a year, by Thornthwaite (1948), read off the seasonal
     * temperature fields.
     *
     * Thornthwaite is the standard temperature-only estimate — it needs no radiation, no humidity
     * and no wind, which is all this pipeline has to offer it. Its monthly form is
     *
     *     i     = (T / 5) ^ 1.514                      for T > 0, else 0
     *     I     = sum of i over the twelve months
     *     a     = 6.75e-7 I^3 - 7.71e-5 I^2 + 1.792e-2 I + 0.49239
     *     PET   = 16 (10 T / I) ^ a                    millimetres in that month
     *
     * The world stores two seasons rather than twelve months, so the year is taken as six months at
     * the warm-season temperature and six at the cold-season one. The daylength correction is left
     * at one: it is a rescaling by latitude of an estimate that is already coarse, and the fields
     * the temperature came from have the latitude in them already.
     *
     * Nothing here is fitted. Put the plan's two calibration points through it and it lands on
     * them by itself:
     *
     * | place                        | warm | cold | I     | a    | PET     |
     * |------------------------------|------|------|-------|------|---------|
     * | hot desert (Sahara-like)     | 35 C | 15 C | 145.9 | 3.56 | 2270 mm |
     * | cool temperate (Europe-like) | 18 C |  2 C |  43.2 | 1.18 |  554 mm |
     *
     * against the 2000 mm and 500 mm the plan asks for. [LakesConfig.evaporationScale] exists to
     * move that without touching the curve; it is 1.
     *
     * Frozen country evaporates nothing, which is why cold basins stay full to the brim and the
     * glacial lakes are untouched.
     */
    fun potentialEvaporationMm(summerC: Float, winterC: Float, scale: Float): Float {
        val warmIndex = heatIndex(summerC)
        val coldIndex = heatIndex(winterC)
        val i = 6.0 * (warmIndex + coldIndex)
        if (i <= 0.0) return 0f
        val a = 6.75e-7 * i * i * i - 7.71e-5 * i * i + 1.792e-2 * i + 0.49239
        val year = 6.0 * monthlyPet(summerC, i, a) + 6.0 * monthlyPet(winterC, i, a)
        return (year * scale).toFloat().coerceAtLeast(0f)
    }

    private fun heatIndex(t: Float): Double =
        if (t <= 0f) 0.0 else (t / 5.0).pow(1.514)

    /** Thornthwaite's monthly total, capped: the power law runs away on a warm, low-index year. */
    private fun monthlyPet(t: Float, i: Double, a: Double): Double {
        if (t <= 0f) return 0.0
        val pet = 16.0 * (10.0 * t / i).pow(a)
        return pet.coerceAtMost(MAX_MONTHLY_PET)
    }

    /**
     * 500 mm in a month is half again the hottest month Thornthwaite is ever asked to produce on
     * Earth. The cap is there only so a world with a warm season and no cold one at all — a tiny
     * heat index under a large exponent — cannot return infinity.
     */
    private const val MAX_MONTHLY_PET = 500.0

    /** Where the water stands in one basin, and how much of the basin it covers. */
    class Balance(
        /** How many of the basin's cells hold water, lowest ground first. */
        val submergedCells: Int,
        /** Water surface, in the same units as the relative elevation. */
        val surface: Float,
        /** True when the balance reaches the brim and the basin overflows as it always has. */
        val atSpill: Boolean
    )

    /**
     * Solves one basin by bisection over its own cells.
     *
     * The basin's cells sorted by the ground beneath them *are* its hypsometry: flooding the lowest
     * k of them is the area at the level that just covers the k-th, so the unknown is an integer
     * between none of them and all of them and the search is a plain bisection on that integer. The
     * running sums of rainfall and evaporation over the sorted cells make each probe O(1), so the
     * whole solve is a sort and a logarithm rather than a flood per candidate level.
     *
     * @param sortedGround the basin's cells' true ground elevation, ascending.
     * @param rainPrefix `rainPrefix[k]` is the rainfall in mm summed over the lowest k cells.
     * @param evaporationPrefix the same running sum for potential evaporation.
     * @param catchmentRainMm rainfall in mm summed over every cell that drains into the basin,
     *   the basin's own cells included — [FlowRouting.accumulate] on the filled surface gives this
     *   at the basin's pour point.
     * @param spillSurface the filled surface's level, the brim.
     * @param minDepth how deep water has to stand before a cell counts as covered, so the answer
     *   is measured the same way the spill-level footprint is.
     * @param runoffFraction the share of catchment rainfall that reaches the basin rather than
     *   evaporating off the ground where it fell.
     */
    fun solve(
        sortedGround: FloatArray,
        rainPrefix: FloatArray,
        evaporationPrefix: FloatArray,
        catchmentRainMm: Float,
        spillSurface: Float,
        minDepth: Float,
        runoffFraction: Float
    ): Balance {
        val n = sortedGround.size
        val inflow = runoffFraction * catchmentRainMm

        // Net gain with the lowest k cells under water. Rain that falls on the lake itself all
        // joins the lake, so those cells swap their runoff share for the whole of it, and pay
        // evaporation.
        fun net(k: Int): Float =
            inflow - runoffFraction * rainPrefix[k] + rainPrefix[k] - evaporationPrefix[k]

        if (net(n) >= 0f) return Balance(n, spillSurface, atSpill = true)
        if (net(1) < 0f) return Balance(0, sortedGround[0], atSpill = false)

        // Largest k that still balances. net(1) >= 0 > net(n), so the answer is in [1, n).
        var low = 1
        var high = n
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (net(mid) >= 0f) low = mid else high = mid
        }
        val surface = (sortedGround[low - 1] + minDepth).coerceAtMost(spillSurface)
        return Balance(low, surface, atSpill = false)
    }

    /**
     * Re-points every cell of an endorheic basin at the water, instead of at the spill it no longer
     * reaches.
     *
     * The filled surface slopes from the basin's interior out to its spill cell, because that is
     * the direction the priority-flood grew, and routing on it sends the basin's water over the
     * brim. That was the right answer while the basin was full. Once the lake sits below the brim,
     * the ground between the shore and the rim is dry land again, and its water runs *down* it into
     * the lake like any other hillside — so the routing inside the basin has to be redone on the
     * true ground.
     *
     * A priority-flood outward from the shore does it: pop the lowest cell reached so far, hand its
     * unvisited neighbours a flow target pointing back at it, and key each of them by the highest
     * ground the path to it had to cross. Every cell then drains to the water by the lowest route
     * there is, which is the route water would take, and the tree it builds cannot contain a cycle.
     *
     * @param water the cells that hold the lake (or the playa), which become sinks.
     * @param pending one flag per cell of the whole map, true for exactly this basin's cells. It is
     *   how a cell is known to be unsettled, and it is left all-false for this basin afterwards —
     *   the caller hands the same array to the next basin without clearing it. A flat array rather
     *   than a map because a hash container's iteration order must never reach a decision here, and
     *   the cheapest way to be sure of that is not to have one.
     * @param flowTarget modified in place.
     */
    fun routeIntoWater(
        width: Int,
        height: Int,
        ground: FloatField,
        pending: BooleanArray,
        water: IntArray,
        cellCount: Int,
        flowTarget: IntArray
    ) {
        val heap = LongMinHeap(cellCount.coerceAtLeast(16))
        for (cell in water) {
            if (!pending[cell]) continue
            pending[cell] = false
            flowTarget[cell] = -1
            heap.push(FlowRouting.encode(ground.data[cell], cell))
        }

        while (!heap.isEmpty()) {
            val popped = heap.pop()
            val cell = FlowRouting.decodeIndex(popped)
            val level = ground.data[cell]
            FlowRouting.forEachNeighbour(width, height, cell % width, cell / width) { n ->
                if (!pending[n]) return@forEachNeighbour
                pending[n] = false
                flowTarget[n] = cell
                // Keyed by the highest ground on the way down, so the route out of a side hollow
                // is the one over its lowest saddle.
                val key = if (ground.data[n] > level) ground.data[n] else level
                heap.push(FlowRouting.encode(key, n))
            }
        }
    }
}
