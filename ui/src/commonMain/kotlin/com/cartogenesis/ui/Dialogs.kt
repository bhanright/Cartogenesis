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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * The three dialogs: Settings, About, and the answer to "check for updates".
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
    onDismiss: () -> Unit,
    /**
     * Where the library is now, in the reader's terms. The desktop's is its folder setting; a
     * browser's is its own storage or the folder the reader chose in the Library pane.
     */
    libraryLocation: String = SettingsEffects.libraryLocation(settings, platform)
) {
    // 2048 in a browser and 4096 on the desktop, for the working resolution and the exports alike;
    // the chips above it stay in their rows, disabled, and each row's small print says why.
    val ceiling = platform.generationCeiling
    val resolutions = Knobs.resolutionChoices(ceiling)
    val exportSizes = SizeChoice.row(Exports.SIZES, ceiling)
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
                    // Seventeen chips in one wrapped block is a wall; three labelled shelves is a
                    // list. Same names, same order within a shelf, same stored value — see
                    // [Menus.themeGroups].
                    Menus.themeGroups.forEach { (group, chromes) ->
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 3.dp)
                        )
                        ChoiceChips(
                            options = chromes,
                            selected = settings.theme,
                            label = { it.label },
                            onSelect = { onSettings(settings.copy(theme = it)) }
                        )
                    }
                }

                SettingRow(
                    "Generation resolution",
                    "The grid a new world starts at. The world on screen keeps its own." +
                        reasonsBelow(resolutions)
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
                        resolutions.forEach { choice ->
                            FilterChip(
                                selected = settings.workingResolution == choice.size,
                                enabled = choice.enabled,
                                onClick = { onSettings(settings.copy(workingResolution = choice.size)) },
                                label = { Text("${choice.size}", maxLines = 1) }
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
                        "Graphics acceleration at launch",
                        // The same sentence the header switch prints, from the same seam, so the
                        // dialog cannot end up claiming the device does more than the panel does.
                        platform.accelerator?.let { platform.accelerationOffered(it.name) }
                            ?: "Unavailable here: ${platform.accelerationUnavailableBecause}"
                    ) {
                        Toggle(
                            "Graphics acceleration",
                            settings.graphicsAccelerationAtLaunch,
                            enabled = platform.accelerator != null
                        ) { onSettings(settings.copy(graphicsAccelerationAtLaunch = it)) }
                    }
                }

                SettingRow(
                    "Export",
                    "What the export buttons start as." + reasonsBelow(exportSizes)
                ) {
                    ChoiceChips(
                        options = ExportFormat.entries,
                        selected = settings.exportFormat,
                        label = { it.label },
                        onSelect = { onSettings(settings.copy(exportFormat = it)) }
                    )
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        exportSizes.forEach { choice ->
                            FilterChip(
                                selected = settings.exportSize == choice.size,
                                enabled = choice.enabled,
                                onClick = { onSettings(settings.copy(exportSize = choice.size)) },
                                label = { Text("${choice.size}", maxLines = 1) }
                            )
                        }
                    }
                }

                SettingRow(
                    "Library folder",
                    libraryLocation
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
                    } else if (platform.folderChooser != null) {
                        // A browser folder is chosen by the browser's own picker and remembered
                        // with its handle, not typed as a path, so it is chosen where the worlds
                        // are rather than here.
                        Text(
                            "In this browser the library can live in a folder on this device, " +
                                "including one a sync client keeps in step. Choose it in the Library.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    ChoiceChips(
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
/**
 * Why each disabled chip of [choices] is disabled, as sentences to follow a row's small print, or
 * nothing when every chip can be pressed. A dialog has no hover to borrow, so the reasons are
 * printed with the row rather than when the pointer finds the chip.
 */
private fun reasonsBelow(choices: List<SizeChoice>): String =
    choices.mapNotNull { it.whyOutOfReach }.joinToString("") { " $it." }

@Composable
private fun SettingRow(title: String, note: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // A heading, so it is lettered the way the chrome letters one — which is where Roman's
        // interpunct actually shows, the panel's own six headings all being single words.
        Text(LocalChromeDetail.current.heading(title), style = MaterialTheme.typography.titleSmall)
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
private fun <T> ChoiceChips(
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
 * The third-party notices are the part that matters legally rather than decoratively: the three
 * type faces the interface is set in are licensed under the SIL Open Font License, which requires the
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
                    LocalChromeDetail.current.heading("Licence"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(BuildInfo.LICENCE, style = MaterialTheme.typography.bodySmall)

                Text(
                    LocalChromeDetail.current.heading("Third-party notices"),
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
 * What Help ▸ Report a bug… did, and the report it did it with.
 *
 * The work is over by the time this is drawn — the text is on the clipboard and the browser has
 * been sent to the form — so this dialog's whole job is to say so, and to show the report, which
 * is the part a reader may want to take somewhere else. Three readers are served by the one panel:
 * the one whose browser opened and who need only be told the fields are filled in, the one who has
 * no GitHub account and wants [BugReport.ADDRESS], and the one on a host that cannot open a link
 * at all, who is given the URL to carry across by hand.
 *
 * [copied] is what the platform actually managed, not what it was asked to do, so the sentence
 * never claims a clipboard the host does not have.
 */
@Composable
internal fun BugReportDialog(
    report: BugReport.Report,
    copied: Boolean,
    platform: Platform,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a bug", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    when {
                        copied && platform.canOpenLinks ->
                            "The report below is on the clipboard, and a new issue has been " +
                                "opened in the browser with the same details already in its fields."

                        copied ->
                            "The report below is on the clipboard. This build cannot open a " +
                                "browser, so the address for a new issue is at the foot."

                        platform.canOpenLinks ->
                            "A new issue has been opened in the browser with these details " +
                                "already in its fields. The clipboard could not be reached here, " +
                                "so the report is below to copy by hand."

                        else ->
                            "The clipboard and the browser are both out of reach here, so the " +
                                "report is below to copy by hand and the address for it is at " +
                                "the foot."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Without a GitHub account, send it to ${BugReport.ADDRESS}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Text(
                    report.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )

                // The address a host that cannot open a link is left with. Printed rather than
                // offered as a button, exactly as the update dialog prints a release page.
                if (!platform.canOpenLinks) {
                    Text(
                        report.url,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (platform.canOpenLinks) {
                TextButton(onClick = { platform.openLink(report.url) }) {
                    Text("Open the issue form")
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
                onValueChange = { typed = it.take(MAX_WORLD_NAME_LENGTH) },
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

/**
 * [LargeLinks]' question: a link names a world [linkSize] across, above the [defaultSize] this
 * host starts at, and nothing is made until the reader picks one of the two sizes.
 *
 * There is no third way out. Pressing outside the dialog or Escape does nothing, because either
 * would have to mean one of the two answers and neither is safe to assume: a reader who followed a
 * link asked for a world, and closing the question on no world at all would leave a blank window
 * with nothing saying why the link did nothing. The smaller answer holds the focus when the dialog opens, so
 * Enter takes the quick one and Tab reaches the other; both are ordinary buttons with their sizes
 * in their names.
 */
@Composable
internal fun LargeLinkDialog(
    question: String,
    linkSize: Int,
    defaultSize: Int,
    onMakeIt: () -> Unit,
    onAtDefault: () -> Unit
) {
    val atDefaultFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("A large world") },
        text = { Text(question, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onMakeIt) { Text(LargeLinks.makeItLabel(linkSize)) }
        },
        dismissButton = {
            TextButton(onClick = onAtDefault, modifier = Modifier.focusRequester(atDefaultFocus)) {
                Text(LargeLinks.atDefaultLabel(defaultSize))
            }
            LaunchedEffect(atDefaultFocus) { atDefaultFocus.requestFocus() }
        }
    )
}
