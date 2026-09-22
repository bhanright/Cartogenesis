package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.River
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Which rule decides how much river a sheet draws. See [RiverSelection]. */
enum class RiverInk(val label: String) {
    /**
     * As much river line per square kilometre of land as a published map at this sheet's scale
     * draws, spread over the map rather than spent wherever the ground happens to be dissected.
     * [RiverSelection] is the whole of it.
     */
    EARTH_DENSITY("Earth's ink at this scale"),

    /**
     * What F14 drew: every traced course on a sheet at a cell to a pixel, and a share of them on
     * anything smaller, by Töpfer and Pillewizer's radical law on the *traced count*.
     *
     * Kept because it is the control the density is measured against, and because the law relates
     * a derived map to a source map and says nothing about either in absolute terms: how much ink
     * it puts on the page depends entirely on how many courses the generator happened to trace, so
     * the same country drawn from a 512 world and from a 2048 world comes out at two densities.
     * [MapSheet.featuresKept] is the arithmetic.
     */
    RADICAL_LAW("Every traced course, thinned by scale")
}

/**
 * Which of the traced courses a sheet at a given scale actually draws.
 *
 * The generator traces every channel the ground can cut and hands on every course over a hundred
 * kilometres (R1). That is a statement about the world, and it is not a statement about the map:
 * a map draws as much river as its scale has room for, and a 1:22 000 000 sheet has room for very
 * little. What decides how little is the question this object answers, and the answer is an Earth
 * figure rather than a share of whatever the generator produced.
 *
 * **The reference.** Natural Earth (naturalearthdata.com; the vector data as published at
 * github.com/nvkelso/natural-earth-vector), the public-domain map data built by cartographers for
 * small-scale mapping at three stated scales. Its `rivers_lake_centerlines` layer is the river
 * line a map at that scale draws, and `featurecla` separates true river line from the centreline
 * carried through a lake — which this map draws as lake and not as river, so the `River` class
 * alone is the match. Measured over Earth's 148.94 million square kilometres of land, on the
 * WGS84 sphere:
 *
 * - **1:50 000 000** — 359 courses, 254 284 km of drawn line: **0.001707 km of river per km² of
 *   land**, 2.41 courses per million km².
 * - **1:10 000 000** — 1 202 courses, 599 507 km: **0.004025 km/km²**, 8.07 courses per million km².
 *
 * (Without Antarctica, which has no rivers on either sheet and is 14.00 of those 148.94 million,
 * both figures rise by a tenth: 0.001884 and 0.004443. The larger land area is taken, because this
 * generator's ice sheets carry no channels either and are inside its own land area for the same
 * reason. The 1:110 000 000 tier is not a third point: it holds thirteen rivers and is a token
 * selection rather than a generalisation of the other two.)
 *
 * **Which figure carries to another scale, and which does not.** Between those two tiers the
 * *length* drawn goes as the 0.533 power of the change in scale, which is Töpfer and Pillewizer's
 * square root to within seven hundredths, so the ink is carried by the published law and the
 * measured exponent stands beside it as the check. The *count* goes as the 0.751 power, which is
 * not the law and is what small-scale generalisation actually does: a sheet at half the scale does
 * not draw half-length rivers, it drops the short courses outright and keeps the long trunks
 * whole — the mean drawn course is 499 km at 1:10M and 708 km at 1:50M. So the budget is in
 * kilometres of ink, which the law carries and which does not depend on where anyone chooses to
 * cut one course from the next; and the count, whose exponent is measured from two points and
 * whose definition of "a course" is the reference's rather than this map's, is used only to set
 * the spacing the ink is spread over.
 *
 * **What the rule then is**, at a sheet whose representative fraction is `1:d`:
 *
 * 1. A budget of `0.001707 · √(50 000 000 / d)` kilometres of drawn river per square kilometre of
 *    this world's land.
 * 2. Courses ranked by discharge, largest first — the peak width ratio, which is the square root
 *    of the flow accumulation normalised over the network, so it rises with discharge and with
 *    nothing else.
 * 3. A first pass that takes at most one course per square of a lattice whose pitch is the mean
 *    spacing the reference's own course density implies at this scale, so a dissected coastal
 *    front cannot spend the whole map's budget on its own gullies; then a second pass over what
 *    the first deferred, which spends whatever is left.
 * 4. Downstream closure: taking a course takes every trunk below it that is not already taken, and
 *    those trunks are charged to the budget. A course is taken only if its whole chain fits, so
 *    the budget is never exceeded and no tributary is ever drawn hanging off a river that is not.
 *
 * **Why closure has to be explicit.** Ranking by discharge alone already keeps the network whole,
 * because a trunk's peak is never below its tributaries' and so is always reached first. The
 * crowding pass breaks that argument — it can defer a trunk whose square is taken and then reach
 * a tributary of it — so the chain is walked and charged rather than assumed.
 *
 * **Ties.** Two courses of equal peak are ordered by their position in the traced list, which is
 * the tracer's own longest-watercourse-first order (`RiverStage.traceRivers`), so the longer river
 * is taken first. A world saved before rivers were sized by flow has no ratios at all and every
 * peak is zero, which makes that order the whole order — the longest courses, up to the budget,
 * which is the right answer for a map that cannot tell its rivers apart.
 */
