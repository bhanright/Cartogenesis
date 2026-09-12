package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders at the largest sizes the desktop build exists for.
 *
 * 4096 is the one that matters: it wants roughly 2GB, and generating a world at that size is
 * minutes of work before anything is drawn. Split out of `ExportSmokeTest` in T1 so the per-merge
 * suite keeps a fast 1024 export as its smoke check while this — the on-demand / nightly audit
 * tier — still proves the sizes the app actually ships.
 */
class ExportAuditTest {

    @Test
    fun `render at the sizes the desktop build exists for`() {
        val outputDir = File("build/exports").apply { mkdirs() }
        val base = WorldGenConfig(seed = 42L, width = 1024, height = 1024)

        listOf(2048, 4096).forEach { size ->
            val destination = File(outputDir, Exporter.defaultName(base, size, ExportFormat.PNG))
            val result = runBlocking {
                Exporter.export(base, RenderOptions(), size, destination, ExportFormat.PNG)
            }

            println(
                "EXPORT %d x %d -> %.1f MB in %.1f s".format(
                    size, size, result.bytes / 1024.0 / 1024.0, result.millis / 1000.0
                )
            )
            println("EXPORT   peak heap: ${peakHeapMb()} MB")
            assertTrue(result.bytes > 0, "wrote an empty file at $size")
        }
    }

    private fun peakHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }
}
