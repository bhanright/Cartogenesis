package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Straight fronts, coasts and mountain fronts both, the basins that leave by them, and how far
 * apart their outlets are.
 *
 * The instrument the coastal-valley question needs. The author's report is that the coasts carry
 * closely spaced valleys perpendicular to the shore about ten cells apart, and ten cells is a
 * measure of the grid, not of the landscape. To find out which of the two sets the spacing, it has
 * to be measured in kilometres, on fronts chosen without ever looking at a traced river, and then
 * re-measured on the same seed built at another grid and with the two settings that could be
 * imposing it moved.
 *
 * Hovius (1996, *Regular spacing of drainage outlets from linear mountain belts*, Basin Research 8,
 * 29-44) measured the same two lengths on Earth's linear belts — the half-width from the main
 * divide to the mountain front, and the spacing of the outlets of the transverse basins that reach
 * that divide — and found their ratio close to 2.1 (1.91 to 2.23 across eleven belts of very
 * different size, climate and rock). That is the figure the numbers here are printed beside, and it
 * is a ratio of trunk basins only.
 *
 * **The fronts, the basins and the trunks never read `RiverResult.rivers`.** The traced courses are
 * a selection — the tracer's length rule, and then the cartographic layer's own — so a spacing
 * measured off them would be measuring the pen. What the finder reads is the height field, the land
 * mask and `RiverResult.flowTarget`, which is the routing's own downhill tree and is what a divide
 * is made of. The traced courses are read for one figure only, the traced spacing below, which is
 * labelled as such wherever it is printed, because an export drew every one of them when the author
 * looked.
 *
 * ## The definitions, all of them, before a number is collected
 *
 * **Kilometre space.** A cell at column `c`, row `r` has its centre at
 * `(c * cellWidthKm, r * cellHeightKm)`. On this project's grid a cell is twice as wide as it is
 * tall (a square grid over an equirectangular world), so every length below is a kilometre length
 * and never a cell count; where a cell count is printed it is printed *beside* the kilometres and
 * labelled, because the whole question is which of the two stays constant as the grid changes.
 *
 * **Belt.** A land cell standing at or above a floor is belt ground, and belt cells are grouped into
 * components by 8-connectivity. Two floors are measured: [BELT_FLOOR_METRES], whose fronts are
 * mountain fronts, and [SHORELINE_FLOOR_METRES], at which every land cell is belt ground and the
 * fronts are straight coasts. The second is the author's complaint read literally: a valley reaching
 * the sea across a straight coast, whatever the ground behind it is made of. The finder does
 * **not** wrap east to west: a belt crossing the seam is traced as two, and [Census.beltsOnTheSeam]
 * counts how many components touch the seam so a reader can see whether it mattered. The routing
 * does wrap, so a basin may reach across the seam from a front that does not, and every position
 * a front reads is taken as the copy of itself nearest the front's midpoint.
 *
 * **Front eligibility.** Fronts are found on the belt as a reference grid draws it: one of
 * [FRONT_REFERENCE_CELLS_ACROSS] columns whose cells are square on the ground, so as many rows as
 * the world's height allows at that cell width. Each block of working cells that is one reference
 * cell is belt ground when at least half of it is. Each component's outer boundary on that grid is
 * traced as a closed chain of cell centres. Along that chain a *front* is a maximal run of points
 * every one of which lies within [FRONT_STRAIGHTNESS_KM] both of the straight chord between the
 * run's first and last point and of the least-squares line through all of them, and it qualifies
 * when it is at least [SHORTEST_FRONT_KM] long. No run includes a cell on the grid's own edge: a
 * component touching the top or bottom row or the seam is cut off by it, and the straight line the
 * cut draws is the grid's and not the ground's. The front's line is the least-squares line, and its
 * ends are the first and last points projected onto it: a walk that carried a run a cell round a
 * corner before the bar stopped it would otherwise tilt the chord by that cell, and a tilted chord
 * moves the outlets at its far end out of the band they are counted in. A front's *landward* side is
 * whichever side of its line carries more belt ground a straightness bar from it, and the
 * perpendicular coordinate `t` below is measured positive in that direction, the along-line
 * coordinate `s` from 0 at its start. A front is **coastal** when open water lies within
 * [COASTAL_REACH_KM] seaward of the line at at least half of the points sampled along it.
 *
 * **Exit.** Following `flowTarget` downstream from a belt cell, the *exit* is the last belt cell
 * before the path leaves belt ground. A path that ends inside the belt — a cell whose target is -1,
 * or a route that never leaves — has no exit, and its cells belong to no basin. Every belt cell
 * sharing an exit is that exit's *basin*; a basin holds belt cells only, so where water from below
 * the floor crosses a belt the part of its catchment below the floor is not counted.
 *
 * **Outlet.** An exit is an outlet of a given front when its `s` lies inside the chord, its `|t|`
 * is at most [FRONT_STRAIGHTNESS_KM] — the line has the width its own straightness bar gives it —
 * and the cell it drains into does not lie landward of that band, so a course leaving the belt
 * into a hollow behind the front is not counted as leaving by it. Which way the step points
 * inside the band is not asked: on a coast that wanders within its bar, a mouth draining along
 * the shore into the sea beside it is still a mouth on that coast.
 *
 * **Catchment floor.** A basin smaller than [InstrumentScales.catchmentFloorKm2] is set aside. The
 * floor is a choice, not a law: it is the area at which Hack's empirical length-area relation puts a
 * typical basin's main stream at the shortest course the map draws, and a real basin of that area
 * may carry a longer or a shorter one. It is in square kilometres, so the same ground qualifies at
 * every grid; what it leaves out at a fine grid is still seen by the traced spacing if the tracer
 * keeps a course there, so a grid-set comb cannot hide behind the floor. The audit re-takes the
 * spacing with the floor halved and doubled, because a floor could as easily steady a spacing
 * across grids as reveal one.
 *
 * **Trunk and divide qualification.** A front's basins, corners included, tile a region `R` of
 * belt ground, which is all the ground draining out through this front. The **main divide** is
 * approximated by where `R` ends landward: cut `R` into strips [InstrumentScales.divideStripKm]
 * wide along `s`, and the divide's distance in each strip is the largest `t` of any cell of `R` in
 * it. A basin's *head* is its cell of largest `t`, and a basin clearing the catchment floor is a
 * **trunk basin** when its head lies within one strip width of the divide in the head's own strip.
 * A basin whose head is enclosed by its larger neighbours reaches the front and not the divide, and
 * is not a trunk. The rule is a distance and not a contact because `R` has holes — a mouth in a bay
 * deeper than the bar is not an outlet of the front, so its basin is not in `R` — and a rule that
 * asked for contact with ground outside `R` would find the divide at the edge of every such hole.
 *
 * **What the divide proxy is not.** `R` holds only the basins whose outlets survived the front's
 * band, so the envelope is theirs and not the watershed's: where a strip holds cells of one basin
 * alone — its neighbours leaving by a bay, or by the ground past the front's end — that basin sets
 * its own strip's divide and qualifies however short it is. [Outlet.aloneInItsStrip] marks those
 * trunks and the audit prints their share. The basin's **divide-to-front distance** is its head's
 * `t`, and a front's half-width is the median of its trunks'; both, and Hovius's ratio built on
 * them, are properties of this proxy and are compared with Hovius's figures only as such.
 *
 * **Along-front spacing.** The outlets of a front's trunk basins are sorted by `s`, and the
 * spacings are the differences between neighbours, in kilometres. A front with fewer than two trunk
 * outlets contributes no spacing. Beside it, two combs. The **catchment spacing** is the same over
 * every basin leaving by the front that clears the catchment floor, trunk or not: the valleys the
 * ground has cut, large enough to carry a drawn course. The **traced spacing** is over every
 * outlet, of any size, whose exit cell a traced course (`RiverResult.rivers`) passes through: the
 * comb an export drew in full until the cartographic layer began choosing among the traced courses
 * (docs/DESIGN_LEDGER.md, X1c), and the comb the author saw. Where the traced comb is finer than
 * the catchment comb, the tracer is keeping courses down strips of ground too narrow to be
 * valleys.
 *
 * **Bearing.** A front is east-west when its line lies within 22.5 degrees of a row, north-south
 * within 22.5 of a column, and diagonal otherwise. Every table is split this way, because a step
 * along a row is a cell width and a step down a column is half of one, so a spacing counted in
 * cells and one set in kilometres read differently on the two. The split was meant to say which
 * of them is real, and it cannot: measured, the world's own outlines are isotropic in cells and not
 * in kilometres, so the ground itself reads the same cells both ways (docs/DESIGN_LEDGER.md, X1d).
 * It is printed, and nothing is concluded from it.
 *
 * **Cross-resolution matching.** Only fronts carrying the spacing being compared take part, so a
 * front with no usable figure cannot take a partner that has one. Two such fronts found on the same
 * seed at different grids are the same front when their midpoints lie within [FRONT_MATCH_KM],
 * their lines agree within [FRONT_MATCH_DEGREES] as undirected lines (a line traced the other way
 * round still matches), their landward sides point the same way (so the two coasts of a peninsula
 * do not pair), and each overlaps the other along the line by at least
 * [SHORTEST_OVERLAP_SHARE] of the shorter one's length. Each front matches at most one front on the
 * other grid, nearest midpoint first; fronts left over are reported as unmatched rather than
 * quietly dropped.
 *
 * **The empty sample.** Nothing here reports a zero where it means "no sample". A seed and grid
 * that yields no belt, no eligible front or no front with two trunk outlets reports its [Census] —
 * what survived each filter in turn — and contributes nothing to any pooled figure. A pooled figure
 * is a median, over fronts (each front counted once) or over gaps (each gap counted once), and the
 * number of fronts or gaps behind it is always printed with it.
 */