object RiverSelection {

    /**
     * Kilometres of drawn river per square kilometre of land on a 1:50 000 000 map.
     *
     * Natural Earth's `ne_50m_rivers_lake_centerlines`, `featurecla = River`: 359 courses,
     * 254 284 km over Earth's 148.94 million km² of land. The 1:10 000 000 tier carried here by
     * [inkExponent] gives 0.001800, so the two tiers bracket 0.001754 within three per cent of
     * each other; the nearer tier is taken because this map's own scales are 1:22M and 1:50M.
     * See the class comment for the whole derivation.
     */
    const val DRAWN_RIVER_KM_PER_SQUARE_KM_AT_FIFTY_MILLION: Double = 0.001707

    /**
     * Drawn courses per square kilometre of land on a 1:50 000 000 map: 2.41 per million.
     *
     * The same 359 courses over the same land area. Used only for [crowdingPitchKilometres], for
     * the reason the class comment gives — a count depends on where the reference cuts one course
     * from the next, and this map cuts them elsewhere.
     */
    const val DRAWN_COURSES_PER_SQUARE_KM_AT_FIFTY_MILLION: Double = 2.41e-6

    /** The scale both figures above were measured at. */
    const val REFERENCE_DENOMINATOR: Double = 50_000_000.0

    /**
     * How the ink carries from one scale to another: Töpfer and Pillewizer's square root.
     *
     * *The principles of selection* (The Cartographic Journal 3(1), 1966, 10-16). Natural Earth's
     * own two tiers measure 0.533 over a fivefold change of scale, so the published law is used
     * and the measurement is the check on it rather than the other way round.
     */
    private const val INK_EXPONENT: Double = 0.5

    /**
     * How the count carries: the 0.751 measured between Natural Earth's 1:10M and 1:50M tiers.
     *
     * Not Töpfer's square root, and the difference is the point — see the class comment. Measured
     * from two tiers and no more, which is why nothing is asserted on it; it sets a spacing.
     */
    private const val COUNT_EXPONENT: Double = 0.751

    /** No trunk below this course: it reaches the sea, a lake, or the edge of the world. */
    const val NO_TRUNK: Int = -1

    /** Kilometres of drawn river per square kilometre of land at a scale of `1:denominator`. */
    fun drawnRiverKmPerSquareKm(denominator: Double): Double =
        DRAWN_RIVER_KM_PER_SQUARE_KM_AT_FIFTY_MILLION *
            (REFERENCE_DENOMINATOR / denominator).pow(INK_EXPONENT)

    /** Drawn courses per square kilometre of land at a scale of `1:denominator`. */
    fun drawnCoursesPerSquareKm(denominator: Double): Double =
        DRAWN_COURSES_PER_SQUARE_KM_AT_FIFTY_MILLION *
            (REFERENCE_DENOMINATOR / denominator).pow(COUNT_EXPONENT)

    /**
     * The side of the crowding lattice, in kilometres on the ground.
     *
     * The mean spacing of the reference's own courses at this scale: one course to every
     * `1 / density` square kilometres is one course to every `√(1 / density)` kilometres of
     * spacing, which is the pitch of a square lattice holding one apiece. 645 km at 1:50 000 000
     * and 475 km at 1:22 000 000 — about eighty cells of a 2048 grid — which is what stops a
     * straight coastal front from keeping every gully it has.
     */
    fun crowdingPitchKilometres(denominator: Double): Double =
        sqrt(1.0 / drawnCoursesPerSquareKm(denominator))

