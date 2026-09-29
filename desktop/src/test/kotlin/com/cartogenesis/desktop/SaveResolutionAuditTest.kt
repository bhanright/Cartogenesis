package com.cartogenesis.desktop

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldCodec
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
import com.sun.management.GarbageCollectionNotificationInfo
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The two largest working resolutions the panel offers save and open, and what each costs in memory.
 *
 * Audit III's F-D3: the largest world could not be saved at all — the payload's size was summed in
 * an `Int`, which 2.45 GB of arrays wrapped negative — and a large save was built as one 612 MB
 * array before its compressed copy was made beside it. The codec now streams a chunk at a time;
 * this is the proof at the two sizes where it matters, through the real desktop library and the
 * real gzip. Sizes are named by their rows, on grids of square cells twice as many across.
 *
 * 2048 rows (4096 by 2048) is a real world. 4096 rows (8192 by 4096, 146 bytes a cell, 4.9 GB of
 * arrays) is a synthetic one, every array its full size, because a generated one is a quarter of
 * an hour and a save does not care what the numbers in its arrays are; the format's round trip on
 * real data is `WorldCodecTest`'s, on every merge.
 *
 * The saved world is let go before the save is opened and what comes back is checked against
 * digests of its arrays, so only one world is held at a time: two 4096-row worlds would be 9.8 GB,
 * more than this tier's 10 GB heap holds beside the codec's work. The audit tier's, and not the
 * per-merge suite's, for the heap.
 */
class SaveResolutionAuditTest {

    @Test
    fun `a 2048 world saves and opens whole`() {
        measure { SharedWorlds.world(WorldGenConfig.forRows(seed = 42L, rows = 2048)) }
    }

    @Test
    fun `a 4096 world saves and opens whole`() {
        measure { synthetic(WorldGenConfig.forRows(seed = 4096L, rows = 4096)) }
    }

    /** What must come back: the arrays' digests and the settings, taken before the world is let go. */
    private class Expected(val config: WorldGenConfig, val heights: String, val biomes: String, val lakes: String)

