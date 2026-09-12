package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Decides whether a change reaching [CartogenesisApp] should run the world generator.
 *
 * The app opens on a blank canvas: nothing is generated until the reader explicitly asks for one,
 * so a setting changed before that first ask must not start a generation of its own — only Go, New
 * world, or the Generate button may. Once a generation has been asked for, every later settings
 * change regenerates immediately, exactly as it always has; [hasGenerated] never resets.
 *
 * [hasGenerated] is a snapshot state (not a plain `Boolean`) so a `LaunchedEffect` keyed on it
 * restarts the moment Generate is pressed, even when the settings it would apply have not
 * themselves changed — the one case a key on the settings alone would miss.
 */
class GenerationGate {
    var hasGenerated: Boolean by mutableStateOf(false)
        private set

    /** Go, New world, or Generate was pressed. Arms the gate; a caller should now generate. */
    fun request() {
        hasGenerated = true
    }
}
