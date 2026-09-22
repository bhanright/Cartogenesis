package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Straight mountain fronts, the trunk basins that reach them, and how far apart their outlets are.
 *
 * The instrument the coastal-valley question needs. The author's report is that the coasts carry
 * closely spaced valleys perpendicular to the shore about ten cells apart, and ten cells is a
 * measure of the grid, not of the landscape. To find out which of the two sets the spacing, it has
 * to be measured in kilometres, on fronts chosen without ever looking at a drawn river, and then
 * re-measured on the same seed built at another grid and with the two settings that could be
 * imposing it moved.
 *
 * Hovius (1996, *Regular spacing of drainage outlets from linear mountain belts*, Basin Research 8,
 * 29-44) measured the same two lengths on Earth's linear belts and found their ratio — basin length
 * over outlet spacing — close to 2.1 across belts of very different size, climate and rock. That is
 * the figure the numbers here are printed beside, and it is a ratio of trunk basins only.
 *
 * **Nothing here reads `RiverResult.rivers`.** The drawn courses are a cartographic selection (R1,
 * and X1c's chunk), so a spacing measured off them would be measuring the pen. What the finder
 * reads is the height field, the land mask and `RiverResult.flowTarget`, which is the routing's own
 * downhill tree and is what a divide is made of.
 *
 * ## The definitions, all of them, before a number is collected
 *
 * **Kilometre space.** A cell at column `c`, row `r` has its centre at
 * `(c * cellWidthKm, r * cellHeightKm)`. On this project's equirectangular grid a cell is twice as
 * wide as it is tall, so every length below is a kilometre length and never a cell count; where a
 * cell count is printed it is printed *beside* the kilometres and labelled, because the whole
 * question is which of the two stays constant as the grid changes.
 *
 * **Belt.** A land cell standing at or above [BELT_FLOOR_METRES] is belt ground, and belt cells are
 * grouped into components by 8-connectivity. The finder does **not** wrap east to west: a belt
 * crossing the seam is traced as two, and [Census.beltsOnTheSeam] counts how many components touch
 * the seam so a reader can see whether it mattered.
 *
 * **Front eligibility.** Each component's outer boundary is traced as a closed chain of cell
 * centres. Along that chain a *front* is a maximal run of points every one of which lies within
 * [FRONT_STRAIGHTNESS_KM] of the straight chord between the run's first and last point, and it
 * qualifies when that chord is at least [SHORTEST_FRONT_KM] long. A front's *landward* side is
 * whichever side of its chord carries more belt ground within a straightness bar of it, and the
 * perpendicular coordinate `t` below is measured positive in that direction.
 *
 * **Exit.** Following `flowTarget` downstream from a belt cell, the *exit* is the last belt cell
 * before the path leaves belt ground. A path that ends inside the belt — a cell whose target is -1,
 * or a route that never leaves — has no exit, and its cells belong to no basin. Every belt cell
 * sharing an exit is that exit's *basin*.
 *
 * **Outlet.** An exit is an outlet of a given front when its projection `s` onto the chord lies
 * inside the chord, its perpendicular distance `|t|` is at most [FRONT_STRAIGHTNESS_KM] — the line
 * has the width its own straightness bar gives it — and the cell it drains into lies seaward of it,
 * so a course crossing the line inward is not counted as leaving by it.
 *
 * **Trunk and divide qualification.** A front's basins tile a region `R` of belt ground. A basin is
 * a **trunk basin** when both of these hold. It touches the *landward rim* of `R`: it holds a cell
 * with an 8-neighbour outside `R` that is itself more than a straightness bar from the chord, so a
 * gully whose head is enclosed by its larger neighbours is not a trunk. And it covers at least
 * [SMALLEST_TRUNK_BASIN_KM2]. Its **divide-to-front distance** is the largest `t` over its own
 * cells, which is Hovius's basin length measured normal to the front.
 *
 * **Along-front spacing.** The outlets of a front's trunk basins are sorted by `s`, and the
 * spacings are the differences between neighbours, in kilometres. A front with fewer than two trunk
 * outlets contributes no spacing at all.
 *
 * **Cross-resolution matching.** Two fronts found on the same seed at different grids are the same
 * front when their chord midpoints lie within [FRONT_MATCH_KM] and their bearings agree within
 * [FRONT_MATCH_DEGREES] as undirected lines, so a chord traced the other way round still matches.
 * Each front matches at most one front on the other grid, nearest midpoint first; fronts left over
 * are reported as unmatched rather than quietly dropped.
 *
 * **The empty sample.** Nothing here reports a zero where it means "no sample". A seed and grid
 * that yields no belt, no eligible front or no front with two trunk outlets reports its [Census] —
 * what survived each filter in turn — and contributes nothing to any pooled figure. A pooled figure
 * is a median over fronts, each front counted once, and the number of fronts behind it is always
 * printed with it.
 */
internal object RangeFront {

    /**
     * Ground at or above this altitude is belt ground, in metres above the shoreline.
     *
     * A round kilometre, above the 840 m a standard continental column floats at under this
     * generator's own isostasy (docs/GEOGRAPHY.md, "The crust floats"), so belt ground is ground
     * standing above the continent it sits on rather than ground merely standing above the sea.
     * The measurement does not hinge on the figure: `CoastalSpacingAuditTest` re-takes a seed at
     * 700 m and at 1,500 m and prints the three side by side.
     */
    const val BELT_FLOOR_METRES = 1_000f

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
     * is `TectonicsConfig.andeanWidthCells` = 14 cells of half-width on the 512 reference grid,
     * which is 328 km; at a ratio of 2.1 a belt that deep spaces its outlets about 156 km apart.
     * Three outlets is the fewest that makes a spacing more than a single number, so the shortest
     * useful front is two of those spacings, 313 km, rounded down to 300.
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
     * The smallest catchment that counts as a trunk basin, in square kilometres.
     *
     * Nine cells of the coarsest grid measured here: one 512 cell is 23.4 by 11.7 km, 274 km2, and
     * a catchment of fewer than nine of them is a corner of the grid rather than a basin. The
     * figure is in square kilometres and not in cells on purpose, so the same ground qualifies at
     * 512, at 1024 (36 cells) and at 2048 (146 cells) and the trunk count is not a function of the
     * grid.
     */
    const val SMALLEST_TRUNK_BASIN_KM2 = 2_500.0

    /** Two fronts this far apart at the midpoint may still be one front: half the shortest one. */
    const val FRONT_MATCH_KM = SHORTEST_FRONT_KM / 2

    /**
     * How far two fronts' bearings may differ and still be the same front, in degrees.
     *
     * Thirty, a little wider than the orientation buckets below are deep, so a front that would
     * fall in a different bucket at the other grid is not matched to it: the buckets exist because
     * a front's bearing is one of the things that might be setting its spacing, and a match across
     * them would hide exactly that.
     */
    const val FRONT_MATCH_DEGREES = 30.0

    /** Hovius (1996): basin length over outlet spacing on Earth's linear mountain belts. */
    const val HOVIUS_RATIO = 2.1

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
    }

    /** One trunk basin of one front. */
    class Trunk(
        val outletCell: Int,
        /** Where the outlet sits along the front's chord, in kilometres from its start. */
        val alongFrontKm: Double,
        val areaKm2: Double,
        /** The largest perpendicular distance from the chord any cell of the basin reaches, in km. */
        val divideToFrontKm: Double
    )

    /** One front, and whatever survived the trunk filter on it. */
    class Measured(
        val front: Front,
        val trunks: List<Trunk>,
        /** Differences between neighbouring *trunk* outlets along the chord, in kilometres. */
        val spacingsKm: List<Double>,
        /**
         * Differences between neighbouring outlets of *every* basin leaving by this front.
         *
         * Hovius's ratio is over trunk basins, so the trunk filter is what the spacing above is
         * for. But the author's report is of what the map shows, and the map draws a course down
         * every gully that carries one: this is the comb the eye sees, and it is the figure the
         * "ten cells" is to be read against.
         */
        val everyOutletSpacingKm: List<Double>
    ) {
        /** Outlets that reached the front before the trunk filter, for the census. */
        val outletsAtFront: Int get() = everyOutletSpacingKm.size + 1

        val medianEveryOutletSpacingKm: Double? get() = median(everyOutletSpacingKm)

        val medianSpacingKm: Double? get() = median(spacingsKm)
        val medianDivideToFrontKm: Double? get() = median(trunks.map { it.divideToFrontKm })

        /** Hovius's ratio for this front: basin length over outlet spacing. */
        val hoviusRatio: Double?
            get() {
                val spacing = medianSpacingKm ?: return null
                val length = medianDivideToFrontKm ?: return null
                return if (spacing <= 0.0) null else length / spacing
            }
    }

    /** What survived each filter, so an empty sample can say which filter emptied it. */
    class Census(
        val beltCells: Int,
        val beltComponents: Int,
        val beltsOnTheSeam: Int,
        val boundaryChains: Int,
        val straightRuns: Int,
        val fronts: Int,
        val frontsWithTwoTrunks: Int
    ) {
        override fun toString(): String =
            "belt cells $beltCells in $beltComponents components ($beltsOnTheSeam touching the " +
                "seam), $boundaryChains outer boundaries, $straightRuns straight runs, $fronts " +
                "over ${SHORTEST_FRONT_KM.toInt()} km, $frontsWithTwoTrunks with two trunk outlets"
    }

    /** Everything one world yields. */
    class Report(
        val seed: Long,
        val cellsAcross: Int,
        val cellWidthKm: Double,
        val cellHeightKm: Double,
        val measured: List<Measured>,
        val census: Census
    ) {
        /** The fronts that carry a spacing: the sample every pooled figure below is over. */
        val sample: List<Measured> get() = measured.filter { it.medianSpacingKm != null }

        val pooledSpacingKm: Double? get() = median(sample.mapNotNull { it.medianSpacingKm })

        val pooledDivideToFrontKm: Double?
            get() = median(sample.mapNotNull { it.medianDivideToFrontKm })

        val pooledHoviusRatio: Double? get() = median(sample.mapNotNull { it.hoviusRatio })

        /** The pooled spacing counted in grid cells along each front, which is the author's unit. */
        val pooledSpacingCells: Double? get() = median(sample.mapNotNull { spacingInCells(it) })

        /** The same over every outlet rather than the trunks: the comb the map draws. */
        val pooledEveryOutletSpacingKm: Double?
            get() = median(measured.mapNotNull { it.medianEveryOutletSpacingKm })

        val pooledEveryOutletSpacingCells: Double?
            get() = median(measured.mapNotNull { everyOutletSpacingInCells(it) })

        fun spacingInCells(measured: Measured): Double? = measured.medianSpacingKm?.let {
            measured.front.cellsAlong(it, cellWidthKm, cellHeightKm)
        }

        fun everyOutletSpacingInCells(measured: Measured): Double? =
            measured.medianEveryOutletSpacingKm?.let {
                measured.front.cellsAlong(it, cellWidthKm, cellHeightKm)
            }

        fun sampleFor(bearing: Bearing): List<Measured> = sample.filter { it.front.bearing == bearing }

        /**
         * Every gap between neighbouring outlets on every front, rather than one median per front.
         *
         * A per-front median weights a front carrying three outlets the same as one carrying forty,
         * which is right for a pooled figure over worlds and wrong when the whole sample is one or
         * two fronts — a 512 world yields that on most seeds. So both are reported: the median over
         * fronts, and the median over gaps with the number of gaps beside it.
         *
         * [trunksOnly] takes the gaps between trunk basins, which is Hovius's sample; false takes
         * the gaps between every outlet, which is the comb the map draws.
         */
        fun gapsKm(trunksOnly: Boolean, bearing: Bearing? = null): List<Double> = measured
            .filter { bearing == null || it.front.bearing == bearing }
            .flatMap { if (trunksOnly) it.spacingsKm else it.everyOutletSpacingKm }

        /** The same gaps, each counted in the grid cells its own front's bearing makes of it. */
        fun gapsInCells(trunksOnly: Boolean, bearing: Bearing? = null): List<Double> = measured
            .filter { bearing == null || it.front.bearing == bearing }
            .flatMap { front ->
                (if (trunksOnly) front.spacingsKm else front.everyOutletSpacingKm)
                    .map { front.front.cellsAlong(it, cellWidthKm, cellHeightKm) }
            }

        /** How many eligible fronts run each way, whether or not they carry a spacing. */
        fun frontsFacing(bearing: Bearing): Int = measured.count { it.front.bearing == bearing }

        fun pooledSpacingKmFor(bearing: Bearing): Double? =
            median(sampleFor(bearing).mapNotNull { it.medianSpacingKm })

        fun pooledSpacingCellsFor(bearing: Bearing): Double? =
            median(sampleFor(bearing).mapNotNull { spacingInCells(it) })

        /** One line of a table of seeds and grids. */
        fun line(label: String): String {
            val spacing = pooledSpacingKm
                ?: return "$label: no sample - ${census}"
            return ("%s: %d fronts, trunk spacing %.1f km (%.2f cells along the front), " +
                "every-outlet spacing %s km (%s cells), divide-to-front %.1f km, " +
                "Hovius %.2f against %.1f")
                .format(
                    label, sample.size, spacing, pooledSpacingCells ?: Double.NaN,
                    show(pooledEveryOutletSpacingKm), show(pooledEveryOutletSpacingCells),
                    pooledDivideToFrontKm ?: Double.NaN, pooledHoviusRatio ?: Double.NaN,
                    HOVIUS_RATIO
                )
        }

        /** The same figures split by which way the front runs, which is the grid's own question. */
        fun bearingLine(label: String): String = Bearing.entries.joinToString(
            prefix = "$label by bearing: ", separator = "; "
        ) { bearing ->
            val fronts = sampleFor(bearing).size
            if (fronts == 0) "$bearing no sample"
            else "%s %d fronts, %.1f km, %.2f cells".format(
                bearing, fronts, pooledSpacingKmFor(bearing) ?: Double.NaN,
                pooledSpacingCellsFor(bearing) ?: Double.NaN
            )
        }
    }

    /** The median of a list, or null where the list is empty: an empty sample is not a zero. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** Runs the whole finder over a finished world. */
    fun measure(world: WorldMap, beltFloorMetres: Float = BELT_FLOOR_METRES): Report = measure(
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
        beltFloorMetres = beltFloorMetres
    )

    /**
     * The finder over plain arrays, so a synthetic fixture can drive it without a world.
     *
     * [isLand] and [metresAboveShoreline] are row-major, one entry per cell; [flowTarget] is the
     * routing's downhill target per cell, -1 where the water leaves the world. Returns one [Report],
     * whose pooled figures are null rather than zero where there is no sample.
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
        beltFloorMetres: Float = BELT_FLOOR_METRES
    ): Report {
        val cellCount = cellsAcross * cellsDown
        val belt = BooleanArray(cellCount) {
            isLand[it] && metresAboveShoreline[it] >= beltFloorMetres
        }
        val beltCells = belt.count { it }

        val component = componentsOf(belt, cellsAcross, cellsDown)
        var componentCount = 0
        component.forEach { if (it + 1 > componentCount) componentCount = it + 1 }
        val seamComponents = HashSet<Int>()
        for (row in 0 until cellsDown) {
            val west = component[row * cellsAcross]
            val east = component[row * cellsAcross + cellsAcross - 1]
            if (west >= 0) seamComponents.add(west)
            if (east >= 0) seamComponents.add(east)
        }

        val exit = exitsOf(belt, flowTarget, cellCount)
        val basins = basinsOf(exit, flowTarget, cellsAcross, cellsDown, cellWidthKm, cellHeightKm)

        val chains = boundaryChains(belt, component, componentCount, cellsAcross, cellsDown)
        var straightRuns = 0
        val fronts = ArrayList<Front>()
        chains.forEach { chain ->
            val runs = straightRunsOf(chain, cellsAcross, cellWidthKm, cellHeightKm)
            straightRuns += runs.size
            runs.forEach { run ->
                if (run.lengthKm >= SHORTEST_FRONT_KM) {
                    fronts.add(orient(run, belt, cellsAcross, cellsDown, cellWidthKm, cellHeightKm))
                }
            }
        }

        val stamp = IntArray(cellCount)
        val measured = fronts.mapIndexed { index, front ->
            measureFront(
                front, basins, stamp, index + 1,
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
                beltCells = beltCells,
                beltComponents = componentCount,
                beltsOnTheSeam = seamComponents.size,
                boundaryChains = chains.size,
                straightRuns = straightRuns,
                fronts = fronts.size,
                frontsWithTwoTrunks = measured.count { it.spacingsKm.isNotEmpty() }
            )
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
        val member: IntArray
    ) {
        val count: Int get() = outletCell.size

        /** Each outlet's position in kilometre space, so a front need not divide by the grid. */
        lateinit var outletKmX: DoubleArray
        lateinit var outletKmY: DoubleArray

        /** Where each outlet's own receiver stands, which says whether the step is seaward. */
        lateinit var receiverKmX: DoubleArray
        lateinit var receiverKmY: DoubleArray

        /**
         * Outlets bucketed by a square of ground, so a front reads the outlets near it and no more.
         *
         * A front's band is at most its own length by two straightness bars, and the world holds
         * hundreds of thousands of outlets at 2048: scanning them all once per front is what made
         * the first run of this measurement take longer than the twenty-one worlds it was over.
         */
        lateinit var bucketStart: IntArray
        lateinit var bucketMember: IntArray
        var bucketsAcross: Int = 0
        var bucketsDown: Int = 0
    }

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
        val basins = Basins(outlets.toIntArray(), start, member)

        basins.outletKmX = DoubleArray(count) { (basins.outletCell[it] % cellsAcross) * cellWidthKm }
        basins.outletKmY = DoubleArray(count) { (basins.outletCell[it] / cellsAcross) * cellHeightKm }
        basins.receiverKmX = DoubleArray(count)
        basins.receiverKmY = DoubleArray(count)
        for (basin in 0 until count) {
            val target = flowTarget[basins.outletCell[basin]]
            if (target < 0) {
                // No receiver: park it on its own outlet, which fails the seaward test below.
                basins.receiverKmX[basin] = basins.outletKmX[basin]
                basins.receiverKmY[basin] = basins.outletKmY[basin]
            } else {
                basins.receiverKmX[basin] = (target % cellsAcross) * cellWidthKm
                basins.receiverKmY[basin] = (target / cellsAcross) * cellHeightKm
            }
        }

        basins.bucketsAcross = max(1, (cellsAcross * cellWidthKm / OUTLET_BUCKET_KM).toInt() + 1)
        basins.bucketsDown = max(1, (cellsDown * cellHeightKm / OUTLET_BUCKET_KM).toInt() + 1)
        val buckets = basins.bucketsAcross * basins.bucketsDown
        val bucketStart = IntArray(buckets + 1)
        val bucketOf = IntArray(count)
        for (basin in 0 until count) {
            val column = (basins.outletKmX[basin] / OUTLET_BUCKET_KM).toInt()
                .coerceIn(0, basins.bucketsAcross - 1)
            val row = (basins.outletKmY[basin] / OUTLET_BUCKET_KM).toInt()
                .coerceIn(0, basins.bucketsDown - 1)
            bucketOf[basin] = row * basins.bucketsAcross + column
            bucketStart[bucketOf[basin] + 1]++
        }
        for (bucket in 1..buckets) bucketStart[bucket] += bucketStart[bucket - 1]
        val bucketFill = bucketStart.copyOf()
        val bucketMember = IntArray(count)
        for (basin in 0 until count) bucketMember[bucketFill[bucketOf[basin]]++] = basin
        basins.bucketStart = bucketStart
        basins.bucketMember = bucketMember
        return basins
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
     * The straight runs of one closed boundary chain.
     *
     * A run stays straight while every point of it lies within [FRONT_STRAIGHTNESS_KM] of the chord
     * between its first and last point. The chain is walked forward, each run extended as far as it
     * will go and the next started where it stopped, with indices taken modulo the chain's length
     * so a run may close round the end.
     *
     * Where a chain is entered decides where its runs are cut, so the walk is made from four evenly
     * spaced entries and the one whose qualifying runs total the most length is kept: a long front
     * is then not halved by the accident of the trace having begun in the middle of it. Four rather
     * than more because the cost is linear in it and the cut only ever moves one run's worth.
     */
    private fun straightRunsOf(
        chain: IntArray,
        cellsAcross: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): List<Run> {
        val points = chain.size
        if (points < 3) return emptyList()
        val kmX = DoubleArray(points) { (chain[it] % cellsAcross) * cellWidthKm }
        val kmY = DoubleArray(points) { (chain[it] / cellsAcross) * cellHeightKm }

        var best: List<Run> = emptyList()
        var bestLength = -1.0
        for (entry in 0 until ENTRY_POINTS_PER_CHAIN) {
            val runs = walk(kmX, kmY, points * entry / ENTRY_POINTS_PER_CHAIN)
            val total = runs.filter { it.lengthKm >= SHORTEST_FRONT_KM }.sumOf { it.lengthKm }
            if (total > bestLength) {
                bestLength = total
                best = runs
            }
        }
        return best
    }

    /** One forward walk of a closed chain from [entry], cutting it into maximal straight runs. */
    private fun walk(kmX: DoubleArray, kmY: DoubleArray, entry: Int): List<Run> {
        val points = kmX.size
        val runs = ArrayList<Run>()
        var covered = 0
        var from = entry
        while (covered < points) {
            var span = 1
            while (covered + span < points && straight(kmX, kmY, from, span + 1)) span++
            val startIndex = from % points
            val endIndex = (from + span) % points
            runs.add(
                Run(
                    kmX[startIndex], kmY[startIndex], kmX[endIndex], kmY[endIndex],
                    hypot(kmX[endIndex] - kmX[startIndex], kmY[endIndex] - kmY[startIndex])
                )
            )
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

    // ---------------------------------------------------------------- one front

    /**
     * The trunk basins of one front and the spacing of their outlets.
     *
     * [stamp] is one array reused by every front, marked with this front's own [token] instead of
     * being cleared, so the cost of a front is its own basins rather than the whole grid.
     */
    private fun measureFront(
        front: Front,
        basins: Basins,
        stamp: IntArray,
        token: Int,
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Measured {
        fun kmXOf(cell: Int) = (cell % cellsAcross) * cellWidthKm
        fun kmYOf(cell: Int) = (cell / cellsAcross) * cellHeightKm

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
        val alongOfOutlet = ArrayList<Double>()
        for (bucketRow in firstBucketRow..lastBucketRow) {
            for (bucketColumn in firstBucketColumn..lastBucketColumn) {
                val bucket = bucketRow * basins.bucketsAcross + bucketColumn
                for (index in basins.bucketStart[bucket] until basins.bucketStart[bucket + 1]) {
                    val basin = basins.bucketMember[index]
                    val kmX = basins.outletKmX[basin]
                    val kmY = basins.outletKmY[basin]
                    val along = front.alongAt(kmX, kmY)
                    if (along < 0.0 || along > front.lengthKm) continue
                    val landward = front.landwardAt(kmX, kmY)
                    if (abs(landward) > FRONT_STRAIGHTNESS_KM) continue
                    // A course crossing the line inward is not leaving the belt by this front.
                    val receiver = front.landwardAt(
                        basins.receiverKmX[basin], basins.receiverKmY[basin]
                    )
                    if (receiver >= landward) continue
                    onFront.add(basin)
                    alongOfOutlet.add(along)
                }
            }
        }
        if (onFront.isEmpty()) return Measured(front, emptyList(), emptyList(), emptyList())

        // The region this front's basins tile, stamped once so the rim test can read it.
        onFront.forEach { basin ->
            for (index in basins.start[basin] until basins.start[basin + 1]) {
                stamp[basins.member[index]] = token
            }
        }

        val areaKm2PerCell = cellWidthKm * cellHeightKm
        val trunks = ArrayList<Trunk>()
        onFront.forEach { basin ->
            val from = basins.start[basin]
            val to = basins.start[basin + 1]
            val areaKm2 = (to - from) * areaKm2PerCell
            var reach = 0.0
            var atRim = false
            for (index in from until to) {
                val cell = basins.member[index]
                val landward = front.landwardAt(kmXOf(cell), kmYOf(cell))
                if (landward > reach) reach = landward
                if (atRim) continue
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (stepRow in -1..1) {
                    for (stepColumn in -1..1) {
                        if (stepRow == 0 && stepColumn == 0) continue
                        val neighbourColumn = column + stepColumn
                        val neighbourRow = row + stepRow
                        if (neighbourColumn < 0 || neighbourColumn >= cellsAcross) continue
                        if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (stamp[neighbour] == token) continue
                        val outside = front.landwardAt(
                            neighbourColumn * cellWidthKm, neighbourRow * cellHeightKm
                        )
                        if (outside > FRONT_STRAIGHTNESS_KM) {
                            atRim = true
                            break
                        }
                    }
                    if (atRim) break
                }
            }
            if (!atRim || areaKm2 < SMALLEST_TRUNK_BASIN_KM2) return@forEach
            val outlet = basins.outletCell[basin]
            trunks.add(
                Trunk(
                    outletCell = outlet,
                    alongFrontKm = front.alongAt(kmXOf(outlet), kmYOf(outlet)),
                    areaKm2 = areaKm2,
                    divideToFrontKm = reach
                )
            )
        }
        trunks.sortBy { it.alongFrontKm }

        val spacings = ArrayList<Double>()
        for (index in 1 until trunks.size) {
            spacings.add(trunks[index].alongFrontKm - trunks[index - 1].alongFrontKm)
        }
        val everyOutlet = alongOfOutlet.sorted()
        val everySpacing = ArrayList<Double>()
        for (index in 1 until everyOutlet.size) {
            everySpacing.add(everyOutlet[index] - everyOutlet[index - 1])
        }
        return Measured(front, trunks, spacings, everySpacing)
    }

    // ---------------------------------------------------------------- cross-resolution matching

    /** One front on one grid paired with the same front on another. */
    class Match(val coarse: Measured, val fine: Measured, val apartKm: Double)

    /**
     * Pairs the fronts of two reports of one seed, nearest midpoint first.
     *
     * A pair must lie within [FRONT_MATCH_KM] and agree in bearing within [FRONT_MATCH_DEGREES] as
     * undirected lines. Each front is used at most once; what is left over is the unmatched count
     * the caller prints beside the pairs.
     */
    fun match(coarse: Report, fine: Report): List<Match> {
        val candidates = ArrayList<Triple<Double, Measured, Measured>>()
        coarse.measured.forEach { a ->
            fine.measured.forEach { b ->
                val apart = hypot(a.front.midKmX - b.front.midKmX, a.front.midKmY - b.front.midKmY)
                if (apart <= FRONT_MATCH_KM && bearingsAgree(a.front, b.front)) {
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

    private fun bearingsAgree(a: Front, b: Front): Boolean {
        val dot = abs(a.alongX * b.alongX + a.alongY * b.alongY).coerceIn(0.0, 1.0)
        return Math.toDegrees(acos(dot)) <= FRONT_MATCH_DEGREES
    }

    /** The relief wavelength this world was built with, in kilometres: `ReliefBand`'s corner. */
    fun reliefWavelengthKm(config: WorldGenConfig): Double = config.terrain.reliefCornerKm

    /** The coastal range's half-width in kilometres, which is the belt-width suspect's figure. */
    fun coastalBeltHalfWidthKm(config: WorldGenConfig): Double =
        config.tectonics.andeanWidthCells * config.cellWidthKm

    /** A figure for a table, or a dash where there is no sample. */
    fun show(value: Double?): String = if (value == null) "-" else "%.1f".format(value)
}
