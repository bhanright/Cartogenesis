package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator

/**
 * What an exported map is written as.
 *
 * PNG is lossless. WebP is about a quarter the size but is *not* lossless: Skia exposes only the
 * lossy encoder, and even at maximum quality the loss falls where it is least welcome on a map.
 * Measured against the PNG of the same world, the average pixel drifts about 3 of 255 — invisible
 * — but the worst 0.1% drift by nearly 60, and those are the river lines, borders and coastlines,
 * because that is where the sharp edges are.
 */
enum class ExportFormat(val label: String, val extension: String, val detail: String) {
    PNG("PNG", "png", "Lossless. Larger file, exact detail."),
    WEBP("WebP", "webp", "About a quarter the size. Slightly softens rivers and borders.")
}

/** Where an exported map ended up, in whatever terms the host can describe. */
class ExportOutcome(val description: String, val millis: Long, val bytes: Long)

/**
 * Everything the shared interface cannot do for itself.
 *
 * The list is deliberately short. A browser tab and a desktop window differ in where files live,
 * what "save this image" means, and whether there is a graphics device to offer — and essentially
 * nowhere else. Anything longer than this would be a sign that platform detail had leaked into the
 * application rather than staying at its edge.
 */
interface Platform {

    /**
     * The working resolution to start at.
     *
     * A platform decision rather than a preference. The JVM spreads generation across every core
     * and can reach for a GPU; a browser tab has one thread, and `Dispatchers.Default` there is
     * that same thread, so generating does not merely take longer — it stops the page answering
     * until it finishes. Starting the web build smaller is the difference between a wait and an
     * apparent hang.
     */
    val defaultResolution: Int

    /** Where saved worlds are kept. */
    val library: WorldLibrary

    /** Shown in the library so someone can find their files. */
    val libraryLocation: String

    /**
     * The accelerator to offer, or null if this machine cannot provide one — in which case
     * [accelerationUnavailableBecause] should say why, since a disabled switch with no explanation
     * is worse than no switch.
     */
    val accelerator: ErosionAccelerator?

    val accelerationUnavailableBecause: String?

    /**
     * Renders at [size] and puts the result wherever this platform puts finished files: a chosen
     * path on the desktop, a download in a browser. Returns null if the user backed out.
     */
    suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome?
}

/** Wall-clock milliseconds, for stamping a save. */
expect fun epochMillis(): Long

/** A fresh identifier for a saved world. */
expect fun randomId(): String

/** A saved world's timestamp, in whatever form is natural for the host. */
expect fun formatTimestamp(millis: Long): String
