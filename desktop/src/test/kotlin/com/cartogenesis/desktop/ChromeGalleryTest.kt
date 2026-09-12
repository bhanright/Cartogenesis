package com.cartogenesis.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.ThemeChoice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The whole application, in both themes, written out to be looked at.
 *
 * `StyleGalleryTest` does this for the map; this does it for everything around the map, and for
 * the same reason. A theme is a claim about appearance and there is no numeric test for whether
 * chrome looks like ink on paper — but a screenshot can be looked at, by whoever is reviewing the
 * change and by whoever revisits it a year later, and a theme that quietly reverted to Material's
 * lilac would be obvious in one glance and invisible in every assertion anyone would think to
 * write.
 *
 * What *is* asserted is the little that can be: that both shots were produced at the size asked
 * for, that they differ from each other (a theme that ignored its `dark` argument would produce
 * two identical files), and that neither is a blank page.
 */
class ChromeGalleryTest {

    @Test
    fun `the whole interface renders in both themes`() {
        val dir = File("build/screens").apply { mkdirs() }

        val light = shoot(dark = false)
        val dark = shoot(dark = true)

        File(dir, "chrome-light.png").writeBytes(light.png)
        File(dir, "chrome-dark.png").writeBytes(dark.png)
        println("CHROME wrote two ${WIDTH}x$HEIGHT shots to ${dir.absolutePath}")
        println(
            "CHROME light fingerprint ${light.fingerprint} over ${light.distinctColours} colours, " +
                "dark ${dark.fingerprint} over ${dark.distinctColours}"
        )

        assertTrue(
            light.fingerprint != dark.fingerprint,
            "the light and dark themes rendered identically, so the theme is not being applied"
        )
        // A window drawn in one flat colour is what a failure to lay anything out looks like, and
        // it would still have written a plausible-looking file.
        assertTrue(light.distinctColours > 200, "the light shot is nearly blank")
        assertTrue(dark.distinctColours > 200, "the dark shot is nearly blank")
    }

    /**
     * The same window with every section of the panel unrolled.
     *
     * F2's subject is the panel, and the panel a reader first sees is five ruled headings with a
     * `+` at the margin — which is the point of it, and which shows none of the settings inside.
     * So there is a second light shot with all six sections open, for reviewing what F2 actually
     * put in them. It doubles as the only assertion anyone can make about a disclosure control
     * without reading pixels: clicking the headings has to change the picture.
     */
    @Test
    fun `the sections unroll`() {
        val dir = File("build/screens").apply { mkdirs() }

        val closed = shoot(dark = false)
        val open = shoot(dark = false, openSections = true)

        File(dir, "chrome-sections.png").writeBytes(open.png)
        println("CHROME wrote the unrolled panel to ${dir.absolutePath}")

        assertTrue(
            closed.fingerprint != open.fingerprint,
            "opening every section changed nothing on screen"
        )
    }

    /**
     * F4's additions — the window with its menu strip, the File menu open, Settings, About — in
     * light, dark and Mars.
     *
     * Twelve shots, and none of them waits for a world: everything F4 draws is chrome, and a blank
     * canvas photographs it in a tenth of the time a generated one does.
     *
     * The menu and the two dialogs are photographed *on their own* rather than over the window,
     * and that is Compose's doing rather than a choice: a `DropdownMenu` and an `AlertDialog` are
     * each drawn into a layer of their own, which is a second root in the semantics tree, and a
     * capture of the window's root does not contain them. So each of those three is captured from
     * its own root — which is also the assertion that it opened at all, since there is no second
     * root until something does.
     *
     * What is asserted otherwise is what a screenshot can be asked: that each chrome produces a
     * different picture of the same thing (a theme that ignored its argument would produce three
     * identical files), and that nothing came out blank.
     */
    @Test
    fun `the menus and the F4 dialogs are photographed in three chromes`() {
        val dir = File("build/screens").apply { mkdirs() }
        val chromes = listOf(
            "light" to ThemeChoice.LIGHT,
            "dark" to ThemeChoice.DARK,
            "mars" to ThemeChoice.MARS
        )
        val subjects = listOf(
            "window" to Opened.NOTHING,
            "menu" to Opened.MENU,
            "settings" to Opened.SETTINGS,
            "about" to Opened.ABOUT
        )

        val fingerprints = mutableMapOf<String, Int>()
        subjects.forEach { (what, opened) ->
            chromes.forEach { (name, choice) ->
                val shot = shootChrome(choice, opened)
                File(dir, "f4-$what-$name.png").writeBytes(shot.png)
                fingerprints["$what-$name"] = shot.fingerprint
                assertTrue(shot.distinctColours > 3, "the $what shot in $name is a flat colour")
            }
            val three = chromes.map { fingerprints["$what-${it.first}"] }
            assertEquals(
                3,
                three.toSet().size,
                "$what looks the same in all three chromes, so the theme is not reaching it"
            )
        }
        println("CHROME wrote twelve F4 shots (window, menu, settings, about x three chromes) to $dir")
    }

