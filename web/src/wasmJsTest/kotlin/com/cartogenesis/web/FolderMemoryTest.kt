package com.cartogenesis.web

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.WorldDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * What the folder library holds for a save while it passes through: never the file, only a part
 * or two of it, however long the file is.
 *
 * A 2048 save is some two hundred megabytes, and a library that gathered it before writing, or
 * handed the stream part after part without waiting for each to be taken, would hold all of it in
 * the tab. The bound is on the library's own [BufferGauge] rather than on the tab's heap, which moves
 * with the collector and everything else the tab does and cannot be asserted against; the gauge
 * moves only when the library takes or lets go of a part. The parts are made small here so a small
 * world's save is many of them long: the property is the ratio, a bound in parts whatever the size.
 *
 * Shown failing on a sink that gathers the whole save and writes it once at the end: see the
 * ledger's row, docs/DESIGN_LEDGER.md, Fix 2c.
 */
class FolderMemoryTest {

    @Test
    fun `a save many parts long is written and read back holding no more than two parts`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("memory") { folder ->
            val library = FolderWorldLibrary(folder.handle, NoCompression, "a test", SMALL_PART_BYTES)
            val bound = FolderWorldLibrary.BUFFER_BOUND_PARTS.toLong() * SMALL_PART_BYTES
            val document = WorldDocument(id = "memory", title = "Memory", config = world.config, savedAt = 1L)

            val key = library.save(document, world)
            val fileBytes = folder.readRaw(key).size
            val writingPeak = library.gauge.peakBytes
            assertTrue(
                fileBytes > LEAST_PARTS * SMALL_PART_BYTES,
                "the save is $fileBytes bytes, too short to show a bound in parts"
            )
            assertTrue(writingPeak <= bound, "writing a $fileBytes-byte save held $writingPeak bytes at once; the bound is $bound")
            assertTrue(writingPeak >= SMALL_PART_BYTES, "the gauge saw $writingPeak bytes, less than the part it must hold")
            assertEquals(0L, library.gauge.heldBytes, "the write kept hold of a part after it finished")

            library.gauge.resetPeak()
            assertIs<LoadOutcome.Loaded>(library.load(key))
            val readingPeak = library.gauge.peakBytes
            assertTrue(readingPeak <= bound, "reading a $fileBytes-byte save held $readingPeak bytes at once; the bound is $bound")
            assertTrue(readingPeak >= SMALL_PART_BYTES, "the gauge saw $readingPeak bytes, less than the slice it must hold")
            assertEquals(0L, library.gauge.heldBytes, "the read kept hold of a slice after it finished")

            println(
                "FOLDER MEMORY save=${fileBytes}B parts=${(fileBytes + SMALL_PART_BYTES - 1) / SMALL_PART_BYTES} " +
                    "part=${SMALL_PART_BYTES}B bound=${bound}B writingPeak=${writingPeak}B readingPeak=${readingPeak}B"
            )
        }
    }

    private companion object {
        /** Sixteen kibibytes: a 32 world's save, stored raw, is some ten of them. */
        const val SMALL_PART_BYTES = 1 shl 14

        /** More parts than the bound allows, with room: a save this long cannot fit under it whole. */
        const val LEAST_PARTS = 4
    }
}
