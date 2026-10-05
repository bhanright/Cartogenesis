package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.GlacialMass
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The ice sheet's scour basins have no preferred bearing on the ground: on a frozen plain they run
 * as far north-south as east-west, kilometer for kilometer.
 *
 * The field that places the basins, and the hummocks the sheet leaves between them, covered as
 * many cycles from pole to pole as round the equator, so on a map twice as wide as it is tall each
 * of their lattice cells was twice as long east-west as north-south on the ground, on every world
 * (the Earth-size audit's D2; docs/DESIGN_LEDGER.md, K1).
 *
 * A control in the style of `GlaciationLatticeTest`, because on the standard worlds the defect is
 * under what a basin's outline can show: a scour basin there is a few tens of cells, peeled to its
 * lowest ground and confined to sheets narrower than the lattice, and on seven seeds at 512 rows
 * the basins of the stretched lattice read anywhere from 0.6 to 1.6 times as long east-west as
 * north-south, one world to the next (the ledger row has the figures). So the stage is handed a
 * world that lets the lattice speak: a frozen plain over most of the map, flat, so the hollowness
 * term is silent, with its largest basin opened up, so a basin is the field's own blob and its
 * outline the noise's.
 *
 * Each basin's aspect is the root of its cells' spread east-west over their spread north-south,
 * each in kilometers with a cell's own extent counted in; the figure is the mean of the logs of
 * the aspects over every basin of eight plains, and a lattice with no bearing puts it at zero. The
 * bar is three of its own standard errors, the basins' scatter over the root of their number, as
 * `GroundIsotropyTest` holds a coast's projections to three of theirs.
 */
class ScourBearingTest {

    @Test
    fun `the sheet's scour basins on a frozen plain run alike north-south and east-west`() {
        val logAspects = ArrayList<Double>()
        for (seed in SEEDS) {
            val config = plainConfig(seed)
            val across = config.width
            val down = config.height
            fun onThePlain(column: Int, row: Int) = row in MARGIN_ROWS until down - MARGIN_ROWS
            val sea = HandMadeWorlds.sea(config, ::onThePlain) { _, _ -> PLAIN_ELEVATION }
            val snow = FloatField(across, down).also { field -> field.data.fill(DEEP_SNOW_MM) }
            var mass: GlacialMass? = null
            runBlocking { GlaciationStage.apply(config, sea, snow, null) { mass = it } }
            val tally = mass ?: error("the stage ran no ice on the plain")
            val byBasin = HashMap<Int, MutableList<Int>>()
            tally.basinFloor.forEachIndexed { cell, number ->
                if (number >= tally.basins) byBasin.getOrPut(number) { ArrayList() }.add(cell)
            }
            for (cells in byBasin.values) {
                val anchor = cells[0] % across
                val eastKm = cells.map { cell ->
                    var offset = cell % across - anchor
                    if (offset > across / 2) offset -= across
                    if (offset < -across / 2) offset += across
                    offset * config.cellWidthKm
                }
                val southKm = cells.map { cell -> (cell / across) * config.cellHeightKm }
                // A cell's own extent, the spread of a point uniform across it, so a basin one row
                // deep has a north-south spread and not none.
                val eastWest = spread(eastKm) + config.cellWidthKm * config.cellWidthKm / UNIFORM_SPREAD_DIVISOR
                val northSouth = spread(southKm) + config.cellHeightKm * config.cellHeightKm / UNIFORM_SPREAD_DIVISOR
                logAspects += 0.5 * ln(eastWest / northSouth)
            }
            println("SCOUR BEARING seed $seed: ${tally.scourBasins} scour basins over ${tally.scourCells} cells of a ${tally.sheetCells}-cell sheet")
        }
        assertTrue(logAspects.size >= LEAST_BASINS, "only ${logAspects.size} scour basins on the plains, too few to read a bearing off")
        val mean = logAspects.average()
        val standardError = sqrt(spread(logAspects) / logAspects.size)
        println(
            "SCOUR BEARING pooled over ${SEEDS.joinToString()}: ${logAspects.size} basins, mean aspect %.3f east-west to north-south, %.3f to %.3f within the bar"
                .format(exp(mean), exp(-STANDARD_ERRORS * standardError), exp(STANDARD_ERRORS * standardError))
        )
        assertTrue(
            abs(mean) <= STANDARD_ERRORS * standardError,
            "the sheet's scour basins on a frozen plain are %.2f times as long east-west as north-south on the ground, past the %.2f their own scatter allows"
                .format(exp(mean), exp(STANDARD_ERRORS * standardError))
        )
    }

    /** The variance of [values] about their mean. */
    private fun spread(values: List<Double>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    /** A world of square cells, 512 by 256, with the stage's largest basin opened up and its hollowness term off. */
    private fun plainConfig(seed: Long): WorldGenConfig {
        val base = WorldGenConfig.forRows(seed, ROWS)
        return base.copy(
            glaciation = base.glaciation.copy(sheetConcavity = 0f, maxLakeShareOfSurface = OPEN_LARGEST_BASIN)
        )
    }

    private companion object {
        val SEEDS = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
        const val ROWS = 256

        /** Rows of open sea kept at each pole, so the plain has a margin and the sheet a dome. */
        const val MARGIN_ROWS = 8

        /** The plain's height, a share of the land's relief: 240 m, low and flat. */
        const val PLAIN_ELEVATION = 0.04f

        /** A year's snow surplus far past the balance's own threshold, so all the plain is ice. */
        const val DEEP_SNOW_MM = 5_000f

        /** The largest basin opened to a hundredth of the surface, so no blob is peeled. */
        const val OPEN_LARGEST_BASIN = 0.01

        /** The variance of a point uniform across a unit width is a twelfth of its square. */
        const val UNIFORM_SPREAD_DIVISOR = 12.0

        /** The fewest basins the pooled aspect is read over. */
        const val LEAST_BASINS = 100

        /** How many of its own standard errors the mean log aspect may stand from zero. */
        const val STANDARD_ERRORS = 3.0
    }
}
