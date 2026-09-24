package com.cartogenesis.desktop

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.ErosionResult
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.NormalField
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateType
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.TerrainResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Every working resolution the panel offers saves and opens, and what each costs in memory.
 *
 * Audit III's F-D3: a 4096 world could not be saved at all — the payload's size was summed in an
 * `Int`, which 2.45 GB of arrays wrapped negative — and a 2048 save was built as one 612 MB array
 * before its compressed copy was made beside it. The codec now streams a chunk at a time; this is
 * the proof at the two sizes where it mattered, through the real desktop library and the real gzip.
 *
 * 2048 is a real world. 4096 is a synthetic one, every array its full size, because a generated
 * 4096 world is the better part of half an hour and a save does not care what the numbers in its
 * arrays are; the format's round trip on real data is `WorldCodecTest`'s, on every merge.
 *
 * The audit tier's, and not the per-merge suite's, for the heap: two 4096 worlds are five
 * gigabytes between them, which the per-merge worker does not have.
 */
class SaveResolutionAuditTest {

    @Test
    fun `a 2048 world saves and opens whole`() {
        measure(SharedWorlds.world(WorldGenConfig(seed = 42L, width = 2048, height = 2048)))
    }

    @Test
    fun `a 4096 world saves and opens whole`() {
        measure(synthetic(WorldGenConfig(seed = 4096L, width = 4096, height = 4096)))
    }

    private fun measure(world: WorldMap) {
        val folder = Files.createTempDirectory("cartogenesis-large-save").toFile()
        try {
            val store = DesktopWorldStore(folder, GzipCompressor, "an audit")
            val document = WorldDocument(id = "large", title = "Large", config = world.config, savedAt = 1L)
            val size = "${world.width}x${world.height}"

            val beforeSave = settledHeap()
            val (saveMillis, savePeak) = sampled { runBlocking { store.save(document, world) } }
            val file = File(folder, "large.cgw")
            println(
                "SAVE $size: ${file.length() / MEBIBYTE} MB on disk in ${saveMillis / 1000.0} s; heap above the " +
                    "world ${(savePeak - beforeSave) / MEBIBYTE} MB at the sampled peak, " +
                    "${(settledHeap() - beforeSave) / MEBIBYTE} MB once settled"
            )

            val beforeOpen = settledHeap()
            var opened: LoadOutcome? = null
            val (openMillis, openPeak) = sampled { opened = runBlocking { store.load("large.cgw") } }
            val loaded = assertIs<LoadOutcome.Loaded>(opened, "the $size save did not open").save.world
            val retained = settledHeap() - beforeOpen
            println(
                "OPEN $size: ${openMillis / 1000.0} s; heap above the first world ${(openPeak - beforeOpen) / MEBIBYTE} MB " +
                    "at the sampled peak, ${retained / MEBIBYTE} MB once settled (the opened world itself)"
            )

            assertTrue(loaded.terrain.height.data.contentEquals(world.terrain.height.data), "the heights did not come back")
            assertTrue(loaded.climate.biome.contentEquals(world.climate.biome), "the biomes did not come back")
            assertTrue(loaded.rivers.lakes.lakeId.contentEquals(world.rivers.lakes.lakeId), "the lakes did not come back")
            assertEquals(world.config, loaded.config)
        } finally {
            folder.deleteRecursively()
        }
    }

    /** Heap in use once a collection has had its chance, which is as near the live set as a JVM says. */
    private fun settledHeap(): Long {
        repeat(SETTLING_COLLECTIONS) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * How long [work] took, and the most heap in use at any sample taken while it ran — an upper
     * bound on what it held, since garbage not yet collected is counted too.
     */
    private fun sampled(work: () -> Unit): Pair<Long, Long> {
        val peak = AtomicLong(0L)
        val running = AtomicBoolean(true)
        val runtime = Runtime.getRuntime()
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Thread.sleep(SAMPLE_MILLIS)
            }
        }.apply { isDaemon = true; start() }
        val started = System.currentTimeMillis()
        try {
            work()
        } finally {
            running.set(false)
            sampler.join()
        }
        return (System.currentTimeMillis() - started) to peak.get()
    }

    /**
     * A world with every array at its full size and nothing in it but valid values: one plate,
     * no rivers, lakes, realms, peoples or landmarks, and ramps in the fields so a section read
     * into the wrong place shows.
     */
    private fun synthetic(config: WorldGenConfig): WorldMap {
        val cells = config.width * config.height
        var ramp = 0
        fun field(): FloatField {
            val start = ++ramp
            return FloatField(config.width, config.height, FloatArray(cells) { ((it + start) % RAMP_PERIOD) * 0.001f })
        }
        val none = { IntArray(cells) { -1 } }
        return WorldMap(
            config = config,
            terrain = TerrainResult(NormalField(field(), field()), field()),
            plates = PlateResult(
                plates = listOf(Plate(0, 0, 0, 0f, 0f, PlateType.OCEANIC)),
                plateId = IntArray(cells),
                boundaryDistance = field(),
                nearestBoundaryType = none(),
                nearestBoundaryClass = none(),
                height = field(),
                seafloorAgeMyr = field(),
                seafloorHalfSpreadingRateKmPerMyr = 25.0,
                continentalShare = field(),
                upliftRateMmPerYear = field(),
                crustAge = field()
            ),
            erosion = ErosionResult(field()),
            sea = SeaLevelResult(0.4f, BooleanArray(cells) { it % 3 == 0 }, field(), (cells + 2) / 3),
            ocean = OceanResult(field(), field(), field(), field()),
            climate = ClimateResult(
                temperature = field(), summerTemperature = field(), winterTemperature = field(),
                precipitation = field(), summerPrecipitation = field(), winterPrecipitation = field(),
                precipitationMm = field(), windDirection = IntArray(cells) { it % 3 - 1 }, windMeridional = field(),
                summerSeaIce = BooleanArray(cells), winterSeaIce = BooleanArray(cells) { it % 7 == 0 },
                biome = Array(cells) { Biome.entries[it % Biome.entries.size] },
                vegetationDensity = field(), permafrost = ByteArray(cells)
            ),
            rivers = RiverResult(
                filledElevation = field(), flowAccumulation = field(), flowTarget = none(), rivers = emptyList(),
                lakes = LakeResult(none(), emptyList(), BooleanArray(cells), config.width)
            ),
            nations = NationResult(none(), emptyList(), field()),
            cultures = CultureResult(none(), emptyList()),
            landmarks = LandmarkResult(emptyList())
        )
    }

    private companion object {
        const val MEBIBYTE = 1L shl 20
        const val SAMPLE_MILLIS = 2L
        const val SETTLING_COLLECTIONS = 3

        /** Long enough that no two neighbouring sections repeat each other, short enough to squeeze. */
        const val RAMP_PERIOD = 4099
    }
}
