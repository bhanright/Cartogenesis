package com.cartogenesis.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That typing a seed and then clicking somewhere else starts nothing.
 *
 * William, on 2.0.1: he would type a seed, click across to another setting, and a world would begin
 * building at once — so the setting he had gone to change was locked out for the length of a
 * generation he had not asked for. The field applied its value on losing focus, and losing focus is
 * exactly what a reader does on their way to the next control. Enter and Go are asks; a click
 * elsewhere is not.
 *
 * Driven against a real composition rather than against [com.cartogenesis.ui.Knobs], because the
 * defect was in when a callback fires and not in what it writes. The evidence that nothing started
 * is taken two ways: the button still reads Generate rather than Stop, and the world on the map is
 * still the world that was there — its cartouche names the seed it was made from, which is the one
 * durable record of which world is on screen.
 *
 * Shown failing by restoring the focus-loss apply (an `onFocusChanged { if (!it.isFocused) apply() }`
 * on the field, which is what 2.0.1 had) in a scratch copy of `App.kt`: the map then carried
 * seed 777001 where this expects the world it started with, and the Stop button was up.
 */
class SeedFieldTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a typed seed applies on Enter and on Go, and never on losing focus`() {
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(SmallWorldPlatform()) } }

            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { seedOnTheMap() != null }
            val started = seedOnTheMap()
            println("SEED the map opened on $started")

            // Typed, then abandoned by going to another control. Nothing may follow from that.
            typeSeed(ABANDONED)
            onNodeWithText("Name").performClick()
            waitForIdle()

            assertTrue(
                onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty(),
                "clicking out of the seed field started a generation: the button reads Stop"
            )
            assertEquals(
                started,
                seedOnTheMap(),
                "clicking out of the seed field changed the world on the map"
            )

            // Enter is an ask, and the map has to follow it.
            typeSeed(BY_ENTER)
            onNodeWithText("Seed").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { seedOnTheMap() == BY_ENTER }
            println("SEED Enter took the map to $BY_ENTER")

            // So is Go.
            typeSeed(BY_GO)
            onNodeWithText("Go").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { seedOnTheMap() == BY_GO }
            println("SEED Go took the map to $BY_GO")
        }
    }

    /** Puts the cursor in the seed field, the way a reader does, and replaces what is in it. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.typeSeed(seed: Long) {
        onNodeWithText("Seed").performClick()
        onNodeWithText("Seed").performTextReplacement(seed.toString())
        waitForIdle()
    }

    /**
     * The seed of the world currently drawn, read off the cartouche in the map's legend.
     *
     * Null until a world exists, which is also how the wait for the first generation is expressed.
     * The cartouche is the only place the seed is written as a fact about the map rather than as
     * the contents of an editable field, so it cannot be confused with what has merely been typed.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.seedOnTheMap(): Long? {
        var found: Long? = null
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                CARTOUCHE_FACTS.find(text.text)?.let { found = it.groupValues[1].toLong() }
            }
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        return found
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900

        /** `seed 59758 · 512 × 512`, which is [com.cartogenesis.ui.Cartouches.facts]. */
        val CARTOUCHE_FACTS = Regex("""seed (-?\d+) · \d+ × \d+""")

        const val ABANDONED = 777_001L
        const val BY_ENTER = 777_002L
        const val BY_GO = 777_003L

        /** Three 512 worlds on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L
    }
}

/** The desktop, told to work at 512, so three generations fit in a test. */
private class SmallWorldPlatform(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
}
