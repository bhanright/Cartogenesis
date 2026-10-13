package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every river the site's opening band draws ends in something: the sea the map draws, a lake's
 * open water, the trunk it joins, or the floor of a basin that keeps its water.
 *
 * The band is [SiteImagery.BAND_SEED]'s world at [SiteImagery.GRID_ROWS], where the maintainer
 * found rivers stopping short of the coast: 328 of the 399 drawn rivers that reached the sea
 * reached it at the head of a drowned valley too narrow to be drawn as sea. The same rule is
 * guarded on the standard worlds at 512 rows by `DrawnRiverMouthTest` in `:cartography`, every
 * merge; this is the audit tier's, because a 2,048-row world is five minutes and a gigabyte and a
 * quarter of arrays (docs/DESIGN_LEDGER.md, RM1).
 */
class SiteRiverMouthsAuditTest {

    @Test
    fun `every river the site's opening draws ends on drawn water, its trunk, or a closed basin`() {
        val world = WorldGenerationEngine.generateBlocking(SiteImagery.bandConfig())
        val selection = RiverSelection.select(world, MapSheet.UNGENERALISED)
        val endings = RiverSelection.endings(world, selection)
        val counts = RiverSelection.Ending.entries.associateWith { ending -> endings.count { it == ending } }
        val mouths = selection.drawnCount - counts.getValue(RiverSelection.Ending.DRAWN_TRUNK)
        println(
            "SITE RIVERMOUTH seed=${SiteImagery.BAND_SEED} ${world.width}x${world.height} " +
                "drawn=${selection.drawnCount} mouths=$mouths reaching drawn water " +
                "${"%.1f".format(100.0 * counts.getValue(RiverSelection.Ending.DRAWN_WATER) / mouths)}% " +
                counts.entries.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
        )
        val short = endings.indices.filter { endings[it] == RiverSelection.Ending.SHORT_OF_THE_WATER }
        assertTrue(counts.getValue(RiverSelection.Ending.DRAWN_WATER) > 0, "no drawn river reaches water")
        assertEquals(
            0, short.size,
            "${short.size} drawn rivers stop short, the first at " +
                short.take(6).joinToString { course ->
                    val end = world.rivers.rivers[course].cells.last()
                    "(${end % world.width}, ${end / world.width})"
                }
        )
    }
}