internal object RangeFront {

    /** The floor at which every land cell is belt ground, so that a front is a straight coast. */
    const val SHORELINE_FLOOR_METRES = 0f

    /**
     * Ground at or above this altitude is belt ground on a mountain front, in metres above the
     * shoreline.
     *
     * Two kilometres. The ground this generator's continents stand on is not Earth's: its land's
     * mean stands 1,200 to 1,700 m above its own sea against Earth's 840 (docs/GEOGRAPHY.md, "Half
     * the land is tundra"), so a floor at or under the mean land level picks out most of a
     * continent rather than its mountains — a thousand metres takes in 71 and 57 per cent of the
     * land of 969495 and 718106 at 512 — and its "fronts" are the continent's upland edge. Two
     * kilometres is above the land's mean on every audited world at every grid (1,159 to 1,788 m),
     * and below the crest a stock coastal range is stamped to (`TectonicsConfig.andeanHeight` of
     * 0.52 on `beltReliefMetres` of 13,000 m, 6,760 m at full strength before erosion).
     * `CoastalSpacingAuditTest` re-takes two seeds at the shoreline and at 1,000, 1,500, 2,000 and
     * 2,500 m and prints the land share and the spacing at each, because a finder whose answer
     * walked with its own floor would be measuring the floor.
     */
    const val BELT_FLOOR_METRES = 2_000f

    /**
     * How far a front may wander from its own chord and still be called straight, in kilometres.
     *
     * One cell width on the coarsest grid measured here — 12,000 km over 512 columns is 23.4 km,
     * rounded up — because a 512 world cannot resolve a bend smaller than its own cell, and a bar
     * that tightened with the grid would find fewer fronts at 2048 than at 512 for a reason about
     * the bar rather than about the landscape. The same kilometre figure is used at every grid,
     * which is what makes the fronts comparable across them.
     */
    const val FRONT_STRAIGHTNESS_KM = 25.0

    /**
     * The shortest chord that counts as a front, in kilometres.
     *
     * Derived from the belt this generator builds and from Hovius's ratio. The stock coastal range
     * is `TectonicsConfig.andeanWidthCells` = 14 cells of half-width on the 512 grid, and
     * `PlateStage` counts that distance in cells without converting it, so in kilometres it is 14
     * cell heights, 164 km, across a front running east-west and 14 cell widths, 328 km, across
     * one running north-south. At a ratio of 2.1 those space their outlets 78 and 156 km apart.
     * Three outlets is the fewest that makes a spacing more than a single number, so the shortest
     * useful front on the broader of the two is two of those spacings, 313 km, rounded down to 300;
     * the narrower fits four outlets in the same chord.
     */
    const val SHORTEST_FRONT_KM = 300.0

    /**
     * The longest chord a run is extended to, in kilometres.
     *
     * Five times [SHORTEST_FRONT_KM], and longer than any straight front on Earth at a 25 km
     * tolerance — the Sierra Nevada's is about 600 km and the Southern Alps' about 500. It is a
     * bound on the work each chain costs rather than a claim about landscapes; the straightness bar
     * ends a real run long before it.
     */
    const val LONGEST_FRONT_KM = 1_500.0

    /**
     * The smallest basin that counts as a catchment, in square kilometres: a choice.
     *
     * Hack (1957) found a stream's length growing as `L = 1.4 A^0.6` in miles and square miles,
     * which in kilometres is `L = 2.253 (A / 2.590)^0.6`. That is an empirical fit over typical
     * basins and not a floor on any of them; inverted at the shortest course the map draws,
     * `RiverConfig.shortestDrawnCourseKm`, 100 km, it gives 1,441 km2, the area at which a basin of
     * typical shape carries a course the map would draw. It is five and a quarter cells at 512 and
     * eighty-four at 2048, so the same ground qualifies at both, and a cell or two at a belt's edge
     * draining straight off it never does. The audit halves and doubles it.
     */
    const val SMALLEST_CATCHMENT_KM2 = 1_440.0

    /**
     * The width of the strips the main divide is found in, and how close a trunk's head must come
     * to it, in kilometres.
     *
     * One straightness bar, because the front's own position is known to that bar and no better, so
     * a finer strip would resolve a divide more finely than the line it is measured from. The audit
     * halves and doubles it.
     */
    const val DIVIDE_STRIP_KM = FRONT_STRAIGHTNESS_KM

    /**
     * The instrument's own two scales that decide which basins count, gathered so the audit can
     * move them: a spacing that walked with either would be the instrument's and not the ground's.
     */
    class InstrumentScales(
        /** Strip width and head tolerance for the main divide, in km; see [DIVIDE_STRIP_KM]. */
        val divideStripKm: Double = DIVIDE_STRIP_KM,
        /** The smallest catchment, in km2; see [SMALLEST_CATCHMENT_KM2]. */
        val catchmentFloorKm2: Double = SMALLEST_CATCHMENT_KM2
    )

    /**
     * How close open water must come seaward of a chord for the front to be coastal, in km.
     *
     * Four straightness bars. The author's valleys run from the range to the shore with a river
     * down each, so the ground between the front and the sea has to be narrow enough that a course
     * leaving the front is a course reaching the sea; a hundred kilometres is four cells across at
     * 512 and seventeen at 2048, which is the width of coastal plain a transverse river crosses on
     * Earth's active margins (the Pacific slope of Peru and Chile is 20 to 150 km).
     */
    const val COASTAL_REACH_KM = 100.0

    /** Two fronts this far apart at the midpoint may still be one front: half the shortest one. */
    const val FRONT_MATCH_KM = SHORTEST_FRONT_KM / 2

    /**
     * How far two fronts' lines may differ in direction and still be one front, in degrees.
     *
     * Two lines each within a straightness bar of one outline 300 km long can differ by as much as
     * `atan(4 * 25 / 300)`, 18.4 degrees, one bar either side at each end; the outline itself moves
     * by up to a coarse cell between grids, so thirty leaves room for that. It does not keep a pair
     * inside one bearing bucket — a front at 20 degrees from a row and its partner at 25 fall in
     * different buckets and still match — and the audit's bearing tables are per grid, not per pair.
     */
    const val FRONT_MATCH_DEGREES = 30.0

    /**
     * How much of the shorter of two matched fronts must lie alongside the other, as a share of
     * its length: half, so a pair is one stretch of outline seen twice and not two neighbouring
     * stretches that happen to have close midpoints.
     */
    const val SHORTEST_OVERLAP_SHARE = 0.5

    /** Hovius (1996): half-width over outlet spacing on Earth's linear mountain belts. */
    const val HOVIUS_RATIO = 2.1

