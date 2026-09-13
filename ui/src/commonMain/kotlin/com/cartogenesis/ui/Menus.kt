package com.cartogenesis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * The menu strip, declared as data and drawn once.
 *
 * Compose Desktop can hang a real native menu bar off its window, and that was the obvious thing
 * to do — and it would have left the browser build with no menus at all, because there is no such
 * thing to hang one off in a page. So the strip is drawn, in Compose, in `:ui`, and both front ends
 * get the same one. The cost is a strip that is not the platform's own; the gain is that File,
 * View and Help exist in the browser, that they are the same three menus with the same items in
 * the same order, and that this file is the only place either of them is described.
 *
 * Declared rather than written inline for the reason [Knobs] is: a menu is a list of promises about
 * what the application can do, and the only way to check that list without a person reading it is
 * for it to be a list. `MenusTest` walks exactly what [MenuStrip] draws.
 */

/** A keystroke, on the platforms that have a keyboard convention for one. */
internal class Shortcut(val key: Key, val shift: Boolean = false, val label: String)

/**
 * Everything the menus can do.
 *
 * Each carries its own label and its own shortcut, so the strip, the keyboard handler and the test
 * are three readers of one declaration rather than three copies of it. [needsWorld] marks the
 * items that are meaningless with a blank canvas up — since F0 the application opens without a
 * world, and offering "Save" then would be offering to save nothing.
 */
internal enum class MenuCommand(
    val label: String,
    val shortcut: Shortcut? = null,
    val needsWorld: Boolean = false
) {
    NEW_WORLD("New world", Shortcut(Key.N, label = "Ctrl+N")),
    OPEN_LIBRARY("Open library", Shortcut(Key.O, label = "Ctrl+O")),
    SAVE("Save", Shortcut(Key.S, label = "Ctrl+S"), needsWorld = true),
    SAVE_AS("Save as…", Shortcut(Key.S, shift = true, label = "Ctrl+Shift+S"), needsWorld = true),
    EXPORT("Export…", Shortcut(Key.E, label = "Ctrl+E"), needsWorld = true),
    SETTINGS("Settings…", Shortcut(Key.Comma, label = "Ctrl+,")),
    QUIT("Quit", Shortcut(Key.Q, label = "Ctrl+Q")),
    TOOLBAR("Toolbar over the map"),
    CHECK_UPDATES("Check for updates…"),
    ABOUT("About Cartogenesis");
}

internal object Menus {

    /**
     * File, in the order the spec gives.
     *
     * Quit is the one item whose presence depends on the host. A browser tab cannot close itself
     * (`window.close()` is refused for a page the script did not open), so offering it there would
     * be offering something that does nothing — see [Platform.canQuit].
     */
    fun file(platform: Platform): List<MenuCommand> = buildList {
        add(MenuCommand.NEW_WORLD)
        add(MenuCommand.OPEN_LIBRARY)
        add(MenuCommand.SAVE)
        add(MenuCommand.SAVE_AS)
        add(MenuCommand.EXPORT)
        add(MenuCommand.SETTINGS)
        if (platform.canQuit) add(MenuCommand.QUIT)
    }

    /** Help: the two items that are about the application rather than about a world. */
    val help: List<MenuCommand> = listOf(MenuCommand.CHECK_UPDATES, MenuCommand.ABOUT)

    /** The chromes the View menu's Theme submenu offers: every one there is. */
    val themes: List<ThemeChoice> = ThemeChoice.entries

    /**
     * The same sixteen, on three shelves.
     *
     * F7 took the list past the point where a flat run is a list: Standard, Accessible and Styled
     * are three headings over the same chromes in the same order within each, and every name and
     * every stored value is exactly what it was. A group with nothing in it is dropped rather than
     * drawn empty, which is only defensive — all three have members and a test says so.
     */
    val themeGroups: List<Pair<ThemeGroup, List<ThemeChoice>>> = ThemeGroup.entries
        .map { group -> group to themes.filter { it.group() == group } }
        .filter { it.second.isNotEmpty() }

