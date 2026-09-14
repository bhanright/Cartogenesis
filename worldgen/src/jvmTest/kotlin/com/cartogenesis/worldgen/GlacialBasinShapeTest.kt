package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether what the ice leaves has the shape of ground or the shape of the grid.
 *
 * `GlaciationTest` asks whether the ice puts lakes where water alone leaves none, and whether the
 * bodies of water it leaves are combed along the flow grid. Neither question caught what the author
 * found on seed 364673 at 2048: a slab of ground cut dead level over ten thousand square
 * kilometres, its outline a rotated rectangle with a cross-shaped arm, every edge at exactly 0, 45
 * or 90 degrees. It held no water, so no lake guard could see it, and it was not narrow, so no bar
 * guard could see it either.
 *
 * So the two things a reader actually looks at are measured here. The *level surface* — how much
 * ground the stage leaves at one height, which is the slab itself and is asked of the whole map,
 * because it turned out to be the trough's cross-section and not the basin anyone suspected. And
 * the *basins*, off [com.cartogenesis.worldgen.pipeline.GlacialMass.basinFloor]: their outlines,
 * which must not run straight along a grid bearing for longer than their own size explains, and
 * their floors, which must not pile their cells at one height. See REALISM_PLAN.md, I2.
 */
class GlacialBasinShapeTest {

