package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.RasterRecipe
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the graphics card is worth at export sizes, in seconds and in gigabytes.
 *
 * Nothing here is asserted tightly: the numbers belong to whatever device is present, and on a
 * machine with none the comparison collapses to the processor twice over. What is asserted is only
 * the claim the feature rests on — that the accelerated raster is faster and that the export still
 * writes a file. The figures are printed for the ledger.
 *
 * Each measurement is a test of its own because each is minutes long — generating a world at 4096 is
 * three minutes before anything is drawn — and because running two of them in one JVM measures the
 * second under the first one's rubbish. Heap is sampled every fifty milliseconds while the work runs
 * and the largest reading kept: a single reading afterwards, which is what the older export guard
 * takes, catches whatever the collector happened to leave behind rather than the high-water mark.
 *
 * Every one of them is off unless asked for:
 *
 * ```
 * ./gradlew :desktop:test --tests "*GpuExportBenchmarkTest*" -Pbenchmark=true
 * ```
 *
 * Together they are the better part of half an hour, most of it generating worlds, which is not
 * something to make every build pay for. The correctness guards are in `GpuRasterTest` and always
 * run.
 */
class GpuExportBenchmarkTest {

    private companion object {
        val CONFIG = WorldGenConfig(seed = 42L, width = 1024, height = 1024)
        const val SIZE = 4096
    }

    /** True when the run asked for measurements. Prints why it is skipping, so it never looks green
     * by having done nothing. */
    private fun measuring(): Boolean {
        if (System.getProperty("cartogenesis.benchmark") == "true") return true
        println("EXPORT skipped: run with -Pbenchmark=true to measure")
        return false
    }

    @Test
    fun `a 4096 export on the processor`() {
        if (!measuring()) return
        val file = destination("4096-cpu")
        val heap = PeakHeap()
        val millis = measureTimeMillis {
            runBlocking { Exporter.export(CONFIG, RenderOptions(), SIZE, file, ExportFormat.PNG) }
        }
        report("4096 whole export on the processor", millis, file, heap)
    }

    @Test
    fun `a 4096 export on the graphics card`() {
        if (!measuring()) return
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("EXPORT GPU unavailable here: ${found.unavailableBecause}")
            return
        }
        println("EXPORT GPU device: ${gpu.name}")

