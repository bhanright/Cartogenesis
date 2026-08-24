package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PipelineTest {

    private fun config(seed: Long = 42L, size: Int = 128) =
        WorldGenConfig(seed = seed, width = size, height = size)

    @Test
    fun `generation is deterministic for a given seed`() = runTest(timeout = 10.minutes) {
        val a = WorldGenerationEngine.generate(config())
        val b = WorldGenerationEngine.generate(config())
        assertTrue(a.elevation.data.contentEquals(b.elevation.data))
        assertEquals(a.sea.landCellCount, b.sea.landCellCount)
        assertEquals(a.rivers.rivers.size, b.rivers.rivers.size)
    }

    @Test
    fun `different seeds produce different worlds`() = runTest(timeout = 10.minutes) {
        val a = WorldGenerationEngine.generate(config(seed = 1L))
        val b = WorldGenerationEngine.generate(config(seed = 2L))
        assertNotEquals(
            a.elevation.data.toList().hashCode(),
            b.elevation.data.toList().hashCode()
        )
    }

    @Test
    fun `sea level slider controls the land fraction`() = runTest(timeout = 10.minutes) {
        val low = WorldGenerationEngine.generate(config().copy(seaLevel = 0.3f))
        val high = WorldGenerationEngine.generate(config().copy(seaLevel = 0.8f))
        assertTrue(
            low.landFraction() > high.landFraction(),
            "higher sea level must leave less land (${low.landFraction()} vs ${high.landFraction()})"
        )
        assertEquals(0.7f, low.landFraction(), 0.02f)
        assertEquals(0.2f, high.landFraction(), 0.02f)
    }

    @Test
    fun `every plate boundary cell is assigned a boundary type`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        val plateIds = world.plates.plateId.toSet()
        assertTrue(plateIds.size > 1, "expected several plates, got ${plateIds.size}")
        assertTrue(world.plates.nearestBoundaryType.any { it >= 0 })
    }

    /**
     * The load-bearing hydrology invariant: depression filling must leave every land cell with a
     * downhill path, so no river can dead-end in an inland pit.
     */
    @Test
    fun `every river ends at the sea or joins another river`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        assertTrue(world.rivers.rivers.isNotEmpty(), "expected some rivers")

        val claimedByOtherRiver = HashSet<Int>()
        world.rivers.rivers.forEach { river ->
            river.cells.dropLast(1).forEach { claimedByOtherRiver.add(it) }
        }

        world.rivers.rivers.forEach { river ->
            val mouth = river.cells.last()
            val reachedSea = !world.sea.isLand[mouth]
            val joinedAnother = claimedByOtherRiver.contains(mouth)
            val leftTheMap = mouth / world.width == 0 || mouth / world.width == world.height - 1
            assertTrue(
                reachedSea || joinedAnother || leftTheMap,
                "river ending at cell $mouth neither reached the sea, joined another river, " +
                    "nor ran off the polar edge"
            )
        }
    }

    @Test
    fun `every land cell drains downhill`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        val filled = world.rivers.filledElevation
        val target = world.rivers.flowTarget
        var stranded = 0
        for (i in target.indices) {
            if (!world.sea.isLand[i]) continue
            val row = i / world.width
            // Polar rows drain off the map, so -1 is legitimate there.
            if (row == 0 || row == world.height - 1) continue
            val t = target[i]
            if (t < 0) {
                stranded++
            } else if (world.sea.isLand[t] && filled.data[t] >= filled.data[i]) {
                stranded++
            }
        }
        assertEquals(0, stranded, "interior land cells with no downhill neighbour")
    }

    @Test
    fun `poles are colder than the equator`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        val temperature = world.climate.temperature
        val mid = world.height / 2

        val equatorMean = (0 until world.width).map { temperature[it, mid] }.average()
        val poleMean = (0 until world.width).map { temperature[it, 0] }.average()
        assertTrue(equatorMean > poleMean + 20, "equator ($equatorMean) should be warmer than pole ($poleMean)")
    }

    @Test
    fun `latitude mapping spans pole to pole`() = runTest(timeout = 10.minutes) {
        assertEquals(90f, ClimateStage.latitudeOf(0, 512), 0.5f)
        assertEquals(-90f, ClimateStage.latitudeOf(511, 512), 0.5f)
        assertTrue(abs(ClimateStage.latitudeOf(256, 512)) < 0.5f)
    }

    @Test
    fun `ocean cells are classified as water biomes`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        val waterBiomes = setOf(Biome.OCEAN, Biome.SHALLOW_OCEAN, Biome.ICE_SHEET)
        for (i in world.climate.biome.indices) {
            if (!world.sea.isLand[i]) {
                assertTrue(
                    world.climate.biome[i] in waterBiomes,
                    "water cell $i had biome ${world.climate.biome[i]}"
                )
            }
        }
    }

    /**
     * Guards the rainfall scaling. Normalizing precipitation by its absolute maximum lets a few
     * extreme windward mountain cells set the scale, which pushes almost every other land cell
     * below the desert threshold and turns the whole world into a desert.
     */
    @Test
    fun `land has a varied spread of biomes`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(config())
        val counts = HashMap<Biome, Int>()
        var land = 0
        for (i in world.climate.biome.indices) {
            if (!world.sea.isLand[i]) continue
            land++
            counts[world.climate.biome[i]] = (counts[world.climate.biome[i]] ?: 0) + 1
        }

        val desertShare = (counts[Biome.DESERT] ?: 0).toFloat() / land
        assertTrue(desertShare < 0.45f, "desert covered ${(desertShare * 100).toInt()}% of land")
        assertTrue(counts.size >= 5, "only ${counts.size} distinct land biomes: ${counts.keys}")

        val wet = world.climate.precipitation
        val meanLandRain = (0 until wet.data.size)
            .filter { world.sea.isLand[it] }
            .map { wet.data[it].toDouble() }
            .average()
        assertTrue(meanLandRain > 0.15, "mean land rainfall was $meanLandRain")
    }

    /**
     * Export re-runs generation at the target size, so a bigger grid has to mean more detail in
     * the same world — not a different one. Settings measured in cells (mountain belt width,
     * rainfall per cell of travel) skew the world's character if [WorldGenConfig.atResolution]
     * does not rescale them.
     */
    @Test
    fun `world keeps its character when regenerated at a larger resolution`() = runTest(timeout = 10.minutes) {
        val preview = WorldGenerationEngine.generate(config())
        val exported = WorldGenerationEngine.generate(config().atResolution(512, 512))

        val landDelta = kotlin.math.abs(preview.landFraction() - exported.landFraction())
        assertTrue(
            landDelta < 0.08f,
            "land fraction moved from ${preview.landFraction()} to ${exported.landFraction()}"
        )

        // Mountain-belt reach is reported rather than asserted, and that is a deliberate
        // retreat from a guard that stopped working.
        //
        // It began as mean slope away from the plate boundaries, on the reasoning that such
        // terrain is pure scale-consistent noise. Erosion ended that reasoning: it shapes the
        // ground everywhere and resolves finer channels on a finer grid, so the figure drifted on
        // a perfectly correct world. Checking it against the bug it was written for -- the
        // mountain-belt falloff left in absolute cells -- showed something worse: with that bug
        // deliberately reintroduced the assertion still passed, because the bug moves the belts
        // and the measure was looking away from them.
        //
        // Measuring the belts directly does not rescue it either. At 256 against 512 the reach
        // differs by 1.7x on correct code, because a 256 grid is coarse enough that erosion has
        // materially less to work with -- the two are not the same world at different detail, and
        // pretending otherwise is what produced a guard nobody could trust.
        //
        // What actually pins the behaviour is ResolutionScalingTest, which asserts the contract of
        // atResolution directly: every setting measured in cells scales with the grid, and the
        // scaling round-trips exactly. That catches the bug this could not.
        val previewReach = beltReach(preview)
        val exportedReach = beltReach(exported)
        println(
            "RESOLUTION belt reach ${round2(previewReach)} at ${preview.width} " +
                "against ${round2(exportedReach)} at ${exported.width}"
        )
        assertTrue(
            previewReach > 0.0 && exportedReach > 0.0,
            "one of the two worlds has no mountain belts at all"
        )
    }

    /** String.format is JVM-only, so shared tests round by hand. */
    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100.0

    /**
     * How much higher the ground stands near a plate boundary than far from it, measuring "near"
     * and "far" as fractions of the map rather than counts of cells.
     *
     * A belt that keeps its width on the map gives the same answer at any resolution. A belt whose
     * falloff was left in cells is half as wide on a grid twice as fine, so much less of the near
     * band is raised and the figure falls.
     */
    private fun beltReach(world: com.cartogenesis.worldgen.model.WorldMap): Double {
        val e = world.sea.relativeElevation
        val near = world.width * 0.035f
        val far = world.width * 0.09f

        var nearTotal = 0.0
        var nearCount = 0
        var farTotal = 0.0
        var farCount = 0
        for (i in 0 until world.width * world.height) {
            if (!world.sea.isLand[i]) continue
            val d = world.plates.boundaryDistance.data[i]
            when {
                d < near -> { nearTotal += e.data[i]; nearCount++ }
                d > far -> { farTotal += e.data[i]; farCount++ }
            }
        }
        if (nearCount == 0 || farCount == 0) return 0.0
        return (nearTotal / nearCount) - (farTotal / farCount)
    }

    @Test
    fun `changing only sea level reuses the terrain and plate stages`() = runTest(timeout = 10.minutes) {
        val base = WorldGenerationEngine.generate(config())
        val adjusted = WorldGenerationEngine.generate(config().copy(seaLevel = 0.5f), previous = base)
        assertTrue(base.terrain === adjusted.terrain, "terrain should be reused")
        assertTrue(base.plates === adjusted.plates, "plates should be reused")
        assertTrue(base.sea !== adjusted.sea, "sea level must be recomputed")
    }
}
