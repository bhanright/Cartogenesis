package com.cartogenesis.web

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataFiles
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldComparison
import com.cartogenesis.cartography.WorldDocument
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
    val bomb = runDecompressionSelfTest()
    return "SELFTEST $gpu $storage $exports $bomb"
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
    for (cell in uplift.data.indices) {
        val delta = abs(uplift.data[cell] - roundTrip[cell])
        if (delta > roundTripWorst) roundTripWorst = delta
    }

    var onCpu = FloatArray(0)
    val cpuElapsed = measureTime { onCpu = ErosionStage.apply(config, uplift).height.data }

    // Once to compile the shaders and warm the device, then the measurement.
    ErosionStage.apply(gpuConfig, uplift, accelerator = accelerator)
    var onGpu = FloatArray(0)
    val gpuElapsed = measureTime {
        onGpu = ErosionStage.apply(gpuConfig, uplift, accelerator = accelerator).height.data
    }

    var worst = 0f
    var total = 0.0
    for (cell in onCpu.indices) {
        val delta = abs(onCpu[cell] - onGpu[cell])
        if (delta > worst) worst = delta
        total += delta.toDouble()
    }

    return "device=${accelerator.name} roundTripWorst=$roundTripWorst " +
        "cpu=${cpuElapsed.inWholeMilliseconds}ms gpu=${gpuElapsed.inWholeMilliseconds}ms " +
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
    var key = ""
    val writeElapsed = measureTime { key = library.save(document, world) }

    var restored: LoadOutcome? = null
    val readElapsed = measureTime { restored = library.load(key) }
    val listed = library.list().any { it.key == key && it.document?.title == document.title }
    library.delete(key)

    // Every field a save carries, as the JVM's round trip compares them, not the heights alone.
    val opened = restored as? LoadOutcome.Loaded
    val difference = opened?.let { WorldComparison.firstDifference(world, it.save.world) }
    val refusal = (restored as? LoadOutcome.Refused)?.refusal?.message

    return "storage compression=${compressor.name} bytes=${bytes.size} " +
        "writeMs=${writeElapsed.inWholeMilliseconds} readMs=${readElapsed.inWholeMilliseconds} " +
        "listed=$listed everyFieldIdentical=${opened != null && difference == null}" +
        (difference?.let { " differs=\"$it\"" } ?: "") + (refusal?.let { " refused=\"$it\"" } ?: "")
}

/**
 * The two questions about exports that can only be asked in a browser.
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
    // Every encoder is asked for its best, so a null back is the encoder declining rather than
    // the quality being one this build happens not to support.
    val world = WorldGenerationEngine.generate(config)

    val bitmap = MapImage.toBitmap(world, RenderOptions())
    val image = Image.makeFromBitmap(bitmap)
    val png = image.encodeToData(EncodedImageFormat.PNG, quality = BEST_QUALITY)
        ?.bytes?.size ?: ENCODER_DECLINED
    val webp = image.encodeToData(EncodedImageFormat.WEBP, quality = BEST_QUALITY)
        ?.bytes?.size ?: ENCODER_DECLINED
    val jpeg = image
        .encodeToData(EncodedImageFormat.JPEG, quality = ExportFormat.JPEG_QUALITY)
        ?.bytes?.size ?: ENCODER_DECLINED
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
        "heightmapBytes=${written?.image?.size ?: ENCODER_DECLINED} " +
        "sidecarBytes=${written?.sidecar?.size ?: ENCODER_DECLINED} " +
        "zipBytes=${written?.asZip()?.size ?: ENCODER_DECLINED} " +
        "heightmapMs=${heightmapElapsed.inWholeMilliseconds}"
}

/**
 * Whether the browser's gunzip stops at the limit it is given: a stream of [BOMB_BYTES] of zeros,
 * which gzip holds in a few tens of kilobytes, expanded to a limit of [BOMB_LIMIT_BYTES]. The
 * answer has to be the limit and one byte over, and the bytes pulled from the stream to get it a
 * small step past the limit rather than the whole stream.
 */
private suspend fun runDecompressionSelfTest(): String {
    if (!compressionStreamsAvailable()) return "gunzip unavailable"
    val bomb = gzipOfZeros(BOMB_BYTES) ?: return "gunzip no-stream"
    val answer = WebGzipCompressor.decompress(bomb, BOMB_LIMIT_BYTES)
    val pulled = WebGzipCompressor.lastPulledBytes.toLong()
    val stopped = answer?.size == BOMB_LIMIT_BYTES + 1 && pulled < BOMB_BYTES / 4
    return "gunzip streamBytes=${bomb.size} expandsTo=$BOMB_BYTES limit=$BOMB_LIMIT_BYTES " +
        "answerBytes=${answer?.size} pulledBytes=$pulled stoppedAtLimit=$stopped"
}

/** 64 MiB: sixty-four times the limit, so a gunzip that read it all would show it plainly. */
private const val BOMB_BYTES = 64 shl 20

/** A chunk, the most any frame may promise. */
private const val BOMB_LIMIT_BYTES = 1 shl 20

/** Lossless for PNG and WebP, so a null back is the encoder missing rather than the setting. */
private const val BEST_QUALITY = 100

/** What a byte count reads as when the encoder handed back nothing at all. */
private const val ENCODER_DECLINED = -1
