package com.cartogenesis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The three dialogs F4 adds: Settings, About, and the answer to "check for updates".
 *
 * None of them draws a control of its own. Every switch, chip and button here comes from
 * [Controls], which is what keeps the dialogs looking like the rest of the application rather than
 * like three Material demonstrations that happen to be in the same window — and it is why none of
 * them names a colour.
 */

/**
 * Preferences, in the order the spec lists them.
 *
 * The heading of each row says what it is a default *for*, because the distinction between a
 * preference and a setting of the world is the one thing a reader has to understand about this
 * dialog: nothing in here changes the world on screen. The small print at the foot says where the
 * file is, which is the other question this dialog gets asked.
 */
@Composable
internal fun SettingsDialog(
    settings: AppSettings,
    platform: Platform,
    onSettings: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    // 2048 rather than 4096 in a phone browser, and the small print below says so. Read from the
    // composition rather than passed in because this dialog is opened from a menu item that knows
    // nothing about the window's shape.
    val ceiling = platform.exportCeiling(LocalWindowShape.current == WindowShape.COMPACT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingRow("Theme", "Applies at once. System follows this machine's own setting.") {
                    ChipRow(
                        options = Menus.themes,
                        selected = settings.theme,
                        label = { it.label },
                        onSelect = { onSettings(settings.copy(theme = it)) }
                    )
                }

                SettingRow(
                    "Working resolution",
                    "The grid a new world starts at. The world on screen keeps its own."
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = settings.workingResolution == AppSettings.FOLLOW_PLATFORM,
                            onClick = {
                                onSettings(
                                    settings.copy(workingResolution = AppSettings.FOLLOW_PLATFORM)
                                )
                            },
                            label = { Text("This platform", maxLines = 1) }
                        )
                        Knobs.RESOLUTIONS.forEach { size ->
                            FilterChip(
                                selected = settings.workingResolution == size,
                                onClick = { onSettings(settings.copy(workingResolution = size)) },
                                label = { Text("$size", maxLines = 1) }
                            )
                        }
                    }
                }

                // Absent rather than disabled where the host has no graphics API at all, for the
                // reason [Arrangements.headerKnobs] gives: a phone browser without WebGPU is owed
                // no explanation of a feature its device does not have, and the header switch this
                // is the preference for is not drawn there either.
                if (platform.graphicsApiPresent) {
                    SettingRow(
                        "Graphics card at launch",
                        platform.accelerator?.let { "Erosion starts on ${it.name}." }
                            ?: "Unavailable here: ${platform.accelerationUnavailableBecause}"
                    ) {
                        Toggle(
                            "Generate on the graphics card",
                            settings.graphicsCardAtLaunch,
                            enabled = platform.accelerator != null
                        ) { onSettings(settings.copy(graphicsCardAtLaunch = it)) }
                    }
                }

                SettingRow(
                    "Export",
                    "What the export buttons start as. " +
                        "Nothing above $ceiling can be finished by this build."
                ) {
                    ChipRow(
                        options = ExportFormat.entries,
                        selected = settings.exportFormat,
                        label = { it.label },
                        onSelect = { onSettings(settings.copy(exportFormat = it)) }
                    )
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Exports.SIZES.forEach { size ->
                            val reachable = Exports.reachable(size, ceiling)
                            FilterChip(
                                selected = settings.exportSize == size,
                                enabled = reachable,
                                onClick = { onSettings(settings.copy(exportSize = size)) },
                                label = { Text("$size", maxLines = 1) }
                            )
                        }
                    }
                }

                SettingRow(
                    "Library folder",
                    SettingsEffects.libraryLocation(settings, platform)
                ) {
                    if (platform.canRevealFolder) {
                        var typed by remember(settings.libraryFolder) {
                            mutableStateOf(settings.libraryFolder)
                        }
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Folder") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onSettings(settings.copy(libraryFolder = typed.trim())) },
                                contentPadding = TIGHT
                            ) { Text("Use this folder", maxLines = 1) }
                            OutlinedButton(
                                onClick = {
                                    platform.revealFolder(
                                        SettingsEffects.libraryLocation(settings, platform)
                                    )
                                },
                                contentPadding = TIGHT
                            ) { Text("Open folder", maxLines = 1) }
                        }
                    } else {
                        Text(
                            "This platform keeps its library where it keeps it; there is no " +
                                "folder to choose.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SettingRow("Interface scale", "Applies at once, to everything but the map.") {
                    ChipRow(
                        options = AppSettings.SCALES,
                        selected = settings.interfaceScale,
                        label = { "${(it * 100).toInt()}%" },
                        onSelect = { onSettings(settings.copy(interfaceScale = it)) }
                    )
                }

                SettingRow(
                    "Updates",
                    "Off by default: opening the application should not talk to GitHub."
                ) {
                    Toggle("Check for updates at launch", settings.checkForUpdatesOnLaunch) {
                        onSettings(settings.copy(checkForUpdatesOnLaunch = it))
                    }
                }

                Text(
                    "Kept in ${platform.settingsStore.location}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { onSettings(AppSettings()) }) { Text("Reset to defaults") }
        }
    )
}

