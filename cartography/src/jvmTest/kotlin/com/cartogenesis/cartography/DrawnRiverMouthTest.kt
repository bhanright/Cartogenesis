package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.SeaChannels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every river the map draws ends in something: the sea the map draws, a lake's open water, the
 * trunk it joins, or the floor of a basin that keeps its water.
 *
 * The maintainer found the case on the site's opening band (seed 1 at 2,048 rows): rivers stopping
 * at the inland end of a thread of sea color, with the coast inked straight across the thread's
 * mouth and dry-looking ground between the line's end and the water. The thread is a drowned
 * valley too narrow for the map to draw as sea (`NarrowSea`), and the tracer stopped every course
 * at its head. On the standard worlds at 512 rows it is the same water a cell wide; the guard on
 * the site's own grid is `SiteRiverMouthsAuditTest` in `:desktop` (docs/DESIGN_LEDGER.md, RM1).
 *
 * Read at two marks of the density scale: Earth's, which is what a map draws by default, and the
 * top, where every traced course is drawn, so that no course the reader could ask for escapes it.
 */
class DrawnRiverMouthTest : BorrowsSharedWorlds() {

    private companion object {
        /** The standard seeds, at the rows a guard on the grid's own detail reads. */
        val SEEDS = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS
        val MARKS = listOf(RiverSelection.EARTH_DENSITY_STEP, RiverSelection.EVERY_COURSE_STEP)
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))

    @Test
    fun `every drawn river ends on drawn water, its trunk, or a closed basin`() {
        var shortTotal = 0
        var intoWaterTotal = 0
        var throughNarrowSeaTotal = 0
        val examples = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val narrow = SeaChannels.narrow(world.config, world.sea.isLand)
            for (mark in MARKS) {
                val selection = RiverSelection.select(world, MapSheet.UNGENERALISED, mark)
                val endings = RiverSelection.endings(world, selection)
                val counts = RiverSelection.Ending.entries.associateWith { ending -> endings.count { it == ending } }
                val short = counts.getValue(RiverSelection.Ending.SHORT_OF_THE_WATER)
                shortTotal += short
                intoWaterTotal += counts.getValue(RiverSelection.Ending.DRAWN_WATER)
                throughNarrowSeaTotal += world.rivers.rivers.indices.count { course ->
                    val cells = world.rivers.rivers[course].cells
                    selection.drawn[course] && (0 until cells.size - 1).any { narrow[cells[it]] }
                }
                endings.forEachIndexed { course, ending ->
                    if (ending == RiverSelection.Ending.SHORT_OF_THE_WATER && examples.size < 6) {
                        val end = world.rivers.rivers[course].cells.last()
                        examples += "seed $seed mark $mark course $course at (${end % world.width}, ${end / world.width})"
                    }
                }
                // Every drawn course that is not a tributary of a drawn course is a river mouth: the
                // share of those that reach water the map draws is the figure a reader sees.
                val mouths = selection.drawnCount - counts.getValue(RiverSelection.Ending.DRAWN_TRUNK)
                println(
                    "RIVERMOUTH seed=$seed mark=$mark drawn=${selection.drawnCount} mouths=$mouths " +
                        "reaching drawn water ${"%.1f".format(100.0 * counts.getValue(RiverSelection.Ending.DRAWN_WATER) / mouths)}% " +
                        counts.entries.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
                )
            }
        }
        println("RIVERMOUTH $throughNarrowSeaTotal drawn courses run down sea too narrow to draw as sea")
        assertTrue(intoWaterTotal > 0, "no drawn course on any seed reaches water, so this guard has stopped discriminating")
        // Recorded at W2b, whose rain moved the ground: on seeds 42 and 99 courses stop in narrow sea
        // the seaward route carries no further, and on seed 42 a few on the ice (docs/TODO.md).
        KnownFailures.expect("W2b: courses stop in narrow sea the seaward route does not carry on", "63") {
            if (shortTotal != 0) {
                throw RecordedViolation(
                    "$shortTotal drawn rivers stop short of the water, their trunk or a closed basin: ${examples.joinToString()}",
                    "$shortTotal"
                )
            }
        }
    }

    /**
     * A course is carried only down water the map does not draw as the sea: sea too narrow for a
     * coast, and the pools along it. Once it reaches the sea the map draws, it has reached the sea and
     * stops, rather than running on across it.
     */
    @Test
    fun `a course runs through no sea the map draws as the sea, only into it`() {
        for (seed in SEEDS) {
            val world = world(seed)
            val isLand = world.sea.isLand
            val passage = SeaChannels.passage(world.config, isLand, SeaChannels.narrow(world.config, isLand))
            var badSteps = 0
            world.rivers.rivers.forEach { river ->
                val cells = river.cells
                for (step in 0 until cells.size - 1) {
                    if (!isLand[cells[step]] && !passage[cells[step]]) badSteps++
                }
            }
            assertEquals(0, badSteps, "seed $seed: $badSteps steps of a course cross the sea rather than end at it")
        }
    }
}
