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
 * change regenerates immediately, exactly as it always has; [hasGenerated] never goes back.
 *
 * What is counted is the *asks*, not merely whether there has been one. The generation runs in a
 * `LaunchedEffect` keyed on the settings and on [requests], and Stop cancels that effect's own
 * coroutine — after which the effect is dead until one of its keys moves. A latched boolean never
 * moves again, so a reader who stopped a generation and then pressed Generate with nothing else
 * changed would be pressing a button that did nothing at all. A count moves every time.
 */
class GenerationGate {

    /** How many times Go, New world or Generate has been pressed. */
    var requests: Int by mutableStateOf(0)
        private set

    /** Whether a generation has ever been asked for, which is what arms live editing. */
    val hasGenerated: Boolean get() = requests > 0

    /** Go, New world, or Generate was pressed. Arms the gate; a caller should now generate. */
    fun request() {
        requests += 1
    }
}