    /**
     * The grid fronts are found on, in columns: the coarsest grid measured, 512, with square cells.
     *
     * A front has to be the same front at every grid or nothing measured on it can be compared
     * across grids, and at 2048 the outline of a coast is serrated at the cell scale by the very
     * valleys and delta lobes this instrument is measuring — enough, measured on the author's
     * world, to push the traced outline of the comb's own coast past the straightness bar, so that
     * the coast the author complained of was not a front at the grid he saw it on. Finding the
     * fronts at the coarsest resolution judges straightness at the one resolution every grid
     * shares; the basins, their outlets and the spacings are still measured at full resolution.
     *
     * Square, 23.4 km a side and so 256 rows, and not the 512 grid's own 23.4 by 11.7 km cells:
     * on those, the 25 km bar allows a north-south outline one column of staircase and an
     * east-west one two rows, so the finder itself preferred a bearing. Squaring the cells removed
     * that and changed little — over the seven audited worlds it found one or two north-south
     * coasts per hundred east-west ones before and one to three after — because most of the
     * preference is in the land (docs/DESIGN_LEDGER.md, X1d).
     */
    const val FRONT_REFERENCE_CELLS_ACROSS = 512

    /** How many evenly spaced entries a boundary chain is walked from; see [straightRunsOf]. */
    private const val ENTRY_POINTS_PER_CHAIN = 4

    /** Which way a front runs, as the grid sees it: the bucket its bearing falls in. */
    enum class Bearing {
        /** Within 22.5 degrees of a row, so a step along it is a cell *width*. */
        EAST_WEST,

        /** Within 22.5 degrees of a column, so a step along it is a cell *height*. */
        NORTH_SOUTH,

        /** Everything in between. */
        DIAGONAL
    }

    /** A straight run of one belt's outer boundary, in kilometre coordinates. */
    class Front(
        val startKmX: Double,
        val startKmY: Double,
        val endKmX: Double,
        val endKmY: Double,
        /** Unit vector along the chord, start to end, in kilometre space. */
        val alongX: Double,
        val alongY: Double,
        /** Unit vector across the chord, positive toward the belt. */
        val landwardX: Double,
        val landwardY: Double,
        val lengthKm: Double
    ) {
        /** Degrees of the chord away from a row of the grid: 0 east-west, 90 north-south. */
        val bearingFromRowDegrees: Double
            get() = Math.toDegrees(atan2(abs(alongY), abs(alongX)))

        val bearing: Bearing
            get() = when {
                bearingFromRowDegrees < 22.5 -> Bearing.EAST_WEST
                bearingFromRowDegrees > 67.5 -> Bearing.NORTH_SOUTH
                else -> Bearing.DIAGONAL
            }

        val midKmX: Double get() = (startKmX + endKmX) / 2
        val midKmY: Double get() = (startKmY + endKmY) / 2

        /** Where along the chord a point in kilometre space falls, from 0 at its start. */
        fun alongAt(kmX: Double, kmY: Double): Double =
            (kmX - startKmX) * alongX + (kmY - startKmY) * alongY

        /** How far toward the belt a point in kilometre space lies, signed. */
        fun landwardAt(kmX: Double, kmY: Double): Double =
            (kmX - startKmX) * landwardX + (kmY - startKmY) * landwardY

        /**
         * How many grid cells a kilometre length along this chord covers.
         *
         * The grid step a chord takes is the larger of its two axis steps — one column and one row
         * together is one cell, not two — so this is the Chebyshev count, which is what "ten cells
         * apart" means when it is said of a picture.
         */
        fun cellsAlong(kilometres: Double, cellWidthKm: Double, cellHeightKm: Double): Double =
            max(abs(kilometres * alongX) / cellWidthKm, abs(kilometres * alongY) / cellHeightKm)

        /**
         * How many kilometres a distance of [cells] counted *isotropically in cells* covers along
         * this front's landward normal.
         *
         * `PlateStage` stamps every belt profile as a function of a Euclidean distance measured in
         * cells, with no row scale, so a half-width written as fourteen cells is fourteen cells in
         * whatever direction the normal runs, and that is a different number of kilometres on a
         * north-south front than on an east-west one. This is the stamp's own half-width in the
         * kilometres the rest of this object measures in.
         */
        fun kilometresOfCellsAcross(cells: Double, cellWidthKm: Double, cellHeightKm: Double): Double {
            val columnsPerKm = landwardX / cellWidthKm
            val rowsPerKm = landwardY / cellHeightKm
            return cells / sqrt(columnsPerKm * columnsPerKm + rowsPerKm * rowsPerKm)
        }
    }

    /** One basin leaving by one front. */
    class Outlet(
        val outletCell: Int,
        /** Where the outlet sits along the front's chord, in kilometres from its start. */
        val alongFrontKm: Double,
        val areaKm2: Double,
        /** The largest perpendicular distance from the chord any cell of the basin reaches, in km. */
        val divideToFrontKm: Double,
        /** Whether the basin's head reaches the main divide; see the definitions. */
        val isTrunk: Boolean,
        /**
         * Whether the strip holding the basin's head holds cells of no other basin of the front's
         * region, so that the basin set its own strip's divide; see the definitions.
         */
        val aloneInItsStrip: Boolean,
        /** The basin's own cells, kept only when the caller asked for them, for drawing. */
        val cells: IntArray?
    )

    /** One front, and whatever survived the catchment floor and the trunk filter on it. */
    class Measured(
        val front: Front,
        val coastal: Boolean,
        /** Every basin leaving by the front that clears the catchment floor, sorted by `s`. */
        val catchments: List<Outlet>,
        /** Basins leaving by the front below the catchment floor, for the census. */
        val cornersSetAside: Int,
        /**
         * Where along the chord each outlet a traced course passes through sits, in km, sorted; null
         * when the caller supplied no traced courses. The tracer's figure, not the ground's.
         */
        val tracedAlongKm: List<Double>?,
        /**
         * The crust pair whose boundary lies nearest most of the front's outlets, where the caller
         * supplied `PlateResult.nearestBoundaryClass`; null otherwise.
         */
        val boundaryClass: BoundaryClass?
    ) {
        val trunks: List<Outlet> get() = catchments.filter { it.isTrunk }

        /** Differences between neighbouring trunk outlets along the chord, in kilometres. */
        val spacingsKm: List<Double> get() = gapsOf(trunks)

        /** Differences between neighbouring catchment outlets, trunk or not: the ground's comb. */
        val catchmentSpacingsKm: List<Double> get() = gapsOf(catchments)

        /** Differences between neighbouring traced outlets: the comb an export drew, or empty. */
        val tracedSpacingsKm: List<Double>
            get() = tracedAlongKm?.let { along -> (1 until along.size).map { along[it] - along[it - 1] } }
                ?: emptyList()

        val medianSpacingKm: Double? get() = median(spacingsKm)
        val medianCatchmentSpacingKm: Double? get() = median(catchmentSpacingsKm)
        val medianTracedSpacingKm: Double? get() = median(tracedSpacingsKm)
        val medianDivideToFrontKm: Double? get() = median(trunks.map { it.divideToFrontKm })

        /**
         * Hovius's ratio read on this instrument: the median half-width, measured to the divide
         * proxy, over the median trunk spacing. A property of the proxy; see the definitions.
         */
        val hoviusRatio: Double?
            get() {
                val spacing = medianSpacingKm ?: return null
                val halfWidth = medianDivideToFrontKm ?: return null
                return if (spacing <= 0.0) null else halfWidth / spacing
            }

        private fun gapsOf(outlets: List<Outlet>): List<Double> =
            (1 until outlets.size).map { outlets[it].alongFrontKm - outlets[it - 1].alongFrontKm }
    }

    /** What survived each filter, so an empty sample can say which filter emptied it. */
    class Census(
        val landCells: Int,
        /** The land's mean altitude above the shoreline, in metres: what the floor is read against. */
        val meanLandMetres: Double,
        val beltCells: Int,
        val beltComponents: Int,
        val beltsOnTheSeam: Int,
        val boundaryChains: Int,
        val straightRuns: Int,
        val fronts: Int,
        val coastalFronts: Int,
        val frontsWithTwoTrunks: Int
    ) {
        override fun toString(): String =
            ("land's mean %.0f m; belt %.1f%% of land (%d cells) in %d components (%d touching " +
                "the seam), %d outer boundaries, %d straight runs, %d over %d km (%d coastal), %d " +
                "with two trunk outlets")
                .format(
                    meanLandMetres, 100.0 * beltCells / max(1, landCells), beltCells, beltComponents,
                    beltsOnTheSeam, boundaryChains, straightRuns, fronts,
                    SHORTEST_FRONT_KM.toInt(), coastalFronts, frontsWithTwoTrunks
                )
    }

