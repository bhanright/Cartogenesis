package com.cartogenesis.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cartogenesis.ui.CartogenesisApp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop front end: a window, and the handful of things a window can do that a browser tab
 * cannot. The application itself lives in `:ui` and is shared with the web build.
 */
fun main(args: Array<String>) {
    // A packaged build is a different runtime from the one the tests run on -- jlink trims it --
    // so there has to be a way to ask it about the GPU without opening the window. This exists
    // because the first packaged build reported the GPU unavailable and nothing in the test suite
    // could have noticed.
    if (args.contains("--gpu-check")) {
        val probe = GpuErosion.createOrNull()
        println(
            probe.accelerator?.let { "GPU available: ${it.name}" }
                ?: "GPU unavailable: ${probe.unavailableBecause}"
        )
        return
    }
    launchWindow()
}

private fun launchWindow() = application {
    val platform = remember { DesktopPlatform() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Cartogenesis",
        state = rememberWindowState(width = 1500.dp, height = 950.dp)
    ) {
        MaterialTheme { CartogenesisApp(platform) }
    }
}

/** Native save dialog. Must run on the UI thread; the rendering behind it must not. */
internal fun chooseSaveFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save map", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name)
}
