package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.SeaConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The six thousand years after the sea stopped rising, in which the waves put a coast back in
 * order.
 *
 * The generator models the drowning and not the tidying up. [SeaConfig.lowstandMetres] drops the
 * base level for nine of the twelve hydraulic rounds, so every cell within 120 m of the shoreline
 * is worked by running water, and the transgression then floods all of it: the result is a
 * saw-tooth a cell or two deep along *every* coast, low or mountainous, sheltered or exposed.
 * Measured at 512 pooled over five seeds, turning the lowstand off takes the shoreline from 60,755
 * cells to 43,967 and its box dimension over the finest octave from 1.26 to 1.22, while the shelf
 * remap, the enclosure rule and the plate detail noise each move it by a twentieth or less. The
 * fringe is the lowstand's, and it sits at one to four cells. The figures are in `GEOGRAPHY.md`.
 *
 * Earth had the same fringe and does not have it now. The sea reached its present level about
 * 6,000 years ago and the shoreline has been worked ever since: drift carries sand alongshore and
 * rivers and tides fill the re-entrants, so a coast on low ground becomes a graded arc of beach,
 * barrier and marsh — Texas, Holland, Bengal — while a coast on high ground keeps the outline the
 * drowning gave it: Galicia's rias, Maine, western Norway. About a third of the world's shoreline
 * is the first kind (`SeaConfig.littoralDepositionalShare`). This pass is that third of the work
 * and only that third; it never touches a coast whose land rises.
 *
 * What it does, per cell and in this order:
 *  - decides how much grading a stretch of coast has had, from the height of the land behind it
 *    ([depositionalRiseLimit]) and the sea in front of it ([OPEN_COAST_WATER_SHARE]);
 *  - runs that many sweeps of a three-by-three fill over the land mask, which is a discrete
 *    curvature flow in one direction: a re-entrant narrower than twice the reach silts up, a
 *    straight or smoothly curved shore stays where it is, and no cell is ever filled whose filling
 *    would cut the water around it in two, so a bay, a strait and a sound all stay open;
 *  - gives the ground it builds the gradient of a real coastal plain, never above the old shore it
 *    laps against.
 *
 * It adds ground and never takes it, which is not a simplification but the arithmetic of six
 * thousand years at a twelve-kilometre cell; the reason is under [majoritySweep], and the headlands
 * come out rounded all the same.
 *
 * The expensive half is a stencil or a prefix sum over plain arrays — the box means, the histogram
 * and the sweeps — and would transliterate into a compute shader rather than needing a rewrite
 * (plan ground rule 8): three separable box filters, a reduction and four stencil passes, all over
 * the same two grids. The cheap half, settling the height of what was built, walks the few thousand
 * cells the sweeps moved and stays on the processor by that rule's own carve-out for a graph walk.
 *
 * All of it is on the processor, and the figure is 72 to 74 ms at 2048 on seed 718106 against the
 * rule's fifty — 122 ms in a test worker that had just built five 512 worlds, which is the
 * collector rather than the pass. Reported rather than acted on: 74 ms is 1.5% of the sea-level
 * stage's own 4.7 seconds at that grid and a thousandth of a whole generation, so a compute shader
 * would buy back time nobody can see. Two thirds of it is the three box filters, and the fetch's —
 * a five-hundred-kilometre window over a field that cannot vary between neighbouring cells — would
 * come down by most of its share on a grid eight times coarser if anyone wants the milliseconds.
 */
internal object LittoralGrading {

    /**
     * How high the land behind a coast may stand and still be a coast the sea has graded, in
     * metres above the shoreline.
     *
     * The postglacial rise itself, a hundred and twenty metres — the same figure
     * [SeaConfig.lowstandMetres] holds, for the same reason. What a coast's plan form records is
     * what the transgression found when it arrived. Where the land within reach of the shore stands
     * lower than the rise, the sea came in across a flat and has been filling it ever since — the
     * Dutch coast is under 10 m for its first fifty kilometres, the Texas coast under 30, Bengal
     * under 10. Where it stands higher, the sea ran up valleys and the outline is the valleys' —
     * Galicia is 200 to 400 m within fifty kilometres of the water, western Norway over 500.
     *
     * Held here rather than read from [SeaConfig.lowstandMetres] because that setting is a switch
     * as well as a figure: the estuary guard turns it off to get its control, and how a coast is
     * shaped does not stop depending on the height of the land behind it when it does.
     *
     * In metres, converted once where each reader spends it, because the three readers want three
     * different halves of the ruler: this pass reads it upward off the land
     * ([WorldScale.reliefShareOfMetres]) and downward into the water
     * ([WorldScale.depthShareOfMetres]), and [DrownedValleys] reads it as a level in the raw height
     * field ([WorldScale.fieldShareOfMetres]). It was 0.015 of "the land's relief" before the world
     * had a ruler, which was 120 m only if that relief was the 8 km the pre-S1 constants assumed.
     */
    const val POSTGLACIAL_RISE_METRES = 120f