    /** Everything one world yields. Carries no grid-sized array unless basin cells were kept. */
    class Report(
        val seed: Long,
        val cellsAcross: Int,
        val cellWidthKm: Double,
        val cellHeightKm: Double,
        val measured: List<Measured>,
        val census: Census
    ) {
        /** The fronts that carry a trunk spacing: the sample every per-front figure is over. */
        val sample: List<Measured> get() = measured.filter { it.medianSpacingKm != null }

        fun sampleWhere(bearing: Bearing? = null, coastalOnly: Boolean = false): List<Measured> =
            sample.filter {
                (bearing == null || it.front.bearing == bearing) && (!coastalOnly || it.coastal)
            }

        /** A front's median trunk spacing counted in grid cells along it: the author's unit. */
        fun spacingInCells(measured: Measured): Double? = measured.medianSpacingKm?.let {
            measured.front.cellsAlong(it, cellWidthKm, cellHeightKm)
        }

        /**
         * Every gap between neighbouring outlets on every front matching the filter, in km.
         *
         * A per-front median weights a front carrying three outlets the same as one carrying forty,
         * which is right for comparing fronts and wrong when the whole sample is a handful of them,
         * so both are reported. [trunksOnly] takes the gaps between trunk basins, which is Hovius's
         * sample; false takes the catchment gaps, which is the comb.
         */
        fun gapsKm(trunksOnly: Boolean, bearing: Bearing? = null, coastalOnly: Boolean = false) =
            measured
                .filter {
                    (bearing == null || it.front.bearing == bearing) && (!coastalOnly || it.coastal)
                }
                .flatMap { if (trunksOnly) it.spacingsKm else it.catchmentSpacingsKm }

        /** The same gaps, each counted in the grid cells its own front's bearing makes of it. */
        fun gapsInCells(trunksOnly: Boolean, bearing: Bearing? = null, coastalOnly: Boolean = false) =
            measured
                .filter {
                    (bearing == null || it.front.bearing == bearing) && (!coastalOnly || it.coastal)
                }
                .flatMap { front ->
                    (if (trunksOnly) front.spacingsKm else front.catchmentSpacingsKm)
                        .map { front.front.cellsAlong(it, cellWidthKm, cellHeightKm) }
                }

        /** Every trunk's divide-to-front distance on fronts matching the filter, in km. */
        fun halfWidthsKm(bearing: Bearing? = null, coastalOnly: Boolean = false) = measured
            .filter { (bearing == null || it.front.bearing == bearing) && (!coastalOnly || it.coastal) }
            .filter { it.spacingsKm.isNotEmpty() }
            .flatMap { front -> front.trunks.map { it.divideToFrontKm } }
    }

