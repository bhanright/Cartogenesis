package com.cartogenesis.desktop

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every export the desktop offers, at the two sizes it offers them at.
 *
 * 4096 is the one that matters: it wants roughly 2GB, and generating a world at that size is
 * minutes of work before anything is drawn. Split out of `ExportSmokeTest` in T1 so the per-merge
 * suite keeps a fast 1024 export as its smoke check while this — the on-demand / nightly audit
 * tier — still proves the sizes the app actually ships.
 *
 * One world per size, and the six exports measured from it. Until F12 this ran the whole pipeline
 * again per export, which was affordable when there was one export and is not now that there are
 * six: at 4096 that would be six generations of two hundred seconds apiece to measure six encodes
 * of a few. Generation is timed and reported on its own line, so the figure a reader of the report
 * wants — what one export costs from the button — is still the sum of two printed numbers, and the
 * encode times are no longer buried inside it. `ExportSmokeTest` and `DataExportTest` exercise the
 * whole path through `Exporter` at 1024 and 512.
 */
class ExportAuditTest {

    @Test
    fun `every export at the sizes the desktop build exists for`() {
        val outputDir = File("build/exports").apply { mkdirs() }
        val base = WorldGenConfig(seed = 42L, width = 1024, height = 1024)

        listOf(2048, 4096).forEach { size ->
            val startedGeneration = System.currentTimeMillis()
            val world = WorldGenerationEngine.generateBlocking(base.atResolution(size, size))
            val generationMillis = System.currentTimeMillis() - startedGeneration
            println("EXPORT %d x %d generation %.1f s".format(size, size, generationMillis / 1000.0))

            val startedRaster = System.currentTimeMillis()
            val pixels = MapRasterizer.rasterize(world, RenderOptions())
            val bitmap = MapImage.toBitmap(world, RenderOptions(), pixels)
            println(
                "EXPORT %d x %d raster %.1f s".format(
                    size, size, (System.currentTimeMillis() - startedRaster) / 1000.0
                )
            )

            ExportFormat.entries.forEach { format ->
                val destination = File(outputDir, Exporter.defaultName(world.config, size, format))
                val started = System.currentTimeMillis()
                val bytes = if (format == ExportFormat.JPEG) {
                    Exporter.encodeJpeg(bitmap, ExportFormat.JPEG_QUALITY)
                } else {
                    org.jetbrains.skia.Image.makeFromBitmap(bitmap)
                        .encodeToData(
                            if (format == ExportFormat.PNG) {
                                org.jetbrains.skia.EncodedImageFormat.PNG
                            } else {
                                org.jetbrains.skia.EncodedImageFormat.WEBP
                            },
                            quality = 100
                        )!!.bytes
                }
                destination.writeBytes(bytes)
                println(
                    "EXPORT %d x %d %s -> %.1f MB, encoded in %.1f s".format(
                        size, size, format.label, bytes.size / 1024.0 / 1024.0,
                        (System.currentTimeMillis() - started) / 1000.0
                    )
                )
                assertTrue(bytes.isNotEmpty(), "wrote an empty ${format.label} at $size")
            }
            bitmap.close()

            DataLayer.entries.forEach { layer ->
                val started = System.currentTimeMillis()
                val files = runBlocking {
                    DataExports.write(world, layer, GzipCompressor, BuildInfo.VERSION)
                }
                File(outputDir, files.imageName).writeBytes(files.image)
                File(outputDir, files.sidecarName).writeBytes(files.sidecar)
                println(
                    "EXPORT %d x %d %s -> %.1f MB image + %d B sidecar, written in %.1f s".format(
                        size, size, layer.label, files.image.size / 1024.0 / 1024.0,
                        files.sidecar.size, (System.currentTimeMillis() - started) / 1000.0
                    )
                )
                assertTrue(files.image.isNotEmpty(), "wrote an empty ${layer.label} at $size")
                assertTrue(files.sidecar.isNotEmpty(), "wrote no sidecar for ${layer.label} at $size")
            }

            println("EXPORT   peak heap: ${peakHeapMb()} MB")
        }
    }

    private fun peakHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }
}