    /**
     * Everything one sheet's selection decided, for a guard or a report to read.
     *
     * [drawn] is one flag per course of `world.rivers.rivers`, in that order. [courseKilometres]
     * is each course's traced length along the ground, and [trunkOf] the course that carries its
     * water on, or [NO_TRUNK].
     */
    class Selection(
        val drawn: BooleanArray,
        val courseKilometres: DoubleArray,
        val trunkOf: IntArray,
        val denominator: Double,
        val landAreaSquareKm: Double,
        val budgetKilometres: Double,
        val crowdingPitchKm: Double
    ) {
        val drawnCount: Int get() = drawn.count { it }

        /** Kilometres of river line this sheet puts on the paper. */
        val drawnKilometres: Double
            get() {
                var total = 0.0
                for (course in drawn.indices) if (drawn[course]) total += courseKilometres[course]
                return total
            }

        /** Kilometres of river line per square kilometre of land: the figure Earth is read for. */
        val drawnKmPerSquareKm: Double
            get() = if (landAreaSquareKm <= 0.0) 0.0 else drawnKilometres / landAreaSquareKm

        /** Every course traced, drawn or not, in the same units — what the budget is a share of. */
        val tracedKilometres: Double get() = courseKilometres.sum()
    }

    /**
     * The courses [sheet] draws, in the order they were traced.
     *
     * [sheet] carries the scale and is the whole of the generalisation; [world] gives the network,
     * the land area the density is per, and the water a course ends in. The same call serves the
     * pane and the export, which differ only in their [MapSheet.pixelsPerCell].
     */
    fun drawnOn(world: WorldMap, sheet: MapSheet, rule: RiverInk): List<River> {
        val rivers = world.rivers.rivers
        if (rivers.isEmpty()) return rivers
        if (rule == RiverInk.RADICAL_LAW) return byRadicalLaw(rivers, sheet)
        val selection = select(world, sheet)
        return rivers.filterIndexed { course, _ -> selection.drawn[course] }
    }

    /** [drawnOn]'s working, kept whole so a guard can read the budget it was spent against. */
    fun select(world: WorldMap, sheet: MapSheet): Selection {
        val rivers = world.rivers.rivers
        val config = world.config
        val denominator = MapScale.representativeFractionDenominator(
            config.scale, world.width, sheet.pixelsPerCell
        )
        val landAreaSquareKm = world.sea.landCellCount * config.squareKilometresPerCell
        val budgetKilometres = drawnRiverKmPerSquareKm(denominator) * landAreaSquareKm
        val pitchKm = crowdingPitchKilometres(denominator)

        val courseKilometres = DoubleArray(rivers.size) { courseKilometres(world, rivers[it]) }
        val trunkOf = trunksOf(world)
        val drawn = BooleanArray(rivers.size)

        if (budgetKilometres > 0.0) {
            val byDischarge = rankedByDischarge(rivers)
            val takenSquares = HashSet<Long>()
            val chain = ArrayList<Int>()
            var spentKilometres = 0.0

            // Two passes over the same ranking: the first holds to one course per lattice square,
            // the second spends what that left. A course deferred by the first is not penalised in
            // the second — it keeps its place in the discharge order.
            for (spreadByLattice in booleanArrayOf(true, false)) {
                for (ranked in byDischarge.indices) {
                    val course = decodeCourse(byDischarge[ranked])
                    if (drawn[course]) continue
                    if (spreadByLattice &&
                        takenSquares.contains(latticeSquare(world, rivers[course], pitchKm))
                    ) {
                        continue
                    }
                    chainDownTo(drawn, trunkOf, course, chain)
                    var cost = 0.0
                    for (link in chain) cost += courseKilometres[link]
                    if (spentKilometres + cost > budgetKilometres) continue
                    spentKilometres += cost
                    for (link in chain) {
                        drawn[link] = true
                        takenSquares.add(latticeSquare(world, rivers[link], pitchKm))
                    }
                }
            }
        }

        return Selection(
            drawn = drawn,
            courseKilometres = courseKilometres,
            trunkOf = trunkOf,
            denominator = denominator,
            landAreaSquareKm = landAreaSquareKm,
            budgetKilometres = budgetKilometres,
            crowdingPitchKm = pitchKm
        )
    }

    /** F14's rule, unchanged, for [RiverInk.RADICAL_LAW]. */
    private fun byRadicalLaw(rivers: List<River>, sheet: MapSheet): List<River> {
        val kept = sheet.featuresKept(rivers.size)
        if (kept >= rivers.size) return rivers
        val cut = rivers.map(::peakWidthRatio).sortedDescending()[kept - 1]
        return rivers.filter { peakWidthRatio(it) >= cut }
    }

    /**
     * The widest point of a river, which for a course traced source to mouth is its mouth.
     *
     * Held inside 0..1, which is what a width ratio is: [rankedByDischarge] packs its raw bits and
     * a value outside that range — or a not-a-number out of a degenerate network — would pack into
     * a key that sorted the wrong way round rather than into a river that merely looked odd.
     */
    internal fun peakWidthRatio(river: River): Float {
        var peak = 0f
        river.widthRatio.forEach { if (it > peak) peak = it }
        return if (peak.isNaN()) 0f else peak.coerceIn(0f, 1f)
    }

