package com.cartogenesis.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.Platform

/**
 * The browser entry point.
 *
 * Compose draws into a canvas filling the page; everything from there on is the same application
 * the desktop build runs. The one asymmetry is that the graphics device has to be asked for
 * asynchronously, so the app starts on the CPU and picks up the GPU when the browser answers.
 */
/**
 * Must match the container in index.html. Compose attaches a shadow root to it and puts its canvas
 * inside, so the canvas will not be found by an ordinary DOM query on the page.
 *
 * **A deployed website depends on this name.** cartogenesis.bfunk.online replaces the emitted
 * index.html with its own shell and creates the div itself, so renaming this leaves the app with
 * nothing to mount into. `WebDeploymentContractTest` fails if it changes; if it genuinely must,
 * that is a coordinated change with the site rather than a rename.
 */
private const val VIEWPORT_ID = "composeTarget"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(VIEWPORT_ID) {
        var platform by remember { mutableStateOf<Platform?>(null) }

        LaunchedEffect(Unit) {
            // The page shows its own message until Compose has something to draw, since the wasm
            // bundle is large enough that the gap is noticeable. This call is also the only exact
            // "the app is up" signal anything outside the shadow root can observe - see
            // [hideLoadingMessage]. It must stay here, and stay first.
            hideLoadingMessage()
            val gpu = WebGpuErosion.createOrNull()
            if (selfTestRequested()) {
                // ?selftest in the URL checks the WebGPU path against the CPU and reports, since
                // a browser's device cannot be reached from an ordinary test.
                publishSelfTest(runSelfTest(gpu.accelerator))
            }
            platform = WebPlatform(gpu.accelerator, gpu.unavailableBecause)
        }

        val ready = platform
        MaterialTheme {
            if (ready != null) CartogenesisApp(ready)
        }
    }
}
