package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the canopy the ground is darkened under is a fact about the cell or a fact about its
 * biome's name.
 *
 * Until W4 it was the name: `ClimateTint` held one figure per biome, so every cell of a grassland
 * was darkened by exactly nothing and every cell of a temperate forest by exactly the same
 * eighty-five per cent, and the line between them — which is one millimetre of rainfall wide — was
 * a step in the drawn ground that no amount of blurring upstream could soften. The world now
 * carries a vegetation density of its own, continuous in the water balance and the growing season,
 * and the tint reads that.
 *
 * Two clauses, and the old table is the control for both: it is still in `ClimateTint`, still
 * reachable through `VegetationConfig.enabled`, and the figures below are measured against it on
 * the same world rather than quoted from memory.
 *
 * The graphics device's half of this is `GpuRasterTest`, which draws every view in every style on
 * both paths and compares the pixels; the field travels to the device like the other per-cell
 * fields, so nothing here needs to repeat that.
 *
 * See docs/DESIGN_LEDGER.md, W4.
 */
class VegetationTintTest {

    private companion object {

        /** The gallery's world, and the same world with the field switched off. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        val WITHOUT_FIELD: WorldMap by lazy {
            val base = WORLD.config
            WorldGenerationEngine.generateBlocking(
                base.copy(vegetation = base.vegetation.copy(enabled = false))
            )
        }

        /** Below this a biome is too rare on this world for a spread within it to say anything. */
        const val MIN_BIOME_CELLS = 200

        /**
         * The classes with no ground to carry a cover, which are not asked to vary.
         *
         * The two seas because there is no soil under them, and the ice sheet because the ground
         * is buried: the field reads 0.002 on average over seed 234475's ice with a spread of
         * 0.015, which is the climate under the ice answering honestly and is not a country a
         * reader is being shown. Asking these three to vary would be asking the field to invent
         * something.
         */
        val NO_GROUND = setOf(Biome.OCEAN, Biome.SHALLOW_OCEAN, Biome.ICE_SHEET)

        /**
         * The least a populous biome's canopy may vary across its own cells, as a standard
         * deviation on the 0..1 scale.
         *
         * A fiftieth. It is a floor and not a target: what it has to separate is a field from a
         * table, and a table's spread within one biome is exactly zero. Two per cent of the scale
         * is under a fifth of a colour step once `ClimateTint.CANOPY_DARKENING`'s twelfth has been
         * applied, so a biome that only just clears it is still drawn as one flat country — which
         * is why the clause below asserts this of *every* populous biome rather than of the map's
         * average, where one varied class could carry a dozen flat ones.
         */
        const val LEAST_SPREAD_WITHIN_A_BIOME = 0.02f

        /**
         * How much of the old table's step across a biome boundary may survive.
         *
         * Half. The boundary does not vanish and should not: a classifier that says *forest* on
         * one side and *grassland* on the other is usually saying so because the two cells really
         * do differ, and the density agrees with it. What was wrong was that every boundary
         * carried the *same* step whatever the two cells' climates were, and that the step was the
         * whole difference rather than part of it. Halving the mean step is the size of the change
         * this claims; the picture is in the crops the render dump writes.
         */
        const val MOST_OF_THE_OLD_STEP = 0.5f
    }

    @Test
    fun `a cell's canopy varies within its own biome, where the table gave every cell one figure`() {
        val populous = Biome.entries.filter { biome ->
            WORLD.climate.biome.count { it == biome } >= MIN_BIOME_CELLS && biome !in NO_GROUND
        }
        assertTrue(populous.size >= 5, "this world has too few populous biomes to measure: $populous")

        populous.forEach { biome ->
            val cells = WORLD.climate.biome.indices.filter { WORLD.climate.biome[it] == biome }
            val field = cells.map { ClimateTint.canopyAt(WORLD, it) }
            val table = cells.map { ClimateTint.canopyAt(WITHOUT_FIELD, it) }
            println(
                "CANOPY %-26s %d cells: field mean %.3f spread %.3f, table mean %.3f spread %.3f"
                    .format(
                        biome.name, cells.size, field.average(), spread(field),
                        table.average(), spread(table)
                    )
            )
            assertTrue(
                spread(table) == 0f,
                "the control is supposed to be one figure a biome, and $biome varies in it"
            )
            assertTrue(
                spread(field) >= LEAST_SPREAD_WITHIN_A_BIOME,
                "$biome is drawn as one flat country: its canopy varies by only ${spread(field)}"
            )
        }
    }

    @Test
    fun `a steppe woodland boundary is graded where it was a step`() {
        val open = setOf(Biome.GRASSLAND, Biome.SHRUBLAND, Biome.SAVANNA)
        val wooded = setOf(
            Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST, Biome.TAIGA,
            Biome.TROPICAL_SEASONAL_FOREST, Biome.MONSOON_FOREST
        )
        val cellsAcross = WORLD.width
        val cellsDown = WORLD.height

        var pairs = 0
        var fieldStep = 0.0
        var tableStep = 0.0
        var worstFieldStep = 0f
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val neighbours = intArrayOf(
                    row * cellsAcross + (column + 1) % cellsAcross,
                    if (row < cellsDown - 1) (row + 1) * cellsAcross + column else -1
                )
                neighbours.forEach { next ->
                    if (next < 0) return@forEach
                    if (!WORLD.sea.isLand[cell] || !WORLD.sea.isLand[next]) return@forEach
                    val here = WORLD.climate.biome[cell]
                    val there = WORLD.climate.biome[next]
                    val crossesTheLine =
                        (here in open && there in wooded) || (here in wooded && there in open)
                    if (!crossesTheLine) return@forEach
                    pairs++
                    val step = abs(
                        ClimateTint.canopyAt(WORLD, cell) - ClimateTint.canopyAt(WORLD, next)
                    )
                    fieldStep += step
                    if (step > worstFieldStep) worstFieldStep = step
                    tableStep += abs(
                        ClimateTint.canopyAt(WITHOUT_FIELD, cell) -
                            ClimateTint.canopyAt(WITHOUT_FIELD, next)
                    )
                }
            }
        }
        assertTrue(pairs >= 200, "this world has only $pairs open-to-wooded boundary pairs")
        val field = fieldStep / pairs
        val table = tableStep / pairs
        println(
            ("CANOPY %d open-to-wooded boundary pairs: mean step %.3f from the field against " +
                "%.3f from the table, x%.2f; worst single step %.3f")
                .format(pairs, field, table, field / table, worstFieldStep)
        )
        assertTrue(
            field <= table * MOST_OF_THE_OLD_STEP,
            "the boundary is still a step: $field against the table's $table"
        )
    }

    private fun spread(values: List<Float>): Float {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).toFloat()
    }
}