    /**
     * F5's subject: the same application at a phone's size and a portrait tablet's, beside the
     * 1440x900 the rest of this file photographs, in both themes.
     *
     * Two shots of each, because a compact window has two states and both are the point: the map
     * with the settings sheet down, which is what the application opens as and is the whole reason
     * for the arrangement, and the sheet pulled up, which is where every knob went. The world is
     * generated from inside the sheet, which is also how a reader gets there.
     *
     * Both sizes are driven as touchscreens ([TouchPlatform]), so what is captured is the theme's
     * coarse-pointer targets — the taller sliders and the 48 dp switches — rather than the mouse's.
     */
    @Test
    fun `the interface renders on a phone and on a tablet`() {
        val dir = File("build/screens").apply { mkdirs() }
        val sizes = listOf("phone" to (390 to 844), "tablet" to (768 to 1024))
        val fingerprints = mutableMapOf<String, Int>()

        sizes.forEach { (name, size) ->
            listOf("light" to false, "dark" to true).forEach { (tone, dark) ->
                val shots = shootCompact(dark = dark, width = size.first, height = size.second)
                File(dir, "$name-$tone.png").writeBytes(shots.first.png)
                File(dir, "$name-$tone-sheet.png").writeBytes(shots.second.png)
                fingerprints["$name-$tone"] = shots.first.fingerprint
                fingerprints["$name-$tone-sheet"] = shots.second.fingerprint
                assertTrue(
                    shots.first.distinctColours > 200,
                    "the $name shot in $tone is nearly blank"
                )
                assertTrue(
                    shots.first.fingerprint != shots.second.fingerprint,
                    "pulling the sheet up on the $name in $tone changed nothing on screen"
                )
            }
        }

        println("CHROME wrote eight compact shots (390x844 and 768x1024, light and dark, sheet " +
            "down and up) to ${dir.absolutePath}")
        println("CHROME compact fingerprints $fingerprints")
        assertEquals(
            fingerprints.size,
            fingerprints.values.toSet().size,
            "two of the compact shots are identical: $fingerprints"
        )
    }

    /**
     * The guard the spec asks `PanelKnobsTest` for, asked again from outside the module and against
     * a real composition.
     *
     * `PanelKnobsTest` walks the declaration the two arrangements are rendered from, which catches
     * a control dropped from the declaration. This catches the other half: a sheet that draws the
     * declaration and then cannot fit it, or a section that never composes because the sheet is the
     * wrong height. It reads every string in the semantics tree at 1440x900 with the panel open and
     * at 390x844 with the sheet open, and requires the panel's own copy — every knob, every heading
     * and every header control — in both.
     *
     * The list is written out rather than read from `Knobs`, which is internal to `:ui`: this is
     * the interface as the outside sees it, and a knob renamed here is a knob renamed for a reader.
     */
    @Test
    fun `the phone's sheet holds every control the wide panel does`() {
        val wide = textsInWideWindow()
        val phone = textsInPhoneSheet()

        val missingFromWide = PANEL_CONTROLS.filterNot { it in wide }
        val missingFromPhone = PANEL_CONTROLS.filterNot { it in phone }
        println("CHROME wide window shows ${wide.size} strings, phone sheet ${phone.size}")
        assertTrue(missingFromWide.isEmpty(), "the wide panel no longer shows: $missingFromWide")
        assertTrue(missingFromPhone.isEmpty(), "the phone's sheet cannot reach: $missingFromPhone")
    }