    @Test
    fun `the ice leaves no dead-level slab of ground`() {
        val failures = ArrayList<String>()
        seeds.forEach { (seed, side) ->
            val measurement = measure(seed, side)
            val allowed = levelSurfaceCapCells(measurement.squareKilometresPerCell)
            println(
                "I2 LEVEL seed $seed: largest surface level to a metre is " +
                    "${measurement.levelSurfaceCells} cells " +
                    "(${"%.0f".format(measurement.levelSurfaceCells * measurement.squareKilometresPerCell)}" +
                    " km2) at (${measurement.levelSurfaceColumn},${measurement.levelSurfaceRow}), " +
                    "against $allowed cells allowed"
            )
            if (measurement.levelSurfaceCells > allowed) {
                failures += "seed $seed: ${measurement.levelSurfaceCells} cells of land at one " +
                    "height at (${measurement.levelSurfaceColumn},${measurement.levelSurfaceRow}), " +
                    "against $allowed allowed"
            }
        }
        assertTrue(
            "the ice left a plate of ground at one height:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * Asked of the valley basins, and reported for the sheet's.
     *
     * The two regimes draw their outlines from different things, and only one of them is I2's. A
     * valley basin's outline is a contour of the ground it was filled up to; a scour basin's is a
     * threshold on a noise field, clipped to the sheet mask — and the sheet mask's own edges come
     * off `GlaciationStage.localRelief`, which measures relief over a *square* sliding window, so
     * they run straight along an axis for as far as one summit stays inside the window. That is a
     * real grid shape and the scour outlines inherit it: seed 7 at 1024 carries a 20-cell straight
     * edge on a 49-cell basin, 1.68 times what its size explains. It is a different cause in a
     * different function and it is written down in TODO.md rather than quietly absorbed here.
     */
    @Test
    fun `a cut basin's outline follows the ground, not the grid`() {
        var worstExcess = 0f
        var worst: Basin? = null
        val failures = ArrayList<String>()
        seeds.forEach { (seed, side) ->
            val basins = measure(seed, side).basins
            basins.filter { it.fromAValley }.forEach { basin ->
                val allowed = allowedOutlineRunCells(basin.cells)
                val excess = basin.longestOutlineRun / allowed
                if (excess > worstExcess) {
                    worstExcess = excess
                    worst = basin
                }
                if (basin.longestOutlineRun > allowed) {
                    failures += "seed $seed basin ${basin.number} of ${basin.cells} cells at " +
                        "(${basin.column},${basin.row}): a run of ${basin.longestOutlineRun} " +
                        "cells along bearing ${basin.longestOutlineBearing}, against " +
                        "${"%.1f".format(allowed)} allowed"
                }
            }
            val valley = basins.filter { it.fromAValley }
            val scour = basins.filter { !it.fromAValley }
            println(
                "I2 OUTLINE seed $seed: ${valley.size} valley basins, longest run " +
                    "${valley.maxOfOrNull { it.longestOutlineRun } ?: 0} cells; " +
                    "${scour.size} scour basins, longest run " +
                    "${scour.maxOfOrNull { it.longestOutlineRun } ?: 0} cells (reported only)"
            )
        }
        val at = worst
        println(
            "I2 OUTLINE worst valley basin over all seeds: " + if (at == null) "none" else
                "seed ${at.seed} basin ${at.number}, ${at.cells} cells, run " +
                    "${at.longestOutlineRun} against " +
                    "${"%.1f".format(allowedOutlineRunCells(at.cells))} allowed " +
                    "(${"%.2f".format(worstExcess)} times the bar)"
        )
        assertTrue(
            "a cut basin's outline is ruled along a grid bearing:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `a cut basin's floor is a bowl, not a plate`() {
        var worstExcess = 0f
        var worst: Basin? = null
        val failures = ArrayList<String>()
        seeds.forEach { (seed, side) ->
            val basins = measure(seed, side).basins
            basins.forEach { basin ->
                val allowed = allowedFlatShare(basin.cells, basin.reliefMetres)
                val excess = basin.flattestShare / allowed
                if (excess > worstExcess) {
                    worstExcess = excess
                    worst = basin
                }
                if (basin.flattestShare > allowed) {
                    failures += "seed $seed basin ${basin.number} of ${basin.cells} cells at " +
                        "(${basin.column},${basin.row}): " +
                        "${"%.1f".format(basin.flattestShare * 100)}% of its floor within a metre " +
                        "of one height over ${"%.0f".format(basin.reliefMetres)} m of relief, " +
                        "against ${"%.1f".format(allowed * 100)}% allowed"
                }
            }
            println(
                "I2 FLOOR seed $seed: ${basins.size} basins, flattest " +
                    "${"%.1f".format((basins.maxOfOrNull { it.flattestShare } ?: 0f) * 100)}%"
            )
        }
        val at = worst
        println(
            "I2 FLOOR worst over all seeds: " + if (at == null) "no basins" else
                "seed ${at.seed} basin ${at.number}, ${at.cells} cells, " +
                    "${"%.1f".format(at.flattestShare * 100)}% within a metre of one height over " +
                    "${"%.0f".format(at.reliefMetres)} m, against " +
                    "${"%.1f".format(allowedFlatShare(at.cells, at.reliefMetres) * 100)}% allowed " +
                    "(${"%.2f".format(worstExcess)} times the bar)"
        )
        assertTrue(
            "a cut basin's floor is a plate at one level:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The largest patch of land, in cells, that may stand level to within a metre.
     *
     * There is one landform on Earth this flat and it is a salt pan. Salar de Uyuni covers 10,582
     * km2 and its surface varies by well under a metre across the hundred kilometres of it — it is
     * the flattest large surface on the planet, which is why satellite altimeters are calibrated
     * against it. Nothing the ice makes is flatter than the flattest place there is, and a metre
     * over a cell of this map is a gradient of one in six thousand, which not even an abyssal
     * plain holds.
     *
     * Measured over all land rather than over the ice's own ground, because the two are the same
     * question here: water-cut terrain is fractal and does not hold one height across a province,
     * so anything that does is something the ice did.
     */
    private fun levelSurfaceCapCells(squareKilometresPerCell: Double): Int =
        (UYUNI_SQUARE_KILOMETRES / squareKilometresPerCell).toInt().coerceAtLeast(1)

    /**
     * The longest run a basin of [cells] cells may make along one grid bearing, in cells.
     *
     * A smooth curve drawn on a square grid makes straight runs of its own, and how long they are
     * is a question about its curvature: a circle of radius `R` cells rises half a cell over a
     * chord of `sqrt(R)`, so its outline runs about `2 * sqrt(R)` cells along an axis before it
     * steps. A round lake of `n` cells therefore shows a run of `2 * (n / pi)^(1/4)` — nine cells
     * for the 671-cell cap a 2048 grid gives
     * [com.cartogenesis.worldgen.model.GlaciationConfig.maxLakeAreaKm2]. That is the floor for any
     * shape and is not a defect; it is the grid, and a rougher shore than a circle's runs
     * *shorter*, not longer.
     *
     * What is allowed above it is [STRAIGHTEST_SHORE_OVER_A_CIRCLE], and it comes off Earth's
     * straightest lake shores, which are the graben ones. Tanganyika is 32,900 km2, an equivalent
     * radius of 102 km, and its western scarp runs about 100 km without a bend worth drawing
     * (Hutchinson, *A Treatise on Limnology*, 1957, on the graben lakes); at the 5.9 km a cell of a
     * 2048 map measures across that is a radius of 17.3 cells against a straight run of 17, which
     * is 2.05 times the 8.3 cells its own circle would have run. So Earth's straightest big lake
     * shore is about twice as straight as a circle, and it lies along a fault, which has no reason
     * to fall on one of a grid's three bearings. Three leaves that a margin.
     */
    private fun allowedOutlineRunCells(cells: Int): Float =
        STRAIGHTEST_SHORE_OVER_A_CIRCLE * 2f * (cells / PI).pow(0.25).toFloat()

    /**
     * The share of a basin floor of [cells] cells spanning [reliefMetres] that may lie within one
     * metre of one height.
     *
     * Two floors under it, and the bar is twice the larger.
     *
     * The first is the hypsometry. A paraboloid is the bowl whose area per unit of depth is
     * constant, so exactly `FLAT_WINDOW_METRES / reliefMetres` of it falls in any window that wide
     * — 2.4% of an 84 m bowl, which is the shallowest a full reach is cut at half the ice
     * thickness the stage floors at.
     *
     * The second is the grid, and at the sizes this stage cuts it is the one that binds. A bowl's
     * cells stand at the height its profile gives their distance from the rim, and on a grid that
     * distance takes only so many values: the outermost ring of a round basin of `n` cells holds
     * `2 * sqrt(pi / n)` of its area — 58% of a 38-cell basin, 14% of the 671-cell cap at 2048 —
     * and every cell of that ring stands at one height by construction. No bowl of any shape can
     * do better, so a bar under it would be a bar nothing could meet, and the question only has
     * teeth on a basin of a few hundred cells or more. That is where the defect lives.
     *
     * The factor of two over whichever floor binds is Hutchinson (*A Treatise on Limnology*,
     * 1957): real lake basins measure a volume development — the basin's volume over a cone's on
     * the same area and depth, three times for a paraboloid — of 0.6 to 1.2, so a real floor can
     * hold about twice an even bowl's share of itself at one level.
     */
    private fun allowedFlatShare(cells: Int, reliefMetres: Float): Float {
        val evenHypsometry =
            if (reliefMetres <= 0f) 1f else FLAT_WINDOW_METRES / reliefMetres
        val outermostRing = 2f * kotlin.math.sqrt(PI.toFloat() / cells)
        return (FLAT_SHARE_OVER_AN_EVEN_BOWL * maxOf(evenHypsometry, outermostRing))
            .coerceAtMost(1f)
    }

    /** One basin's floor, as the two basin cases read it. */
    private class Basin(
        val seed: Long,
        val number: Int,
        /** True for a valley basin from `cutBasins`, false for one the sheet's scour left. */
        val fromAValley: Boolean,
        val cells: Int,
        /** Where it is, so a report names ground a reader can go and look at. */
        val column: Int,
        val row: Int,
        val longestOutlineRun: Int,
        val longestOutlineBearing: Int,
        /** Top to bottom of the floor, in metres. */
        val reliefMetres: Float,
        /** The largest share of the floor lying inside any [FLAT_WINDOW_METRES] of height. */
        val flattestShare: Float
    )

    /** What one world says about the shapes the ice left on it. */
    private class Measurement(
        val squareKilometresPerCell: Double,
        val levelSurfaceCells: Int,
        val levelSurfaceColumn: Int,
        val levelSurfaceRow: Int,
        val basins: List<Basin>
    )

    /**
     * Everything the three cases read off [seed] at a [side]-square grid, measured once and kept.
     *
     * Generating one of these worlds is the better part of a minute and all three cases want the
     * same one, so the work is done once per seed for the class rather than once per case.
     */
    private fun measure(seed: Long, side: Int): Measurement = measured.getOrPut(seed) {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)
        val world = WorldGenerationEngine.generateBlocking(config)
        val sea = SeaLevelStage.apply(world.erosion.height, config)
        val balance = if (config.climate.snowBalance) {
            ClimateStage.provisionalSnowBalance(config, sea, OceanStage.withoutCurrents(config, sea))
        } else null
        // The stage run again on the same ground the engine ran it on — the engine's own call is
        // exactly this — purely to be handed the tally. `basinFloor` is an observer and the world
        // it is measured from is untouched.
        var basins = emptyList<Basin>()
        runBlocking {
            GlaciationStage.apply(config, sea, balance) { mass ->
                println(
                    "I2 TALLY seed $seed at $side: valley ${mass.basins}/${mass.basinCells} cells," +
                        " scour ${mass.scourBasins}/${mass.scourCells} cells," +
                        " budget ${mass.lakeBudget}, refused ${mass.basinsWithNoFloor}/" +
                        "${mass.basinsTooStraight}/${mass.basinsTooSmall}/${mass.basinsOverBudget}"
                )
                basins = readBasins(
                    seed, config, mass.basinFloor, mass.basins, world.relativeElevation.data
                )
            }
        }
        val level = largestLevelSurface(config, world.relativeElevation.data, world.sea.isLand)
        Measurement(
            squareKilometresPerCell = config.squareKilometresPerCell,
            levelSurfaceCells = level.size,
            levelSurfaceColumn = level.firstOrNull()?.rem(side) ?: -1,
            levelSurfaceRow = level.firstOrNull()?.div(side) ?: -1,
            basins = basins
        )
    }

    /**
     * The largest run of land cells standing within a metre of one height.
     *
     * Grown from each unvisited cell in index order and admitting a neighbour only if it is within
     * the metre of *that* cell's height, so the answer does not chain up a shallow ramp and does
     * not depend on where a walk happened to start.
     */
    private fun largestLevelSurface(
        config: WorldGenConfig,
        relativeElevation: FloatArray,
        isLand: BooleanArray
    ): List<Int> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val level = config.scale.reliefShareOfMetres(FLAT_WINDOW_METRES / 2f)
        val seen = BooleanArray(relativeElevation.size)
        var largest = emptyList<Int>()
        val stack = ArrayDeque<Int>()
        for (start in relativeElevation.indices) {
            if (seen[start] || !isLand[start]) continue
            val height = relativeElevation[start]
            seen[start] = true
            stack.addLast(start)
            val found = ArrayList<Int>()
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                found.add(cell)
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowOffset in -1..1) {
                    val neighbourRow = row + rowOffset
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnOffset in -1..1) {
                        val neighbour =
                            neighbourRow * cellsAcross + (column + columnOffset + cellsAcross) % cellsAcross
                        if (seen[neighbour] || !isLand[neighbour]) continue
                        if (abs(relativeElevation[neighbour] - height) > level) continue
                        seen[neighbour] = true
                        stack.addLast(neighbour)
                    }
                }
            }
            if (found.size > largest.size) largest = found
        }
        return largest
    }

