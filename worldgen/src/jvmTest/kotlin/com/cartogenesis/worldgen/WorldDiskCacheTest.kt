package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The test tiers' world cache, shown handing back exactly the world that was generated, and shared
 * safely by every checkout on the machine.
 *
 * Every test here works in a directory of its own, never in the build's cache, and on a cache with a
 * fingerprint of its own, so nothing here depends on or disturbs what the tiers have stored. A
 * fingerprint stands for one checkout's code: two caches with the same one are two checkouts of the
 * same commit, and two with different ones are worktrees with different code side by side.
 */
class WorldDiskCacheTest {

    private val directory: File = Files.createTempDirectory("world-cache-test").toFile()

    @AfterTest
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    /**
     * The exactness guard: a standard world generated fresh, stored, and read back by another cache
     * on the same directory, compared branch by branch through every array and list it holds. A
     * world in the cache that differed by one bit from the generator's would change a test's figure
     * without anything else changing, so this is what the cache's whole use rests on.
     */
    @Test
    fun `a world read from the cache equals the fresh one digest by digest`() {
        val fresh = WorldGenerationEngine.generateBlocking(standardConfig)
        val freshDigests = ReachableState.digestsByBranch(fresh)
        cache().obtain(standardConfig) { fresh }

        val read = cache().obtain(standardConfig) { fail("the world was on disk and was generated instead") }

        assertTrue(read.readFromDisk)
        val readDigests = ReachableState.digestsByBranch(read.world)
        assertEquals(freshDigests.keys.toList(), readDigests.keys.toList(), "the same branches in the same order")
        for ((branch, digest) in freshDigests) {
            assertEquals(digest, readDigests[branch], "$branch differs between the fresh and the cached world")
        }
        // The digest reads an array met twice as a reference back to the first; the same number of
        // containers says a shared array was read back shared rather than as two.
        assertEquals(
            ReachableState.mutableContainers(fresh).size,
            ReachableState.mutableContainers(read.world).size,
            "the cached world holds as many arrays, lists and objects as the fresh one"
        )
        assertEquals(fresh.config, read.world.config)
    }

    /**
     * A cached world behaves as the fresh one does when the engine is handed it as `previous`: each
     * stage it may reuse is reused or rebuilt alike, the world made from it is the same, and nothing
     * the engine does writes into the world it was given. The engine decides reuse by identity
     * between results and by equality between settings, so a copy that held its settings or its
     * results in any other shape would show here; and a stage that wrote into a result it reuses
     * would write into a lent world.
     */
    @Test
    fun `the engine reuses a cached world as it reuses a fresh one, and writes into neither`() {
        val fresh = WorldGenerationEngine.generateBlocking(smallConfig)
        // The fresh world itself is stored, not a deep copy of it: the copy makes a fresh list of
        // Kotlin's one empty list, which the file keeps as that one instance, and would show here.
        cache().obtain(smallConfig) { fresh }
        val cached = cache().obtain(smallConfig) { fail("the world was on disk and was generated instead") }.world
        val freshBefore = ReachableState.digestsByBranch(fresh)
        val cachedBefore = ReachableState.digestsByBranch(cached)
        // One setting moved in each section a late stage reads, and one that every stage reads.
        val variants = listOf(
            smallConfig.copy(seaLevel = 0.55f),
            smallConfig.copy(climate = smallConfig.climate.copy(pressureWinds = false)),
            smallConfig.copy(rivers = smallConfig.rivers.copy(coverRaisesChannelHead = false)),
            smallConfig.copy(lakes = smallConfig.lakes.copy(evaporationScale = 0.5f)),
            smallConfig.copy(erosion = smallConfig.erosion.copy(deltaLobe = false))
        )

        for (variant in variants) {
            val fromFresh = WorldGenerationEngine.generateBlocking(variant, previous = fresh)
            val fromCached = WorldGenerationEngine.generateBlocking(variant, previous = cached)

            assertEquals(
                ReachableState.digestsByBranch(fromFresh), ReachableState.digestsByBranch(fromCached),
                "the world made from the cached one differs from the one made from the fresh one"
            )
            val stagesShared = { made: WorldMap, from: WorldMap ->
                listOf(made.terrain === from.terrain, made.plates === from.plates, made.erosion === from.erosion,
                    made.sea === from.sea, made.ocean === from.ocean, made.climate === from.climate,
                    made.rivers === from.rivers, made.nations === from.nations)
            }
            assertEquals(stagesShared(fromFresh, fresh), stagesShared(fromCached, cached), "reused differently")
        }
        assertEquals(freshBefore, ReachableState.digestsByBranch(fresh), "the engine wrote into the fresh world")
        assertEquals(cachedBefore, ReachableState.digestsByBranch(cached), "the engine wrote into the cached world")
    }

