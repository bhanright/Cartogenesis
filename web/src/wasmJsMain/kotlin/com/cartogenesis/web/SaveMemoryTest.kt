package com.cartogenesis.web

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.WorldComparison
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.time.measureTime

@JsFun("() => location.search.indexOf('savetest') >= 0")
internal external fun saveTestRequested(): Boolean

/** The tab's JavaScript heap in use, which holds a Kotlin/Wasm program's objects; -1 where the browser does not say. */
@JsFun("() => (performance.memory ? performance.memory.usedJSHeapSize : -1)")
private external fun heapInUse(): Double

/**
 * The tab's memory after a collection, where the page is cross-origin isolated and the browser
 * offers `measureUserAgentSpecificMemory`; -1 otherwise. It resolves after the browser's next
 * collection, which can take some seconds.
 */
@JsFun(
    """() => (async () => {
        try {
            if (!self.crossOriginIsolated || !performance.measureUserAgentSpecificMemory) return -1;
            const result = await performance.measureUserAgentSpecificMemory();
            return result.bytes;
        } catch (e) { return -1; }
    })()"""
)
private external fun measuredMemoryPromise(): JsHandle

@JsFun("(value) => Number(value)")
private external fun asNumber(value: JsHandle): Double

private suspend fun measuredMemory(): Double = awaitPromise(measuredMemoryPromise())?.let(::asNumber) ?: -1.0

/**
 * `?savetest`: what a 2048 world costs the tab to save to and open from the library.
 *
 * Generates a 2048 world, measures the tab, saves it to the real IndexedDB library and opens it
 * again, measuring the tab at every part as it goes in and comes out and — where the browser can
 * say, in a cross-origin isolated page — once more after a collection halfway through each, and
 * then compares every field of the opened world with the one saved. Not part of `?selftest`: a
 * 2048 world is minutes of generation in a tab.
 */
internal suspend fun runSaveMemoryTest(): String {
    val config = WorldGenConfig(seed = 42L, width = 2048, height = 2048)
    val world = WorldGenerationEngine.generate(config)
    val compressor = if (compressionStreamsAvailable()) WebGzipCompressor else NoCompression
    val library = IndexedDbLibrary(compressor, "web-savetest")
    val document = WorldDocument(id = "savetest-2048", title = "Save test 2048", config = config, savedAt = epochMillisNow())

    val beforeSave = measuredMemory()
    val heapBeforeSave = heapInUse()
    var heapPeak = heapBeforeSave
    var midSave = -1.0
    var midOpen = -1.0
    var parts = 0
    library.partObserver = PartObserver { writing, index ->
        heapPeak = maxOf(heapPeak, heapInUse())
        if (writing) parts = index + 1
        if (index == MEASURED_PART) {
            if (writing) midSave = measuredMemory() else midOpen = measuredMemory()
        }
    }

    var key = ""
    val saveTime = measureTime { key = library.save(document, world) }
    val heapSavePeak = heapPeak
    heapPeak = heapInUse()
    var opened: LoadOutcome? = null
    val openTime = measureTime { opened = library.load(key) }
    val heapOpenPeak = heapPeak
    library.partObserver = null

    val loaded = (opened as? LoadOutcome.Loaded)?.save?.world
    val difference = loaded?.let { WorldComparison.firstDifference(world, it) }
    library.delete(key)
    return "SAVETEST 2048 parts=$parts saveMs=${saveTime.inWholeMilliseconds} openMs=${openTime.inWholeMilliseconds} " +
        "opened=${loaded != null} everyFieldIdentical=${loaded != null && difference == null}" +
        (difference?.let { " differs=\"$it\"" } ?: "") +
        " heapBeforeSaveMB=${mebibytes(heapBeforeSave)} heapPeakSavingMB=${mebibytes(heapSavePeak)} " +
        "heapPeakOpeningMB=${mebibytes(heapOpenPeak)} measuredBeforeSaveMB=${mebibytes(beforeSave)} " +
        "measuredMidSaveMB=${mebibytes(midSave)} measuredMidOpenMB=${mebibytes(midOpen)}"
}

private fun mebibytes(bytes: Double): Long = if (bytes < 0) -1 else (bytes / (1 shl 20)).toLong()

/** Which part the collected measurement is taken at: well inside a 2048 save's two hundred or so. */
private const val MEASURED_PART = 100
