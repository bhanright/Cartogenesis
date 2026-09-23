package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * E8's census: how many basins the sea-level cut leaves standing at the waterline, and what became
 * of the rule that was meant to take them.
 *
 * `SeaConfig.postCutOutlet` cuts a converted basin's sill by what the basin's own outflow can take
 * off it and stops when it reaches the shoreline, which is right — a lake whose surface is at sea
 * level has no fall left to cut with. What it leaves is a hollow with its brim a few metres above
 * the waterline behind ground of the same height, and on Earth that is not a barrier: a spring tide
 * is 2 to 4 m on an open coast, a severe cyclone surge 8 to 9 (Katrina 8.5, Bhola 9), the record
 * 13.7, and the sea has stood where it stands for six thousand years. The Bosporus sill let the
 * Mediterranean into the Black Sea. So E8 built the rule — a basin whose exit stands within ten
 * metres of the waterline, 0.00125 of this map's eight kilometres of relief, has that exit cut to a
 * surge below the waterline by the sea rather than by any river, and the enclosure labelling then
 * leaves the basin ocean.
 *
 * ### It was built, measured and reverted, and this class is what is left
 *
 * It worked, in the narrow sense. Measured at 512 on seeds 7/42/1234/99: 10/5/23/22 basins stood at
 * the waterline over 15/12/189/47 cells, and with the rule none did; at 2048 on the author's world
 * it turned 129 patches of 1517 cells from dry to wet, the largest 413, and the crop reads as a
 * coastal lagoon open to the sea where there had been a pond behind a lip.
 *
 * It was reverted on what it cost against what it bought. It could not do the thing it was
 * specified for at all — see `RiftDepthAuditTest`, where the author's trough turns out to be an arm
 * of the sea already, with no sill to breach, and where only 30% of its floor lies below the
 * waterline for *any* marine process to reach. And it broke three guards that have no Earth figure
 * to re-derive from: `DepositionTest`'s pinned land count (6327 to 6316 at 128), `GlaciationTest`'s
 * control that the ice is what put the lakes in the cold country (0.21 against a bar of 3 x 0.07,
 * a ratio of two very small numbers pushed onto its edge), and `DeltaMouthTest`'s vacuity check
 * that the old lobe leaves no pocket (one seed of four gained one). Moving those to buy 1517 cells
 * of 4.19 million is what ground rule 5 exists to refuse.
 *
 * So what ships is the measurement. This reports the population any future transgression rule would
 * act on — and it is also the population S2's rift subsidence will move, which is the chunk that
 * can actually reach the scene E8 was aimed at. Nothing here is asserted except that the census
 * found something to count.
 */
class WaterlineBasinTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    /**
     * Earth's surge range, in metres above the waterline.
     *
     * A spring tide is 2 to 4 m on an open coast, a severe tropical-cyclone surge 8 to 9 m (Katrina
     * put 8.5 m on the Mississippi coast in 2005, the 1970 Bhola cyclone about 9 m into the Bay of
     * Bengal) and the record is the 13.7 m measured at Bathurst Bay in 1899.
     *
     * In metres since S1, converted through `WorldScale` where it is used. It was 0.00125 of "the
     * land's relief", read against the eight kilometres `SeaConfig.lowstand` also assumed, and
     * neither figure was the one the pipeline actually spent.
     */
    private val surgeMetres = 10f

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    @Test
    fun `report the basins standing at the waterline`() {
        var found = 0
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val world = SharedWorlds.world(config)
            // The sea stage's own result as well as the finished field: glaciation runs inside this
            // step and gouging basins is the one thing it is for, so a cirque on low coastal ground
            // is a hollow at the waterline that no sea-level rule ever saw.
            val stage = count(config, SeaLevelStage.apply(world.erosion.height, config))
            val finished = count(config, world.sea)
            found += stage.at
            println(
                ("E8 seed %d at 512: %d basins below the cut over %d cells — %d standing at the " +
                    "waterline over %d cells (largest %d), %d behind a sill higher than a surge, " +
                    "%d whose exit is already below the waterline; on the finished field, which " +
                    "the ice has carved since, %d at the waterline").format(
                    seed, stage.drowned, stage.drownedCells, stage.at, stage.atCells, stage.largest,
                    stage.above, stage.below, finished.at
                )
            )
        }
        assertTrue(
            found > 0,
            "no basin stood at the waterline on any seed, so this census is counting nothing and " +
                "the finding it records — that the rule which would take them cannot pay for " +
                "itself — no longer has anything behind it"
        )
    }

    private class Count(
        val drowned: Int,
        val drownedCells: Int,
        val at: Int,
        val atCells: Int,
        val above: Int,
        val below: Int,
        val largest: Int
    )

    /**
     * The basins a fill finds below the sea-level cut, sorted by where the ground at their exit
     * stands.
     *
     * The *ground* at the exit cell, not the level the fill raised the basin to, and the difference
     * is not pedantry: the exit is by definition a cell the fill did not have to pond, but the
     * epsilon nudge that gives a flat its gradient still accumulates along one, so a basin whose
     * exit stands a hair below the waterline can carry a brim a few metres above it. Reading the
     * brim counts those as basins behind a sill, and they are not — their exit runs into more
     * converted ground below the waterline, which is to say they lie inside a tract the ocean
     * cannot reach at all and belong to `SeaConfig.enclosedSeaIsLand` rather than to any surge.
     */
    private fun count(config: WorldGenConfig, sea: SeaLevelResult): Count {
        val w = sea.relativeElevation.width
        val h = sea.relativeElevation.height
        val filled = FlowRouting.fillDepressions(w, h, sea.isLand, sea.relativeElevation)
        val flow =
            FlowRouting.flowDirections(
                w, h, sea.isLand, sea.relativeElevation, filled, config.seed, config.facetRouting,
                config.flatPotential
            )
        val notch = FlowRouting.spillways(
            w, h, sea.isLand, sea.relativeElevation.data, filled.data, flow,
            config.scale.reliefShareOfMetres(HydraulicErosion.POND_DEPTH_METRES)
        )
        var drowned = 0
        var drownedCells = 0
        var at = 0
        var atCells = 0
        var above = 0
        var below = 0
        // A crest above the waterline is a height on land, so it is read off the land's half of
        // the ruler.
        val surge = config.scale.reliefShareOfMetres(surgeMetres)
        var largest = 0
        for (b in 0 until notch.count) {
            if (notch.floor[b] >= 0f) continue
            drowned++
            drownedCells += notch.cells[b]
            val crest =
                if (notch.spill[b] >= 0) sea.relativeElevation.data[notch.spill[b]] else -1f
            when {
                notch.spill[b] < 0 || crest < 0f -> below++
                crest <= surge -> {
                    at++
                    atCells += notch.cells[b]
                    if (notch.cells[b] > largest) largest = notch.cells[b]
                }
                else -> above++
            }
        }
        return Count(drowned, drownedCells, at, atCells, above, below, largest)
    }
}
