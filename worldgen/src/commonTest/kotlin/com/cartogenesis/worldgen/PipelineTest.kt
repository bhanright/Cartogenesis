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

        // Terrain away from plate boundaries, measured as slope per unit of map width rather than
        // per cell. This is the part of the world that should be pure scale-consistent noise, so
        // it is the cleanest read on whether the larger grid is the same world in more detail.
        // Leaving the mountain-belt falloff in absolute cells flattens it noticeably.
        val previewSlope = meanSlopeAwayFromBoundaries(preview)
        val exportedSlope = meanSlopeAwayFromBoundaries(exported)
        val ratio = exportedSlope / previewSlope
        assertTrue(
            ratio in 0.78..1.3,
            "slope away from plate boundaries changed ${round2(ratio)}x " +
                "(${round2(previewSlope)} -> ${round2(exportedSlope)})"
        )
    }

    /** String.format is JVM-only, so shared tests round by hand. */
    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100.0

    private fun meanSlopeAwayFromBoundaries(world: com.cartogenesis.worldgen.model.WorldMap): Double {
        val e = world.sea.relativeElevation
        val nearRadius = world.width * 0.03f
        var total = 0.0
        var count = 0
        for (y in 1 until world.height - 1) {
            for (x in 1 until world.width - 1) {
                val i = y * world.width + x
                if (!world.sea.isLand[i]) continue
                if (world.plates.boundaryDistance.data[i] < nearRadius) continue
                val dx = e.sample(x + 1, y) - e.sample(x - 1, y)
                val dy = e.sample(x, y + 1) - e.sample(x, y - 1)
                total += kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) * world.width
                count++
            }
        }
        return if (count == 0) 0.0 else total / count
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
