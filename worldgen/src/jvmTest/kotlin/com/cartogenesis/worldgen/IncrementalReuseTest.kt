package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether reusing the previous world's stages gives the same answer as generating from scratch.
 *
 * [WorldGenerationEngine.generate] can be handed the last world and skip any stage whose settings
 * have not changed, which is what keeps a change to a late setting — realm count, wilderness — from
 * re-running erosion. That is only sound if each stage's guard names every config section the stage
 * actually reads, and three of them did not: erosion reads `seaLevel`, ocean reads `climate`, and
 * rivers read `lakes`, while each was guarded on its own section alone. The bugs stayed invisible
 * because no app ever passed a previous world — only `PipelineTest` did, and it checks that terrain
 * and plates *are* reused rather than that anything downstream is correctly discarded.
 *
 * So this compares the two routes for a change to every section in turn. They must agree exactly.
 */
class IncrementalReuseTest {

    // Seed 99 rather than the usual 42, because 42 at this size has no lakes at all and the
    // lakes case then passes without ever exercising the setting it names.
    private val base = WorldGenConfig(seed = 99L, width = 256, height = 256)

    @Test
    fun `reusing stages gives the same world as generating afresh`() {
        val previous = WorldGenerationEngine.generateBlocking(base)
        println("REUSE base ${fingerprint(previous)}")

        // A case can only 'agree' meaningfully if there was something to disagree about. The first
        // version of this ran on a world with no lakes, so the lakes case passed while reusing a
        // stale river stage — it went through the motions without looking at what the setting
        // changes. These assertions make that failure loud instead of green.
        assertTrue(previous.rivers.lakes.lakes.isNotEmpty(), "base world has no lakes to compare")
        assertTrue(previous.rivers.rivers.isNotEmpty(), "base world has no rivers to compare")
        assertTrue(previous.nations.nations.isNotEmpty(), "base world has no realms to compare")
        assertTrue(previous.landmarks.landmarks.isNotEmpty(), "base world has no landmarks")

        val variants = listOf(
            "terrain" to base.copy(terrain = base.terrain.copy(octaves = base.terrain.octaves - 1)),
            "tectonics" to base.copy(
                tectonics = base.tectonics.copy(plateCount = base.tectonics.plateCount + 3)
            ),
            "erosion" to base.copy(erosion = base.erosion.copy(enabled = false)),
            "seaLevel" to base.copy(seaLevel = base.seaLevel - 0.04f),
            "climate" to base.copy(
                climate = base.climate.copy(
                    equatorTemperatureC = base.climate.equatorTemperatureC + 4f
                )
            ),
            "rivers" to base.copy(rivers = base.rivers.copy(maxRivers = base.rivers.maxRivers / 2)),
            "lakes" to base.copy(lakes = base.lakes.copy(enabled = !base.lakes.enabled)),
            "ocean" to base.copy(ocean = base.ocean.copy(enabled = !base.ocean.enabled)),
            "nations" to base.copy(nations = base.nations.copy(nationCount = base.nations.nationCount + 4)),
            "wilderness" to base.copy(
                nations = base.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)
            ),
            "landmarks" to base.copy(landmarks = base.landmarks.copy(count = base.landmarks.count + 7))
        )

        val disagreed = ArrayList<String>()
        variants.forEach { (name, config) ->
            val fresh = fingerprint(WorldGenerationEngine.generateBlocking(config))
            val reused = fingerprint(WorldGenerationEngine.generateBlocking(config, previous = previous))
            if (fresh != reused) {
                disagreed.add(name)
                println("REUSE $name DIFFERS\n  fresh  $fresh\n  reused $reused")
            } else {
                println("REUSE $name agrees")
            }
        }
        assertEquals(
            emptyList(), disagreed,
            "reusing the previous world changed the result for: $disagreed"
        )
    }

    @Test
    fun `reuse makes a late setting change much cheaper`() {
        // Larger than the correctness case, because the point is the cost of erosion and that only
        // dominates once the grid is big enough to be worth measuring.
        val config = WorldGenConfig(seed = 99L, width = 512, height = 512)
        val previous = WorldGenerationEngine.generateBlocking(config)
        val toggled = config.copy(
            nations = config.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)
        )

        fun time(block: () -> Unit): Long {
            val started = System.nanoTime()
            block()
            return (System.nanoTime() - started) / 1_000_000
        }

        // Once each first, so neither route pays for class loading or a cold JIT.
        WorldGenerationEngine.generateBlocking(toggled)
        WorldGenerationEngine.generateBlocking(toggled, previous = previous)

        val fresh = time { WorldGenerationEngine.generateBlocking(toggled) }
        val reused = time { WorldGenerationEngine.generateBlocking(toggled, previous = previous) }

        println("REUSE COST wilderness toggle: fresh ${fresh}ms, reused ${reused}ms")
        // Deliberately loose. The real figure is far better than this, but a timing assertion that
        // sits near the true ratio fails on a busy machine and teaches everyone to ignore it.
        assertTrue(
            reused * 2 < fresh,
            "reuse saved little: fresh ${fresh}ms against reused ${reused}ms"
        )
    }

    /**
     * A number per stage rather than one for the whole world, so a disagreement says which stage
     * went stale instead of merely that something did.
     */
    private fun fingerprint(world: WorldMap): String {
        fun sum(values: FloatArray): Long {
            var checksum = 0L
            values.forEach { checksum = checksum * 31 + it.toRawBits() }
            return checksum
        }
        return listOf(
            "terrain=${sum(world.terrain.height.data)}",
            "plates=${sum(world.plates.height.data)}",
            "erosion=${sum(world.erosion.height.data)}",
            "sea=${sum(world.sea.relativeElevation.data)}",
            "ocean=${sum(world.ocean.velocityX.data)},${sum(world.ocean.velocityY.data)}",
            "climate=${sum(world.climate.temperature.data)},${sum(world.climate.precipitation.data)}",
            "rivers=${world.rivers.rivers.size},${sum(world.rivers.flowAccumulation.data)}",
            // Lakes are their own result hanging off the river stage. Leaving them out made
            // the `lakes` case pass while reusing a stale river stage — the check went
            // through the motions without ever looking at what the setting changes.
            "lakes=${world.rivers.lakes.lakes.size},${world.rivers.lakes.lakeId.sum()}",
            "nations=${world.nations.nations.size},${world.nations.nationId.sum()}",
            "landmarks=${world.landmarks.landmarks.size}"
        ).joinToString("\n         ")
    }
}