    /** The median of a list, or null where the list is empty: an empty sample is not a zero. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** The lower and upper quartile of a list, or null where it has fewer than four entries. */
    fun quartiles(values: List<Double>): Pair<Double, Double>? {
        if (values.size < 4) return null
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 4] to sorted[(3 * (sorted.size - 1)) / 4]
    }

    /** Runs the whole finder over a finished world. */
    fun measure(
        world: WorldMap,
        beltFloorMetres: Float = BELT_FLOOR_METRES,
        keepBasinCells: Boolean = false,
        scales: InstrumentScales = InstrumentScales()
    ): Report = measure(
        seed = world.config.seed,
        cellsAcross = world.width,
        cellsDown = world.height,
        cellWidthKm = world.config.cellWidthKm,
        cellHeightKm = world.config.cellHeightKm,
        isLand = world.sea.isLand,
        metresAboveShoreline = FloatArray(world.width * world.height) {
            world.config.scale.metresAboveShoreline(world.sea.relativeElevation.data[it])
        },
        flowTarget = world.rivers.flowTarget,
        beltFloorMetres = beltFloorMetres,
        nearestBoundaryClass = world.plates.nearestBoundaryClass,
        tracedCourse = BooleanArray(world.width * world.height).also { traced ->
            world.rivers.rivers.forEach { river -> river.cells.forEach { traced[it] = true } }
        },
        keepBasinCells = keepBasinCells,
        scales = scales
    )

    /**
     * The finder over plain arrays, so a synthetic fixture can drive it without a world.
     *
     * [isLand] and [metresAboveShoreline] are row-major, one entry per cell; [flowTarget] is the
     * routing's downhill target per cell, -1 where the water leaves the world;
     * [nearestBoundaryClass], where given, is `PlateResult.nearestBoundaryClass`; [tracedCourse],
     * where given, marks every cell a traced course passes through, and is read for the traced
     * spacing and for nothing else. Returns one [Report], whose figures are null rather than zero
     * where there is no sample.
     */
    fun measure(
        seed: Long,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double,
        isLand: BooleanArray,
        metresAboveShoreline: FloatArray,
        flowTarget: IntArray,
        beltFloorMetres: Float = BELT_FLOOR_METRES,
        nearestBoundaryClass: IntArray? = null,
        tracedCourse: BooleanArray? = null,
        keepBasinCells: Boolean = false,
        scales: InstrumentScales = InstrumentScales()
    ): Report {
        val cellCount = cellsAcross * cellsDown
        val belt = BooleanArray(cellCount) {
            isLand[it] && metresAboveShoreline[it] >= beltFloorMetres
        }
        val beltCells = belt.count { it }
        val landCells = isLand.count { it }
        var landMetres = 0.0
        for (cell in 0 until cellCount) if (isLand[cell]) landMetres += metresAboveShoreline[cell]

        // The belt as the square reference grid draws it, which is where the fronts are found.
        val columnsPerBlock = max(1, cellsAcross / FRONT_REFERENCE_CELLS_ACROSS)
        val rowsPerBlock = max(1, (columnsPerBlock * cellWidthKm / cellHeightKm).roundToInt())
        val referenceAcross = cellsAcross / columnsPerBlock
        val referenceDown = cellsDown / rowsPerBlock
        val referenceBelt = BooleanArray(referenceAcross * referenceDown) { block ->
            val firstColumn = (block % referenceAcross) * columnsPerBlock
            val firstRow = (block / referenceAcross) * rowsPerBlock
            var inBelt = 0
            for (row in firstRow until firstRow + rowsPerBlock) {
                for (column in firstColumn until firstColumn + columnsPerBlock) {
                    if (belt[row * cellsAcross + column]) inBelt++
                }
            }
            inBelt * 2 >= columnsPerBlock * rowsPerBlock
        }
        val referenceCellWidthKm = cellWidthKm * columnsPerBlock
        val referenceCellHeightKm = cellHeightKm * rowsPerBlock
        // A reference cell's centre in the working grid's coordinates, which count from the first
        // cell's centre: a block of n cells is centred (n - 1) / 2 cells in.
        val referenceOffsetKmX = (columnsPerBlock - 1) / 2.0 * cellWidthKm
        val referenceOffsetKmY = (rowsPerBlock - 1) / 2.0 * cellHeightKm

        val component = componentsOf(referenceBelt, referenceAcross, referenceDown)
        var componentCount = 0
        component.forEach { if (it + 1 > componentCount) componentCount = it + 1 }
        val seamComponents = HashSet<Int>()
        for (row in 0 until referenceDown) {
            val west = component[row * referenceAcross]
            val east = component[row * referenceAcross + referenceAcross - 1]
            if (west >= 0) seamComponents.add(west)
            if (east >= 0) seamComponents.add(east)
        }

        val exit = exitsOf(belt, flowTarget, cellCount)
        val basins = basinsOf(exit, flowTarget, cellsAcross, cellsDown, cellWidthKm, cellHeightKm)

        val chains =
            boundaryChains(referenceBelt, component, componentCount, referenceAcross, referenceDown)
        var straightRuns = 0
        val fronts = ArrayList<Front>()
        chains.forEach { chain ->
            val runs = straightRunsOf(
                chain, referenceAcross, referenceDown, referenceCellWidthKm, referenceCellHeightKm,
                referenceOffsetKmX, referenceOffsetKmY
            )
            straightRuns += runs.size
            runs.forEach { run ->
                if (run.lengthKm >= SHORTEST_FRONT_KM) {
                    fronts.add(orient(run, belt, cellsAcross, cellsDown, cellWidthKm, cellHeightKm))
                }
            }
        }

        val measured = fronts.map { front ->
            measureFront(
                front, basins, isLand, nearestBoundaryClass, tracedCourse, keepBasinCells, scales,
                cellsAcross, cellsDown, cellWidthKm, cellHeightKm
            )
        }

        return Report(
            seed = seed,
            cellsAcross = cellsAcross,
            cellWidthKm = cellWidthKm,
            cellHeightKm = cellHeightKm,
            measured = measured,
            census = Census(
                landCells = landCells,
                meanLandMetres = landMetres / max(1, landCells),
                beltCells = beltCells,
                beltComponents = componentCount,
                beltsOnTheSeam = seamComponents.size,
                boundaryChains = chains.size,
                straightRuns = straightRuns,
                fronts = fronts.size,
                coastalFronts = measured.count { it.coastal },
                frontsWithTwoTrunks = measured.count { it.spacingsKm.isNotEmpty() }
            )
        )
    }

    /**
     * One given line measured as a front, whether or not the finder would choose it: the same
     * basins, outlets, catchments and trunks, on a line fixed in kilometres, so one stretch of coast
     * can be followed across grids without depending on where each grid's outline puts its front.
     *
     * [startKmX], [startKmY], [endKmX] and [endKmY] are the line's ends in kilometre space; its
     * landward side is decided as a found front's is. Returns the one [Measured].
     */
    fun measureLine(
        world: WorldMap,
        startKmX: Double,
        startKmY: Double,
        endKmX: Double,
        endKmY: Double,
        beltFloorMetres: Float = SHORELINE_FLOOR_METRES,
        scales: InstrumentScales = InstrumentScales()
    ): Measured {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val cellCount = cellsAcross * cellsDown
        val isLand = world.sea.isLand
        val belt = BooleanArray(cellCount) {
            isLand[it] && world.config.scale.metresAboveShoreline(world.sea.relativeElevation.data[it]) >=
                beltFloorMetres
        }
        val flowTarget = world.rivers.flowTarget
        val basins = basinsOf(
            exitsOf(belt, flowTarget, cellCount), flowTarget, cellsAcross, cellsDown,
            cellWidthKm, cellHeightKm
        )
        val traced = BooleanArray(cellCount).also { mask ->
            world.rivers.rivers.forEach { river -> river.cells.forEach { mask[it] = true } }
        }
        val run = Run(startKmX, startKmY, endKmX, endKmY, hypot(endKmX - startKmX, endKmY - startKmY))
        val front = orient(run, belt, cellsAcross, cellsDown, cellWidthKm, cellHeightKm)
        return measureFront(
            front, basins, isLand, world.plates.nearestBoundaryClass, traced, false, scales,
            cellsAcross, cellsDown, cellWidthKm, cellHeightKm
        )
    }

    // ---------------------------------------------------------------- belt components

    /** Component id per cell, -1 off the belt. 8-connected, and deliberately not wrapping. */
    private fun componentsOf(belt: BooleanArray, cellsAcross: Int, cellsDown: Int): IntArray {
        val component = IntArray(belt.size) { -1 }
        var next = 0
        val stack = IntArray(belt.size)
        for (start in belt.indices) {
            if (!belt[start] || component[start] >= 0) continue
            var top = 0
            stack[top++] = start
            component[start] = next
            while (top > 0) {
                val cell = stack[--top]
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (stepRow in -1..1) for (stepColumn in -1..1) {
                    if (stepRow == 0 && stepColumn == 0) continue
                    val neighbourColumn = column + stepColumn
                    val neighbourRow = row + stepRow
                    if (neighbourColumn < 0 || neighbourColumn >= cellsAcross) continue
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    val neighbour = neighbourRow * cellsAcross + neighbourColumn
                    if (belt[neighbour] && component[neighbour] < 0) {
                        component[neighbour] = next
                        stack[top++] = neighbour
                    }
                }
            }
            next++
        }
        return component
    }

    // ---------------------------------------------------------------- exits

    /**
     * The last belt cell on each belt cell's downhill path before the path leaves the belt, or -1.
     *
     * Iterative rather than recursive, and memoised in the one array: a 2048 world's longest
     * downhill path runs to thousands of cells and a recursive walk over four million of them
     * overflows the stack. A cell still being walked is marked, so a route that closes on itself
     * ends the walk instead of spinning.
     */
    private fun exitsOf(belt: BooleanArray, flowTarget: IntArray, cellCount: Int): IntArray {
        val unknown = -2
        val walking = -3
        val exit = IntArray(cellCount) { unknown }
        val path = IntArray(cellCount)
        for (start in 0 until cellCount) {
            if (!belt[start] || exit[start] != unknown) continue
            var depth = 0
            var cell = start
            while (true) {
                if (exit[cell] != unknown) break
                val target = flowTarget[cell]
                if (target < 0) {
                    exit[cell] = -1
                    break
                }
                if (!belt[target]) {
                    exit[cell] = cell
                    break
                }
                exit[cell] = walking
                path[depth++] = cell
                cell = target
            }
            val answer = if (exit[cell] == walking) -1 else exit[cell]
            while (depth > 0) {
                val walked = path[--depth]
                if (exit[walked] == walking) exit[walked] = answer
            }
        }
        for (cell in 0 until cellCount) {
            if (exit[cell] == unknown || exit[cell] == walking) exit[cell] = -1
        }
        return exit
    }

    // ---------------------------------------------------------------- basins, held once

    /**
     * Every basin of the world at once: the cells sharing an exit, laid out back to back.
     *
     * Built once per world rather than once per front, because a front reads only the basins whose
     * exit lies on it and a 2048 world holds four million cells: walking all of them once per front
     * is the difference between a measurement that runs and one that does not.
     */
    private class Basins(
        /** The exit cell of each basin. */
        val outletCell: IntArray,
        /** Where each basin's cells begin in [member]; one longer than the basin count. */
        val start: IntArray,
        /** Every belt cell that has an exit, grouped by basin. */
        val member: IntArray,
        /** Each outlet's position in kilometre space, so a front need not divide by the grid. */
        val outletKmX: DoubleArray,
        val outletKmY: DoubleArray,
        /** Where each outlet's own receiver stands, which says whether the step is seaward. */
        val receiverKmX: DoubleArray,
        val receiverKmY: DoubleArray,
        /**
         * Outlets bucketed by a square of ground, so a front reads the outlets near it and no more:
         * scanning a 2048 world's hundreds of thousands of outlets once per front costs more than
         * generating the world.
         */
        val bucketStart: IntArray,
        val bucketMember: IntArray,
        val bucketsAcross: Int,
        val bucketsDown: Int
    )

    /** The side of one bucket of the outlet index, in kilometres. */
    private const val OUTLET_BUCKET_KM = 100.0

    private fun basinsOf(
        exit: IntArray,
        flowTarget: IntArray,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Basins {
        val idOfExit = HashMap<Int, Int>()
        val basinOf = IntArray(exit.size) { -1 }
        val outlets = ArrayList<Int>()
        for (cell in exit.indices) {
            val outlet = exit[cell]
            if (outlet < 0) continue
            basinOf[cell] = idOfExit.getOrPut(outlet) {
                outlets.add(outlet)
                outlets.size - 1
            }
        }
        val count = outlets.size
        val start = IntArray(count + 1)
        for (cell in basinOf.indices) {
            val id = basinOf[cell]
            if (id >= 0) start[id + 1]++
        }
        for (id in 1..count) start[id] += start[id - 1]
        val fill = start.copyOf()
        val member = IntArray(start[count])
        for (cell in basinOf.indices) {
            val id = basinOf[cell]
            if (id >= 0) member[fill[id]++] = cell
        }
        val outletCell = outlets.toIntArray()

        val outletKmX = DoubleArray(count) { (outletCell[it] % cellsAcross) * cellWidthKm }
        val outletKmY = DoubleArray(count) { (outletCell[it] / cellsAcross) * cellHeightKm }
        val receiverKmX = DoubleArray(count)
        val receiverKmY = DoubleArray(count)
        for (basin in 0 until count) {
            val target = flowTarget[outletCell[basin]]
            if (target < 0) {
                // No receiver: park it on its own outlet, which fails the seaward test.
                receiverKmX[basin] = outletKmX[basin]
                receiverKmY[basin] = outletKmY[basin]
            } else {
                receiverKmX[basin] = (target % cellsAcross) * cellWidthKm
                receiverKmY[basin] = (target / cellsAcross) * cellHeightKm
            }
        }

        val bucketsAcross = max(1, (cellsAcross * cellWidthKm / OUTLET_BUCKET_KM).toInt() + 1)
        val bucketsDown = max(1, (cellsDown * cellHeightKm / OUTLET_BUCKET_KM).toInt() + 1)
        val buckets = bucketsAcross * bucketsDown
        val bucketStart = IntArray(buckets + 1)
        val bucketOf = IntArray(count)
        for (basin in 0 until count) {
            val column = (outletKmX[basin] / OUTLET_BUCKET_KM).toInt().coerceIn(0, bucketsAcross - 1)
            val row = (outletKmY[basin] / OUTLET_BUCKET_KM).toInt().coerceIn(0, bucketsDown - 1)
            bucketOf[basin] = row * bucketsAcross + column
            bucketStart[bucketOf[basin] + 1]++
        }
        for (bucket in 1..buckets) bucketStart[bucket] += bucketStart[bucket - 1]
        val bucketFill = bucketStart.copyOf()
        val bucketMember = IntArray(count)
        for (basin in 0 until count) bucketMember[bucketFill[bucketOf[basin]]++] = basin

        return Basins(
            outletCell, start, member, outletKmX, outletKmY, receiverKmX, receiverKmY,
            bucketStart, bucketMember, bucketsAcross, bucketsDown
        )
    }

    // ---------------------------------------------------------------- boundary chains

    /**
     * The outer boundary of each belt component, as a closed chain of cell indices in order.
     *
     * Moore-neighbour tracing, clockwise, entered from the west at the component's first cell in
     * row-major order — which is its topmost and then leftmost cell, and so lies on its outer
     * boundary with no belt north or west of it. The chain closes when the trace returns to that
     * cell. **Only the outer boundary**: ground enclosed inside a belt has a front of its own and
     * this finder does not look at it, because the question being asked is about coasts.
     */
    private fun boundaryChains(
        belt: BooleanArray,
        component: IntArray,
        componentCount: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): List<IntArray> {
        if (componentCount == 0) return emptyList()
        val first = IntArray(componentCount) { -1 }
        for (cell in belt.indices) {
            val id = component[cell]
            if (id >= 0 && first[id] < 0) first[id] = cell
        }

        // West, then clockwise through north: the order Moore tracing scans its neighbours in.
        val stepColumn = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
        val stepRow = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

        fun beltAt(column: Int, row: Int): Boolean {
            if (column < 0 || column >= cellsAcross || row < 0 || row >= cellsDown) return false
            return belt[row * cellsAcross + column]
        }

        val chains = ArrayList<IntArray>()
        first.forEach { start ->
            if (start < 0) return@forEach
            val startColumn = start % cellsAcross
            val startRow = start / cellsAcross
            val chain = ArrayList<Int>()
            var column = startColumn
            var row = startRow
            var backtrack = 0
            // A contour cannot be longer than four passes over the grid; the bound is there so a
            // pathological shape ends the trace rather than the run.
            val limit = 4 * belt.size
            var steps = 0
            while (steps < limit) {
                chain.add(row * cellsAcross + column)
                var direction = -1
                for (turn in 1..8) {
                    val candidate = (backtrack + turn) % 8
                    if (beltAt(column + stepColumn[candidate], row + stepRow[candidate])) {
                        direction = candidate
                        break
                    }
                }
                if (direction < 0) break
                column += stepColumn[direction]
                row += stepRow[direction]
                backtrack = (direction + 4) % 8
                steps++
                if (column == startColumn && row == startRow) break
            }
            if (chain.size >= 3) chains.add(chain.toIntArray())
        }
        return chains
    }

    // ---------------------------------------------------------------- straight runs

    private class Run(
        val startKmX: Double,
        val startKmY: Double,
        val endKmX: Double,
        val endKmY: Double,
        val lengthKm: Double
    )

    /**
     * The run of [span] + 1 points from [from] as a line: the least-squares line through them, cut
     * where the first and last point project onto it.
     */
    private fun fittedRun(kmX: DoubleArray, kmY: DoubleArray, from: Int, span: Int): Run {
        val points = kmX.size
        var meanX = 0.0
        var meanY = 0.0
        for (step in 0..span) {
            meanX += kmX[(from + step) % points]
            meanY += kmY[(from + step) % points]
        }
        meanX /= span + 1
        meanY /= span + 1
        var spreadXX = 0.0
        var spreadYY = 0.0
        var spreadXY = 0.0
        for (step in 0..span) {
            val offX = kmX[(from + step) % points] - meanX
            val offY = kmY[(from + step) % points] - meanY
            spreadXX += offX * offX
            spreadYY += offY * offY
            spreadXY += offX * offY
        }
        // The principal axis of the points, turned to run the way the chain does.
        val angle = 0.5 * atan2(2 * spreadXY, spreadXX - spreadYY)
        var alongX = kotlin.math.cos(angle)
        var alongY = kotlin.math.sin(angle)
        val first = from % points
        val last = (from + span) % points
        if ((kmX[last] - kmX[first]) * alongX + (kmY[last] - kmY[first]) * alongY < 0) {
            alongX = -alongX
            alongY = -alongY
        }
        val startAlong = (kmX[first] - meanX) * alongX + (kmY[first] - meanY) * alongY
        val endAlong = (kmX[last] - meanX) * alongX + (kmY[last] - meanY) * alongY
        return Run(
            meanX + alongX * startAlong, meanY + alongY * startAlong,
            meanX + alongX * endAlong, meanY + alongY * endAlong,
            endAlong - startAlong
        )
    }

    /**
     * The straight runs of one closed boundary chain.
     *
     * A run stays straight while every point of it lies within [FRONT_STRAIGHTNESS_KM] of the chord
     * between its first and last point. The chain is walked forward, each run extended as far as it
     * will go and the next started where it stopped, with indices taken modulo the chain's length
     * so a run may close round the end.
     *
     * Where a chain is entered decides where its runs are cut, so the walk is made from four evenly
     * spaced entries and the one whose qualifying runs score the most is kept, the score being the
     * sum of their squared lengths: a long front cut in two by the accident of where the trace
     * began scores half what it would whole, where a plain sum of lengths would score the two
     * halves the same as the front. Four entries rather than more because the cost is linear in it
     * and the cut only ever moves one run's worth.
     */
    private fun straightRunsOf(
        chain: IntArray,
        cellsAcross: Int,
        gridRows: Int,
        cellWidthKm: Double,
        cellHeightKm: Double,
        offsetKmX: Double,
        offsetKmY: Double
    ): List<Run> {
        val points = chain.size
        if (points < 3) return emptyList()
        val kmX = DoubleArray(points) { (chain[it] % cellsAcross) * cellWidthKm + offsetKmX }
        val kmY = DoubleArray(points) { (chain[it] / cellsAcross) * cellHeightKm + offsetKmY }
        val onTheGridEdge = BooleanArray(points) {
            val column = chain[it] % cellsAcross
            val row = chain[it] / cellsAcross
            column == 0 || column == cellsAcross - 1 || row == 0 || row == gridRows - 1
        }

        var best: List<Run> = emptyList()
        var bestLength = -1.0
        for (entry in 0 until ENTRY_POINTS_PER_CHAIN) {
            val runs = walk(kmX, kmY, onTheGridEdge, points * entry / ENTRY_POINTS_PER_CHAIN)
            val total = runs.filter { it.lengthKm >= SHORTEST_FRONT_KM }
                .sumOf { it.lengthKm * it.lengthKm }
            if (total > bestLength) {
                bestLength = total
                best = runs
            }
        }
        return best
    }

    /**
     * One forward walk of a closed chain from [entry], cutting it into maximal straight runs, none
     * of which reaches a point [onTheGridEdge] marks.
     */
    private fun walk(
        kmX: DoubleArray,
        kmY: DoubleArray,
        onTheGridEdge: BooleanArray,
        entry: Int
    ): List<Run> {
        val points = kmX.size
        val runs = ArrayList<Run>()
        var covered = 0
        var from = entry
        while (covered < points) {
            if (onTheGridEdge[from % points]) {
                covered++
                from++
                continue
            }
            var span = 1
            while (covered + span < points &&
                !onTheGridEdge[(from + span + 1) % points] &&
                straight(kmX, kmY, from, span + 1) &&
                straightAboutTheFit(kmX, kmY, from, span + 1)
            ) {
                span++
            }
            runs.add(fittedRun(kmX, kmY, from, span))
            covered += span
            from += span
        }
        return runs
    }

    /** Whether the [span] points after [from] all lie within the straightness bar of the chord. */
    private fun straight(kmX: DoubleArray, kmY: DoubleArray, from: Int, span: Int): Boolean {
        val points = kmX.size
        val startIndex = from % points
        val endIndex = (from + span) % points
        val chordX = kmX[endIndex] - kmX[startIndex]
        val chordY = kmY[endIndex] - kmY[startIndex]
        val chord = hypot(chordX, chordY)
        if (chord <= 0.0) return false
        if (chord > LONGEST_FRONT_KM) return false
        for (step in 1 until span) {
            val index = (from + step) % points
            val offX = kmX[index] - kmX[startIndex]
            val offY = kmY[index] - kmY[startIndex]
            if (abs(offX * chordY - offY * chordX) / chord > FRONT_STRAIGHTNESS_KM) return false
        }
        return true
    }

    /** Whether the [span] + 1 points from [from] all lie within the bar of their fitted line. */
    private fun straightAboutTheFit(kmX: DoubleArray, kmY: DoubleArray, from: Int, span: Int): Boolean {
        val points = kmX.size
        val line = fittedRun(kmX, kmY, from, span)
        if (line.lengthKm <= 0.0) return false
        val alongX = (line.endKmX - line.startKmX) / line.lengthKm
        val alongY = (line.endKmY - line.startKmY) / line.lengthKm
        for (step in 0..span) {
            val index = (from + step) % points
            val offX = kmX[index] - line.startKmX
            val offY = kmY[index] - line.startKmY
            if (abs(offX * alongY - offY * alongX) > FRONT_STRAIGHTNESS_KM) return false
        }
        return true
    }

    /** Which side of a chord the belt lies on, which is the side its basins come from. */
    private fun orient(
        run: Run,
        belt: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Front {
        val chordX = run.endKmX - run.startKmX
        val chordY = run.endKmY - run.startKmY
        val length = hypot(chordX, chordY)
        val alongX = chordX / length
        val alongY = chordY / length
        var acrossX = -alongY
        var acrossY = alongX

        // Count belt ground a straightness bar either side of the chord, at a sample of points
        // along it, and call the busier side landward.
        var onPlus = 0
        var onMinus = 0
        val samples = max(8, (length / FRONT_STRAIGHTNESS_KM).roundToInt())
        for (sample in 0..samples) {
            val at = length * sample / samples
            val baseX = run.startKmX + alongX * at
            val baseY = run.startKmY + alongY * at
            for (side in intArrayOf(1, -1)) {
                val probeX = baseX + acrossX * side * FRONT_STRAIGHTNESS_KM
                val probeY = baseY + acrossY * side * FRONT_STRAIGHTNESS_KM
                val column = (probeX / cellWidthKm).roundToInt()
                val row = (probeY / cellHeightKm).roundToInt()
                if (column < 0 || column >= cellsAcross || row < 0 || row >= cellsDown) continue
                if (belt[row * cellsAcross + column]) {
                    if (side > 0) onPlus++ else onMinus++
                }
            }
        }
        if (onMinus > onPlus) {
            acrossX = -acrossX
            acrossY = -acrossY
        }
        return Front(
            run.startKmX, run.startKmY, run.endKmX, run.endKmY,
            alongX, alongY, acrossX, acrossY, length
        )
    }

    /** Whether open water lies within [COASTAL_REACH_KM] seaward of the chord along half of it. */
    private fun isCoastal(
        front: Front,
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Boolean {
        // A probe every half cell of the finer axis, across the reach, so no cell of water between
        // the chord and the reach is stepped over at any grid.
        val probeStepKm = min(cellWidthKm, cellHeightKm) / 2
        val samples = max(8, (front.lengthKm / FRONT_STRAIGHTNESS_KM).roundToInt())
        var wet = 0
        for (sample in 0..samples) {
            val at = front.lengthKm * sample / samples
            var away = 0.0
            while (away <= COASTAL_REACH_KM) {
                val kmX = front.startKmX + front.alongX * at - front.landwardX * away
                val kmY = front.startKmY + front.alongY * at - front.landwardY * away
                val column = (kmX / cellWidthKm).roundToInt()
                val row = (kmY / cellHeightKm).roundToInt()
                if (column in 0 until cellsAcross && row in 0 until cellsDown &&
                    !isLand[row * cellsAcross + column]
                ) {
                    wet++
                    break
                }
                away += probeStepKm
            }
        }
        return wet * 2 >= samples + 1
    }

    // ---------------------------------------------------------------- one front

    /** The catchments and trunk basins of one front. */
    private fun measureFront(
        front: Front,
        basins: Basins,
        isLand: BooleanArray,
        nearestBoundaryClass: IntArray?,
        tracedCourse: BooleanArray?,
        keepBasinCells: Boolean,
        scales: InstrumentScales,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Measured {
        // The routing wraps east to west, so a basin can reach across the seam from a front that
        // does not: every position is read as the copy of itself nearest the front's midpoint.
        val worldWidthKm = cellsAcross * cellWidthKm
        fun nearFront(kmX: Double): Double {
            var offset = kmX - front.midKmX
            if (offset > worldWidthKm / 2) offset -= worldWidthKm
            if (offset < -worldWidthKm / 2) offset += worldWidthKm
            return front.midKmX + offset
        }
        fun kmXOf(cell: Int) = nearFront((cell % cellsAcross) * cellWidthKm)
        fun kmYOf(cell: Int) = (cell / cellsAcross) * cellHeightKm

        val coastal = isCoastal(front, isLand, cellsAcross, cellsDown, cellWidthKm, cellHeightKm)

        // Only the buckets the front's own band can reach, which is what keeps the cost of a front
        // to its own neighbourhood rather than to the whole world's outlets.
        val lowKmX = min(front.startKmX, front.endKmX) - FRONT_STRAIGHTNESS_KM
        val highKmX = max(front.startKmX, front.endKmX) + FRONT_STRAIGHTNESS_KM
        val lowKmY = min(front.startKmY, front.endKmY) - FRONT_STRAIGHTNESS_KM
        val highKmY = max(front.startKmY, front.endKmY) + FRONT_STRAIGHTNESS_KM
        val firstBucketColumn = (lowKmX / OUTLET_BUCKET_KM).toInt().coerceAtLeast(0)
        val lastBucketColumn = (highKmX / OUTLET_BUCKET_KM).toInt()
            .coerceAtMost(basins.bucketsAcross - 1)
        val firstBucketRow = (lowKmY / OUTLET_BUCKET_KM).toInt().coerceAtLeast(0)
        val lastBucketRow = (highKmY / OUTLET_BUCKET_KM).toInt().coerceAtMost(basins.bucketsDown - 1)

        val onFront = ArrayList<Int>()
        for (bucketRow in firstBucketRow..lastBucketRow) {
            for (bucketColumn in firstBucketColumn..lastBucketColumn) {
                val bucket = bucketRow * basins.bucketsAcross + bucketColumn
                for (index in basins.bucketStart[bucket] until basins.bucketStart[bucket + 1]) {
                    val basin = basins.bucketMember[index]
                    val kmX = nearFront(basins.outletKmX[basin])
                    val kmY = basins.outletKmY[basin]
                    val along = front.alongAt(kmX, kmY)
                    if (along < 0.0 || along > front.lengthKm) continue
                    val landward = front.landwardAt(kmX, kmY)
                    if (abs(landward) > FRONT_STRAIGHTNESS_KM) continue
                    // A course leaving into a hollow behind the band is not leaving by this front.
                    val receiver = front.landwardAt(
                        nearFront(basins.receiverKmX[basin]), basins.receiverKmY[basin]
                    )
                    if (receiver > FRONT_STRAIGHTNESS_KM) continue
                    onFront.add(basin)
                }
            }
        }
        val tracedAlong = tracedCourse?.let { traced ->
            onFront.filter { traced[basins.outletCell[it]] }
                .map { front.alongAt(nearFront(basins.outletKmX[it]), basins.outletKmY[it]) }
                .sorted()
        }
        if (onFront.isEmpty()) return Measured(front, coastal, emptyList(), 0, tracedAlong, null)

        // The main divide: how far landward the region draining out through this front reaches,
        // strip by strip along it. Strips are indexed from the region's own westmost `s`, which
        // may lie before the chord's start where a basin's head fans out past the front's end.
        var lowestAlong = Double.MAX_VALUE
        var highestAlong = -Double.MAX_VALUE
        onFront.forEach { basin ->
            for (index in basins.start[basin] until basins.start[basin + 1]) {
                val cell = basins.member[index]
                val along = front.alongAt(kmXOf(cell), kmYOf(cell))
                if (along < lowestAlong) lowestAlong = along
                if (along > highestAlong) highestAlong = along
            }
        }
        fun stripOf(along: Double) = ((along - lowestAlong) / scales.divideStripKm).toInt()
        val strips = stripOf(highestAlong) + 1
        val divideKm = DoubleArray(strips) { -Double.MAX_VALUE }
        // Which basin first put a cell in each strip, and whether a second one did too.
        val firstBasinInStrip = IntArray(strips) { -1 }
        val stripShared = BooleanArray(strips)
        onFront.forEach { basin ->
            for (index in basins.start[basin] until basins.start[basin + 1]) {
                val cell = basins.member[index]
                val strip = stripOf(front.alongAt(kmXOf(cell), kmYOf(cell)))
                val landward = front.landwardAt(kmXOf(cell), kmYOf(cell))
                if (landward > divideKm[strip]) divideKm[strip] = landward
                if (firstBasinInStrip[strip] < 0) firstBasinInStrip[strip] = basin
                else if (firstBasinInStrip[strip] != basin) stripShared[strip] = true
            }
        }

        val areaKm2PerCell = cellWidthKm * cellHeightKm
        val catchments = ArrayList<Outlet>()
        var corners = 0
        val classVotes = IntArray(BoundaryClass.entries.size)
        onFront.forEach { basin ->
            val from = basins.start[basin]
            val to = basins.start[basin + 1]
            if ((to - from) * areaKm2PerCell < scales.catchmentFloorKm2) {
                corners++
                return@forEach
            }
            var headLandwardKm = -Double.MAX_VALUE
            var headAlongKm = 0.0
            for (index in from until to) {
                val cell = basins.member[index]
                val landward = front.landwardAt(kmXOf(cell), kmYOf(cell))
                if (landward > headLandwardKm) {
                    headLandwardKm = landward
                    headAlongKm = front.alongAt(kmXOf(cell), kmYOf(cell))
                }
            }
            val headStrip = stripOf(headAlongKm)
            val reachesTheDivide = headLandwardKm >= divideKm[headStrip] - scales.divideStripKm
            val outlet = basins.outletCell[basin]
            nearestBoundaryClass?.let { classes ->
                val ordinal = classes[outlet]
                if (ordinal >= 0) classVotes[ordinal]++
            }
            catchments.add(
                Outlet(
                    outletCell = outlet,
                    alongFrontKm = front.alongAt(kmXOf(outlet), kmYOf(outlet)),
                    areaKm2 = (to - from) * areaKm2PerCell,
                    divideToFrontKm = headLandwardKm,
                    isTrunk = reachesTheDivide,
                    aloneInItsStrip = !stripShared[headStrip],
                    cells = if (keepBasinCells) basins.member.copyOfRange(from, to) else null
                )
            )
        }
        catchments.sortBy { it.alongFrontKm }

        val modalClass = if (nearestBoundaryClass == null || classVotes.all { it == 0 }) null
        else BoundaryClass.entries[classVotes.indices.maxBy { classVotes[it] }]
        return Measured(front, coastal, catchments, corners, tracedAlong, modalClass)
    }

    // ---------------------------------------------------------------- cross-resolution matching

    /** One front on one grid paired with the same front on another. */
    class Match(val coarse: Measured, val fine: Measured, val apartKm: Double)

    /**
     * Pairs the fronts of two reports of one seed that both carry the figure being compared,
     * nearest midpoint first.
     *
     * [usable] is the filter, applied before any pairing so a front with nothing to compare cannot
     * take a partner that has something. A pair must then pass [sameFront]. Each front is used at
     * most once; what is left over is the unmatched count the caller prints beside the pairs.
     */
    fun match(coarse: Report, fine: Report, usable: (Measured) -> Boolean): List<Match> {
        val candidates = ArrayList<Triple<Double, Measured, Measured>>()
        coarse.measured.filter(usable).forEach { a ->
            fine.measured.filter(usable).forEach { b ->
                val apart = hypot(a.front.midKmX - b.front.midKmX, a.front.midKmY - b.front.midKmY)
                if (apart <= FRONT_MATCH_KM && sameFront(a.front, b.front)) {
                    candidates.add(Triple(apart, a, b))
                }
            }
        }
        candidates.sortBy { it.first }
        val usedCoarse = HashSet<Measured>()
        val usedFine = HashSet<Measured>()
        val matches = ArrayList<Match>()
        candidates.forEach { (apart, a, b) ->
            if (a in usedCoarse || b in usedFine) return@forEach
            usedCoarse.add(a)
            usedFine.add(b)
            matches.add(Match(a, b, apart))
        }
        return matches
    }

    /**
     * Whether two fronts are one stretch of outline seen at two grids: lines within
     * [FRONT_MATCH_DEGREES], landward sides the same way, and an overlap along the line of at least
     * [SHORTEST_OVERLAP_SHARE] of the shorter front. The midpoint distance is the caller's.
     */
    fun sameFront(a: Front, b: Front): Boolean {
        val dot = abs(a.alongX * b.alongX + a.alongY * b.alongY).coerceIn(0.0, 1.0)
        if (Math.toDegrees(acos(dot)) > FRONT_MATCH_DEGREES) return false
        if (a.landwardX * b.landwardX + a.landwardY * b.landwardY <= 0.0) return false
        val bStart = a.alongAt(b.startKmX, b.startKmY)
        val bEnd = a.alongAt(b.endKmX, b.endKmY)
        val overlap = min(a.lengthKm, max(bStart, bEnd)) - max(0.0, min(bStart, bEnd))
        return overlap >= SHORTEST_OVERLAP_SHARE * min(a.lengthKm, b.lengthKm)
    }

    /** The relief wavelength this world was built with, in kilometres: `ReliefBand`'s corner. */
    fun reliefWavelengthKm(config: WorldGenConfig): Double = config.terrain.reliefCornerKm

    /**
     * The stamped half-width of the belt kind a front sits on, in kilometres across that front.
     *
     * `TectonicsConfig.andeanWidthCells` for an Andean margin and `collisionWidthCells` for a
     * plateau, each converted along the front's own normal by [Front.kilometresOfCellsAcross]; null
     * for any other kind or where the kind is unknown. Nominal: the stamp swells and pinches this
     * by `PlateStage`'s along-strike width noise.
     */
    fun stampedHalfWidthKm(config: WorldGenConfig, measured: Measured): Double? {
        val cells = when (measured.boundaryClass) {
            BoundaryClass.ANDEAN_MARGIN -> config.tectonics.andeanWidthCells.toDouble()
            BoundaryClass.COLLISION_PLATEAU -> config.tectonics.collisionWidthCells.toDouble()
            else -> return null
        }
        return measured.front.kilometresOfCellsAcross(cells, config.cellWidthKm, config.cellHeightKm)
    }

    /** A figure for a table, or a dash where there is no sample. */
    fun show(value: Double?, decimals: Int = 1): String =
        if (value == null) "-" else "%.${decimals}f".format(value)
}
