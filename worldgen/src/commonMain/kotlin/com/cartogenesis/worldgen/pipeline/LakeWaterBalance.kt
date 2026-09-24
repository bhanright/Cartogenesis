package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.GroundSteps
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
        // The bare figures below are Thornthwaite's own, in the order the formula above gives
        // them: `I` is the year's heat index, `a` its cubic in `I`.
        val warmIndex = monthlyHeatIndex(summerC)
        val coldIndex = monthlyHeatIndex(winterC)
        val yearHeatIndex = MONTHS_PER_SEASON * (warmIndex + coldIndex)
        if (yearHeatIndex <= 0.0) return 0f
        val exponent = 6.75e-7 * yearHeatIndex * yearHeatIndex * yearHeatIndex -
            7.71e-5 * yearHeatIndex * yearHeatIndex + 1.792e-2 * yearHeatIndex + 0.49239
        val yearMm = MONTHS_PER_SEASON * monthlyPotentialMm(summerC, yearHeatIndex, exponent) +
            MONTHS_PER_SEASON * monthlyPotentialMm(winterC, yearHeatIndex, exponent)
        return (yearMm * scale).toFloat().coerceAtLeast(0f)
    }

    /** Months the world's two seasonal temperature fields each stand for. */
    private const val MONTHS_PER_SEASON = 6.0

    private fun monthlyHeatIndex(temperatureC: Float): Double =
        if (temperatureC <= 0f) 0.0 else (temperatureC / 5.0).pow(1.514)

    /** Thornthwaite's monthly total, capped: the power law runs away on a warm, low-index year. */
    private fun monthlyPotentialMm(
        temperatureC: Float,
        yearHeatIndex: Double,
        exponent: Double
    ): Double {
        if (temperatureC <= 0f) return 0.0
        val potentialMm = 16.0 * (10.0 * temperatureC / yearHeatIndex).pow(exponent)
        return potentialMm.coerceAtMost(MAX_MONTHLY_PET)
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
        val basinCellCount = sortedGround.size
        val inflowMm = runoffFraction * catchmentRainMm

        // Net gain in mm with the lowest `submerged` cells under water. Rain that falls on the
        // lake itself all joins the lake, so those cells swap their runoff share for the whole of
        // it, and pay evaporation.
        fun netGainMm(submerged: Int): Float =
            inflowMm - runoffFraction * rainPrefix[submerged] + rainPrefix[submerged] -
                evaporationPrefix[submerged]

        if (netGainMm(basinCellCount) >= 0f) {
            return Balance(basinCellCount, spillSurface, atSpill = true)
        }
        if (netGainMm(1) < 0f) return Balance(0, sortedGround[0], atSpill = false)

        // The most cells that still balance. One balances and all of them do not, so the answer
        // lies between.
        var balances = 1
        var doesNot = basinCellCount
        while (balances + 1 < doesNot) {
            val midpoint = (balances + doesNot) / 2
            if (netGainMm(midpoint) >= 0f) balances = midpoint else doesNot = midpoint
        }
        val surface = (sortedGround[balances - 1] + minDepth).coerceAtMost(spillSurface)
        return Balance(balances, surface, atSpill = false)
    }

    /**
     * A seeded, low-amplitude, spatially coherent perturbation of the ground, in the same units as
     * the relative elevation.
     *
     * It exists for one case: ground that is *exactly* flat. Deposition lays its lacustrine fans to
     * a single level, so the floor of a basin that has been silting up for a while can be hundreds
     * of cells holding one identical elevation to the last bit — measured on seed 718106 at 2048,
     * a 287-cell basin floor with one distinct height in it, and 41% of all exposed basin floor on
     * seed 59758 has a neighbour at exactly the same height. On ground like that every comparison
     * of two heights is a tie, every tie falls to the cell index, and anything that walks the grid
     * comes out as a scan: paths that run due east or due south for as far as the flat goes, several
     * of them side by side.
     *
     * Value noise on a lattice of a few cells rather than per-cell white noise, because the point
     * is to give the flat a *gradient* to follow. White noise would make each cell pick
     * an unrelated direction and the path would stagger; a smooth field gives it a slope that turns
     * gently, so the path meanders the way water on a floodplain does. The field itself is
     * [FlowRouting.smoothSeededField], shared with the routing's own sub-grid draw so that the two
     * decisions a coarse grid leaves open are made off one surface rather than two.
     *
     * The amplitude is chosen, not tuned: ten times the 1e-6 the depression fill nudges a flat cell
     * by, so it decides wherever the fill's own staircase would have, and a hundredth of the
     * smallest real cell-to-cell drop the routing has to respect — a basin floor measured at 2048
     * falls by 3e-3 to 1.3e-2 per cell — so nowhere with genuine relief in it is moved at all.
     */
    fun jitter(width: Int, x: Int, y: Int, seed: Long): Float =
        FlowRouting.smoothSeededField(width, x, y, seed) * JITTER_AMPLITUDE

    private const val JITTER_AMPLITUDE = 1e-5f

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
     * A priority-flood outward from the shore reaches every cell in the right order: pop the lowest
     * cell settled so far and offer its unsettled neighbours a place in the queue, keyed by the
     * highest ground the path to them had to cross, so each one is reached over its lowest saddle
     * and no cell is reached before the route to it exists.
     *
     * What the flood must *not* decide is which neighbour a cell drains into. Handing a cell to
     * whichever neighbour happened to reach it first makes the answer a property of the wavefront
     * rather than of the ground, and a wavefront on flat ground is a scan — which is how a basin
     * floor ends up with several parallel rivers running dead straight across it. So the parent is
     * chosen at pop time instead, by steepest descent over the real ground among the neighbours
     * already settled, distance-weighted so a diagonal has to be half again as deep to win. Ties
     * fall to the lower cell index, and on ground flat enough for a tie the [jitter] has already
     * decided, so the index almost never gets a say.
     *
     * Choosing among *settled* neighbours only is what keeps the result a tree: a settled neighbour
     * was popped earlier than this cell, so following the targets walks strictly backwards through
     * the pop order and must end at the water.
     *
     * @param water the cells that hold the lake (or the playa), which become sinks.
     * @param pending one flag per cell of the whole map, true for exactly this basin's cells. It is
     *   how a cell is known to be unqueued, and it is left all-false for this basin afterwards —
     *   the caller hands the same array to the next basin without clearing it. A flat array rather
     *   than a map because a hash container's iteration order must never reach a decision here, and
     *   the cheapest way to be sure of that is not to have one.
     * @param settled which cells this flood has already popped, stamped with [mark] so the same
     *   array serves every basin without being cleared between them.
     * @param pathKey scratch, one float per cell: the highest ground on the route to that cell.
     * @param seed the world's seed, so the [jitter] is this world's and not every world's.
     * @param cellHeightInCellWidths how tall a row is against a column's width, so the descent is
     *   steepest on the ground and not on a square of cells.
     * @param flowTarget modified in place.
     */
    fun routeIntoWater(
        width: Int,
        height: Int,
        ground: FloatField,
        pending: BooleanArray,
        water: IntArray,
        cellCount: Int,
        flowTarget: IntArray,
        settled: IntArray,
        mark: Int,
        pathKey: FloatArray,
        seed: Long,
        cellHeightInCellWidths: Double
    ) {
        val steps = GroundSteps(cellHeightInCellWidths)
        fun surfaceAt(cell: Int): Float =
            ground.data[cell] + jitter(width, cell % width, cell / width, seed)

        val frontier = LongMinHeap(cellCount.coerceAtLeast(MIN_HEAP_CAPACITY))
        for (cell in water) {
            if (!pending[cell]) continue
            pending[cell] = false
            settled[cell] = mark
            flowTarget[cell] = -1
            val here = surfaceAt(cell)
            pathKey[cell] = here
            frontier.push(FlowRouting.encode(here, cell))
        }

        while (!frontier.isEmpty()) {
            val cell = FlowRouting.decodeIndex(frontier.pop())
            val column = cell % width
            val row = cell / width
            val here = surfaceAt(cell)

            // Water cells are sinks and were settled with the seeds; everything else drains.
            if (settled[cell] != mark) {
                settled[cell] = mark
                var steepestNeighbour = -1
                var steepestDrop = -Float.MAX_VALUE
                FlowRouting.forEachNeighbourWithDistance(
                    width, height, column, row, steps
                ) { neighbour, distance ->
                    if (settled[neighbour] != mark) return@forEachNeighbourWithDistance
                    val drop = (here - surfaceAt(neighbour)) / distance
                    if (drop > steepestDrop ||
                        (drop == steepestDrop && neighbour < steepestNeighbour)
                    ) {
                        steepestDrop = drop
                        steepestNeighbour = neighbour
                    }
                }
                flowTarget[cell] = steepestNeighbour
            }

            val highestOnRoute = pathKey[cell]
            FlowRouting.forEachNeighbour(width, height, column, row) { neighbour ->
                if (!pending[neighbour]) return@forEachNeighbour
                pending[neighbour] = false
                // Keyed by the highest ground on the way down, so the route out of a side hollow
                // is the one over its lowest saddle.
                val there = surfaceAt(neighbour)
                val key = if (there > highestOnRoute) there else highestOnRoute
                pathKey[neighbour] = key
                frontier.push(FlowRouting.encode(key, neighbour))
            }
        }
    }

    /**
     * Floor on the priority queue's initial capacity, so a two-cell playa does not allocate a heap
     * that has to grow on its first few pushes.
     */
    private const val MIN_HEAP_CAPACITY = 16
}
