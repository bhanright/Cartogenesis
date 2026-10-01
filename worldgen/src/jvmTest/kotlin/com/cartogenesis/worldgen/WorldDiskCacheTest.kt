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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The test tiers' world cache, shown handing back exactly the world that was generated.
 *
 * Every test here works in a directory of its own, never in the build's cache, and on a cache with a
 * fingerprint of its own, so nothing here depends on or disturbs what the tiers have stored.
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

    private fun cache(fingerprint: String = "generator-under-test", capacity: Long = Long.MAX_VALUE) =
        WorldDiskCache(directory, capacity) { fingerprint }

    private companion object {
        /** A standard world: a standard seed at default settings, at the coarse grid's rows. */
        val standardConfig = WorldGenConfig.forRows(42L, SharedWorlds.COARSE_ROWS)

        /** Small enough to generate in a second, for the tests of the cache's mechanics. */
        val smallConfig = WorldGenConfig.forRows(42L, 128)

        val small: WorldMap by lazy { WorldGenerationEngine.generateBlocking(smallConfig) }
    }
}