        val file = destination("4096-gpu")
        val heap = PeakHeap()
        val millis = measureTimeMillis {
            runBlocking {
                Exporter.export(CONFIG, RenderOptions(), SIZE, file, ExportFormat.PNG, gpu)
            }
        }
        report("4096 whole export on the graphics card", millis, file, heap)
        assertTrue(file.length() > 0, "the accelerated export wrote nothing")
    }

    /**
     * The raster on its own, which is the part this chunk changed. A whole export is dominated by
     * generating the world at 4096 — the same work whichever processor draws it afterwards — so the
     * end-to-end figures above hide most of the difference.
     */
    @Test
    fun `the 4096 raster alone, both ways`() {
        if (!measuring()) return
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("EXPORT GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val world = WorldGenerationEngine.generateBlocking(CONFIG.atResolution(SIZE, SIZE))
        val options = RenderOptions(riverScale = 4f)
        val recipe = requireNotNull(RasterRecipe.of(world, options))
        // Once to warm the driver, then the measurement.
        runBlocking { gpu.rasterize(recipe) }

        val settled = usedHeapMb()
        val cpuHeap = PeakHeap()
        var onCpu = IntArray(0)
        val cpuMs = measureTimeMillis { onCpu = MapRasterizer.rasterize(world, options) }
        val cpuPeak = cpuHeap.stop()
        val gpuHeap = PeakHeap()
        var onGpu: IntArray? = null
        val gpuMs = measureTimeMillis { onGpu = runBlocking { gpu.rasterize(recipe) } }
        val gpuPeak = gpuHeap.stop()

        // 4096 is four tiles rather than the one a 1024 map takes, so this is also where the banding
        // is checked: a seam between tiles, or a band written to the wrong offset, would show up
        // here as a stripe of large differences and nowhere in the parity guard.
        var worst = 0
        var differing = 0
        val gpuPixels = requireNotNull(onGpu) { "the accelerator declined a 4096 raster" }
        for (i in onCpu.indices) {
            var pixel = 0
            for (shift in 0..16 step 8) {
                val delta = kotlin.math.abs(
                    ((onCpu[i] shr shift) and 0xFF) - ((gpuPixels[i] shr shift) and 0xFF)
                )
                if (delta > pixel) pixel = delta
            }
            if (pixel > 0) differing++
            if (pixel > worst) worst = pixel
        }
        println(
            "EXPORT 4096 raster across four tiles: %.4f%% of pixels differ from the CPU, worst %d"
                .format(differing * 100.0 / onCpu.size, worst)
        )
        assertTrue(worst <= 2, "the tiled 4096 raster drifted by $worst, past the 2 a rounding costs")

        println(
            ("EXPORT 4096 raster alone: the world alone holds %d MB; CPU %d ms, peak heap %d MB; " +
                "GPU %d ms, peak heap %d MB (%.1fx)")
                .format(
                    settled, cpuMs, cpuPeak, gpuMs, gpuPeak,
                    cpuMs.toDouble() / gpuMs.coerceAtLeast(1)
                )
        )
        assertTrue(gpuMs < cpuMs, "the accelerated raster was not faster: ${gpuMs}ms vs ${cpuMs}ms")
    }

    /**
     * 8192, which nobody had tried before this chunk.
     *
     * Written down as it goes, because the interesting outcome is as likely to be "it ran out of
     * memory in the generator after nine minutes" as a time, and a test killed by the heap prints
     * nothing at the end.
     */
    @Test
    fun `an 8192 export, attempted`() {
        if (!measuring()) return
        val log = File("build/exports").apply { mkdirs() }.resolve("8192-attempt.log")
        log.writeText("")
        fun note(line: String) {
            println("EXPORT8192 $line")
            log.appendText("$line\n")
        }

        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            note("GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val file = destination("8192-gpu")
        note("starting, heap ceiling ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")

        // The export's own steps rather than a call to Exporter, so that each one is written down
        // as it finishes. Whatever stops an 8192 export is going to stop it in the middle.
        val heap = PeakHeap()
        try {
            val started = System.currentTimeMillis()
            lateinit var world: com.cartogenesis.worldgen.model.WorldMap
            val generateMs = measureTimeMillis {
                world = WorldGenerationEngine.generateBlocking(CONFIG.atResolution(8192, 8192))
            }
            note("generated the world in %.1f s, heap now %d MB".format(generateMs / 1000.0, usedHeapMb()))

            val options = RenderOptions(riverScale = 8f)
            var pixels: IntArray? = null
            val rasterMs = measureTimeMillis {
                val recipe = RasterRecipe.of(world, options)
                pixels = if (recipe == null) null else runBlocking { gpu.rasterize(recipe) }
            }
            if (pixels == null) {
                note("the accelerator declined 8192; the processor would draw it instead")
                return
            }
            note("rastered 67.1 million pixels on the device in %.2f s".format(rasterMs / 1000.0))

            var bytes = 0L
            val encodeMs = measureTimeMillis {
                val bitmap = com.cartogenesis.ui.MapImage.toBitmap(world, options, requireNotNull(pixels))
                val image = org.jetbrains.skia.Image.makeFromBitmap(bitmap)
                val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100)
                    ?: error("the encoder refused 8192")
                file.writeBytes(data.bytes)
                bytes = file.length()
                image.close()
                bitmap.close()
            }
            note("overlays and PNG in %.1f s, %.1f MB".format(encodeMs / 1000.0, bytes / 1024.0 / 1024.0))
            note(
                "finished in %.1f s, peak heap %d MB"
                    .format((System.currentTimeMillis() - started) / 1000.0, heap.stop())
            )
        } catch (e: OutOfMemoryError) {
            note("ran out of heap, peak ${heap.stop()} MB: ${e.message}")
        } catch (e: Exception) {
            note("failed with ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun destination(name: String): File =
        File("build/exports").apply { mkdirs() }.resolve("gpu-benchmark-$name.png")

    private fun report(what: String, millis: Long, destination: File, heap: PeakHeap) {
        println(
            "EXPORT %s: %.1f s, %.1f MB, peak heap %d MB"
                .format(what, millis / 1000.0, destination.length() / 1024.0 / 1024.0, heap.stop())
        )
    }

    private fun usedHeapMb(): Long {
        System.gc()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    /** Watches the heap while something runs, and remembers the largest it ever got. */
    private class PeakHeap {
        @Volatile private var running = true
        @Volatile private var peak = 0L

        private val watcher = Thread {
            val runtime = Runtime.getRuntime()
            while (running) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                Thread.sleep(50)
            }
        }.apply { isDaemon = true; start() }

        /** Megabytes at the high-water mark. */
        fun stop(): Long {
            running = false
            watcher.join(500)
            return peak / 1024 / 1024
        }
    }
}
