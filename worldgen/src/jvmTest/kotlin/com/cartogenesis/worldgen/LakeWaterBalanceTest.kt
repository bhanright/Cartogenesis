package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.LakeWaterBalance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2. A closed basin holds as much water as its catchment can keep wet, not as much as its rim
 * could contain.
 *
 * The two seeds were found by searching 1..120 at 512 for the largest spill-level basins and
 * reading off their rainfall (`ScratchLakeSearch`, not kept):
 *
 *  - **seed 43** carries the largest basin in dry country anywhere in that range — 1775 cells at
 *    (416,384), averaging 172 mm of rain a year against 577 mm of potential evaporation. It is this
 *    world's Lake Eyre, and filling it to the brim was always the wrong answer.
 *  - **seed 99** carries a large basin in wet country — 433 cells at (356,247), 730 mm of rain — and
 *    is one of the four seeds `GeographyAuditTest` already watches. Its catchment can keep the
 *    whole basin wet, so the water balance must leave it exactly where it was.
 *
 * Both directions matter. A change that shrank every lake would pass the first of these and fail
 * the second, and a world with no lakes in it is not more realistic than one with too many.
 */
class LakeWaterBalanceTest {

    private val drySeed = 43L
    private val wetSeed = 99L

    /**
     * Both worlds are generated with the outlet notch off, and that is not a convenience.
     *
     * E1 drains a filled basin by cutting its lip down, and the two basins this test is built
     * around are the two largest found anywhere in seeds 1..120 — which makes them the first things
     * it takes: on the finished code seed 43's dry basin falls from 1775 cells at spill level to
     * 110, and seed 99's wet one from 433 to 31, so there is nothing left here to put a water
     * balance on. The two mechanisms are orthogonal — one decides how much rock stands between a
     * basin and its outlet, the other how much water a catchment can keep in it — and this test is
     * about the second. Measuring it on terrain that still has basins in it is what keeps it a test
     * of the balance rather than a test of the notch. Re-picking a seed instead would only have to
     * be done again the next time anything moves the terrain.
     */
    private fun world(seed: Long, waterBalance: Boolean, size: Int = 512): WorldMap {
        val base = WorldGenConfig(seed = seed, width = size, height = size)
        return WorldGenerationEngine.generateBlocking(
            base.copy(
                lakes = base.lakes.copy(waterBalance = waterBalance),
                erosion = base.erosion.copy(outletIncision = false)
            )
        )
    }

    /** The cells of the largest spill-level basin whose footprint averages less than [maxRain]. */
    private fun basinOf(world: WorldMap, minRain: Float, maxRain: Float): List<Int> {
        val lakes = world.rivers.lakes
        return lakes.lakes
            .map { lake -> lakes.lakeId.indices.filter { lakes.lakeId[it] == lake.id } }
            .filter { cells ->
                val rain = cells.map { world.climate.precipitationMm.data[it] }.average()
                rain in minRain.toDouble()..maxRain.toDouble()
            }
            .maxByOrNull { it.size }
            ?: emptyList()
    }

    @Test
    fun `a dry basin settles far below its spill level`() {
        val off = world(drySeed, waterBalance = false)
        val on = world(drySeed, waterBalance = true)

        val basin = basinOf(off, 0f, 300f)
        assertTrue(basin.size >= 500, "seed $drySeed has no large dry basin any more (${basin.size} cells)")

        val rain = basin.map { off.climate.precipitationMm.data[it] }.average()
        val evaporation = basin.map {
            LakeWaterBalance.potentialEvaporationMm(
                off.climate.summerTemperature.data[it], off.climate.winterTemperature.data[it], 1f
            )
        }.average()

        val stillWet = basin.count { on.rivers.lakes.isLake(it) }
        val share = stillWet.toDouble() / basin.size
        val control = basin.count { off.rivers.lakes.isLake(it) }.toDouble() / basin.size

        println(
            "BALANCE seed $drySeed dry basin: ${basin.size} cells at spill, " +
                "${"%.0f".format(rain)} mm rain against ${"%.0f".format(evaporation)} mm evaporation; " +
                "$stillWet cells at balance (${"%.0f".format(share * 100)}% of spill area), " +
                "control ${"%.0f".format(control * 100)}%"
        )

        // The control is the same measurement with the balance off, and it is the whole basin by
        // construction: this is the guard failing without the fix, measured rather than asserted
        // from memory.
        assertEquals(1.0, control, 1e-9, "with waterBalance off the basin should still be full")
        assertTrue(
            share < 0.30,
            "seed $drySeed's dry basin holds ${"%.0f".format(share * 100)}% of its spill area, wanted under 30%"
        )

        val lake = basin.mapNotNull { cell ->
            val id = on.rivers.lakes.lakeId[cell]
            if (id >= 0) on.rivers.lakes.lakes[id] else null
        }.firstOrNull()
        assertTrue(lake == null || lake.endorheic, "the shrunken lake is not marked endorheic")
        assertTrue(
            lake == null || lake.surfaceElevation < lake.spillElevation,
            "an endorheic lake's surface should stand below its spill"
        )
    }

