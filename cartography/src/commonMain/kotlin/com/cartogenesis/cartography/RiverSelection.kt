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
 * kilometres (R1). That is a statement about the world and not about the map: a map draws as much
 * river as its scale has room for, and how much that is is an Earth figure rather than a share of
 * whatever the generator produced. The rule at a sheet whose representative fraction is `1:d`:
 *
 * 1. A budget of [drawnRiverKmPerSquareKm] kilometres of drawn river per square kilometre of this
 *    world's land, which is Natural Earth's measured figure carried by Töpfer's law.
 * 2. Courses ranked by discharge, largest first, on the peak width ratio.
 * 3. A first pass taking at most one *candidate* per square of the [crowdingPitchKilometres]
 *    lattice, so a dissected coastal front does not spend the budget on its own gullies before the
 *    rest of the map is served; then a second pass, with no lattice, spending whatever is left.
 * 4. Downstream closure: taking a course takes every trunk below it not already taken, charged to
 *    the budget, and a course is taken only if its whole chain fits.
 *
 * Closure is explicit because the crowding pass breaks the argument that would otherwise give it
 * for nothing. Ranking by discharge reaches a trunk before any of its tributaries, since a trunk's
 * peak is never below theirs — but reaching it is not taking it, and the lattice can defer it.
 *
 * What the lattice is and is not: the first pass tests the *candidate's* own square, so a trunk
 * pulled in by closure may land in a square already taken, and the second pass ignores squares
 * altogether. It spreads the ink rather than bounding it. The fullest square it leaves is a
 * measurement and not a guarantee — 2 drawn courses against the radical law's 49 to 70 on the four
 * standard seeds, and `RiverSelectionTest` shows it against the same selection with the lattice
 * switched off.
 *
 * Ties fall to the course the tracer reached first, which is the head with the longest way down to
 * the water; that is not the same as the longest *drawn* course, because a tributary is cut at the
 * junction it joins. A world saved before rivers were sized by flow has no ratios at all and every
 * peak is zero, which makes that order the whole order.
 *
 * The figures, the reference and what was declined are in docs/GEOGRAPHY.md and in the ledger row
 * X1c; the two densities' derivations are beside the constants below, as rule 8 asks.
 */
object RiverSelection {

    /**
     * Kilometres of drawn river per square kilometre of land on a 1:50 000 000 map.
     *
     * The reference is **Natural Earth**'s `rivers_lake_centerlines`, `featurecla = River` — the
     * public-domain linework built for small-scale mapping at three stated scales, measured from
     * the GeoJSON at `github.com/nvkelso/natural-earth-vector` (the 1:50M and 1:10M files last
     * moved at commit 0e1681f, 2017-10-23; repository version 5.2.0-pre when read) by summing
     * great-circle distances over every vertex. At 1:50M that is **359 courses and 254 284 km**
     * over Earth's 148.94 million km² of land; at 1:10M, 1 202 courses and 599 507 km, which is
     * 0.004025. `Lake Centerline` is excluded because this map draws a lake as a lake.
     *
     * Carried here by [INK_EXPONENT], the 1:10M tier gives 0.001800, **five and a half per cent
     * above this**; the two bracket 0.001754, and the 1:50M tier is taken outright because this
     * map's own two scales are 1:22M and 1:50M and it is the nearer of the two. It is a *chosen
     * benchmark and not a physical constant*: Natural Earth's linework is hand-smoothed and
     * hand-ranked, its own documentation recommends the 1:10M tier around 1:30M and supplements
     * elsewhere, and a different atlas would give a different figure. Earth's land area is quoted
     * with Antarctica in it, which carries no river on either tier; without it both densities rise
     * by a tenth. See docs/GEOGRAPHY.md for the rest, and `RiverSelectionTest` for the check that
     * the two tiers still agree.
     */
    const val DRAWN_RIVER_KM_PER_SQUARE_KM_AT_FIFTY_MILLION: Double = 0.001707

    /**
     * Drawn courses per square kilometre of land on a 1:50 000 000 map: 2.41 per million.
     *
     * The same 359 courses over the same land area. Used only for [crowdingPitchKilometres], and
     * not for the budget, because a count depends on where the reference cuts one course from the
     * next and this map cuts them elsewhere — at a hundred kilometres, against a reference whose
     * mean drawn course is 708 km at this scale and 499 km at 1:10M.
     */
    const val DRAWN_COURSES_PER_SQUARE_KM_AT_FIFTY_MILLION: Double = 2.41e-6

    /** The scale both figures above were measured at. */
    const val REFERENCE_DENOMINATOR: Double = 50_000_000.0

