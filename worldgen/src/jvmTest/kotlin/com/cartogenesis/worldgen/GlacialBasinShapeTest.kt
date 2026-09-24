package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.PI
import kotlin.math.abs
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
 * their floors, which must not pile their cells at one height. See docs/DESIGN_LEDGER.md, I2.
 */
class GlacialBasinShapeTest : BorrowsSharedWorlds() {

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
     * off `GlaciationStage.localRelief`, which measures relief over a sliding window with straight
     * facets (an octagon since F30, a square before it), so they run straight along a facet's
     * bearing for as far as one summit stays inside the window. That is a
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
                    val said = "seed $seed basin ${basin.number} of ${basin.cells} cells at " +
                        "(${basin.column},${basin.row}): " +
                        "${"%.1f".format(basin.flattestShare * 100)}% of its floor within a metre " +
                        "of one height over ${"%.0f".format(basin.reliefMetres)} m of relief, " +
                        "against ${"%.1f".format(allowed * 100)}% allowed"
                    failures += said
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
        // **W3 closed I1's open finding, which is what the clause it replaced asked for.** Seed
        // 364673's great southern basin sat over Salar de Uyuni's flatness for as long as this bar
        // existed — 30.7% of its floor within a metre of one height against 27.4% allowed on the
        // I1 merge, 1.12 times the bar — and the mechanism was the flexure rather than the
        // carving: a thinner margin presses its bed down less, so the tilt across the basin is
        // weaker and its floor reads flatter. W3's moisture budget moved the rain that decides
        // where the ice is at all, and on this tree that basin is inside the bar with room to
        // spare: the worst floor over every audited seed is now seed 7's 45-cell basin at 13.3%
        // against 100% allowed, 0.13 times the bar. So the exemption, the clause that kept it
        // measured and the clause that kept it to one basin all go together, and the floor claim
        // is an assertion over every basin on every seed again. See docs/DESIGN_LEDGER.md, W3.
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
     * [OutlineRuns.allowedRunCells] holds the derivation; F30 asks the same of a lake's shore off
     * the same function.
     */
    private fun allowedOutlineRunCells(cells: Int): Float = OutlineRuns.allowedRunCells(cells)

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
        val world = SharedWorlds.world(config)
        val sea = SeaLevelStage.apply(world.erosion.height, config)
        val balance = if (config.climate.snowBalance) {
            ClimateStage.provisionalSnowBalance(config, sea, OceanStage.withoutCurrents(config, sea))
        } else null
        // The stage run again on the same ground the engine ran it on — the engine's own call is
        // exactly this — purely to be handed the tally. `basinFloor` is an observer and the world
        // it is measured from is untouched.
        //
        // Measured on the bed, not on the map's surface. The finished field carries the ice
        // sheet's surface wherever there is a sheet, so a basin under one read off it is the ice's
        // slope rather than the bowl's; the tally's own thickness, taken off in the field's units,
        // gives the ground back, and is zero everywhere there is no sheet.
        var basins = emptyList<Basin>()
        var bed = world.relativeElevation.data
        runBlocking {
            GlaciationStage.apply(config, sea, balance, null) { mass ->
                println(
                    "I2 TALLY seed $seed at $side: valley ${mass.basins}/${mass.basinCells} cells," +
                        " scour ${mass.scourBasins}/${mass.scourCells} cells," +
                        " budget ${mass.lakeBudget}, refused ${mass.basinsWithNoFloor}/" +
                        "${mass.basinsTooStraight}/${mass.basinsTooSmall}/${mass.basinsOverBudget}"
                )
                val metresPerUnit = config.scale.highestLandMetres
                val surface = world.relativeElevation.data
                bed = FloatArray(surface.size) { surface[it] - mass.iceThicknessMetres[it] / metresPerUnit }
                val underIce = mass.iceThicknessMetres.count { it > 0f }
                println("I2 BED seed $seed at $side: $underIce cells under the sheet read at the bed")
                basins = readBasins(seed, config, mass.basinFloor, mass.basins, bed)
            }
        }
        val level = largestLevelSurface(config, bed, world.sea.isLand)
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
            val run = OutlineRuns.longestOutlineRun(
                cells, basinFloor, number, cellsAcross, cellsDown
            )
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
                longestOutlineRun = run.cells,
                longestOutlineBearing = run.bearing,
                reliefMetres = heights.last() - heights.first(),
                flattestShare = flattestShareOf(heights)
            )
        }
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

        /** The window a height has to fall in to count as "one height", in metres. */
        const val FLAT_WINDOW_METRES = 2f

        /** How much of one level a real lake basin may hold over an even bowl's share. */
        const val FLAT_SHARE_OVER_AN_EVEN_BOWL = 2f

        /** One measurement per seed, shared by the three cases. */
        val measured = HashMap<Long, Measurement>()
    }
}