    @Test
    fun `a wet basin sits at its spill level`() {
        val off = world(wetSeed, waterBalance = false)
        val on = world(wetSeed, waterBalance = true)

        val basin = basinOf(off, 700f, Float.MAX_VALUE)
        assertTrue(basin.size >= 200, "seed $wetSeed has no large wet basin any more (${basin.size} cells)")

        val rain = basin.map { off.climate.precipitationMm.data[it] }.average()
        val stillWet = basin.count { on.rivers.lakes.isLake(it) }
        println(
            "BALANCE seed $wetSeed wet basin: ${basin.size} cells at spill, " +
                "${"%.0f".format(rain)} mm rain; $stillWet cells at balance " +
                "(${"%.0f".format(stillWet * 100.0 / basin.size)}%)"
        )

        assertEquals(basin.size, stillWet, "a wet basin must stay full to its spill")
        val lake = on.rivers.lakes.lakes[on.rivers.lakes.lakeId[basin.first()]]
        assertTrue(!lake.endorheic, "a basin that overflows must not be marked endorheic")
        assertEquals(
            lake.spillElevation, lake.surfaceElevation, 0f,
            "a lake at its spill must have the spill for a surface"
        )
    }

    /**
     * The rule the whole river network is judged by, restated for a world that now has basins water
     * does not leave: every drawn river still ends in water — the sea, a lake, a playa — or runs on
     * into another drawn river that does, or off the polar edge of the map.
     */
    @Test
    fun `rivers still reach water`() {
        listOf(drySeed, wetSeed, 7L, 42L, 1234L).forEach { seed ->
            val world = world(seed, waterBalance = true)
            val w = world.width
            val h = world.height
            val onRiver = BooleanArray(w * h)
            world.rivers.rivers.forEach { river -> river.cells.forEach { onRiver[it] = true } }

            var stranded = 0
            val lakes = world.rivers.lakes
            world.rivers.rivers.forEach { river ->
                var cell = river.cells.last()
                var steps = 0
                while (steps++ < w * h) {
                    if (!world.sea.isLand[cell]) return@forEach
                    if (lakes.isLake(cell) || lakes.isPlaya(cell)) return@forEach
                    val next = world.rivers.flowTarget[cell]
                    if (next < 0) {
                        // Off the top or bottom of the map is the one acceptable land ending.
                        val y = cell / w
                        if (y != 0 && y != h - 1) stranded++
                        return@forEach
                    }
                    if (!onRiver[next] && world.sea.isLand[next] &&
                        !lakes.isLake(next) && !lakes.isPlaya(next)
                    ) {
                        stranded++
                        return@forEach
                    }
                    cell = next
                }
            }
            println("BALANCE seed $seed: ${world.rivers.rivers.size} rivers, $stranded ending nowhere")
            assertTrue(stranded == 0, "seed $seed has $stranded rivers ending on dry land")
        }
    }

    /** Lake count, share of land, largest lake, endorheic basins and playas, before and after. */
    @Test
    fun `report the lake budget`() {
        val cases = listOf(
            Triple(drySeed, 512, "dry-basin seed"),
            Triple(718106L, 512, "author"),
            Triple(718106L, 1024, "author"),
            Triple(7L, 512, ""),
            Triple(42L, 512, ""),
            Triple(1234L, 512, "")
        )
        cases.forEach { (seed, size, note) ->
            listOf(false, true).forEach { balance ->
                val world = world(seed, balance, size)
                val lakes = world.rivers.lakes
                val cells = lakes.lakeId.count { it >= 0 }
                val largest = lakes.lakes.maxOfOrNull { it.cellCount } ?: 0
                println(
                    "BUDGET seed $seed at $size $note waterBalance=$balance: " +
                        "${lakes.lakes.size} lakes, ${"%.3f".format(cells * 100.0 / world.sea.landCellCount)}% of land, " +
                        "largest ${"%.4f".format(largest * 100.0 / (size.toDouble() * size))}% of map, " +
                        "endorheic ${lakes.lakes.count { it.endorheic }}, " +
                        "playa cells ${lakes.playa.count { it }}, " +
                        "rivers ${world.rivers.rivers.size}"
                )
            }
        }
    }

    /**
     * Thornthwaite at the plan's two calibration points, so a change to the curve has to say so.
     * Nothing was fitted to land on these — the published constants do it by themselves.
     */
    @Test
    fun `the evaporation curve is calibrated`() {
        val hotDesert = LakeWaterBalance.potentialEvaporationMm(35f, 15f, 1f)
        val coolTemperate = LakeWaterBalance.potentialEvaporationMm(18f, 2f, 1f)
        val frozen = LakeWaterBalance.potentialEvaporationMm(-5f, -30f, 1f)
        println(
            "BALANCE evaporation: hot desert ${"%.0f".format(hotDesert)} mm/yr, " +
                "cool temperate ${"%.0f".format(coolTemperate)} mm/yr, frozen ${"%.0f".format(frozen)} mm/yr"
        )
        assertTrue(hotDesert in 1800f..2500f, "hot desert evaporates $hotDesert mm/yr, wanted near 2000")
        assertTrue(coolTemperate in 400f..650f, "cool temperate evaporates $coolTemperate mm/yr, wanted near 500")
        assertEquals(0f, frozen, 0f, "frozen ground evaporates nothing")
    }
}
