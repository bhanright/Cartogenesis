package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GenerationGate] is the whole of what stops [CartogenesisApp] from generating the moment it
 * opens: `LaunchedEffect(config, gate.hasGenerated)` there does exactly `if (!gate.hasGenerated)
 * return@LaunchedEffect` before calling the engine, which this exercises without needing a running
 * composition.
 */
class GenerationGateTest {

    @Test
    fun `constructing the app state and changing settings never reaches the engine`() {
        val gate = GenerationGate()
        var engineCalls = 0
        fun onSettingsChanged() {
            if (gate.hasGenerated) engineCalls++
        }

        // Nothing has asked for a generation yet - not even the first composition.
        assertFalse(gate.hasGenerated)

        // Dragging sliders, toggling switches, before ever pressing Generate.
        onSettingsChanged()
        onSettingsChanged()
        onSettingsChanged()

        assertEquals(0, engineCalls)
    }

    @Test
    fun `go, new world, and generate each arm the gate and reach the engine`() {
        val go = GenerationGate()
        go.request()
        assertTrue(go.hasGenerated)

        val newWorld = GenerationGate()
        newWorld.request()
        assertTrue(newWorld.hasGenerated)

        val generate = GenerationGate()
        generate.request()
        assertTrue(generate.hasGenerated)
    }

    @Test
    fun `settings changes reach the engine live once a generation has been requested`() {
        val gate = GenerationGate()
        var engineCalls = 0
        fun onSettingsChanged() {
            if (gate.hasGenerated) engineCalls++
        }

        gate.request()
        onSettingsChanged()
        onSettingsChanged()

        assertEquals(2, engineCalls)
    }

    @Test
    fun `requesting again is harmless`() {
        val gate = GenerationGate()
        gate.request()
        gate.request()
        assertTrue(gate.hasGenerated)
    }
}