/** A heading, a line of why, and the control. The whole layout vocabulary of the dialog. */
@Composable
private fun SettingRow(title: String, note: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}

/** One choice from a short list, as a row of chips that wraps if the dialog is narrow. */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1) }
            )
        }
    }
}

/**
 * Which build this is, what it may be used under, and what it is built out of.
 *
 * The third-party notices are the part that matters legally rather than decoratively: the two type
 * faces the interface is set in are licensed under the SIL Open Font License, which requires the
 * licence to travel with anything that embeds them, and they *are* embedded — in the desktop jar
 * and in the wasm bundle alike. So the list is generated from the dependency graph at build time
 * (see `ui/build.gradle.kts`) rather than typed here, where it would go stale the first time
 * anybody changed a dependency and nobody would notice.
 */
@Composable
internal fun AboutDialog(platform: Platform, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cartogenesis", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.widthIn(max = 560.dp).heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Version ${BuildInfo.VERSION}, built ${BuildInfo.BUILD_DATE}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "A generator of worlds: tectonics, erosion, climate, rivers and peoples, " +
                        "drawn as a map.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Text(
                    "Licence",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(BuildInfo.LICENCE, style = MaterialTheme.typography.bodySmall)

                Text(
                    "Third-party notices",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    "${Notices.entries.size} components, generated from this build's own " +
                        "dependency graph.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Notices.entries.forEach { notice ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(notice.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            notice.licence,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (platform.canOpenLinks) {
                TextButton(onClick = { platform.openLink(BuildInfo.PROJECT_URL) }) {
                    Text("Project page")
                }
            }
        }
    )
}

/**
 * What the update check found, once it has finished finding it.
 *
 * The fetch is started by whoever opened this — see [CartogenesisApp] — and handed in as a state,
 * so the dialog is a pure rendering of an answer and never itself reaches the network.
 */
@Composable
internal fun UpdateDialog(
    status: Updates.Status?,
    platform: Platform,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check for updates", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.widthIn(max = 480.dp)) {
                when (status) {
                    null -> Text("Asking GitHub…", style = MaterialTheme.typography.bodyMedium)

                    is Updates.Status.UpToDate -> Text(
                        "Version ${status.version} is the latest release.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is Updates.Status.Unknown -> Text(
                        status.reason,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is Updates.Status.Available -> {
                        Text(
                            "Version ${status.version} is available. This is ${BuildInfo.VERSION}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            status.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            status.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "Nothing is downloaded or installed here: the button opens the " +
                                "release page.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        if (!platform.canOpenLinks) {
                            Text(
                                status.page,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            val available = status as? Updates.Status.Available
            if (available != null && platform.canOpenLinks) {
                TextButton(onClick = { platform.openLink(available.page) }) {
                    Text("Open the release page")
                }
            }
        }
    )
}

/**
 * `Save as`: the one File item that needs a word from the reader before it can act.
 *
 * Save writes over the document already in the library; this makes a new one, so it asks what to
 * call it and starts from the current name, the way every application that has ever had the two
 * items does.
 */
@Composable
internal fun SaveAsDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var typed by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { typed = initial }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as") },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(60) },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (typed.isNotBlank()) onConfirm(typed.trim()) },
                enabled = typed.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
