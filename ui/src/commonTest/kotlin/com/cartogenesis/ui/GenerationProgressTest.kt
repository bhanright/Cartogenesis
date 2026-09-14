package com.cartogenesis.ui

import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * That a generation lets the interface draw, and does so at every stage.
 *
 * The defect this guards was invisible to every test the project had, because every test the
 * project had ran the engine on a thread of its own. In a browser there is one thread: pressing
 * Generate sets the busy state and then the first stage takes the thread and does not give it back
 * until the tenth has finished, so nothing the press changed is ever painted. From the outside that
 * is a page that has stopped answering — which is what William reported from his phone at 2048.
 *
 * So the test does what a browser does. It runs the generation and a second coroutine on the *same*
 * dispatcher, and asks what that second coroutine — standing in for the frame the browser wants to
 * draw — managed to see. If the generation never stands aside, the painter never runs and sees
 * nothing at all; if it stands aside at each boundary, the painter sees all ten stage names, in
 * order. That is the assertion.
 *
 * Shown failing first by deleting the wait from [Generation]'s stage callback, which is the whole
 * of the fix: the painter then recorded an empty list.
 */
class GenerationProgressTest {

    @Test
    fun `the interface gets the thread back at every stage of a generation`() = runTest {
        /** What the interface is currently showing, which is all a repaint would read. */
        var showing: String? = null

        /** What a repaint would have put on screen, each time it got the chance. */
        val painted = mutableListOf<String>()

        val painter = launch {
            while (true) {
                val now = showing
                if (now != null && painted.lastOrNull() != now) painted += now
                // Shorter than the generation's own wait, so a painter that is given the thread at
                // all is given it between every pair of stages rather than every other pair.
                delay(1)
            }
        }

        val world = Generation.run(
            SMALL_WORLD, previous = null, accelerator = null, oceanAccelerator = null
        ) {
            showing = it.label
        }
        painter.cancel()

        println("PROGRESS the interface painted ${painted.size} of ${STAGE_LABELS.size} stages")
        println("PROGRESS $painted")
        assertEquals(
            STAGE_LABELS,
            painted,
            "a browser would have painted these and no others while the world was made"
        )
        assertTrue(world.config.width == SMALL_WORLD.width, "the world came back the wrong size")
    }

    /**
     * That the busy state is on screen before the first cell is computed.
     *
     * The half of the complaint that is not about stages: "it does hang for a bit when you first
     * hit generate without any sign it's still working". Whatever the press of Generate set has to
     * be painted before the arithmetic starts, which means the very first thing a generation does
     * must be to stand aside — not the first thing after the terrain stage.
     */
    @Test
    fun `the interface is given a frame before any work starts`() = runTest {
        var painted = false
        var firstStage: String? = null

        val painter = launch {
            delay(1)
            painted = true
        }

        Generation.run(
            SMALL_WORLD, previous = null, accelerator = null, oceanAccelerator = null
        ) {
            if (firstStage == null) {
                firstStage = it.label
                assertTrue(
                    painted,
                    "the first stage started before the interface had drawn the busy state"
                )
            }
        }
        painter.cancel()
        assertEquals(STAGE_LABELS.first(), firstStage)
    }

    private companion object {
        /**
         * 128 cells: large enough that every stage runs and produces a world, small enough that the
         * guard is about the interleaving rather than about the generator's speed.
         */
        val SMALL_WORLD = WorldGenConfig(seed = 42L, width = 128, height = 128)

        val STAGE_LABELS: List<String> = GenerationStage.entries.map { it.label }
    }
}
