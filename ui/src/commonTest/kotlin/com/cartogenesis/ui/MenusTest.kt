package com.cartogenesis.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the three menus offer, checked against the specification rather than against themselves.
 *
 * The list is written out here in full and in order on purpose. A test that asked "does File
 * contain what `Menus.file` returns?" would pass whatever either of them said; this one fails if an
 * item is dropped, renamed or reordered, which is exactly the change nobody notices in a diff.
 */
class MenusTest {

    @Test
    fun `File offers the six document actions, and Quit only where there is something to quit`() {
        val browser = FakePlatform(canQuit = false)
        val desktop = FakePlatform(canQuit = true)

        assertEquals(
            listOf(
                MenuCommand.NEW_WORLD,
                MenuCommand.OPEN_LIBRARY,
                MenuCommand.SAVE,
                MenuCommand.SAVE_AS,
                MenuCommand.EXPORT,
                MenuCommand.SETTINGS
            ),
            Menus.file(browser)
        )
        assertEquals(
            Menus.file(browser) + MenuCommand.QUIT,
            Menus.file(desktop),
            "the desktop's File menu is the same one with Quit at the foot"
        )
    }

    @Test
    fun `Help offers the update check and About`() {
        assertEquals(listOf(MenuCommand.CHECK_UPDATES, MenuCommand.ABOUT), Menus.help)
    }

    @Test
    fun `View offers every chrome, every panel section and the toolbar`() {
        assertEquals(ThemeChoice.entries.toList(), Menus.themes)
        assertEquals(PANEL_SECTIONS, Menus.sections)
        assertEquals(6, Menus.sections.size)
        // The toolbar toggle is a command like any other, so the keyboard and the strip agree.
        assertTrue(MenuCommand.TOOLBAR.label.isNotBlank())
        assertNull(MenuCommand.TOOLBAR.shortcut)
    }

    @Test
    fun `the items that need a world say so, and the ones that do not, do not`() {
        assertTrue(MenuCommand.SAVE.needsWorld)
        assertTrue(MenuCommand.SAVE_AS.needsWorld)
        assertTrue(MenuCommand.EXPORT.needsWorld)
        assertFalse(MenuCommand.NEW_WORLD.needsWorld)
        assertFalse(MenuCommand.SETTINGS.needsWorld)
        assertFalse(MenuCommand.ABOUT.needsWorld)
    }

    @Test
    fun `no two shortcuts are the same keystroke`() {
        val strokes = MenuCommand.entries.mapNotNull { it.shortcut }.map { it.key to it.shift }
        assertEquals(strokes.size, strokes.toSet().size, "two menu items share a keystroke")
        // Save and Save as differ by shift alone, which is the convention and also the case a
        // match on the key alone would get wrong.
        val save = requireNotNull(MenuCommand.SAVE.shortcut)
        val saveAs = requireNotNull(MenuCommand.SAVE_AS.shortcut)
        assertEquals(save.key, saveAs.key)
        assertFalse(save.shift)
        assertTrue(saveAs.shift)
    }

    @Test
    fun `the keyboard is the desktop's, and it is the File menu's`() {
        val desktop = FakePlatform(canQuit = true)
        val browser = FakePlatform(canQuit = false)

        assertEquals(Menus.file(desktop), Menus.shortcuts(desktop))
        assertTrue(
            Menus.shortcuts(browser).isEmpty(),
            "the browser owns Ctrl+N, Ctrl+O and Ctrl+S; the page must not take them"
        )
    }

    @Test
    fun `every shortcut prints the keystroke it actually listens for`() {
        val newWorld = requireNotNull(MenuCommand.NEW_WORLD.shortcut)
        val settings = requireNotNull(MenuCommand.SETTINGS.shortcut)
        assertEquals("Ctrl+N", newWorld.label)
        assertEquals(Key.N, newWorld.key)
        assertEquals("Ctrl+Shift+S", requireNotNull(MenuCommand.SAVE_AS.shortcut).label)
        assertEquals("Ctrl+,", settings.label)
        assertEquals(Key.Comma, settings.key)
        assertEquals("Ctrl+Q", requireNotNull(MenuCommand.QUIT.shortcut).label)
    }
}