    /**
     * The share of open water around a cell on a coast that faces the open ocean.
     *
     * A half, by symmetry: a straight shore cuts the window in two. It is the denominator that
     * turns the openness measured around a cell into a fraction of full exposure, so that a cell at
     * the back of a bay seeing a fifth of the window reads as a fifth of an ocean coast's exposure
     * rather than as a fifth of everything there is.
     */
    const val OPEN_COAST_WATER_SHARE = 0.5f

    /**
     * How much land there must be within the backshore window before there is a littoral system
     * here at all.
     *
     * Longshore drift needs a shore to run along and a supply to carry. A stack or a skerry
     * standing alone in the sea has neither, which is why Earth's offshore rocks stay ragged while
     * the mainland behind them is graded, and why without this floor the pass would weld a scatter
     * of islets into one blob of new ground. A sixth of the window: a cell on any real shore clears
     * it several times over, and an islet of one or two cells cannot reach it at any of the three
     * working resolutions.
     */
    const val MIN_DRIFT_LAND_SHARE = 0.167f

    /**
     * How steeply a prograded coastal plain rises, in metres per kilometre.
     *
     * One, which is what Earth's coastal plains measure: the United States' Atlantic plain rises
     * about 100 m over its first 100 km, the Gulf plain less, the Ganges delta plain far less. It
     * decides the height this pass gives the ground it builds, and one consequence is worth stating
     * as an invariant. The reach is [SeaConfig.littoralReachKm], and one metre per kilometre over
     * that reach is 23 m — 0.004 of the land's relief, and so always less than
     * [HydraulicErosion.POND_DEPTH]. A coastal flat this pass lays can never stand high enough
     * above the waterline to hold standing water, at any resolution.
     */
    const val COASTAL_PLAIN_METRES_PER_KILOMETRE = 1f

    /**
     * Grades the coast and returns the cut with its new shoreline, or the cut itself where the pass
     * is switched off or the grid too coarse to carry it.
     *
     * [cut] is the sea-level result as the percentile, the enclosure rule and the drowned-basin
     * outlets leave it. What comes back has the same threshold and the same units, with
     * [SeaLevelResult.isLand], [SeaLevelResult.relativeElevation] and [SeaLevelResult.landCellCount]
     * all agreeing with the graded shoreline. Deterministic: it reads the cut and [config] and
     * nothing else, so the engine's stage reuse gets the same answer as a fresh generation.
     *
     * [config] is read for [SeaConfig] and for [WorldScale], which is what turns this pass's three
     * reaches into cells of this grid and the one height it is bounded by — the postglacial rise —
     * into a height in the land's units and a depth in the water's.
     */
    fun apply(cut: SeaLevelResult, config: WorldGenConfig): SeaLevelResult {
        val sea = config.sea
        val scale = config.scale
        val cellsAcross = cut.relativeElevation.width
        val cellsDown = cut.relativeElevation.height
        val reachCells = scale.cellsAcrossFor(sea.littoralReachKm, cellsAcross).roundToInt()
        if (!sea.littoralGrading || reachCells < 1) return cut

        val cellCount = cellsAcross * cellsDown
        if (cut.landCellCount == 0 || cut.landCellCount == cellCount) return cut

        val sweeps = gradingSweepsPerCell(cut, config, reachCells, floodedDepthLimit(scale))
        val candidates = cellsWithAnySweep(sweeps)
        if (candidates.isEmpty()) return cut

        val isLand = cut.isLand.copyOf()
        var built = 0
        for (sweep in 1..reachCells) {
            built += majoritySweep(isLand, sweeps, candidates, sweep, cellsAcross, cellsDown)
        }
        if (built == 0) return cut

        val relative = cut.relativeElevation.copy()
        // A coastal plain's gradient over one cell of this grid, in the land's own units.
        val risePerCell = scale.reliefShareOfMetres(
            COASTAL_PLAIN_METRES_PER_KILOMETRE * scale.cellWidthKm(cellsAcross).toFloat()
        )
        settleBuiltGround(cut.isLand, isLand, relative, candidates, risePerCell)
        // The pass only ever turns water into land, so the new land count is the old one and what
        // the sweeps filled.
        return SeaLevelResult(cut.shorelineHeight, isLand, relative, cut.landCellCount + built)
    }

