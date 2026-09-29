package com.cartogenesis.desktop

import com.cartogenesis.ui.WorldCeilings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the desktop offers 4096 rows where its heap holds the world and 2048 elsewhere, and says
 * what the machine lacks where it does not.
 *
 * The packaged app's heap is three quarters of the machine's memory, so a 32 GB machine has a
 * 24 GB heap and a 16 GB machine a 12 GB one; the heap is handed to the platform here rather than
 * read off this test's own JVM, whose heap is the test budget's and says nothing about a
 * reader's machine. The threshold between the two is derived in
 * `WorldCeilings.HEAP_FOR_LARGEST_DESKTOP_BYTES` from what the 4096 world measured.
 */
class DesktopCeilingTest {

    private val gibibyte = 1L shl 30

    @Test
    fun `a 24 GB heap is offered 4096 rows and a 12 GB heap 2048`() {
        assertEquals(4096, DesktopPlatform(heapBytes = 24 * gibibyte).generationCeiling, "a 32 GB machine's heap")
        assertEquals(2048, DesktopPlatform(heapBytes = 12 * gibibyte).generationCeiling, "a 16 GB machine's heap")
        // A 24 GB machine, whose heap is 18 GB less what the hardware keeps back, is offered it
        // too; the threshold is between the two machines' heaps, not at either.
        assertEquals(4096, DesktopPlatform(heapBytes = 17_900L * (1L shl 20)).generationCeiling, "a 24 GB machine's heap")
        println(
            "CEILING 4096 rows from a heap of ${WorldCeilings.HEAP_FOR_LARGEST_DESKTOP_BYTES / (1L shl 20)} MiB; " +
                "24 GiB -> ${DesktopPlatform(heapBytes = 24 * gibibyte).generationCeiling}, " +
                "12 GiB -> ${DesktopPlatform(heapBytes = 12 * gibibyte).generationCeiling}"
        )
    }

    @Test
    fun `the 4096 chip's reason says what the machine lacks`() {
        val small = DesktopPlatform(heapBytes = 12 * gibibyte)
        val reason = assertNotNull(
            WorldCeilings.whyOutOfReach(4096, small.generationCeiling, small.heapBytes),
            "4096 has no reason on a 12 GB heap"
        )
        println("CEILING reason on a 12 GB heap: $reason")
        assertTrue("12.0 GB" in reason, "the reason does not say what this machine gives the app: $reason")
        assertTrue("17.1 GB" in reason, "the reason does not say what the app needs: $reason")
        assertTrue("23 GB of memory" in reason, "the reason does not say what machine makes it: $reason")
        val large = DesktopPlatform(heapBytes = 24 * gibibyte)
        assertNull(WorldCeilings.whyOutOfReach(4096, large.generationCeiling, large.heapBytes), "a 24 GB heap is refused 4096")
        assertNotNull(
            WorldCeilings.whyOutOfReach(8192, large.generationCeiling, large.heapBytes),
            "8192 rows, twice 4096's cells, is offered"
        )
    }

    @Test
    fun `the packaged heap's share is the one the ceiling reckons with`() {
        val script = File(repoRoot(), "desktop/build.gradle.kts").readText()
        val percent = Regex("""-XX:MaxRAMPercentage=(\d+)""").find(script)?.groupValues?.get(1)?.toInt()
        assertEquals(
            (WorldCeilings.PACKAGED_HEAP_SHARE_OF_MEMORY * 100).toInt(), percent,
            "the packaged app's heap share in desktop/build.gradle.kts is not the one WorldCeilings derives 4096's threshold from"
        )
    }

    private fun repoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").exists()) directory = directory.parentFile
        return directory ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }
}