    private enum class Opened { NOTHING, MENU, SETTINGS, ABOUT }

    /**
     * The window in one chrome with one thing open, on a blank canvas.
     *
     * The dialogs are reached the way a reader reaches them — File, then Settings; Help, then
     * About — rather than by being composed directly, so the shot also proves the menu items are
     * wired to what they claim to open.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun shootChrome(choice: ThemeChoice, opened: Opened): Shot {
        var shot: Shot? = null
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent {
                CartogenesisTheme(choice = choice) {
                    CartogenesisApp(ChromePlatform())
                }
            }
            waitForIdle()
            when (opened) {
                Opened.NOTHING -> Unit
                Opened.MENU -> onNodeWithText("File").performClick()
                Opened.SETTINGS -> {
                    onNodeWithText("File").performClick()
                    waitForIdle()
                    onNodeWithText("Settings…").performClick()
                }
                Opened.ABOUT -> {
                    onNodeWithText("Help").performClick()
                    waitForIdle()
                    onNodeWithText("About Cartogenesis").performClick()
                }
            }
            waitForIdle()

            val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
            if (opened == Opened.NOTHING) {
                assertEquals(1, roots, "something was open over an untouched window")
            } else {
                assertEquals(2, roots, "$opened did not open: there is no layer over the window")
            }
            shot = capture(onAllNodes(isRoot())[roots - 1])
        }
        return shot ?: error("the composition never produced a frame")
    }

    private class Shot(val png: ByteArray, val fingerprint: Int, val distinctColours: Int)

    /**
     * Composes the application at 1440x900, presses Generate, waits for the world, and captures.
     *
     * Pressing the button rather than handing the app a world is deliberate: since F0 the app opens
     * on a blank canvas, so this is also the only way to photograph it with a map in it, and it
     * exercises the same path a reader takes.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun shoot(dark: Boolean, openSections: Boolean = false): Shot {
        var shot: Shot? = null
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            val platform = ChromePlatform()
            setContent {
                CartogenesisTheme(dark = dark) { CartogenesisApp(platform) }
            }
            onNodeWithText("Generate").performClick()
            // The cartouche in the map's legend is written only once a world exists, and its
            // "seed N · 512 × 512" is the only place the resolution appears written out that way —
            // the panel's own chips say "512" alone. Waiting on anything vaguer than this
            // photographs a half-drawn world, which is what the first run of this test did.
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("512 × 512", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()

            if (openSections) {
                // In the panel's own order. Since F3 took the style and view lists out of
                // Cartography there is nothing inside a section whose name could be mistaken for
                // a heading, but the order is still the reader's.
                listOf("Terrain", "Climate", "Water", "Peoples", "Cartography").forEach {
                    onNodeWithText(it).performClick()
                    waitForIdle()
                }
            }

            shot = capture()
        }
        return shot ?: error("the composition never produced a frame")
    }

    /**
     * The compact window, twice: with the settings sheet down, and with it up.
     *
     * The order is the reader's. Nothing can be generated until the sheet is up, because that is
     * where Generate lives now, so the sheet goes up first and the map-only shot is taken after it
     * comes back down — which is also the only assertion available that the handle works in both
     * directions.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun shootCompact(dark: Boolean, width: Int, height: Int): Pair<Shot, Shot> {
        var down: Shot? = null
        var up: Shot? = null
        runDesktopComposeUiTest(width = width, height = height) {
            val platform = TouchPlatform()
            setContent {
                CartogenesisTheme(dark = dark, coarsePointer = platform.coarsePointer) {
                    CartogenesisApp(platform)
                }
            }
            waitForIdle()
            // The sheet's own handle. "Settings…" on the File menu is a different string and that
            // menu is not composed until it is opened, so this is unambiguous.
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("512 × 512", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            up = capture()

            onNodeWithText("Settings").performClick()
            waitForIdle()
            down = capture()
        }
        return (down ?: error("no frame")) to (up ?: error("no frame"))
    }

    /** Every string in the semantics tree of a 1440x900 window with all six sections unrolled. */
    @OptIn(ExperimentalTestApi::class)
    private fun textsInWideWindow(): Set<String> {
        var found: Set<String> = emptySet()
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(ChromePlatform()) } }
            waitForIdle()
            unrollEverySection()
            found = allText()
        }
        return found
    }

    /** The same, at a phone's size, with the sheet pulled up and every section unrolled. */
    @OptIn(ExperimentalTestApi::class)
    private fun textsInPhoneSheet(): Set<String> {
        var found: Set<String> = emptySet()
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            val platform = TouchPlatform()
            setContent {
                CartogenesisTheme(dark = false, coarsePointer = platform.coarsePointer) {
                    CartogenesisApp(platform)
                }
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            unrollEverySection()
            found = allText()
        }
        return found
    }

    /**
     * Opens the five rolled-up sections, scrolling each heading into view first.
     *
     * The scroll is not decoration. Expanding one section pushes the next heading below the fold of
     * a 320 dp column — and below the fold of a phone's sheet even faster — and a click on a node
     * whose centre is off screen lands on nothing, so without this only the first section ever
     * opens and the rest of the panel is never composed. That is exactly the failure this test
     * caught on its first run.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.unrollEverySection() {
        listOf("Terrain", "Climate", "Water", "Peoples", "Cartography").forEach { heading ->
            onNodeWithText(heading).performScrollTo().performClick()
            waitForIdle()
        }
    }

    /**
     * Every piece of text the composition is showing, however deep.
     *
     * A scrolling column composes all of its children whether or not they are on screen, so this
     * finds what is below the fold as well — which is the point, since the whole panel does not fit
     * on a phone at once and is not meant to.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.allText(): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { found += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { found += it.text }
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        return found
    }

    /** The window as it stands: a PNG, a content hash, and how many colours are actually in it. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.capture(): Shot = capture(onRoot())

    /** The same, of one node — a dialog's own layer, where that is what is being photographed. */
    @OptIn(ExperimentalTestApi::class)
    private fun capture(node: SemanticsNodeInteraction): Shot {
        val bitmap = node.captureToImage().asSkiaBitmap()
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
        val pixels = bitmap.readPixels()!!
        var hash = 17
        for (k in pixels.indices step 997) hash = hash * 31 + pixels[k]
        val colours = HashSet<Int>()
        // Every hundredth pixel, packed: enough to tell a drawn window from a filled one.
        for (k in 0 until pixels.size - 4 step 400) {
            colours += (pixels[k].toInt() and 0xFF shl 16) or
                (pixels[k + 1].toInt() and 0xFF shl 8) or
                (pixels[k + 2].toInt() and 0xFF)
        }
        return Shot(png, hash, colours.size)
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900

        /** An iPhone 14's viewport in CSS pixels, which is the size F5 was drawn against. */
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        /** Generous: this is a full 512 world on the CPU, on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L

        /**
         * The panel's own copy, as a reader sees it: the header's controls, the six section
         * headings, and every knob inside them. Both arrangements must show all of it.
         */
        val PANEL_CONTROLS = listOf(
            "Seed", "Name", "Generate", "New world", "Working resolution", "Export",
            "Library", "Atlas",
            "World", "Ocean coverage",
            "Terrain", "Plates", "Mountain height", "Erosion strength",
            "Climate", "Seasonal tilt", "Rain shadow", "Ice sheets and glaciers",
            "Water", "Rivers", "Lakes", "Dry basins hold less water",
            "Peoples", "Realms", "Leave wilderness unclaimed", "Realm borders",
            "Cartography", "Relief shading", "Coastline"
        )
    }
}

/**
 * The desktop, told to start small.
 *
 * Everything real — the library on disk, the graphics device, the export dialog — is the desktop's
 * own, so the shot shows the interface a reader actually gets, including whatever the machine says
 * about its graphics card. Only the working resolution is overridden, because 1024 is a slow world
 * to wait for in a test and 512 photographs identically.
 */
private class ChromePlatform(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
}

/**
 * The same desktop, reporting a fingertip.
 *
 * What the compact shots are of is a phone, and half of what F5 changed for one is the size of the
 * targets — which the theme takes from the pointer rather than from the width, so photographing the
 * arrangement without also reporting a coarse pointer would photograph a phone-shaped window drawn
 * with a mouse's 13 dp slider thumbs. A delegating override rather than a second platform, so
 * everything else about the shot is still the real desktop's answers.
 */
private class TouchPlatform(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
    override val coarsePointer: Boolean = true
}