    private fun measure(make: () -> WorldMap) {
        val folder = Files.createTempDirectory("cartogenesis-large-save").toFile()
        try {
            val store = DesktopWorldStore(folder, GzipCompressor, "an audit")
            var world: WorldMap? = make()
            val size = "${world!!.config.height} rows (${world.width}x${world.height})"
            val document = WorldDocument(id = "large", title = "Large", config = world.config, savedAt = 1L)

            val beforeSave = settledHeap()
            val saving = sampled { runBlocking { store.save(document, world!!) } }
            val expected = Expected(
                world.config, digest(world.terrain.height.data), digest(world.climate.biome), digest(world.rivers.lakes.lakeId)
            )
            val file = File(folder, "large.cgw")
            val front = file.inputStream().use { it.readNBytes(HEADER_READ_BYTES) }
            val header = WorldCodec.decodeHeader(front)
            val headerLength = java.nio.ByteBuffer.wrap(front, WorldCodec.HEADER_LENGTH_OFFSET, Int.SIZE_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            println(
                "SAVE $size: header $headerLength bytes, lists ${header.sections.first().count} bytes, " +
                    "payload ${header.payloadBytes} bytes expanded"
            )
            println(
                "SAVE $size: ${file.length() / MEBIBYTE} MB on disk in ${saving.millis / 1000.0} s; heap above the " +
                    // With no collection during the save there is no live reading to give.
                    "world " + (if (saving.collections == 0) "unread, no collection ran" else
                        "${(saving.livePeak - beforeSave) / MEBIBYTE} MB at most after any of ${saving.collections} collections") +
                    ", ${(saving.sampledPeak - beforeSave) / MEBIBYTE} MB at the " +
                    "sampled peak counting garbage, ${(settledHeap() - beforeSave) / MEBIBYTE} MB once settled"
            )
            // Let the saved world go, so the one opened is the only one held. A shared world stays
            // with its lender, which is a 2048-row world and fits beside its copy.
            world = null

            val beforeOpen = settledHeap()
            var opened: LoadOutcome? = null
            val opening = sampled { opened = runBlocking { store.load("large.cgw") } }
            val loaded = assertIs<LoadOutcome.Loaded>(opened, "the $size save did not open").save.world
            val retained = settledHeap() - beforeOpen
            println(
                "OPEN $size: ${opening.millis / 1000.0} s; heap above what was held before " +
                    "${(opening.livePeak - beforeOpen) / MEBIBYTE} MB at most after any of ${opening.collections} " +
                    "collections, ${(opening.sampledPeak - beforeOpen) / MEBIBYTE} MB at the sampled peak counting " +
                    "garbage, ${retained / MEBIBYTE} MB once settled (the opened world itself)"
            )

            assertEquals(expected.heights, digest(loaded.terrain.height.data), "the heights did not come back")
            assertEquals(expected.biomes, digest(loaded.climate.biome), "the biomes did not come back")
            assertEquals(expected.lakes, digest(loaded.rivers.lakes.lakeId), "the lakes did not come back")
            assertEquals(expected.config, loaded.config)
        } finally {
            folder.deleteRecursively()
        }
    }

    /** SHA-256 of [values]' bits, a mebibyte at a time. */
    private fun digest(values: FloatArray): String = digestOf(values.size) { buffer, at -> buffer.putFloat(values[at]) }

    private fun digest(values: IntArray): String = digestOf(values.size) { buffer, at -> buffer.putInt(values[at]) }

    private fun digest(values: Array<Biome>): String = digestOf(values.size) { buffer, at -> buffer.putInt(values[at].ordinal) }

    private fun digestOf(count: Int, put: (java.nio.ByteBuffer, Int) -> Unit): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(MEBIBYTE.toInt())
        for (at in 0 until count) {
            if (buffer.remaining() < Int.SIZE_BYTES) {
                sha.update(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            put(buffer, at)
        }
        sha.update(buffer.array(), 0, buffer.position())
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** Heap in use once a collection has had its chance, which is as near the live set as a JVM says. */
    private fun settledHeap(): Long {
        repeat(SETTLING_COLLECTIONS) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * What [work] cost: how long it took; the most heap in use after any collection while it ran,
     * which is as near its live peak as a JVM will say; how many collections that was taken over;
     * and the most heap in use at any sample, garbage and all, which is an upper bound.
     */
    private class Cost(val millis: Long, val livePeak: Long, val collections: Int, val sampledPeak: Long)

    private fun sampled(work: () -> Unit): Cost {
        val sampledPeak = AtomicLong(0L)
        val livePeak = AtomicLong(0L)
        val collections = AtomicLong(0L)
        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.map { it.name }.toSet()
        val listener = NotificationListener { notification, _ ->
            if (notification.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                val after = info.gcInfo.memoryUsageAfterGc.filterKeys { it in heapPools }.values.sumOf { it.used }
                livePeak.accumulateAndGet(after, ::maxOf)
                collections.incrementAndGet()
            }
        }
        val emitters = ManagementFactory.getGarbageCollectorMXBeans().map { it as NotificationEmitter }
        emitters.forEach { it.addNotificationListener(listener, null, null) }
        val running = AtomicBoolean(true)
        val runtime = Runtime.getRuntime()
        val sampler = Thread {
            while (running.get()) {
                sampledPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Thread.sleep(SAMPLE_MILLIS)
            }
        }.apply { isDaemon = true; start() }
        val started = System.currentTimeMillis()
        try {
            work()
        } finally {
            running.set(false)
            sampler.join()
            emitters.forEach { it.removeNotificationListener(listener) }
        }
        return Cost(System.currentTimeMillis() - started, livePeak.get(), collections.get().toInt(), sampledPeak.get())
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

        /** Enough of the front of a save to hold its header, which is ten kilobytes or so. */
        const val HEADER_READ_BYTES = 1 shl 20
        const val SAMPLE_MILLIS = 2L
        const val SETTLING_COLLECTIONS = 3

        /** Long enough that no two neighbouring sections repeat each other, short enough to squeeze. */
        const val RAMP_PERIOD = 4099
    }
}