    /**
     * How the ink carries from one scale to another: Töpfer and Pillewizer's square root.
     *
     * *The principles of selection* (The Cartographic Journal 3(1), 1966, 10-16) measured a
     * **count**, so carrying a *length* by it is an assumption and not the law restated. It is a
     * documented one: Wilmer and Brewer (*Application of the radical law in generalization of
     * national hydrography data for multiscale mapping*, ISPRS Archives XXXVIII-4, AutoCarto 2010)
     * apply the law to hydrography measured as flowline length per square kilometre and find it
     * needs a correction of its own. Here the assumption is checked rather than assumed: Natural
     * Earth's two tiers measure **0.533** over a fivefold change of scale, a thirtieth from the
     * law, and 0.5 is taken as the simpler of the two. Between 1:10M and 1:50M that is
     * interpolation; outside them it is extrapolation, and this map's export at 512 cells across is
     * already at 1:88M.
     */
    private const val INK_EXPONENT: Double = 0.5

    /**
     * How the count carries: the 0.751 measured between Natural Earth's 1:10M and 1:50M tiers.
     *
     * Not Töpfer's square root, and the gap is what small-scale generalisation does — it drops
     * short courses outright rather than drawing half-length rivers. Two points and no published
     * law behind it, which is why nothing is asserted on it and it sets a spacing rather than the
     * budget. Natural Earth's 1:110M tier is not a third point: thirteen rivers is a token
     * selection, not a generalisation of the other two.
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
     * and 475 km at 1:22 000 000 — about eighty cells across a 2048 grid. The lattice is laid out
     * in ground kilometres rather than cells, because a cell of this world is twice as wide as it
     * is tall; it does not wrap the east-west seam, so two mouths either side of it are never
     * crowded against each other.
     */
    fun crowdingPitchKilometres(denominator: Double): Double =
        sqrt(1.0 / drawnCoursesPerSquareKm(denominator))

    /** Everything one sheet's selection decided, for a guard or a report to read. */
    class Selection(
        /** One flag per course of `world.rivers.rivers`, in that order: drawn or not drawn. */
        val drawn: BooleanArray,
        /** Each course's traced length along the ground, in kilometres. See [courseKilometres]. */
        val courseKilometres: DoubleArray,
        /** Each course's trunk, or [NO_TRUNK] where it reaches water or the edge of the world. */
        val trunkOf: IntArray,
        /** The sheet's representative fraction: the `22 000 000` of `1:22 000 000`. */
        val denominator: Double,
        /** This world's land, in square kilometres, which the budget is per. */
        val landAreaSquareKm: Double,
        /** Kilometres of river line this sheet is allowed, at Earth's density for its scale. */
        val budgetKilometres: Double,
        /** The side of the crowding lattice, in ground kilometres. */
        val crowdingPitchKm: Double
    ) {
        val drawnCount: Int get() = drawn.count { it }

        /**
         * Kilometres of traced course this sheet draws.
         *
         * The centreline as the tracer laid it, which is what the reference measures too, and a
         * few parts in a thousand above the ink the reader finally sees: the rasterizer cuts the
         * last stroke back at the shore (`MapRasterizer.trimmedAtTheShore`) and skips any segment
         * lying inside open water.
         */
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

    /**
     * [drawnOn]'s working, kept whole so a guard can read the budget it was spent against.
     *
     * [world] gives the network, the land the density is per and the water a course ends in;
     * [sheet] gives the scale, through [MapSheet.pixelsPerCell] and nothing else. Every length in
     * the result is in kilometres on the ground and every area in square kilometres. The invariant
     * is that the drawn kilometres never exceed [Selection.budgetKilometres] and that every drawn
     * course's trunk is drawn.
     *
     * [spreadByCrowdingLattice] is the control, not a setting: with it false the budget is spent
     * on the discharge ranking alone, which is what `RiverSelectionTest` measures the lattice
     * against. Nothing in the drawing ever passes false.
     */
    fun select(
        world: WorldMap,
        sheet: MapSheet,
        spreadByCrowdingLattice: Boolean = true
    ): Selection {
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
            for (spreadByLattice in booleanArrayOf(spreadByCrowdingLattice, false)) {
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
     * The traced centreline, mouth step included, which is the same quantity the Earth reference
     * measures. It is a few parts in a thousand above the ink actually laid down: the rasterizer
     * cuts the last stroke back at the shore, by half the mouth step plus half a pen width, and
     * skips any segment lying inside open water. A step east or west across the world's seam is
     * one cell like any other, not the width of the map.
     */
    fun courseKilometres(world: WorldMap, river: River): Double {
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
            val eastWestKm = across * cellWidthKm
            val northSouthKm = down * cellHeightKm
            kilometres += sqrt(eastWestKm * eastWestKm + northSouthKm * northSouthKm)
        }
        return kilometres
    }

    /** The raw bits of 1.0f, the largest a width ratio can be. See [rankedByDischarge]. */
    private val BIGGEST_RATIO_BITS: Long = 1f.toRawBits().toLong()
}
