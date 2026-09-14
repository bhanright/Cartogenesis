package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.PI
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a basin the ice cut has the shape of a hollow in the ground or the shape of the grid.
 *
 * `GlaciationTest` asks whether the ice puts lakes where water alone leaves none, and whether the
 * bodies of water it leaves are combed along the flow grid. Neither question caught what the author
 * found on seed 364673 at 2048: a basin whose floor was cut dead level, whose outline was a rotated
 * rectangle with a cross-shaped arm and whose every edge lay at exactly 0, 45 or 90 degrees. It
 * held no water, so no lake guard could see it, and it was not narrow, so no bar guard could see it
 * either. What a reader sees is a slab stamped into a hillside.
 *
 * So the two things a reader actually looks at are measured here, off
 * [com.cartogenesis.worldgen.pipeline.GlacialMass.basinFloor]: the *outline*, which must not run
 * straight along a grid bearing for longer than its own size explains, and the *floor*, which must
 * not pile its cells at one height. Both are asked of every basin both regimes cut. See
 * REALISM_PLAN.md, I2.
 */
class GlacialBasinShapeTest {

    @Test
    fun `a cut basin's outline follows the ground, not the grid`() {
        var worst: Basin? = null
        var worstExcess = 0f
        val failures = ArrayList<String>()
        seeds.forEach { seed ->
            val basins = basinsOf(seed)
            basins.forEach { basin ->
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
            println(
                "I2 OUTLINE seed $seed: ${basins.size} basins, longest run " +
                    "${basins.maxOfOrNull { it.longestOutlineRun } ?: 0} cells"
            )
        }
        val at = worst
        println(
            "I2 OUTLINE worst over all seeds: " +
                if (at == null) "no basins" else
                    "seed ${at.seed} basin ${at.number}, ${at.cells} cells, run " +
                        "${at.longestOutlineRun} against ${"%.1f".format(allowedOutlineRunCells(at.cells))} " +
                        "allowed (${"%.2f".format(worstExcess)} times the bar)"
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
        seeds.forEach { seed ->
            val basins = basinsOf(seed)
            basins.forEach { basin ->
                val allowed = allowedFlatShare(basin.reliefMetres)
                val excess = basin.flattestShare / allowed
                if (excess > worstExcess) {
                    worstExcess = excess
                    worst = basin
                }
                if (basin.flattestShare > allowed) {
                    failures += "seed $seed basin ${basin.number} of ${basin.cells} cells at " +
                        "(${basin.column},${basin.row}): ${"%.1f".format(basin.flattestShare * 100)}% " +
                        "of its floor within a metre of one height over " +
                        "${"%.0f".format(basin.reliefMetres)} m of relief, against " +
                        "${"%.1f".format(allowed * 100)}% allowed"
                }
            }
            println(
                "I2 FLOOR seed $seed: ${basins.size} basins, flattest " +
                    "${"%.1f".format((basins.maxOfOrNull { it.flattestShare } ?: 0f) * 100)}%"
            )
        }
        val at = worst
        println(
            "I2 FLOOR worst over all seeds: " +
                if (at == null) "no basins" else
                    "seed ${at.seed} basin ${at.number}, ${at.cells} cells, " +
                        "${"%.1f".format(at.flattestShare * 100)}% within a metre of one height " +
                        "over ${"%.0f".format(at.reliefMetres)} m, against " +
                        "${"%.1f".format(allowedFlatShare(at.reliefMetres) * 100)}% allowed"
        )
        assertTrue(
            "a cut basin's floor is a plate at one level:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The longest run a basin of [cells] cells may make along one grid bearing, in cells.
     *
     * A smooth curve drawn on a square grid makes straight runs of its own, and how long they are
     * is a question about its curvature: a circle of radius `R` cells rises half a cell over a
     * chord of `sqrt(R)`, so its outline runs about `2 * sqrt(R)` cells along an axis before it
     * steps. A round lake of `n` cells therefore shows a run of `2 * (n / pi)^(1/4)` — nine cells
     * for the 671-cell cap a 2048 grid gives [com.cartogenesis.worldgen.model.
     * GlaciationConfig.maxLakeAreaKm2]. That is the floor for any shape and is not a defect; it is
     * the grid, and a rougher shore than a circle's runs *shorter*, not longer.
     *
     * What is above it is [STRAIGHTEST_SHORE_OVER_A_CIRCLE], and it comes off Earth's straightest
     * lake shores, which are the graben ones. Tanganyika is 32,900 km2, an equivalent radius of
     * 102 km, and its western scarp runs about 100 km without a bend worth drawing (Hutchinson, *A
     * Treatise on Limnology*, 1957, on the graben lakes); at the 5.9 km a cell of a 2048 map
     * measures across that is a radius of 17.3 cells against a straight run of 17, which is 2.05
     * times the 8.3 cells its own circle would have run. So Earth's straightest big lake shore is
     * about twice as straight as a circle, and it lies along a fault, which has no reason to fall
     * on one of a grid's three bearings.
     */
    private fun allowedOutlineRunCells(cells: Int): Float {
        val circleRunCells = 2f * (cells / PI).pow(0.25).toFloat()
        return STRAIGHTEST_SHORE_OVER_A_CIRCLE * circleRunCells
    }

    /**
     * The share of a basin floor that may lie within one metre of one height, given the
     * [reliefMetres] the floor spans.
     *
     * A paraboloid is the bowl whose area per unit of depth is constant, so exactly
     * `FLAT_WINDOW_METRES / reliefMetres` of it falls in any window that wide — 2.4% of an 84 m
     * bowl, which is the shallowest a full reach is cut at
     * [com.cartogenesis.worldgen.model.GlaciationConfig.overDeepeningMetres] and half the ice
     * thickness the stage floors at. Real lake basins are not exactly paraboloids: Hutchinson
     * (1957) measures their volume development — the ratio of the basin's volume to a cone's on
     * the same area and depth, three times for a paraboloid — between 0.6 and 1.2, so a real floor
     * can hold up to about twice a paraboloid's share at one level. [FLAT_SHARE_OVER_A_PARABOLOID]
     * is three, which allows that and leaves a little for the grid quantising a bowl a few hundred
     * cells across.
     *
     * A basin whose floor spans almost no relief is allowed almost anything, and correctly: three
     * metres of floor cannot be anything but level, and nobody reads three metres as a slab.
     */
    private fun allowedFlatShare(reliefMetres: Float): Float =
        if (reliefMetres <= 0f) 1f
        else (FLAT_SHARE_OVER_A_PARABOLOID * FLAT_WINDOW_METRES / reliefMetres).coerceAtMost(1f)

    /** One basin's floor, as the two guards above read it. */
    private class Basin(
        val seed: Long,
        val number: Int,
        val cells: Int,
        /** Where it is, for a report a reader can go and look at. */
        val column: Int,
        val row: Int,
        val longestOutlineRun: Int,
        val longestOutlineBearing: Int,
        /** Top to bottom of the floor, in metres. */
        val reliefMetres: Float,
        /** The largest share of the floor lying inside any [FLAT_WINDOW_METRES] of height. */
        val flattestShare: Float
    )

    /**
     * Every basin both ice regimes cut on [seed], measured once and kept.
     *
     * Generating a world at [SIDE] is the better part of a minute, and both cases above want the
     * same basins, so the work is done once per seed for the class rather than once per case.
     */
    private fun basinsOf(seed: Long): List<Basin> = measured.getOrPut(seed) {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(SIDE, SIDE)
        val world = WorldGenerationEngine.generateBlocking(config)
        val sea = SeaLevelStage.apply(world.erosion.height, config)
        val balance = if (config.climate.snowBalance) {
            ClimateStage.provisionalSnowBalance(config, sea, OceanStage.withoutCurrents(config, sea))
        } else null
        // The stage run again on the same ground the engine ran it on, purely to be handed the
        // tally: `basinFloor` is an observer and leaves the world it was measured on untouched.
        var basins = emptyList<Basin>()
        runBlocking {
            GlaciationStage.apply(config, sea, balance) { mass ->
                basins = readBasins(seed, config, mass.basinFloor, sea.relativeElevation.data)
            }
        }
        basins
    }

    /** Splits [basinFloor] into its basins and measures each one's outline and floor. */
    private fun readBasins(
        seed: Long,
        config: WorldGenConfig,
        basinFloor: IntArray,
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
     * [com.cartogenesis.worldgen.pipeline.GlaciationStage]'s own `isStraightBar` asks whether a
     * whole body is a ruled bar; this asks the same question of its edge, which is what a reader
     * sees, and asks it in the same coordinates — `GlaciationStage.alongBearingOf` and
     * `acrossBearingOf` are that instrument's, shared rather than copied. An outline cell is a
     * basin cell with a non-basin cell orthogonally beside it; a run is a set of outline cells
     * sharing an across coordinate and consecutive in the along coordinate, which steps by one on
     * an axis and by two on a diagonal.
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
            val west = row * cellsAcross + (column + cellsAcross - 1) % cellsAcross
            val east = row * cellsAcross + (column + 1) % cellsAcross
            basinFloor[west] != number || basinFloor[east] != number ||
                row == 0 || basinFloor[(row - 1) * cellsAcross + column] != number ||
                row == cellsDown - 1 || basinFloor[(row + 1) * cellsAcross + column] != number
        }
        var longest = 0
        var atBearing = 0
        for (bearing in 0 until GlaciationStage.BEARINGS) {
            val step = if (GlaciationStage.isDiagonalBearing(bearing)) 2 else 1
            // Across in the high half and along in the low, so sorting the longs groups each line
            // of the outline and orders it: the same packing every ordered walk in the pipeline
            // uses, and it makes the scan below a single pass.
            val lines = LongArray(outline.size) { index ->
                val cell = outline[index]
                val row = cell / cellsAcross
                var columnOffset = (cell % cellsAcross) - anchorColumn
                if (columnOffset > cellsAcross / 2) columnOffset -= cellsAcross
                if (columnOffset < -cellsAcross / 2) columnOffset += cellsAcross
                val column = anchorColumn + columnOffset
                val across = GlaciationStage.acrossBearingOf(column, row, bearing)
                val along = GlaciationStage.alongBearingOf(column, row, bearing)
                (across.toLong() shl 32) or ((along + COORDINATE_BIAS).toLong() and 0xFFFFFFFFL)
            }
            lines.sort()
            var run = 0
            var previousAcross = Long.MIN_VALUE
            var previousAlong = Long.MIN_VALUE
            for (packed in lines) {
                val across = packed shr 32
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
         * The grid the shapes are measured on.
         *
         * 1024 rather than the 2048 the author found the slab at, because both regimes are working
         * at 1024 — `GlaciationTest` moved to it for that reason — and a 2048 world is four times
         * the generation for a question about shape, which does not change with the grid. The
         * outline bar is a multiple of what a circle of the basin's own size does on a grid, so it
         * is the same bar at either resolution.
         */
        const val SIDE = 1024

        /**
         * The worlds the basins are pooled over.
         *
         * The three `GlaciationTest` already pools its lake densities over, so the cold country
         * being measured is cold country the suite already knows, and 364673 — the author's own
         * seed, the one the slab was found on.
         */
        val seeds = listOf(42L, 7L, 718106L, 364673L)

        /** How straight Earth's straightest lake shore is against a circle of its own size. */
        const val STRAIGHTEST_SHORE_OVER_A_CIRCLE = 3f

        /** The window a height has to fall in to count as "one height", in metres. */
        const val FLAT_WINDOW_METRES = 2f

        /** How much of one level a real lake basin may hold over a paraboloid's even share. */
        const val FLAT_SHARE_OVER_A_PARABOLOID = 3f

        /**
         * Added to a bearing coordinate before it is packed, so that the negative ones — a
         * north-east coordinate is `column - row` — sort as smaller longs rather than larger.
         */
        const val COORDINATE_BIAS = 1 shl 24

        /** One measurement per seed, shared by the two cases. */
        val measured = HashMap<Long, List<Basin>>()
    }
}