    /**
     * The courses' indices ordered by falling discharge, ties to the earlier-traced course.
     *
     * Packed into longs and sorted rather than sorted with a comparator, so that the order is the
     * same arithmetic on every platform: the peak's raw bits — a width ratio is never negative, so
     * they sort as the float does — complemented into the high half so a plain ascending sort puts
     * the biggest first, and the course's own index in the low half so ties fall to the course the
     * tracer reached first, which is the longer watercourse.
     */
    private fun rankedByDischarge(rivers: List<River>): LongArray {
        val ranked = LongArray(rivers.size)
        for (course in rivers.indices) {
            val bits = peakWidthRatio(rivers[course]).toRawBits().toLong()
            ranked[course] = ((BIGGEST_RATIO_BITS - bits) shl 32) or course.toLong()
        }
        ranked.sort()
        return ranked
    }

    private fun decodeCourse(ranked: Long): Int = (ranked and 0xFFFFFFFFL).toInt()

    /**
     * [course] and every trunk below it not yet drawn, nearest first, into [into].
     *
     * A trunk always stands earlier in the traced list than anything joining it, so the walk
     * strictly descends and cannot loop; the bound is belt and braces against a malformed save.
     */
    private fun chainDownTo(
        drawn: BooleanArray,
        trunkOf: IntArray,
        course: Int,
        into: ArrayList<Int>
    ) {
        into.clear()
        var link = course
        while (link != NO_TRUNK && !drawn[link] && into.size <= drawn.size) {
            into.add(link)
            link = trunkOf[link]
        }
    }

    /**
     * Which square of the crowding lattice a course ends in, packed as one key.
     *
     * The *end* and not the source, because that is where a course meets the map's other courses:
     * a mouth on the coast, or the junction where it joins its trunk. The lattice is laid out in
     * kilometres on the ground rather than in cells, because a cell of this world is twice as wide
     * as it is tall and a lattice in cells would be a lattice of rectangles.
     */
    private fun latticeSquare(world: WorldMap, river: River, pitchKm: Double): Long {
        val end = river.cells[river.cells.size - 1]
        val column = end % world.width
        val row = end / world.width
        val acrossPitch = (column * world.config.cellWidthKm / pitchKm).toLong()
        val downPitch = (row * world.config.cellHeightKm / pitchKm).toLong()
        return (downPitch shl 32) or acrossPitch
    }

    /**
     * For each course, the course carrying its water on, or [NO_TRUNK] where it reaches water.
     *
     * The tracer keeps the junction cell on the tributary so the two lines meet, then stops
     * (`RiverStage.traceRivers`), so a course's last cell is either the water it ends in or a cell
     * of its trunk. Ownership is first claim in traced order, which is the same claim the tracer
     * itself made. Two courses can reach the *same* cell of sea, which would make the later one
     * look like a tributary of the earlier, so a course ending in water is given no trunk outright.
     */
    private fun trunksOf(world: WorldMap): IntArray {
        val rivers = world.rivers.rivers
        val owner = IntArray(world.width * world.height) { NO_TRUNK }
        for (course in rivers.indices) {
            for (cell in rivers[course].cells) if (owner[cell] == NO_TRUNK) owner[cell] = course
        }
        val water = world.rivers.lakes
        return IntArray(rivers.size) { course ->
            val end = rivers[course].cells[rivers[course].cells.size - 1]
            if (!world.sea.isLand[end] || water.isOpenWater(end)) NO_TRUNK
            else owner[end].let { if (it == course) NO_TRUNK else it }
        }
    }

    /**
     * How long a course runs on the ground, in kilometres.
     *
     * The traced polyline, mouth step included — the same line the rasterizer strokes, before it
     * cuts the last stroke back half its width at the shore, which is a pen's worth of ink and not
     * a length. A step east or west across the world's seam is one cell like any other, not the
     * width of the map.
     */
    internal fun courseKilometres(world: WorldMap, river: River): Double {
        val cellsAcross = world.width
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        var kilometres = 0.0
        for (step in 0 until river.cells.size - 1) {
            val from = river.cells[step]
            val to = river.cells[step + 1]
            var across = abs(to % cellsAcross - from % cellsAcross)
            if (across > cellsAcross / 2) across = cellsAcross - across
            val down = abs(to / cellsAcross - from / cellsAcross)
            val eastWest = across * cellWidthKm
            val northSouth = down * cellHeightKm
            kilometres += sqrt(eastWest * eastWest + northSouth * northSouth)
        }
        return kilometres
    }

    /** The raw bits of 1.0f, the largest a width ratio can be. See [rankedByDischarge]. */
    private val BIGGEST_RATIO_BITS: Long = 1f.toRawBits().toLong()
}