    /** The panel sections View can show and hide: the same six the panel draws. */
    val sections: List<PanelSection> = PANEL_SECTIONS

    /**
     * Every shortcut, and what it does — the keyboard's copy of the menus.
     *
     * Only the desktop gets these. A browser has its own claim on Ctrl+N, Ctrl+O and Ctrl+S, and
     * quietly stealing them from the page's host is the sort of thing that loses somebody a tab
     * full of work.
     */
    fun shortcuts(platform: Platform): List<MenuCommand> =
        if (!platform.canQuit) emptyList()
        else file(platform).filter { it.shortcut != null }

    /**
     * The command this keystroke means, or null.
     *
     * Ctrl on Windows and Linux, Command on a Mac — both are accepted everywhere rather than being
     * chosen by host, since a keyboard that sends neither cannot trigger anything by accident. The
     * shift state must match exactly, or Ctrl+Shift+S would also fire Save.
     */
    fun match(event: KeyEvent, available: List<MenuCommand>): MenuCommand? {
        if (event.type != KeyEventType.KeyDown) return null
        if (!event.isCtrlPressed && !event.isMetaPressed) return null
        return available.firstOrNull { command ->
            val shortcut = command.shortcut ?: return@firstOrNull false
            shortcut.key == event.key && shortcut.shift == event.isShiftPressed
        }
    }
}

/**
 * The strip itself: three words along the top of the window, and the menus behind them.
 *
 * Drawn as a thin ruled band in the theme's own colours rather than in [OverMap]'s, because unlike
 * the toolbar this is *not* over the chart — it is the top edge of the window, above everything,
 * and it belongs to the chrome. One open menu at a time, held here rather than in three separate
 * flags, which is what stops two dropdowns being open at once.
 */
