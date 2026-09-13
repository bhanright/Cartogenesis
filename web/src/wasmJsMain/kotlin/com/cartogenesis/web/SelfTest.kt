package com.cartogenesis.web

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataFiles
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.time.measureTime
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Checks the WebGPU path against the CPU, and the IndexedDB round trip, in the browser, and
 * reports what it finds.
 *
 * This exists because there is nowhere else to run either check. The desktop GPU path has a
 * normal test, but a browser's device can only be reached from a page, so `?selftest` in the URL
 * runs the same comparison here: erode one terrain both ways, and report how long each took and
 * how far apart the answers are. IndexedDB is the same story - `WorldLibraryTest` in `:cartography`
 * proves the format and the listing contract against an in-memory fake, but only a real browser
 * has a real IndexedDB to write bytes into and read them back out of.
 *
 * The two erosion runs go through the whole erosion stage, differing only in what the config asks
 * for. Comparing the accelerator against the stage directly — which is what this did at first —
 * stopped being a fair test the moment hydraulic erosion was added, because the accelerator only
 * ever carries the thermal sweeps and the stage now does both. It did not fail; it reported a
 * disagreement of 0.18 where the truth was seven parts in a million, which is worse than failing.
 *
 * The GPU answers are still expected to differ slightly — that is the premise of the whole
 * feature — so this reports the size of the difference rather than asserting there is none.
 */
internal suspend fun runSelfTest(accelerator: WebGpuErosion?): String {
    val gpu = if (accelerator == null) "no WebGPU device available" else runGpuSelfTest(accelerator)
    val storage = runStorageSelfTest()
    val exports = runExportSelfTest()
    return "SELFTEST $gpu $storage $exports"
}

private suspend fun runGpuSelfTest(accelerator: WebGpuErosion): String {
    val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
    val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
    val gpuConfig = config.copy(
        erosion = config.erosion.copy(acceleration = Acceleration.GPU)
    )

    // A zero-sweep run must hand the input straight back, which separates a broken exchange across
    // the wasm boundary from a broken shader.
    val roundTrip = accelerator.erode(
        config.width, config.height, uplift.data, ErosionStage.maxOrthogonalDrop(config), 0,
        config.erosion.rate
    ) ?: return "device=${accelerator.name} declined a zero-sweep run"
    var roundTripWorst = 0f
    for (i in uplift.data.indices) {
        val delta = abs(uplift.data[i] - roundTrip[i])
        if (delta > roundTripWorst) roundTripWorst = delta
    }

    var onCpu = FloatArray(0)
    val cpu = measureTime { onCpu = ErosionStage.apply(config, uplift).height.data }

    // Once to compile the shaders and warm the device, then the measurement.
    ErosionStage.apply(gpuConfig, uplift, accelerator)
    var onGpu = FloatArray(0)
    val gpu = measureTime {
        onGpu = ErosionStage.apply(gpuConfig, uplift, accelerator).height.data
    }

    var worst = 0f
    var total = 0.0
    for (i in onCpu.indices) {
        val delta = abs(onCpu[i] - onGpu[i])
        if (delta > worst) worst = delta
        total += delta.toDouble()
    }

    return "device=${accelerator.name} roundTripWorst=$roundTripWorst " +
        "cpu=${cpu.inWholeMilliseconds}ms gpu=${gpu.inWholeMilliseconds}ms " +
        "meanDelta=${total / onCpu.size} worstDelta=$worst"
}

/**
 * Saves a small world to IndexedDB, then reads it back through a fresh connection and checks the
 * height field survived untouched.
 *
 * "Reload the page", which is what the plan this guard comes from actually asks for, would lose
 * the page running the test along with the world sitting in memory - there would be nothing left
 * to report from. What a reload is actually standing in for is the property that the bytes on
 * disk survive independent of the in-memory object that wrote them, and [IndexedDbLibrary] gives
 * that for free: every one of its operations opens its own connection and closes it again, so
 * `save` here and `load` a few lines down are never talking to the same handle - which is the
 * same thing a reload would have proven, reached a different way.
 */
private suspend fun runStorageSelfTest(): String {
    val config = WorldGenConfig(seed = 918273L, width = 128, height = 128)
    val world = WorldGenerationEngine.generate(config)
    val document = WorldDocument(
        id = "selftest-storage-roundtrip",
        title = "Self-test storage",
        config = config,
        savedAt = epochMillisNow()
    )

    val compressor = if (compressionStreamsAvailable()) WebGzipCompressor else NoCompression
    val bytes = WorldCodec.encode(document, world, compressor, "web-selftest")

    val library = IndexedDbLibrary(compressor, "web-selftest")
    val writeElapsed = measureTime { library.save(document, world) }

    var restored: WorldSave? = null
    val readElapsed = measureTime { restored = library.load(document.id) }
    library.delete(document.id)

    val restoredHeights = restored?.world?.terrain?.height?.data
    val identical = restoredHeights != null && restoredHeights.contentEquals(world.terrain.height.data)

    return "storage compression=${compressor.name} bytes=${bytes.size} " +
        "writeMs=${writeElapsed.inWholeMilliseconds} readMs=${readElapsed.inWholeMilliseconds} " +
        "heightIdentical=$identical"
}

/**
 * The two things F12's exports can only be asked in a browser.
 *
 * The first is whether the Skia that ships inside this wasm bundle will encode a JPEG at all. On
 * the desktop that question does not arise — the JDK's own encoder writes it — but here the same
 * `Image.encodeToData` that writes the PNG and the WebP is asked for a third format, and whether
 * the build it came from was compiled with a JPEG encoder is not something any test outside a page
 * can find out. A null back from it would reach a reader as "Export failed" and nothing else, so it
 * is worth one line of a self-test.
 *
 * The second is the zip a data export is delivered as. The bytes are built in common code and
 * `DataExportTest` reads them back on the JVM, but the browser is where they actually go, and this
 * proves the whole path runs here and reports what it costs.
 *
 * Small deliberately: 256 is a real world through the whole pipeline and takes about a second,
 * which is what a self-test that runs on page load can afford.
 */
private suspend fun runExportSelfTest(): String {
    val config = WorldGenConfig(seed = 402627L, width = 256, height = 256)
    val world = WorldGenerationEngine.generate(config)

    val bitmap = MapImage.toBitmap(world, RenderOptions())
    val image = Image.makeFromBitmap(bitmap)
    val png = image.encodeToData(EncodedImageFormat.PNG, quality = 100)?.bytes?.size ?: -1
    val webp = image.encodeToData(EncodedImageFormat.WEBP, quality = 100)?.bytes?.size ?: -1
    val jpeg = image
        .encodeToData(EncodedImageFormat.JPEG, quality = ExportFormat.JPEG_QUALITY)
        ?.bytes?.size ?: -1
    image.close()
    bitmap.close()

    val compressor = if (compressionStreamsAvailable()) WebGzipCompressor else NoCompression
    var heightmap: DataFiles? = null
    val heightmapElapsed = measureTime {
        heightmap = DataExports.write(world, DataLayer.HEIGHTMAP, compressor, BuildInfo.VERSION)
    }
    val written = heightmap

    // Minus one anywhere means an encoder declined, which is the failure this is looking for.
    return "exports pngBytes=$png webpBytes=$webp jpegBytes=$jpeg " +
        "heightmapBytes=${written?.image?.size ?: -1} " +
        "sidecarBytes=${written?.sidecar?.size ?: -1} " +
        "zipBytes=${written?.asZip()?.size ?: -1} " +
        "heightmapMs=${heightmapElapsed.inWholeMilliseconds}"
}