    @Test
    fun `a file whose contents changed on disk fails the read rather than lending a different world`() {
        val stored = cache().also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }.fileFor(smallConfig)
        // Flip one bit halfway through the file, which is inside the per-cell arrays that make up
        // nearly all of it.
        RandomAccessFile(stored, "rw").use { file ->
            val position = file.length() / 2
            file.seek(position)
            val byte = file.read()
            file.seek(position)
            file.write(byte xor 1)
        }

        val refused = assertFailsWith<IllegalStateException> { cache().obtain(smallConfig) { fail("read, not made") } }
        assertTrue("differs from the one generated" in refused.message!!, refused.message)
    }

    @Test
    fun `a file cut short is deleted and the world generated again`() {
        val stored = cache().also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }.fileFor(smallConfig)
        RandomAccessFile(stored, "rw").use { it.setLength(it.length() / 2) }

        val again = cache().obtain(smallConfig) { ReachableState.deepCopy(small) }

        assertFalse(again.readFromDisk)
        assertEquals(ReachableState.digestsByBranch(small), ReachableState.digestsByBranch(again.world))
        assertTrue(cache().obtain(smallConfig) { fail("stored again, so read") }.readFromDisk)
    }

    @Test
    fun `two workers asking for the same world at once generate it once`() {
        val generations = AtomicInteger()
        val bothAsking = CountDownLatch(2)
        val generate: (WorldGenConfig) -> WorldMap = {
            generations.incrementAndGet()
            Thread.sleep(200)
            ReachableState.deepCopy(small)
        }
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<WorldDiskCache.Obtained> {
                bothAsking.countDown()
                bothAsking.await()
                // A cache each, as two workers would have, sharing only the directory.
                cache().obtain(smallConfig, generate)
            }
        }.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, generations.get())
        assertEquals(1, results.count { it.readFromDisk })
        results.forEach {
            assertEquals(ReachableState.digestsByBranch(small), ReachableState.digestsByBranch(it.world))
        }
        assertEquals(emptyList(), directory.walkTopDown().filter { it.name.endsWith(".partial") }.toList())
    }

    @Test
    fun `a change to the generator misses every world stored before it`() {
        cache(fingerprint = "generator-before").obtain(smallConfig) { ReachableState.deepCopy(small) }

        val after = cache(fingerprint = "generator-after").obtain(smallConfig) { ReachableState.deepCopy(small) }

        assertFalse(after.readFromDisk)
    }

    @Test
    fun `a setting changed is a different world, and the seed and grid are in the name`() {
        val variant = smallConfig.copy(facetRouting = false)
        val cache = cache()

        assertTrue(cache.fileFor(variant) != cache.fileFor(smallConfig))
        assertTrue(cache.fileFor(variant).name.startsWith("seed42-256x128-variant-"), cache.fileFor(variant).name)
        assertTrue(cache.fileFor(smallConfig).name.startsWith("seed42-256x128-default-"), cache.fileFor(smallConfig).name)
    }

    @Test
    fun `past its capacity the cache lets the least recently used worlds go`() {
        val probe = cache().also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }.fileFor(smallConfig)
        val oneWorld = probe.length()
        probe.delete()
        val roomForTwo = cache(capacity = oneWorld * 5 / 2)
        val seeds = listOf(1L, 2L, 3L)
        for ((age, seed) in seeds.withIndex()) {
            val config = smallConfig.copy(seed = seed)
            roomForTwo.obtain(config) { ReachableState.deepCopy(small) }
            // A minute apart, oldest first, whatever the file system's clock resolution.
            roomForTwo.fileFor(config).setLastModified(System.currentTimeMillis() - (seeds.size - age) * 60_000L)
        }

        val kept = seeds.filter { roomForTwo.fileFor(smallConfig.copy(seed = it)).isFile }
        assertEquals(listOf(2L, 3L), kept)
    }

    @Test
    fun `past its capacity a variant goes before a standard world, however recently it was used`() {
        val probe = cache().also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }.fileFor(smallConfig)
        val oneWorld = probe.length()
        probe.delete()
        val roomForTwo = cache(capacity = oneWorld * 5 / 2)
        val standard = smallConfig.copy(seed = 1L)
        val variants = listOf(smallConfig.copy(seed = 2L, facetRouting = false), smallConfig.copy(seed = 3L, facetRouting = false))
        for ((age, config) in (listOf(standard) + variants).withIndex()) {
            roomForTwo.obtain(config) { ReachableState.deepCopy(small) }
            roomForTwo.fileFor(config).setLastModified(System.currentTimeMillis() - (3 - age) * 60_000L)
        }

        assertTrue(roomForTwo.fileFor(standard).isFile, "the standard world, the oldest, was let go")
        assertFalse(roomForTwo.fileFor(variants[0]).isFile, "the older variant was kept")
        assertTrue(roomForTwo.fileFor(variants[1]).isFile, "the variant just stored was let go")
    }

    /**
     * Two writers of the same world in two JVMs, as two checkouts of one commit are, while a third
     * checkout with other code stores a world and so runs an eviction over the whole directory. The
     * second writer must wait on the first's lock and read what it wrote. Before the cache was shared,
     * an eviction removed every generator's directory that held no world yet, the lock file of a
     * generation under way with it, and a second writer then locked a new file of the same name and
     * generated beside the first: a cache of each checkout's own never met another code's eviction.
     */
    @Test
    fun `two checkouts of one code generate a world once, while a checkout of other code evicts`() {
        // Made first, so that everything this checkout does below falls inside the other's hold.
        val expected = ReachableState.digestsByBranch(small)
        val generating = File(directory, "second-checkout-generating")
        val java = ProcessHandle.current().info().command().orElse(File(System.getProperty("java.home"), "bin/java").path)
        val secondCheckout = ProcessBuilder(
            java, "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"),
            WorldDiskCacheSecondCheckout::class.java.name, directory.path, SHARED_CODE, generating.path
        ).redirectErrorStream(true).start()
        val output = StringBuffer()
        val echo = thread { secondCheckout.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECOND_CHECKOUT_TIMEOUT_SECONDS)
        while (!generating.exists()) {
            check(secondCheckout.isAlive) { "the second checkout stopped before generating:\n$output" }
            check(System.nanoTime() < deadline) { "the second checkout never started generating:\n$output" }
            Thread.sleep(50)
        }

        cache(fingerprint = "other-code").obtain(smallConfig.copy(seed = 7L)) { ReachableState.deepCopy(small) }
        val sharedCode = cache(fingerprint = SHARED_CODE)
        assertTrue(
            File(sharedCode.fileFor(smallConfig).path + ".lock").isFile,
            "another code's eviction took the lock of a generation under way"
        )
        val mine = sharedCode.obtain(smallConfig) { fail("generated beside the other checkout rather than waiting for it") }

        assertTrue(secondCheckout.waitFor(SECOND_CHECKOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the second checkout never finished:\n$output")
        echo.join()
        assertEquals(0, secondCheckout.exitValue(), output.toString())
        assertTrue("GENERATED" in output, output.toString())
        assertTrue(mine.readFromDisk)
        assertEquals(expected, ReachableState.digestsByBranch(mine.world))
    }

    /**
     * A reader in one checkout holds a world open while another checkout's eviction wants it gone,
     * as the least recently used world in the cache. The reader still reads every byte that was
     * there: Windows refuses the deletion while the file is open, and elsewhere the deletion takes
     * only the name. Once the reader is done, the next eviction lets the world go.
     */
    @Test
    fun `a reader holding a world reads all of it while another checkout's eviction wants it gone`() {
        val oneWorld = oneWorldBytes()
        val held = cache(fingerprint = "reading-code").also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }
            .fileFor(smallConfig)
        held.setLastModified(System.currentTimeMillis() - 10 * MINUTE_MILLIS)
        val whole = held.readBytes()
        val roomForOne = cache(fingerprint = "evicting-code", capacity = oneWorld * 3 / 2)

        held.inputStream().use { reading ->
            val firstHalf = reading.readNBytes(whole.size / 2)
            roomForOne.obtain(smallConfig.copy(seed = 7L)) { ReachableState.deepCopy(small) }
            assertContentEquals(whole, firstHalf + reading.readAllBytes(), "the eviction cut the reader's world short")
        }

        roomForOne.obtain(smallConfig.copy(seed = 8L)) { ReachableState.deepCopy(small) }
        assertFalse(held.isFile, "the world nobody reads any more was kept past the cap")
    }

    /**
     * Recency decides across every checkout's code: a standard world of code since changed goes
     * before a variant this code used more recently. Only this code's own standard worlds wait until
     * nothing else is left. Before the cache was shared, every standard world waited, which in a
     * shared cache would keep every commit's standard worlds over the variants of the code running.
     */
    @Test
    fun `past its capacity a stranded code's standard world goes before a variant this code used since`() {
        val roomForTwo = oneWorldBytes() * 5 / 2
        val stranded = cache(fingerprint = "code-before", capacity = roomForTwo)
        val running = cache(fingerprint = "code-after", capacity = roomForTwo)
        val strandedStandard = smallConfig.copy(seed = 1L)
        val runningVariant = smallConfig.copy(seed = 2L, facetRouting = false)
        val runningStandard = smallConfig.copy(seed = 3L)
        stranded.obtain(strandedStandard) { ReachableState.deepCopy(small) }
        stranded.fileFor(strandedStandard).setLastModified(System.currentTimeMillis() - 3 * MINUTE_MILLIS)
        running.obtain(runningVariant) { ReachableState.deepCopy(small) }
        running.fileFor(runningVariant).setLastModified(System.currentTimeMillis() - 2 * MINUTE_MILLIS)

        running.obtain(runningStandard) { ReachableState.deepCopy(small) }

        assertFalse(stranded.fileFor(strandedStandard).isFile, "the stranded code's standard world, the oldest, was kept")
        assertTrue(running.fileFor(runningVariant).isFile, "this code's variant went before an older world")
        assertTrue(running.fileFor(runningStandard).isFile, "the world just stored was let go")
    }

    @Test
    fun `a hit marks the world used, whatever the file system keeps of access times`() {
        val cache = cache().also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }
        val stored = cache.fileFor(smallConfig)
        stored.setLastModified(System.currentTimeMillis() - 10 * WorldDiskCache.MILLIS_PER_DAY)

        cache.obtain(smallConfig) { fail("read, not made") }

        assertTrue(System.currentTimeMillis() - stored.lastModified() < 10 * MINUTE_MILLIS, "the hit left the world looking unused")
    }

    /**
     * At its first use a cache removes every other code's directory in which nothing has been read,
     * written or locked for the stale age, and keeps one a generation is under way in though it
     * holds no world yet. Before the cache was shared nothing went by age, so a commit's worlds
     * stayed until the cap pushed them out; and every directory holding no world went at each
     * store, a generation's lock with it.
     */
    @Test
    fun `at its first use a cache removes every other code's directory unused for the stale age`() {
        val layout = staleLayout(directory)

        cache(fingerprint = "running-code").obtain(smallConfig) { ReachableState.deepCopy(small) }

        assertEquals(layout.kept + "running-code", directory.list()!!.sorted(), "the generators' directories left")
    }

    @Test
    fun `clearWorldCacheStale removes what a cache's first use would`() {
        val layout = staleLayout(directory)

        val removed = WorldDiskCache.removeStaleGenerators(directory, STALE_AFTER_MILLIS)

        assertEquals(layout.stale, removed.map { it.name }.sorted())
        assertEquals(layout.kept, directory.list()!!.sorted())
    }

    /**
     * Where the cache lives: the build's `-PworldCacheDirectory` first, then the environment's
     * `CARTOGENESIS_WORLD_CACHE`, then `.cartogenesis/world-cache` under the user's home, the same
     * for every checkout. Before, the build put it under each checkout's own `build/`, so every
     * checkout filled its own to the cap.
     */
    @Test
    fun `every checkout shares the cache under the user's home unless the build or the environment names another`() {
        val home = File(directory, "home").path
        val named = File(directory, "named").path
        val fromEnvironment = File(directory, "from-environment").path

        assertEquals(File(home, ".cartogenesis/world-cache"), WorldDiskCache.sharedDirectory(null, null, home))
        assertEquals(File(fromEnvironment), WorldDiskCache.sharedDirectory(null, fromEnvironment, home))
        assertEquals(File(named), WorldDiskCache.sharedDirectory(named, fromEnvironment, home))
        assertEquals(File(home, ".cartogenesis/world-cache"), WorldDiskCache.sharedDirectory(" ", "", home), "a blank name is no name")

        // And as the build configured this JVM, when it named no other place.
        val configured = WorldDiskCache.fromSystemProperties()
        if (configured != null && System.getProperty(WorldDiskCache.DIRECTORY_PROPERTY) == null &&
            System.getenv(WorldDiskCache.DIRECTORY_ENVIRONMENT) == null
        ) {
            assertEquals(File(System.getProperty("user.home"), ".cartogenesis/world-cache"), configured.directory)
        }
    }

    /** The bytes of one small world's file, measured on a cache of its own and then removed. */
    private fun oneWorldBytes(): Long {
        val probe = cache(fingerprint = "probe-code").also { it.obtain(smallConfig) { ReachableState.deepCopy(small) } }
        return probe.fileFor(smallConfig).length().also { File(directory, "probe-code").deleteRecursively() }
    }

    private fun cache(fingerprint: String = "generator-under-test", capacity: Long = Long.MAX_VALUE) =
        WorldDiskCache(directory, capacity, STALE_AFTER_MILLIS) { fingerprint }

    /** Which generators' directories [staleLayout] made stale and which in use, by name. */
    private class StaleLayout(val stale: List<String>, val kept: List<String>)

    /**
     * Generators' directories under [directory], aged against [STALE_AFTER_MILLIS]: one whose files
     * were all last used four days ago, one empty for four days, one whose world was read two days
     * ago though its lock is six days old, and one holding only the lock of a generation under way.
     */
    private fun staleLayout(directory: File): StaleLayout {
        val now = System.currentTimeMillis()
        val day = WorldDiskCache.MILLIS_PER_DAY
        fun generator(name: String, vararg filesAged: Pair<String, Long>) = File(directory, name).also { generator ->
            generator.mkdirs()
            for ((file, ageMillis) in filesAged) File(generator, file).apply { writeText(name) }.setLastModified(now - ageMillis)
        }
        generator("stale-code", "seed1-128x64-default-0.world" to 4 * day, "seed1-128x64-default-0.world.lock" to 5 * day)
        generator("empty-code").setLastModified(now - 4 * day)
        generator("recent-code", "seed1-128x64-default-0.world" to 2 * day, "seed2-128x64-default-0.world.lock" to 6 * day)
        generator("generating-code", "seed1-128x64-default-0.world.lock" to 0L)
        return StaleLayout(stale = listOf("empty-code", "stale-code"), kept = listOf("generating-code", "recent-code"))
    }

    private companion object {
        /** A standard world: a standard seed at default settings, at the coarse grid's rows. */
        val standardConfig = WorldGenConfig.forRows(42L, SharedWorlds.COARSE_ROWS)

        val small: WorldMap by lazy { WorldGenerationEngine.generateBlocking(smallConfig) }

        const val MINUTE_MILLIS = 60_000L

        /** The build's default, `-PworldCacheStaleDays` 3. */
        const val STALE_AFTER_MILLIS = 3 * WorldDiskCache.MILLIS_PER_DAY

        /** The code both checkouts of the two-writer guard run. */
        const val SHARED_CODE = "shared-code"

        /** A JVM's start and a small world's generation take seconds; this is for a loaded machine. */
        const val SECOND_CHECKOUT_TIMEOUT_SECONDS = 180L
    }
}

/** Small enough to generate in a second, for the tests of the cache's mechanics. */
private val smallConfig = WorldGenConfig.forRows(42L, 128)

/**
 * The second checkout of the two-writer guard, in a JVM of its own as another worktree's test worker
 * is: it obtains [smallConfig] from the directory and code it is given, marks when it holds the lock
 * and is generating, and holds it for [HOLD_MILLIS] before it generates, so that the first checkout
 * asks while it holds it.
 */
object WorldDiskCacheSecondCheckout {
    private const val HOLD_MILLIS = 3_000L

    @JvmStatic
    fun main(arguments: Array<String>) {
        val (directory, fingerprint, generatingMark) = arguments
        val cache = WorldDiskCache(File(directory), Long.MAX_VALUE, 3 * WorldDiskCache.MILLIS_PER_DAY) { fingerprint }
        val obtained = cache.obtain(smallConfig) { config ->
            File(generatingMark).createNewFile()
            Thread.sleep(HOLD_MILLIS)
            WorldGenerationEngine.generateBlocking(config)
        }
        println(if (obtained.readFromDisk) "READ" else "GENERATED")
    }
}