@Composable
internal fun MenuStrip(
    platform: Platform,
    hasWorld: Boolean,
    settings: AppSettings,
    sections: SectionState,
    toolbarVisible: Boolean,
    onCommand: (MenuCommand) -> Unit,
    onTheme: (ThemeChoice) -> Unit
) {
    var open by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme

    Surface(color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuButton("File", open == "File", { open = if (open == "File") null else "File" }) {
                Menus.file(platform).forEach { command ->
                    CommandItem(command, hasWorld) {
                        open = null
                        onCommand(command)
                    }
                }
            }

            MenuButton("View", open == "View", { open = if (open == "View") null else "View" }) {
                // The theme submenu, flattened into a labelled run of items with a tick beside the
                // current one. A real nested submenu is a hover-timing problem Material 3 has no
                // component for, and sixteen items do not need one — but they do need the three
                // headings F7 put over them, which is all `themeGroups` is.
                MenuHeading("Theme")
                Menus.themeGroups.forEach { (group, chromes) ->
                    MenuSubHeading(group.label)
                    chromes.forEach { theme ->
                        Ticked(theme.label, settings.theme == theme) {
                            open = null
                            onTheme(theme)
                        }
                    }
                }
                MenuHeading("Panel")
                Menus.sections.forEach { section ->
                    Ticked(section.title, sections.isOpen(section)) { sections.toggle(section) }
                }
                MenuHeading("Map")
                Ticked(MenuCommand.TOOLBAR.label, toolbarVisible) {
                    onCommand(MenuCommand.TOOLBAR)
                }
            }

            MenuButton("Help", open == "Help", { open = if (open == "Help") null else "Help" }) {
                Menus.help.forEach { command ->
                    CommandItem(command, hasWorld) {
                        open = null
                        onCommand(command)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))
            Text(
                "Cartogenesis ${BuildInfo.VERSION}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
    Box(Modifier.fillMaxWidth().background(scheme.outlineVariant).padding(top = 1.dp))
}

/**
 * The whole menu strip as one button, for a window with no room for a strip.
 *
 * Three words along the top of a 390 dp screen is not a bad menu bar; it is 30 dp of a screen that
 * has 844 of them, spent on three targets of about 40 by 24 dp each, above a map that wants every
 * pixel. So on a phone the strip folds into a single glyph over the map's top-left corner and the
 * three menus become one, in the same order, under their own headings — File, then View's themes,
 * sections and toolbar, then Help. Nothing is dropped: [MenuStrip] and this draw the same
 * [Menus.file], [Menus.themes], [Menus.sections] and [Menus.help], which is what `PanelKnobsTest`
 * compares when it asks whether the compact arrangement can still reach everything.
 *
 * Over the chart it is drawn in [OverMap]'s ink, which is what [tint] defaults to — the menu it
 * opens is a sheet over the application and takes the theme's paper either way, exactly as the view
 * menu beside it does. F8 gave the atlas and the library a bar of their own, which is ordinary
 * chrome rather than an annotation on a chart, and the same glyph on that bar has to be the
 * scheme's ink or it is parchment on paper. Hence the argument: one button, drawn in whatever
 * colour the surface it lies on calls for.
 */
@Composable
internal fun CompactMenuButton(
    platform: Platform,
    hasWorld: Boolean,
    settings: AppSettings,
    sections: SectionState,
    toolbarVisible: Boolean,
    onCommand: (MenuCommand) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    tint: Color = OverMap.Parchment
) {
    var open by remember { mutableStateOf(false) }
    val minimum = LocalTouchTargets.current.minTarget
    Box {
        Box(
            Modifier
                .clickableNoRipple { open = true }
                .sizeIn(minWidth = minimum, minHeight = minimum)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Menu",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 6.dp
        ) {
            MenuHeading("File")
            Menus.file(platform).forEach { command ->
                CommandItem(command, hasWorld) {
                    open = false
                    onCommand(command)
                }
            }
            MenuHeading("Theme")
            Menus.themeGroups.forEach { (group, chromes) ->
                MenuSubHeading(group.label)
                chromes.forEach { theme ->
                    Ticked(theme.label, settings.theme == theme) {
                        open = false
                        onTheme(theme)
                    }
                }
            }
            MenuHeading("Panel")
            Menus.sections.forEach { section ->
                Ticked(section.title, sections.isOpen(section)) { sections.toggle(section) }
            }
            MenuHeading("Map")
            Ticked(MenuCommand.TOOLBAR.label, toolbarVisible) { onCommand(MenuCommand.TOOLBAR) }
            MenuHeading("Help")
            Menus.help.forEach { command ->
                CommandItem(command, hasWorld) {
                    open = false
                    onCommand(command)
                }
            }
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Box {
        // A menu title is not a button: no border, no fill, and it inks up when its menu is open.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (expanded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickableNoRipple(onToggle)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onToggle,
            // A menu is a sheet lifted off the page, and the theme deliberately leaves no tonal
            // elevation to say so — every surface is the same paper. So it is told apart the way
            // every other panel here is: a shade of the paper, and a ruled edge.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 6.dp
        ) { content() }
    }
}

@Composable
private fun CommandItem(command: MenuCommand, hasWorld: Boolean, onClick: () -> Unit) {
    val enabled = !command.needsWorld || hasWorld
    DropdownMenuItem(
        text = { Text(command.label, style = MaterialTheme.typography.bodyMedium) },
        enabled = enabled,
        onClick = onClick,
        trailingIcon = command.shortcut?.let { shortcut ->
            {
                Text(
                    shortcut.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/** A choice that is either on or off, marked the way a printed list marks one: with a tick. */
@Composable
private fun Ticked(label: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        leadingIcon = {
            Text(
                if (on) "✓" else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        LocalChromeDetail.current.heading(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
    )
}

/**
 * A shelf inside a run of menu items: Standard, Accessible, Styled.
 *
 * Quieter and further in than [MenuHeading], because these sit *under* "Theme" rather than beside
 * "Panel" and "Map" — three of them at the same weight as their parent would read as six headings
 * rather than as one with three shelves.
 */
@Composable
private fun MenuSubHeading(text: String) {
    Text(
        LocalChromeDetail.current.heading(text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 1.dp)
    )
}

/**
 * A click without Material's ripple.
 *
 * The strip's titles are words on a band, and a spreading circle of tinted ink under one is the
 * single most Material thing left in the application.
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )
