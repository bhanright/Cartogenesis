package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders at a size the desktop build offers, in both formats it offers.
 *
 * This records how big each format comes out and what WebP costs in fidelity — the UI makes a
 * claim about that, and a claim about an image format is exactly the sort that should not be
 * taken on trust. The larger exports (2048, 4096 — 4096 is the one that matters: it wants roughly
 * 2GB) moved to `ExportAuditTest` in T1, so this stays a fast per-merge smoke check; the audit
 * tier still proves the sizes the app actually ships.
 */
class ExportSmokeTest {

    /**
     * How far a colour channel may drift, at the 99.9th percentile, before the description of WebP
     * in the UI stops being honest. Skia exposes no lossless WebP encoder, so this is not zero and
     * cannot be: the bound records what the encoder actually does today so that a change for the
     * worse is caught rather than shipped. It measured 58 on the August 2026 worlds; the September
     * realism work (crust-pair belts, deltas, Koppen biomes) put more sharp edges on the same seed
     * and it now measures 67 with the encoder untouched, so the bound and the README moved with it.
     */
    private companion object {
        const val MAX_CHANNEL_DRIFT = 72
    }


    /**
     * The sizes this test renders are the sizes the interface offers, and no more.
     *
     * 8192 is offered as a disabled chip because it does not complete: G2 measured it exhausting a
     * 10 GB heap inside the generator after about nineteen minutes, before a pixel is drawn. The
     * ceiling lives on the platform so that the build which fixes the memory raises it in one
     * place — and this is the assertion that will fail, correctly, when it does, so that this test
     * is extended to render the size it has started letting through.
     */
    @Test
    fun `the desktop build's export ceiling is 4096`() {
        assertEquals(4096, DesktopPlatform().exportCeiling(compact = false))
    }

    @Test
    fun `WebP is smaller than PNG, and how much it costs in fidelity`() {
        val outputDir = File("build/exports").apply { mkdirs() }
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)

        val results = ExportFormat.entries.associateWith { format ->
            val destination = File(outputDir, Exporter.defaultName(base, 1024, format))
            runBlocking { Exporter.export(base, RenderOptions(), 1024, destination, format) }
        }

        val png = results.getValue(ExportFormat.PNG)
        val webp = results.getValue(ExportFormat.WEBP)
        println(
            "EXPORT PNG %.2f MB vs WebP %.2f MB (%.0f%% of the PNG)".format(
                png.bytes / 1024.0 / 1024.0,
                webp.bytes / 1024.0 / 1024.0,
                webp.bytes * 100.0 / png.bytes
            )
        )
        assertTrue(
            webp.bytes < png.bytes,
            "WebP (${webp.bytes}) was not smaller than PNG (${png.bytes})"
        )

        // Decode both through Skia and compare every pixel. ImageIO has no WebP reader, and
        // "it is smaller" is not evidence of anything on its own — the encoder could be discarding
        // detail. This is what says whether the format label in the UI is true.
        val pngPixels = decode(png.file)
        val webpPixels = decode(webp.file)
        assertEquals(pngPixels.size, webpPixels.size)

        // A single worst-case pixel says little about a photograph-sized image, so measure the
        // distribution: what a typical pixel loses, and what the tail looks like.
        var differing = 0
        var totalDrift = 0L
        var worst = 0
        val drifts = IntArray(256)
        for (i in pngPixels.indices) {
            var pixelWorst = 0
            // RGB only. Alpha is 255 everywhere on an exported map, and including it would just
            // dilute the average with zeroes.
            for (shift in 0..16 step 8) {
                val delta = kotlin.math.abs(
                    ((pngPixels[i] shr shift) and 0xFF) - ((webpPixels[i] shr shift) and 0xFF)
                )
                if (delta > pixelWorst) pixelWorst = delta
            }
            if (pixelWorst > 0) differing++
            totalDrift += pixelWorst
            drifts[pixelWorst]++
            if (pixelWorst > worst) worst = pixelWorst
        }

        var seen = 0
        var percentile999 = 0
        val target = (pngPixels.size * 0.999).toInt()
        for (d in 0 until 256) {
            seen += drifts[d]
            if (seen >= target) { percentile999 = d; break }
        }

        println(
            "EXPORT WebP vs PNG: %.1f%% of pixels differ, mean drift %.2f, 99.9th percentile %d, worst %d (of 255)"
                .format(differing * 100.0 / pngPixels.size, totalDrift.toDouble() / pngPixels.size, percentile999, worst)
        )
        assertTrue(
            percentile999 <= MAX_CHANNEL_DRIFT,
            "WebP drifted $percentile999 of 255 at the 99.9th percentile, past the $MAX_CHANNEL_DRIFT this is described as"
        )
    }

    private fun decode(file: File): IntArray {
        val image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(
            org.jetbrains.skia.ImageInfo.makeS32(
                image.width, image.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL
            )
        )
        check(image.readPixels(bitmap)) { "could not read pixels back from ${file.name}" }
        val bytes = bitmap.readPixels() ?: error("no pixels in ${file.name}")
        bitmap.close()
        image.close()
        return IntArray(bytes.size / 4) { i ->
            val o = i * 4
            (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 3].toInt() and 0xFF) shl 24)
        }
    }
}
