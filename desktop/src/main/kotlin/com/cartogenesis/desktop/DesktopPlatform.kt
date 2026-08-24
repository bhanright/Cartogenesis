package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the desktop can do that the shared interface cannot assume.
 *
 * Files on disk, a native save dialog, and a real graphics device reached through OpenGL. The
 * browser build answers the same questions with local storage, a download, and WebGPU, and neither
 * front end knows the other exists.
 */
class DesktopPlatform : Platform {

    // Every core available and a 12GB heap, so there is no reason to start small.
    override val defaultResolution: Int = 1024

    override val library: WorldLibrary = DesktopWorldStore()

    override val libraryLocation: String get() = (library as DesktopWorldStore).location

    // Probed once, at startup. A machine with no usable device gets the switch disabled and told
    // why, which is more use than a switch that silently does nothing.
    private val gpu = GpuErosion.createOrNull()

    override val accelerator: ErosionAccelerator? get() = gpu.accelerator

    override val accelerationUnavailableBecause: String? get() = gpu.unavailableBecause

    override suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? {
        // The dialog is native and has to run on the caller's thread; the rendering behind it must
        // not, or the window stops answering for the best part of a minute.
        val destination = chooseSaveFile(Exporter.defaultName(config, size, format)) ?: return null
        val result = withContext(Dispatchers.Default) {
            Exporter.export(config, options, size, destination, format)
        }
        return ExportOutcome(result.file.name, result.millis, result.bytes)
    }
}
