package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders at the sizes that motivated the desktop build.
 *
 * 4096 is the one that matters: on Android it reliably died allocating a single 134MB FFT buffer
 * against a ~600MB ceiling. Here it should simply work, and this records how long it takes and
 * how big the result is.
 */
class ExportSmokeTest {

    @Test
    fun `render at sizes Android could not reach`() {
        val outputDir = File("build/exports").apply { mkdirs() }
        val base = WorldGenConfig(seed = 42L, width = 1024, height = 1024)

        listOf(2048, 4096).forEach { size ->
            val destination = File(outputDir, Exporter.defaultName(base, size))
            val result = Exporter.export(base, RenderOptions(), size, destination)

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