    /** Splits [basinFloor] into its basins and measures each one's outline and floor. */
    private fun readBasins(
        seed: Long,
        config: WorldGenConfig,
        basinFloor: IntArray,
        valleyBasins: Int,
        relativeElevation: FloatArray
    ): List<Basin> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val byBasin = HashMap<Int, MutableList<Int>>()
        basinFloor.forEachIndexed { cell, number ->
            if (number >= 0) byBasin.getOrPut(number) { ArrayList() }.add(cell)
        }
        return byBasin.entries.sortedBy { it.key }.map { (number, cells) ->
            val run = longestStraightOutlineRun(cells, basinFloor, number, cellsAcross, cellsDown)
            val heights = cells
                .map { config.scale.metresAboveShoreline(relativeElevation[it]) }
                .sorted()
            Basin(
                seed = seed,
                number = number,
                fromAValley = number < valleyBasins,
                cells = cells.size,
                column = cells[0] % cellsAcross,
                row = cells[0] / cellsAcross,
                longestOutlineRun = run.first,
                longestOutlineBearing = run.second,
                reliefMetres = heights.last() - heights.first(),
                flattestShare = flattestShareOf(heights)
            )
        }
    }

    /**
     * The longest unbroken run of a basin's *outline* along one grid bearing, and which bearing.
     *
     * [com.cartogenesis.worldgen.pipeline.GlaciationStage]'s own bar test asks whether a whole body
     * is a ruled bar; this asks the same question of its edge, which is what a reader sees, and
     * asks it in the same coordinates — `GlaciationStage.alongBearingOf` and `acrossBearingOf` are
     * that instrument's, shared rather than copied. An outline cell is a basin cell with a
     * non-basin cell orthogonally beside it; a run is a set of outline cells sharing an across
     * coordinate and consecutive in the along coordinate, which steps by one on an axis and by two
     * on a diagonal.
     */
    private fun longestStraightOutlineRun(
        cells: List<Int>,
        basinFloor: IntArray,
        number: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): Pair<Int, Int> {
        val anchorColumn = cells[0] % cellsAcross
        val outline = cells.filter { cell ->
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            basinFloor[row * cellsAcross + (column + cellsAcross - 1) % cellsAcross] != number ||
                basinFloor[row * cellsAcross + (column + 1) % cellsAcross] != number ||
                row == 0 || basinFloor[(row - 1) * cellsAcross + column] != number ||
                row == cellsDown - 1 || basinFloor[(row + 1) * cellsAcross + column] != number
        }
        var longest = 0
        var atBearing = 0
        for (bearing in 0 until GlaciationStage.BEARINGS) {
            val step = if (GlaciationStage.isDiagonalBearing(bearing)) 2 else 1
            // Across in the high half and along in the low, so sorting the longs groups each line
            // of the outline and orders it, and the scan below is a single pass.
            val lines = LongArray(outline.size) { index ->
                val cell = outline[index]
                val row = cell / cellsAcross
                var columnOffset = (cell % cellsAcross) - anchorColumn
                if (columnOffset > cellsAcross / 2) columnOffset -= cellsAcross
                if (columnOffset < -cellsAcross / 2) columnOffset += cellsAcross
                val column = anchorColumn + columnOffset
                val across = GlaciationStage.acrossBearingOf(column, row, bearing)
                val along = GlaciationStage.alongBearingOf(column, row, bearing)
                ((across + COORDINATE_BIAS).toLong() shl 32) or
                    ((along + COORDINATE_BIAS).toLong() and 0xFFFFFFFFL)
            }
            lines.sort()
            var run = 0
            var previousAcross = Long.MIN_VALUE
            var previousAlong = Long.MIN_VALUE
            for (packed in lines) {
                val across = packed ushr 32
                val along = packed and 0xFFFFFFFFL
                run = if (across == previousAcross && along == previousAlong + step) run + 1 else 1
                if (run > longest) {
                    longest = run
                    atBearing = bearing
                }
                previousAcross = across
                previousAlong = along
            }
        }
        return longest to atBearing
    }

    /** The largest share of [sortedHeights] falling inside any [FLAT_WINDOW_METRES] of height. */
    private fun flattestShareOf(sortedHeights: List<Float>): Float {
        var most = 0
        var low = 0
        sortedHeights.indices.forEach { high ->
            while (sortedHeights[high] - sortedHeights[low] > FLAT_WINDOW_METRES) low++
            if (high - low + 1 > most) most = high - low + 1
        }
        return most.toFloat() / sortedHeights.size
    }

    private companion object {
        /**
         * The worlds the shapes are pooled over, each at the grid it is worth measuring on.
         *
         * The three `GlaciationTest` already pools its lake densities over, at the 1024 that case
         * moved to because both ice regimes are working there. And 364673 at 2048: the author's own
         * seed, the one the slab was found on, and the one grid it can be found at.
         *
         * That 2048 world costs this case the better part of two minutes and it is not optional.
         * The defect is *finer* than a 1024 grid can see, which is the whole reason it survived
         * three passes over this stage: the trough's flat floor is 300 km of cross-section planed
         * to one height, and how much of it lands within a metre of one height depends on how far
         * the floor falls between one cell of the flow path and the next. At 1024 a cell is 11.7 km
         * and the floor steps down fast enough to break the slab into patches of two or three
         * thousand square kilometres, which is unremarkable. At 2048 a cell is 5.9 km, the steps
         * are half the size, and the same slab comes out as one surface of ten thousand square
         * kilometres. Measured on main: 2,403 km2 at 1024 against 10,120 km2 at 2048.
         */
        val seeds = listOf(42L to 1024, 7L to 1024, 718106L to 1024, 364673L to 2048)

        /** Salar de Uyuni, the flattest large surface on Earth, in square kilometres. */
        const val UYUNI_SQUARE_KILOMETRES = 10_582.0

        /** How straight Earth's straightest lake shore is against a circle of its own size. */
        const val STRAIGHTEST_SHORE_OVER_A_CIRCLE = 3f

        /** The window a height has to fall in to count as "one height", in metres. */
        const val FLAT_WINDOW_METRES = 2f

        /** How much of one level a real lake basin may hold over an even bowl's share. */
        const val FLAT_SHARE_OVER_AN_EVEN_BOWL = 2f

        /**
         * Added to a bearing coordinate before it is packed, so the negative ones — a north-east
         * coordinate is `column - row` — sort as smaller longs rather than as larger.
         */
        const val COORDINATE_BIAS = 1 shl 24

        /** One measurement per seed, shared by the three cases. */
        val measured = HashMap<Long, Measurement>()
    }
}