    /**
     * The share of the shoreline this criterion calls depositional — the figure Earth's third is
     * compared against, and the only reason [gradingSweepsPerCell] is reachable from a test.
     *
     * Counted over the land cells at the water's edge, because that is what Earth's figure counts:
     * Luijendijk's classification asks what the beach is made of, one point of shoreline at a time.
     * Counted on the cut the pass is handed rather than on the graded coast, so it answers "how much
     * of this world's coast is low and open" and not "how much of it did the pass move".
     */
    fun depositionalShareOfShoreline(cut: SeaLevelResult, config: WorldGenConfig): Float {
        val scale = config.scale
        val cellsAcross = cut.relativeElevation.width
        val cellsDown = cut.relativeElevation.height
        val reachCells =
            scale.cellsAcrossFor(config.sea.littoralReachKm, cellsAcross).roundToInt()
                .coerceAtLeast(1)
        val sweeps = gradingSweepsPerCell(cut, config, reachCells, floodedDepthLimit(scale))
        var shoreline = 0
        var depositional = 0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!cut.isLand[cell]) continue
                if (!onTheShoreline(cut.isLand, cellsAcross, cellsDown, row, column)) continue
                shoreline++
                if (sweeps[cell] > 0) depositional++
            }
        }
        return if (shoreline == 0) 0f else depositional.toFloat() / shoreline
    }

    /** Whether a cell has the other element of land and water on one of its four sides. */
    private fun onTheShoreline(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        row: Int,
        column: Int
    ): Boolean {
        val here = isLand[row * cellsAcross + column]
        if (isLand[row * cellsAcross + (column + 1) % cellsAcross] != here) return true
        if (isLand[row * cellsAcross + (column + cellsAcross - 1) % cellsAcross] != here) return true
        if (row > 0 && isLand[(row - 1) * cellsAcross + column] != here) return true
        if (row + 1 < cellsDown && isLand[(row + 1) * cellsAcross + column] != here) return true
        return false
    }

    /**
     * How deep the water may be and still be water the transgression made, in the sea's own units.
     *
     * The one bound that keeps this a pass about the fringe rather than a pass about the coastline.
     * A re-entrant whose floor lies within the postglacial rise of today's shoreline is ground that
     * was dry at the low stand, was cut by running water and was then flooded — the fringe William's
     * crops are about, and the fringe the sediment has had six thousand years to fill. A re-entrant
     * deeper than that is an embayment that was already an embayment: the Bristol Channel, the Bay
     * of Biscay, a flooded rift. Sediment does not fill those, and neither does this.
     *
     * The same [POSTGLACIAL_RISE_METRES] the land is judged by, read downwards off the sea's own
     * half of the ruler: a height above the shoreline and a depth below it are measured against
     * different ranges, and 120 m is a distance on the ground rather than a fraction of either.
     */
    private fun floodedDepthLimit(scale: WorldScale): Float =
        -scale.depthShareOfMetres(POSTGLACIAL_RISE_METRES)

    /**
     * The height of the backshore that separates the depositional coasts from the rest, taken as
     * the share of this world's shoreline Earth gives to depositional coast.
     *
     * A rank rather than a fixed height, and the reason is that no fixed height can be derived.
     * Two were tried. The postglacial rise, [POSTGLACIAL_RISE_METRES] — did the sea flood a flat or run
     * up a valley — calls 59% of the shoreline depositional on the four standard seeds and 298405.
     * A coastal plain's own gradient, [COASTAL_PLAIN_METRES_PER_KILOMETRE] over the backshore
     * window, calls 1.9% of it depositional. Earth's answer is between them and neither figure
     * lands near it, because what separates a graded coast from a ragged one on Earth is not height
     * alone: Finland, the Canadian Shield and western Scotland are flat, ragged and rocky, and this
     * generator has no lithology to tell them from a coastal plain with (the plan's H3). Choosing a
     * height between the two so that the share came out right would be tuning a threshold to a
     * target, which the plan's rule 5 forbids.
     *
     * So Earth's figure goes in directly instead, the way `SeaConfig.enclosedSeaMaxKm2` carries
     * the Caspian's share of Earth's surface and `GlaciationConfig.maxLakeShareOfMap` carries
     * Superior's: the lowest [SeaConfig.littoralDepositionalShare] of a world's shoreline, ranked by
     * the height of the land behind it, is its depositional coast. What is measured and what is
     * asserted then separate cleanly — the share is Earth's by construction, and what the guards
     * ask is whether that share of coast actually comes out smooth and whether the world's pooled
     * roughness stays where Earth puts it.
     *
     * By histogram rather than by sorting, which is the bracket-and-resolve [SeaLevelStage] takes
     * its own percentile with, and for the same reason: it is one pass over the coast.
     */
    private fun depositionalRiseLimit(shorelineRises: FloatArray, share: Float): Float {
        if (shorelineRises.isEmpty()) return 0f
        var highest = 0f
        for (rise in shorelineRises) if (rise > highest) highest = rise
        if (highest <= 0f) return 0f

        val binWidth = highest / RISE_HISTOGRAM_BINS
        val histogram = IntArray(RISE_HISTOGRAM_BINS + 1)
        for (rise in shorelineRises) {
            histogram[(rise / binWidth).toInt().coerceIn(0, RISE_HISTOGRAM_BINS)]++
        }
        val target = (shorelineRises.size * share.coerceIn(0f, 1f)).toInt()
        var below = 0
        for (bin in histogram.indices) {
            below += histogram[bin]
            if (below >= target) return bin * binWidth
        }
        return highest
    }

    /** The backshore height of every land cell at the water's edge, in no particular order. */
    private fun shorelineRises(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        backshoreRiseOf: (Int) -> Float
    ): FloatArray {
        var count = 0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                if (!isLand[row * cellsAcross + column]) continue
                if (onTheShoreline(isLand, cellsAcross, cellsDown, row, column)) count++
            }
        }
        val rises = FloatArray(count)
        var next = 0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!isLand[cell]) continue
                if (!onTheShoreline(isLand, cellsAcross, cellsDown, row, column)) continue
                rises[next++] = backshoreRiseOf(cell)
            }
        }
        return rises
    }

    /**
     * Bins the backshore histogram is bracketed in.
     *
     * A thousand over a span that is at most the whole land's relief, so a bin is eight metres —
     * finer than the difference between two coasts anything downstream can tell apart, and coarse
     * enough that a world's few tens of thousands of coast cells fill the bins that matter.
     */
    private const val RISE_HISTOGRAM_BINS = 1000

    /**
     * How many sweeps of the fill each cell has earned: none where the coast is not a depositional
     * one, the full reach where it is low and open, and the sweeps between are what a partly graded
     * coast gets.
     *
     * Two quantities, and they do different jobs. Whether a coast is depositional at all is a fact
     * about the ground and has to read the same at every grid, so [depositionalRiseLimit] decides it
     * on its own from the height of the land behind — and every coast it admits gets at least one
     * sweep. How far the grading then reaches is a distance, so the two weights are spent there: the
     * height of the backshore again, as a ramp within the depositional band, and the sea in front,
     * through the wave height a fetch can raise. `H` grows as the square root of the fetch in the
     * Sverdrup-Munk-Bretschneider relation the audit's coast section quotes, so exposure enters as
     * the square root of the open water around the cell against [OPEN_COAST_WATER_SHARE]. A shore at
     * the back of a bay still grades, as Holland's and Bengal's quiet-water coasts do, but over a
     * shorter reach than one facing the ocean.
     */
    private fun gradingSweepsPerCell(
        cut: SeaLevelResult,
        config: WorldGenConfig,
        reachCells: Int,
        floodedDepthLimit: Float
    ): IntArray {
        val sea = config.sea
        val scale = config.scale
        val cellsAcross = cut.relativeElevation.width
        val cellsDown = cut.relativeElevation.height
        val cellCount = cellsAcross * cellsDown
        val relative = cut.relativeElevation.data
        val isLand = cut.isLand

        val landMask = FloatArray(cellCount) { if (isLand[it]) 1f else 0f }
        val landRelief = FloatArray(cellCount) {
            if (isLand[it] && relative[it] > 0f) relative[it] else 0f
        }
        val backshoreCells =
            scale.cellsAcrossFor(sea.littoralBackshoreKm, cellsAcross).roundToInt()
                .coerceAtLeast(1)
        val fetchCells =
            scale.cellsAcrossFor(sea.littoralFetchKm, cellsAcross).roundToInt().coerceAtLeast(1)
        val alongRows = FloatArray(cellCount)
        val landNearby = FloatArray(cellCount)
        val reliefNearby = FloatArray(cellCount)
        boxMean(landMask, cellsAcross, cellsDown, backshoreCells, landNearby, alongRows)
        boxMean(landRelief, cellsAcross, cellsDown, backshoreCells, reliefNearby, alongRows)
        // The fetch reuses the relief's input buffer for its own answer: `landRelief` has been read
        // for the last time by the line above, and one more grid of floats at 2048 is sixteen
        // megabytes for nothing.
        val landWithinFetch = landRelief
        boxMean(landMask, cellsAcross, cellsDown, fetchCells, landWithinFetch, alongRows)
        // The mean height of the *land* in the window, not of the window: a cell with a sliver of
        // high ground behind it and open sea around the rest is a cliff, and averaging the sea's
        // zeroes into it would call it a plain.
        val backshoreRiseOf = { cell: Int ->
            val nearby = landNearby[cell]
            if (nearby > 0f) reliefNearby[cell] / nearby else 0f
        }
        val riseLimit = depositionalRiseLimit(
            shorelineRises(isLand, cellsAcross, cellsDown, backshoreRiseOf),
            sea.littoralDepositionalShare
        )

        val sweeps = IntArray(cellCount)
        for (cell in 0 until cellCount) {
            val nearby = landNearby[cell]
            if (nearby < MIN_DRIFT_LAND_SHARE || nearby >= 1f) continue
            // Only the water the transgression itself made. Anything deeper than the rise is an
            // embayment that was already an embayment before the sea came up. See
            // [floodedDepthLimit].
            if (!isLand[cell] && relative[cell] < floodedDepthLimit) continue
            val backshoreRise = backshoreRiseOf(cell)
            if (backshoreRise >= riseLimit) continue
            // At least one sweep for every coast the line above admitted, or the classification and
            // the reach would be entangled: at 512 the reach is a single cell, and rounding a weight
            // of 0.4 down to nothing would have a coarse grid finding a third fewer depositional
            // coasts than a fine one finds on the same ground.
            val lowGround = (1f - backshoreRise / riseLimit).coerceIn(0f, 1f)
            val openWater = 1f - landWithinFetch[cell]
            val exposure = sqrt((openWater / OPEN_COAST_WATER_SHARE).coerceIn(0f, 1f))
            sweeps[cell] = (reachCells * lowGround * exposure).roundToInt().coerceAtLeast(1)
        }
        return sweeps
    }

    /** The cells the majority may move, gathered once so a sweep costs the coast and not the map. */
    private fun cellsWithAnySweep(sweeps: IntArray): IntArray {
        var count = 0
        for (sweep in sweeps) if (sweep > 0) count++
        val cells = IntArray(count)
        var next = 0
        for (cell in sweeps.indices) if (sweeps[cell] > 0) cells[next++] = cell
        return cells
    }

    /**
     * One sweep of the fill over the cells that have earned it; returns how many it filled.
     *
     * A water cell with land on the majority of the nine cells around it is a re-entrant, and a
     * re-entrant on a graded coast fills. A straight shore and a diagonal one are both fixed points
     * of that rule — three of the nine cells are land on the water side of either — so what it
     * fills is what is crooked.
     *
     * **Filling and not cutting**, which is the arithmetic of six thousand years rather than a
     * preference. Waves take a cliff back at a tenth of a metre to a metre a year, so the whole
     * Holocene is 0.6 to 6 km of retreat — under a tenth of a cell at 2048 and a fortieth at 512,
     * which is to say a headland at this grid cannot be cut back at all. Sediment moves at another
     * order entirely: the Mississippi's plain advanced about a hundred kilometres over the same six
     * thousand years, the Nile's fifty, an ordinary barrier coast five to twenty. So the pass adds
     * ground and never takes it, and the headlands still come out rounded, because a rounded
     * headland on a map is the bays either side of it filled up to its own line. An earlier draft
     * cut as well as filled and the cost was visible in the numbers as well as the physics: it took
     * 298405 at 512 from 36 separate bodies of land to 56, dissolving spits into chains of islets.
     *
     * **And never closing anything off**, which [WaterTopology.severs] enforces cell by cell, in
     * row-major order so that two cells across the mouth of a bay cannot both pass it. Without that
     * rule the pass sealed them, and nothing downstream can reopen one: it took `GlaciationTest`'s
     * resolution contract from 1.91 to 2.37 against a bar of 2.20.
     */
    private fun majoritySweep(
        isLand: BooleanArray,
        sweeps: IntArray,
        candidates: IntArray,
        sweep: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): Int {
        var filled = 0
        for (cell in candidates) {
            if (sweeps[cell] < sweep || isLand[cell]) continue
            val row = cell / cellsAcross
            val column = cell % cellsAcross
            var land = 0
            for (rowStep in -1..1) {
                val neighbourRow = (row + rowStep).coerceIn(0, cellsDown - 1)
                for (columnStep in -1..1) {
                    val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                    if (isLand[neighbourRow * cellsAcross + neighbourColumn]) land++
                }
            }
            if (land < 5) continue
            if (WaterTopology.severs(isLand, row, column, cellsAcross, cellsDown)) continue
            isLand[cell] = true
            filled++
        }
        return filled
    }

    /**
     * Gives the ground the pass built the height of a coastal plain: nothing at the water's edge,
     * rising inland at [COASTAL_PLAIN_METRES_PER_KILOMETRE], and never above the old shore it laps
     * against.
     *
     * A ring at a time out from the new shoreline, which is the shape of a prograded plain and,
     * more to the point, is strictly downhill towards the water: a flat handed to the river stage
     * all at one height would be a pond waiting for the depression fill to find it.
     *
     * The clamp is the other half and it is not a detail. New ground laps *onto* the old coast; it
     * does not bury it, because the sediment is only carried up to the waterline and the old shore
     * stands above that. Without the clamp the pass could leave a ridge across the front of a low
     * coastal basin with no way out — nothing downstream cuts a plug laid after the sea-level cut,
     * since E1's notch runs inside the hydraulic rounds and [SeaLevelStage] drains only the basins
     * the enclosure rule made — and it did: on seed 718106 at sea 0.70 with the outlet notch off,
     * which is the world `GlaciationTest` measures resolution invariance on, the standing water on
     * cold flat ground went from 0.0036 to 0.0040 at 512 and from 0.0069 to 0.0096 at 1024, taking
     * the growth between the two grids from 1.91 to 2.37 against a bar of 2.20. Holding every
     * built cell strictly below the lowest old land its own flat touches makes the ridge impossible
     * rather than unlikely.
     */
    private fun settleBuiltGround(
        wasLand: BooleanArray,
        isLand: BooleanArray,
        relative: FloatField,
        candidates: IntArray,
        risePerCell: Float
    ) {
        val cellsAcross = relative.width
        val cellsDown = relative.height
        val built = candidates.filter { !wasLand[it] && isLand[it] }
        if (built.isEmpty()) return

        val ringOf = HashMap<Int, Int>(built.size * 2)
        var frontier = built.filter { cell ->
            neighboursOf(cell, cellsAcross, cellsDown).any { !isLand[it] }
        }
        var ring = 1
        while (frontier.isNotEmpty()) {
            frontier.forEach { ringOf[it] = ring }
            val next = ArrayList<Int>()
            frontier.forEach { cell ->
                neighboursOf(cell, cellsAcross, cellsDown).forEach { neighbour ->
                    if (!wasLand[neighbour] && isLand[neighbour] && !ringOf.containsKey(neighbour)) {
                        ringOf[neighbour] = ring + 1
                        next.add(neighbour)
                    }
                }
            }
            frontier = next
            ring++
        }

        // Each flat is settled on its own, because the old shore it laps against is its own.
        val groupOf = HashMap<Int, Int>(built.size * 2)
        val deepestRing = ArrayList<Int>()
        val lowestOldShore = ArrayList<Float>()
        val stack = ArrayList<Int>()
        built.forEach { start ->
            if (groupOf.containsKey(start)) return@forEach
            val group = deepestRing.size
            var deepest = 1
            var lowest = Float.MAX_VALUE
            groupOf[start] = group
            stack.add(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeAt(stack.size - 1)
                deepest = maxOf(deepest, ringOf[cell] ?: ring)
                neighboursOf(cell, cellsAcross, cellsDown).forEach { neighbour ->
                    if (wasLand[neighbour]) {
                        lowest = minOf(lowest, relative.data[neighbour])
                    } else if (isLand[neighbour] && !groupOf.containsKey(neighbour)) {
                        groupOf[neighbour] = group
                        stack.add(neighbour)
                    }
                }
            }
            deepestRing.add(deepest)
            lowestOldShore.add(if (lowest == Float.MAX_VALUE) 0f else maxOf(lowest, 0f))
        }

        // A built cell the walk never reached has no water in sight, which can only happen where a
        // whole re-entrant closed over; the innermost ring is as good a height for it as any.
        built.forEach { cell ->
            val group = groupOf.getValue(cell)
            val rings = deepestRing[group]
            val step = minOf(risePerCell, lowestOldShore[group] / (rings + 1))
            relative.data[cell] = (ringOf[cell] ?: rings) * step
        }
    }

    /** The eight neighbours of a cell, wrapping in x and clamping at the poles. */
    private fun neighboursOf(cell: Int, cellsAcross: Int, cellsDown: Int): List<Int> {
        val row = cell / cellsAcross
        val column = cell % cellsAcross
        val neighbours = ArrayList<Int>(8)
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                neighbours.add(neighbourRow * cellsAcross + neighbourColumn)
            }
        }
        return neighbours
    }

    /**
     * The mean of [values] over a square window of [radius] cells either side of each cell,
     * wrapping in x and clamping at the poles.
     *
     * Separable and by prefix sums, so a window five cells across and one two hundred across cost
     * the same two passes over the grid — which is what makes the exposure term affordable at a
     * fetch of hundreds of kilometres. Double accumulators, because a row of a 2048 grid is two
     * thousand terms and the answer is a mean of numbers no larger than one.
     *
     * Writes into [mean] and borrows [alongRows], rather than allocating either. Three of these run
     * per pass and a grid-sized float array is sixteen megabytes at 2048: allocating and zeroing six
     * of them was a third of the pass's whole cost.
     */
    private fun boxMean(
        values: FloatArray,
        cellsAcross: Int,
        cellsDown: Int,
        radius: Int,
        mean: FloatArray,
        alongRows: FloatArray
    ) {
        val rowPrefix = DoubleArray(cellsAcross + 1)
        val columnsInWindow = (2 * radius + 1).coerceAtMost(cellsAcross)
        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            for (column in 0 until cellsAcross) {
                rowPrefix[column + 1] = rowPrefix[column] + values[rowStart + column]
            }
            val wholeRow = rowPrefix[cellsAcross]
            for (column in 0 until cellsAcross) {
                val left = column - radius
                val right = column + radius
                val sum = when {
                    columnsInWindow >= cellsAcross -> wholeRow
                    left < 0 -> rowPrefix[right + 1] + (wholeRow - rowPrefix[left + cellsAcross])
                    right >= cellsAcross ->
                        (wholeRow - rowPrefix[left]) + rowPrefix[right - cellsAcross + 1]
                    else -> rowPrefix[right + 1] - rowPrefix[left]
                }
                alongRows[rowStart + column] = sum.toFloat()
            }
        }

        // Down the columns by a sliding window rather than a prefix sum per column, which is the
        // same arithmetic in a friendlier order: a prefix per column walks the grid with a stride of
        // a whole row and misses the cache on every read, and at 2048 that alone was most of the
        // pass's cost. The window carries one running total per column and is advanced a row at a
        // time, so every array access is sequential.
        val running = DoubleArray(cellsAcross)
        var lowestRowInWindow = 0
        var highestRowInWindow = -1
        for (row in 0 until cellsDown) {
            val wanted = (row + radius).coerceAtMost(cellsDown - 1)
            while (highestRowInWindow < wanted) {
                highestRowInWindow++
                val start = highestRowInWindow * cellsAcross
                for (column in 0 until cellsAcross) running[column] += alongRows[start + column]
            }
            while (lowestRowInWindow < row - radius) {
                val start = lowestRowInWindow * cellsAcross
                for (column in 0 until cellsAcross) running[column] -= alongRows[start + column]
                lowestRowInWindow++
            }
            val cells = (highestRowInWindow - lowestRowInWindow + 1).toDouble() * columnsInWindow
            val start = row * cellsAcross
            for (column in 0 until cellsAcross) {
                mean[start + column] = (running[column] / cells).toFloat()
            }
        }
    }
}
