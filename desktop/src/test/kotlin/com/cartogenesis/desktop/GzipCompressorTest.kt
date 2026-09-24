package com.cartogenesis.desktop

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The desktop's gzip stops expanding one byte past the limit it is given, and holds no more than
 * that while it does.
 *
 * A save's chunk says how long it expands to, and a crafted one can say a mebibyte and hold a
 * stream that expands to hundreds. The codec refuses any chunk that comes back longer than its
 * frame promised; this is the half the codec cannot see, that the decompressor itself stops rather
 * than expanding the whole stream and handing back the first part of it.
 */
class GzipCompressorTest {

    @Test
    fun `a stream that expands far past the limit is read no further than the limit`() = runBlocking {
        val expanded = EXPANDED_MEBIBYTES shl 20
        val bomb = GzipCompressor.compress(ByteArray(expanded))
        assertTrue(bomb.size < expanded / 500, "the stream was meant to squeeze: ${bomb.size} bytes")

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(thread)
        val answer = GzipCompressor.decompress(bomb, LIMIT_BYTES)
        val allocated = threads.getThreadAllocatedBytes(thread) - before

        assertEquals(LIMIT_BYTES + 1, answer.size, "the answer is not the limit and one byte over")
        println("GZIP a $EXPANDED_MEBIBYTES MiB stream read to a ${LIMIT_BYTES shr 20} MiB limit allocated ${allocated shr 10} KiB")
        assertTrue(
            allocated < LIMIT_BYTES + ALLOCATION_ALLOWANCE_BYTES,
            "reading to a ${LIMIT_BYTES shr 20} MiB limit allocated ${allocated shr 20} MiB, as if it expanded the whole stream"
        )
    }

    private companion object {
        /** What the crafted stream expands to: 256 MiB of zeros, which gzip holds in a quarter of a MiB. */
        const val EXPANDED_MEBIBYTES = 256

        /** A chunk's worth, which is the most any frame may promise. */
        const val LIMIT_BYTES = 1 shl 20

        /**
         * What reading may allocate besides the answer: the inflater's buffers and a coroutine's
         * bookkeeping, a few hundred kilobytes at most. Four mebibytes is far below the 256 MiB a
         * decompressor that expanded everything would allocate, and far above anything else.
         */
        const val ALLOCATION_ALLOWANCE_BYTES = 4L shl 20
    }
}
