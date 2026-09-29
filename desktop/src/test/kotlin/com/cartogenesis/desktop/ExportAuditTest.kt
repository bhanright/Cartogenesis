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
 * Every export the desktop offers, at the sizes every desktop offers them at: 1024 and 2048 rows,
 * grids of square cells 2048 by 1024 and 4096 by 2048, exported at their own size as the app does.
 *
 * 2048 is the one that matters: generating it is three and a half minutes of work before anything
 * is drawn. 4096 rows, offered only where the heap holds it (`WorldCeilings.forDesktopHeap`), holds
 * 10.3 GB live, more than this tier's 10 GB heap, and was measured on its own (docs/DESIGN_LEDGER.md,
 * Q5). Split out of `ExportSmokeTest` so the per-merge suite keeps a fast export as its smoke check
 * while this — the on-demand / nightly audit tier — still proves the sizes the app actually ships.
 *
 * One world per size, and the six exports measured from it. Before the data exports existed
 * this ran the whole pipeline
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
        listOf(1024, 2048).forEach { size ->
            val startedGeneration = System.currentTimeMillis()
            val world = WorldGenerationEngine.generateBlocking(WorldGenConfig.forRows(seed = 42L, rows = size))
            val generationMillis = System.currentTimeMillis() - startedGeneration
            // Named as the application names a size, by its rows, with the grid beside it.
            val grid = "$size rows (${world.width} x ${world.height})"
            println("EXPORT %s generation %.1f s".format(grid, generationMillis / 1000.0))

            val startedRaster = System.currentTimeMillis()
            val pixels = MapRasterizer.rasterize(world, RenderOptions())
            val bitmap = MapImage.toBitmap(world, RenderOptions(), pixels)
            println(
                "EXPORT %s raster and sheet %.1f s".format(
                    grid, (System.currentTimeMillis() - startedRaster) / 1000.0
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
                    "EXPORT %s %s -> %.1f MB, encoded in %.1f s".format(
                        grid, format.label, bytes.size / 1024.0 / 1024.0,
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
                    "EXPORT %s %s -> %.1f MB image + %d B sidecar, written in %.1f s".format(
                        grid, layer.label, files.image.size / 1024.0 / 1024.0,
                        files.sidecar.size, (System.currentTimeMillis() - started) / 1000.0
                    )
                )
                assertTrue(files.image.isNotEmpty(), "wrote an empty ${layer.label} at $size")
                assertTrue(files.sidecar.isNotEmpty(), "wrote no sidecar for ${layer.label} at $size")
            }

            println("EXPORT $grid heap in use after the exports: ${heapInUseMb()} MB")
        }
    }

    /** The heap in use when asked, garbage included: not a peak, which this does not sample. */
    private fun heapInUseMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }
}
