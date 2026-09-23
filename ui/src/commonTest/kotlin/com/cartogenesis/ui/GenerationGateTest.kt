package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [GenerationGate] is the whole of what stops [CartogenesisApp] from generating the moment it
 * opens, and the whole of what lets Generate run again after Stop.
 *
 * The application's generation runs in `LaunchedEffect(config, gate.requests)`, which returns at
 * once while `gate.hasGenerated` is false. So the two things the gate must do are read off the gate
 * itself: [GenerationGate.hasGenerated] stays false until something asks, and
 * [GenerationGate.requests] moves on every ask — the second is what restarts the effect when
 * nothing else has changed, which after a Stop is the only way it can restart at all.
 */
class GenerationGateTest {

    @Test
    fun `nothing has asked for a generation when the app opens`() {
        val gate = GenerationGate()
        assertFalse(gate.hasGenerated, "a gate nobody has asked is already armed")
        assertEquals(0, gate.requests)
    }

    @Test
    fun `go, new world, and generate each arm the gate`() {
        // Three presses from three places, each on a fresh gate: each one alone arms it.
        repeat(3) {
            val gate = GenerationGate()
            gate.request()
            assertTrue(gate.hasGenerated)
        }
    }

    /**
     * The count the class exists for. A latched boolean would arm the gate exactly as well, and
     * would leave the effect keyed on it dead after a Stop: pressing Generate again with nothing
     * else changed would move no key. Each ask has to move [GenerationGate.requests].
     */
    @Test
    fun `every ask moves the key the generation is started by`() {
        val gate = GenerationGate()
        val keys = ArrayList<Int>()
        repeat(4) {
            gate.request()
            keys.add(gate.requests)
        }
        assertEquals(listOf(1, 2, 3, 4), keys, "an ask left the generation's key where it was")
        // Stop cancels the effect without touching the gate; the next Generate must still move it.
        val beforeGenerateAgain = gate.requests
        gate.request()
        assertNotEquals(beforeGenerateAgain, gate.requests, "Generate after Stop moved no key, so it would run nothing")
        assertTrue(gate.hasGenerated)
    }
}
