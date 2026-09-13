package com.cartogenesis.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.cartography.resolve
import com.cartogenesis.cartography.StoredTerrain
import com.cartogenesis.cartography.TerrainSnapshot
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The application, with its preferences read and its chrome put on: what a front end launches.
 *
 * The split between this and [CartogenesisApp] is the whole of how F4's settings reach the
 * interface. Preferences are read through the [Platform] seam, which is asynchronous on both hosts
 * (a file on one, browser storage on the other), and two of them — the chrome and the interface
 * scale — are properties of the theme rather than of the application, so they have to be applied
 * *outside* it. So this loads them, wraps [CartogenesisTheme] around the application in whatever
 * they say, and hands them down; [CartogenesisApp] itself takes settings as an argument and applies
 * no theme, which is what lets `ChromeGalleryTest` photograph it in a theme of the test's choosing.
 *
 * Nothing is drawn until the settings have been read. That is a few milliseconds on the desktop and
 * one asynchronous storage read in a browser, and the alternative — draw with the defaults, then
 * re-theme and re-default when the file arrives — is a visible flash of the wrong chrome and a
 * working resolution that changes under the reader's hands.
 */
@Composable
fun CartogenesisRoot(platform: Platform) {
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(platform) {
        settings = SettingsCodec.decode(
            runCatching { platform.settingsStore.read() }.getOrNull()
        )
    }

    val current = settings ?: return
    CartogenesisTheme(
        choice = current.theme,
        scale = current.interfaceScale,
        // Asked of the host rather than guessed from the width: a tablet in landscape is as wide as
        // a laptop and is still driven by a thumb, and the theme's touch targets follow the pointer
        // rather than the window. See [Platform.coarsePointer].
        coarsePointer = platform.coarsePointer
    ) {
        CartogenesisApp(
            platform = platform,
            settings = current,
            onSettings = { updated ->
                settings = updated
                // Written straight through rather than on a Save button: every control in the
                // dialog is a preference that has already taken effect, so a dialog that could be
                // cancelled would be offering to undo something already done.
                scope.launch {
                    runCatching { platform.settingsStore.write(SettingsCodec.encode(updated)) }
                }
            }
        )
    }
}

/**
 * The application, and the one decision that has to be made before any of it is drawn: which shape
 * of window this is.
 *
 * [BoxWithConstraints] rather than a platform question, because the answer is about the window and
 * not about the host — a desktop window dragged narrow is a compact window, and the same browser is
 * wide in landscape and compact in portrait. It measures and places its content exactly as a plain
 * `Box(Modifier.fillMaxSize())` would, so the wide arrangement below is laid out to the pixel as it
 * was before F5; all this adds is the width, in dp, to decide with.
 */
@Composable
fun CartogenesisApp(
    platform: Platform,
    settings: AppSettings = AppSettings(),
    onSettings: (AppSettings) -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shape = Layouts.shape(maxWidth.value, platform.coarsePointer)
        CompositionLocalProvider(LocalWindowShape provides shape) {
            Application(platform, settings, onSettings, shape, maxHeight)
        }
    }
}

@Composable
private fun Application(
    platform: Platform,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    shape: WindowShape,
    windowHeight: Dp
) {
    val compact = shape == WindowShape.COMPACT
    /** What this arrangement puts within reach. See [Arrangements]. */
    val reach = remember(shape, platform) { Arrangements.of(shape, platform) }
    /** 2048 in a phone browser, 4096 otherwise. See [Platform.exportCeiling]. */
    val exportCeiling = platform.exportCeiling(compact)
    var config by remember {
        mutableStateOf(
            SettingsEffects.startingConfig(settings, platform, Random.nextLong(1_000_000), compact)
        )
    }
    var options by remember { mutableStateOf(RenderOptions()) }
    var world by remember { mutableStateOf<WorldMap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Notices only, now: what an export or a save did. What used to be the status line — the seed,
    // the size, the realm count and the time — is the cartouche in the map's legend, and is read
    // off the world itself rather than accumulated into a sentence here.
    var status by remember { mutableStateOf("") }
    /** How long the last generation took, for the cartouche's footnote. Zero for an opened save. */
    var generationMillis by remember { mutableStateOf(0L) }
    var pendingExport by remember { mutableStateOf<Int?>(null) }
    // The preference is the *starting* format, not a live binding: changing the default in the
    // dialog must not change the format of an export the reader has already set up.
    var exportFormat by remember { mutableStateOf(settings.exportFormat) }

    // ---- F4: what the menu strip opens, and what it opens onto. ----
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    /** Null while GitHub has not answered yet, which the dialog draws as "Asking GitHub…". */
    var updateStatus by remember { mutableStateOf<Updates.Status?>(null) }
    var saveAs by remember { mutableStateOf(false) }
    /** The toolbar over the map, which View can put away for an uncluttered picture. */
    var toolbarVisible by remember { mutableStateOf(true) }

    /**
     * Whether the compact arrangement's settings sheet is pulled up. Unused when wide.
     *
     * It starts down, over a whole-screen map, which is the same decision F0 made about the blank
     * canvas: the application opens showing what it is for rather than showing its controls.
     */
    var sheetOpen by remember { mutableStateOf(false) }

    // Probed once. A machine with no usable device gets the toggle disabled and told why, rather
    // than a switch that silently does nothing.
    val accelerator = platform.accelerator
    // Only ever set by opening a version-2 save made on the graphics card, which carried its
    // eroded terrain because the seed alone did not pin it down. A version-3 save carries every
    // stage instead and is handed straight to the engine as a world to reuse, so nothing written
    // by this build ever takes this path.
    var storedTerrain by remember { mutableStateOf<TerrainSnapshot?>(null) }
    var overrides by remember { mutableStateOf(WorldOverrides()) }
    var selectedNation by remember { mutableStateOf<Int?>(null) }
    var screen by remember { mutableStateOf(Screen.MAP) }
    var labels by remember { mutableStateOf(listOf<MapLabel>()) }
    var nextLabelId by remember { mutableStateOf(1L) }
    var pendingLabel by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var labelMode by remember { mutableStateOf(false) }
    var documentId by remember { mutableStateOf(randomId()) }
    // The world's name: generated after each generation, editable in the header, and what the
    // save is filed under. See [WorldNaming] for the rule about which of those wins when.
    val naming = remember { WorldNaming() }
    var saved by remember { mutableStateOf(listOf<LibraryEntry>()) }
    val store = platform.library
    // Nothing generates until this is armed - by Go, New world, or Generate. Opening a save from
    // the library arms it too, since a world is then on screen and later edits should live-update
    // it exactly as if it had been generated here.
    val gate = remember { GenerationGate() }
    // Which of the panel's sections are unrolled. Remembered here rather than inside the panel so
    // that a trip to the atlas or the library and back does not roll them all up again.
    val sections = remember { SectionState() }
    // Where the map is being looked at from. Hoisted out of the canvas because the zoom readout
    // and its buttons are in the legend along the bottom edge now, not floating over the corner.
    val camera = remember { MapCamera() }
    // Click handlers are plain callbacks, not suspend functions, but the library now is - it
    // lives in IndexedDB on the web build, which is asynchronous throughout. This is how a
    // button press reaches a suspend call without making the composable itself suspend.
    val scope = rememberCoroutineScope()

    // What opening a save amounts to, whether it came from the library or from an uploaded file:
    // hand the world back to the engine as the world to reuse, which recomputes nothing.
    fun openSave(save: WorldSave) {
        val doc = save.document
        documentId = doc.id
        naming.opened(doc.config.seed, doc.title)
        overrides = doc.overrides
        labels = doc.labels
        nextLabelId = (doc.labels.maxOfOrNull { it.id } ?: 0L) + 1
        storedTerrain = doc.terrain
        world = save.world
        config = doc.config
        // Nothing was generated, so there is no time to quote: the footnote stays off until this
        // world is next made rather than read.
        generationMillis = 0L
        gate.request()
        screen = Screen.MAP
    }

    /** The document as it stands, which is what both Save and Download hand to the platform. */
    fun document() = WorldDocument(
        id = documentId,
        title = naming.title,
        config = config,
        overrides = overrides,
        labels = labels,
        savedAt = epochMillis()
    )

    /**
     * Writes the world to the library.
     *
     * A save is tens of megabytes now, so it can fail where it never used to — a browser's storage
     * quota, a full disk. That is a message, not a crash. The world goes in the file, not the
     * recipe for it: nothing here depends on this machine reproducing the same world from the same
     * seed, which is what the whole format was changed for.
     */
    fun saveWorld() {
        val current = world
        scope.launch {
            status = runCatching {
                store.save(document(), current)
                saved = store.list()
                "Saved \"${naming.title}\""
            }.getOrElse {
                "Could not save \"${naming.title}\": ${it.message ?: it::class.simpleName}"
            }
        }
    }

    LaunchedEffect(Unit) { saved = store.list() }

    // The library and the atlas are whole screens of their own, and a settings sheet pulled up over
    // the top third of one is a sheet in the way. Choosing either puts it down; nothing puts it
    // back up but the reader.
    LaunchedEffect(screen) { if (screen != Screen.MAP) sheetOpen = false }

    /**
     * The update check, run on demand and never on its own unless asked.
     *
     * [AppSettings.checkForUpdatesOnLaunch] is off by default, so on both platforms the only thing
     * that reaches GitHub is a reader choosing Help ▸ Check for updates — which is what keeps the
     * web bundle from making a cross-origin request in the page's load path.
     */
    suspend fun runUpdateCheck() {
        updateStatus = null
        val body = runCatching { platform.fetchText(Updates.LATEST_RELEASE_URL) }.getOrNull()
        updateStatus = Updates.evaluate(BuildInfo.VERSION, body)
    }

    LaunchedEffect(Unit) {
        if (!SettingsEffects.checksAtLaunch(settings)) return@LaunchedEffect
        runUpdateCheck()
        // Only worth interrupting for if there is actually something to say.
        if (updateStatus is Updates.Status.Available) updateOpen = true
    }

    /** What a menu item, or the keystroke that stands for it, actually does. */
    fun perform(command: MenuCommand) {
        when (command) {
            MenuCommand.NEW_WORLD -> {
                config = Knobs.withSeed(config, Random.nextLong(1_000_000))
                gate.request()
                screen = Screen.MAP
            }

            MenuCommand.OPEN_LIBRARY ->
                screen = if (screen == Screen.LIBRARY) Screen.MAP else Screen.LIBRARY

            MenuCommand.SAVE -> saveWorld()

            MenuCommand.SAVE_AS -> saveAs = true

            MenuCommand.EXPORT ->
                pendingExport = SettingsEffects.exportSizeWithin(settings, exportCeiling)

            MenuCommand.SETTINGS -> showSettings = true

            MenuCommand.QUIT -> platform.quit()

            MenuCommand.TOOLBAR -> toolbarVisible = !toolbarVisible

            MenuCommand.CHECK_UPDATES -> {
                updateOpen = true
                scope.launch { runUpdateCheck() }
            }

            MenuCommand.ABOUT -> showAbout = true
        }
    }

    // Regenerate whenever the settings change - but only once a generation has been asked for.
    // The app opens on a blank canvas, so the very first run must wait for Go, New world, or
    // Generate; after that, no debounce, since on desktop a generation is fast enough that the
    // settings panel uses explicit buttons rather than live-dragging sliders. Keyed on
    // gate.hasGenerated too, not just config, because pressing Generate with nothing changed
    // still has to run - the one case a key on the settings alone would miss.
    LaunchedEffect(config, gate.hasGenerated) {
        if (!gate.hasGenerated) return@LaunchedEffect
        busy = true
        val started = epochMillis()
        // The world we already have, so the engine can skip any stage whose settings did not
        // change. Most of what this panel adjusts sits late in the pipeline - realm count,
        // wilderness, landmarks - and erosion is over half the work, so handing the old world back
        // is the difference between a redraw and a full regeneration. The engine drops it entirely
        // if the seed or resolution moved, so there is nothing to guard here.
        val reusable = world
        val generated = withContext(Dispatchers.Default) {
            // A version-2 GPU save's terrain takes precedence: it is the world as it was saved,
            // and recomputing it on this machine's hardware could only be a worse answer.
            val accelerator = storedTerrain?.let { StoredTerrain(it) } ?: accelerator
            // Through [Generation] rather than straight to the engine: in a browser the generator
            // and the interface share one thread, so a stage name written here is invisible unless
            // the thread is handed back to let a frame out. See that object for the whole of it.
            Generation.run(config, reusable, accelerator) { stage = it.label }
        }
        val rendered = withContext(Dispatchers.Default) { MapImage.render(generated, options) }
        world = generated
        image = rendered
        stage = null
        busy = false
        generationMillis = epochMillis() - started
        // A world nobody has named yet, or a world at a seed this name was not given to, takes the
        // name its largest people would give it. A settings edit at the same seed keeps whatever
        // is in the field.
        naming.generated(config.seed, Cartouches.suggest(generated))
        // Any notice from an earlier export or save is about a world that is no longer on screen.
        status = ""
    }

    LaunchedEffect(options) {
        val current = world ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) { MapImage.render(current, options) }
    }

    LaunchedEffect(pendingExport) {
        val size = pendingExport ?: return@LaunchedEffect
        busy = true
        stage = "Rendering ${size}x$size"
        // Where a finished map goes is the one thing a browser tab and a desktop window
        // genuinely disagree about, so the platform is asked rather than told.
        status = runCatching { platform.export(config, options, size, exportFormat) }.fold(
            onSuccess = {
                if (it == null) "Export cancelled"
                else "Saved ${it.description} - ${it.bytes / 1024 / 1024} MB in ${it.millis / 1000}s"
            },
            onFailure = { "Export failed: ${it::class.simpleName} ${it.message.orEmpty()}" }
        )
        stage = null
        busy = false
        pendingExport = null
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            platform = platform,
            onSettings = { updated ->
                onSettings(updated)
                // The one preference that is not merely a default for next time: the library has
                // to actually move, and the listing has to be of the new place.
                if (updated.libraryFolder != settings.libraryFolder &&
                    updated.libraryFolder.isNotBlank()
                ) {
                    scope.launch {
                        if (platform.useLibraryFolder(updated.libraryFolder)) {
                            saved = platform.library.list()
                            status = "Library folder is now ${updated.libraryFolder}"
                        } else {
                            status = "Could not use ${updated.libraryFolder} as the library folder"
                        }
                    }
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showAbout) AboutDialog(platform) { showAbout = false }

    if (updateOpen) UpdateDialog(updateStatus, platform) { updateOpen = false }

    if (saveAs) {
        SaveAsDialog(
            initial = naming.title,
            onDismiss = { saveAs = false },
            onConfirm = { title ->
                // A new document rather than a new name on the old one, which is the difference
                // between Save as and renaming: the world already in the library stays there.
                documentId = randomId()
                naming.rename(title)
                saveAs = false
                saveWorld()
            }
        )
    }

    pendingLabel?.let { (x, y) ->
        NameLabelDialog(
            onDismiss = { pendingLabel = null },
            onConfirm = { text, kind ->
                labels = labels + MapLabel(nextLabelId, text, x, y, kind)
                nextLabelId += 1
                pendingLabel = null
            }
        )
    }

    // The map is the point, so it takes everything the panel does not. F3 gave it the right-hand
    // column as well: Export was the only thing left over there after F2, a 200dp strip holding
    // two chips and three buttons, and it is a thing done to a finished map rather than a thing
    // about the map on screen — so it has folded into the header panel, under the resolution row,
    // beside Library and Atlas which are the other two document actions. The alternative the spec
    // offered was a popover from a toolbar button; both free the same 210dp, and this one needs no
    // overlay machinery and keeps the map's own chrome to the two things that are about the map.
    // The strip is drawn once, above everything, on both platforms — see [MenuStrip] for why it is
    // drawn rather than hung off the window. The keyboard shortcuts are previewed at the root so
    // that Ctrl+S works wherever the focus happens to be; they are filtered on a modifier being
    // held, so typing a seed or a name never reaches them, and [Menus.shortcuts] hands back an
    // empty list on the web, where these keystrokes belong to the browser.
    //
    // F5 adds the second arrangement of all of this, and nothing else. The five values below are
    // the contents — the pane, the banner, the legend, the panel's header and the panel's sections
    // — and the two branches after them are the two ways of enclosing those five. Written as
    // composable values rather than as private functions because between them they read some thirty
    // pieces of this composable's state, and a parameter list carrying all of it out to a function
    // would be a second and worse copy of the same thing.
    val shortcuts = remember(platform) { Menus.shortcuts(platform) }

    // Only the map gets the dark backdrop. The atlas and library are ordinary reading
    // surfaces and must take their colour from the theme, or their (dark) text lands on
    // near-black and becomes invisible.
    val backdrop =
        if (screen == Screen.MAP) Color(options.style.backdrop)
        else MaterialTheme.colorScheme.background

    /**
     * The ink for anything the pane draws without naming a colour, which is most of its words.
     *
     * The panes are the one part of this application painted straight onto a background rather than
     * laid inside a `Surface`, and a `Surface` is what otherwise says what ink its paper takes. So
     * `LocalContentColor` here was Material's own default — plain black — and the library's two
     * headings and every unstyled line of a realm's page were drawn in it. On paper that is very
     * nearly right and nobody noticed for two rounds of review; on the fifteen chromes whose ground
     * is not paper it ran from poor to invisible, and on High contrast it was black on pure black
     * at exactly 1.0:1. The controls around them were never affected, because a text field, a
     * button and a card each carry their own colour or their own `Surface`.
     *
     * Declared beside the ground it belongs to, and provided once for the whole pane, so this is a
     * pairing rather than a colour written onto a heading — the fix has to hold for every word
     * either pane draws, in all sixteen chromes, and for whatever a later one draws.
     * `PhoneAtlasTest` measures it off the drawn pixels in each.
     */
    val paneInk =
        if (screen == Screen.MAP) OverMap.Parchment
        else MaterialTheme.colorScheme.onBackground

    /** Whichever of the three screens is up, drawn to fill whatever it is given. */
    val paneContents: @Composable () -> Unit = {
        val current = world
        if (screen == Screen.LIBRARY) {
            LibraryPane(
                title = naming.name,
                worlds = saved,
                location = platform.libraryLocation,
                supportsFileTransfer = platform.supportsFileTransfer,
                onTitleChange = naming::rename,
                // The same call File ▸ Save makes, so there is one way to write a world to the
                // library rather than a pane's way and a menu's way.
                onSave = { saveWorld() },
                onDownload = {
                    // Handing over the same bytes a save would have written - the format is
                    // shared, so this is the whole of moving a world to the other front end.
                    scope.launch {
                        status = runCatching {
                            platform.downloadWorld(document(), current)
                            "Downloaded \"${naming.title}\""
                        }.getOrElse {
                            "Could not download \"${naming.title}\": ${it.message ?: it::class.simpleName}"
                        }
                    }
                },
                onUpload = {
                    scope.launch {
                        status = runCatching {
                            val save = platform.uploadWorld()
                            if (save == null) {
                                "No file opened"
                            } else {
                                openSave(save)
                                "Opened \"${save.document.title}\" from file"
                            }
                        }.getOrElse { "Could not open file: ${it.message ?: it::class.simpleName}" }
                    }
                },
                onOpen = { id ->
                    // Handing the saved world back as the world to reuse is the whole of
                    // opening it: the generation the settings change kicks off finds every
                    // stage already matching its config and computes none of them.
                    scope.launch { store.load(id)?.let(::openSave) }
                },
                onDelete = { id -> scope.launch { store.delete(id); saved = store.list() } }
            )
        } else if (screen == Screen.ATLAS && current != null) {
            AtlasPane(
                nations = current.nations.nations.map { it.resolve(overrides.forNation(it.id)) },
                landmarks = current.landmarks.landmarks.map {
                    it.resolve(overrides.forLandmark(it.id))
                },
                selected = selectedNation,
                onSelect = { selectedNation = it },
                onEditNation = { id, transform ->
                    overrides = overrides.withNation(id, transform(overrides.forNation(id)))
                },
                onResetNation = { id -> overrides = overrides.withNation(id, NationOverride()) },
                onEditLandmark = { id, transform ->
                    overrides = overrides.withLandmark(id, transform(overrides.forLandmark(id)))
                },
                config = config,
                onConfig = { config = it },
                options = options,
                onOptions = { options = it },
                busy = busy,
                labelMode = labelMode,
                onToggleLabels = { labelMode = !labelMode; screen = Screen.MAP }
            )
        } else if (screen == Screen.ATLAS) {
            // Reachable now that the app opens blank: nothing to browse until a world exists.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Generate a world to see its atlas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            MapView(
                image = image,
                labels = labels,
                labelMode = labelMode,
                camera = camera,
                // Only where there is no wheel and no Fit button within a thumb's reach. A
                // double-tap handler makes every *single* tap wait for the second one, and on the
                // desktop a single tap is how a label is placed — a third of a second of nothing
                // happening after a click is a worse trade than the gesture is worth there.
                doubleTapToFit = compact,
                onPlace = { x, y -> pendingLabel = x to y },
                onLabelClick = { label -> labels = labels.filterNot { it.id == label.id } }
            )
        }
    }

    /** The same, told what ink the ground it is being drawn on takes. See [paneInk]. */
    val pane: @Composable () -> Unit = {
        CompositionLocalProvider(LocalContentColor provides paneInk, content = paneContents)
    }

    /** The progress banner, between the toolbar and the map while a world is being made. */
    val banner: @Composable () -> Unit = {
        if (busy) {
            Surface(color = OverMap.Veil, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        Modifier.width(20.dp),
                        color = OverMap.Parchment,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stage ?: "Generating…",
                        color = OverMap.Parchment,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    /** The chart legend along the map's foot, and the label-mode notice above it. */
    val legend: @Composable ColumnScope.() -> Unit = {
        if (labelMode) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Click the map to place a label. Click an existing one to remove it.",
                    Modifier.padding(12.dp)
                )
            }
        }
        ChartLegend(
            // No world, no cartouche: an empty sheet is named by nothing, so the
            // legend carries F0's one line of instruction instead.
            cartouche = world?.let {
                Cartouches.of(it, naming.title, generationMillis)
            },
            prompt = "Pick a seed and settings, then Generate.",
            // Compact only: with the sheet up, the banner along the map's top edge is a long way
            // from where the reader just pressed Generate. The same sentence, at the foot.
            progress = if (compact && busy) "${stage ?: "Generating"}…" else null,
            camera = camera,
            parts = reach.legend
        )
    }

    /** The slim header: which world, at what size, and what to do with it. */
    val header: @Composable ColumnScope.() -> Unit = {
        PanelHeader(
            config = config,
            busy = busy,
            status = status,
            hasWorld = world != null,
            exportFormat = exportFormat,
            exportCeiling = exportCeiling,
            exportSizes = reach.exportSizes,
            headerKnobs = Arrangements.headerKnobs(platform),
            worldName = naming.name,
            platform = platform,
            atlasLabel = if (screen == Screen.ATLAS) "Show map" else "Atlas",
            libraryLabel = if (screen == Screen.LIBRARY) "Show map" else "Library",
            onWorldName = naming::rename,
            onConfig = { config = it },
            onSeed = { config = Knobs.withSeed(config, it); gate.request() },
            onResolution = { config = Knobs.atResolution(config, it) },
            onNewWorld = {
                config = Knobs.withSeed(config, Random.nextLong(1_000_000))
                gate.request()
            },
            onGenerate = { gate.request() },
            onExportFormat = { exportFormat = it },
            // Clamped here as well as at the button. The disabled chip is a courtesy; this
            // is the guarantee, and it is what a size restored from an older build's
            // preference — which could still say 8192 — passes through.
            onExport = { pendingExport = Exports.clamp(it, exportCeiling) },
            onToggleAtlas = {
                screen = if (screen == Screen.ATLAS) Screen.MAP else Screen.ATLAS
            },
            onToggleLibrary = {
                screen = if (screen == Screen.LIBRARY) Screen.MAP else Screen.LIBRARY
            }
        )
    }

    /** Six sections in pipeline order, five of them rolled up. */
    val settingsBody: @Composable ColumnScope.() -> Unit = {
        SettingsPanel(
            config = config,
            options = options,
            busy = busy,
            platform = platform,
            sections = sections,
            onConfig = { config = it },
            onOptions = { options = it }
        )
    }

    /** The keystrokes, previewed above everything, in whichever arrangement is drawn. */
    val frame = Modifier.fillMaxSize()
        // The paper the panels are laid on, which for eleven of the fifteen chromes is the same
        // paper the panels are — see [ChromeDetail.windowGround].
        .background(LocalChromeDetail.current.ground(MaterialTheme.colorScheme))
        // So the cloth runs behind the gutter between the panels and the map as well as inside
        // them, which is what stops Hessian looking like linen panels pasted onto paper.
        .chromeWeave()
        .onPreviewKeyEvent { event ->
            val command = Menus.match(event, shortcuts) ?: return@onPreviewKeyEvent false
            if (command.needsWorld && world == null) return@onPreviewKeyEvent false
            perform(command)
            true
        }

    if (!compact) {
        Column(frame) {
            MenuStrip(
                platform = platform,
                hasWorld = world != null,
                settings = settings,
                sections = sections,
                toolbarVisible = toolbarVisible,
                onCommand = { perform(it) },
                onTheme = { onSettings(settings.copy(theme = it)) }
            )

            // Everything below the strip: the panel and the map, taking whatever height is left.
            Row(Modifier.weight(1f).fillMaxWidth().padding(10.dp)) {

                Column(
                    Modifier.width(320.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Panel { header() }

                    // Six sections in pipeline order, five of them rolled up. The panel it replaced
                    // was one undivided column of every control there was, ordered by nothing.
                    Panel(Modifier.weight(1f)) { settingsBody() }
                }

                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp)
                        .background(backdrop)
                ) {
                    pane()

                    // The two strips that make the map the instrument, and the progress banner
                    // between them and the map. Everything here is over the chart, in ink, so it is
                    // stacked rather than aligned piecemeal: the toolbar first, the banner under it
                    // while a world is being made, and the legend at the foot.
                    Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                        if (screen == Screen.MAP && toolbarVisible) {
                            MapToolbar(options, reach.styles, reach.views) { options = it }
                        }
                        banner()
                    }

                    if (screen == Screen.MAP) {
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { legend() }
                    }
                }
            }
        }
    } else {
        // The compact arrangement. The map has the whole screen — no gutter, no panel column and no
        // menu strip above it — and everything else is either over it or under it: the collapsed
        // toolbar along the top, the legend along the foot, and the panel in a sheet that pulls up
        // from the bottom edge. The sheet is a sibling of the map rather than an overlay on it, so
        // pulling it up shortens the map instead of hiding half of it, and the legend it carries
        // stays visible with the settings open.
        Column(frame) {
            // The pane and whatever belongs above it. A column rather than the map's own Box,
            // because the bar over a reading surface takes its height out of the layout, while the
            // strips over the map lie on top of the picture — see [PaneTopBar].
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (screen != Screen.MAP) {
                    PaneTopBar(
                        title = if (screen == Screen.LIBRARY) "Library" else naming.title,
                        onMap = { screen = Screen.MAP }
                    ) {
                        CompactMenuButton(
                            platform = platform,
                            hasWorld = world != null,
                            settings = settings,
                            sections = sections,
                            toolbarVisible = toolbarVisible,
                            onCommand = { perform(it) },
                            onTheme = { onSettings(settings.copy(theme = it)) },
                            // Ordinary chrome, so the scheme's ink rather than the chart's
                            // parchment. The same glyph, in the colour of the paper it lies on.
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(backdrop)) {
                    pane()

                    Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                        // Only over the map. The strip is translucent ink laid on a chart, and on a
                        // page of text it is a lid: it hid the top of the atlas on William's phone
                        // and, since the header holding "Show map" is inside the sheet here, there
                        // was then nothing on screen that went back. The wide arrangement has
                        // always withheld it, and [PaneTopBar] is what the compact one shows
                        // instead.
                        if (screen == Screen.MAP) {
                            CompactMapToolbar(
                                options = options,
                                styles = reach.styles,
                                views = reach.views,
                                // The style and view menus are about the picture, so they go when
                                // View has put the toolbar away; the menu button is how the
                                // application is reached at all here and stays.
                                choices = toolbarVisible,
                                onOptions = { options = it }
                            ) {
                                CompactMenuButton(
                                    platform = platform,
                                    hasWorld = world != null,
                                    settings = settings,
                                    sections = sections,
                                    toolbarVisible = toolbarVisible,
                                    onCommand = { perform(it) },
                                    onTheme = { onSettings(settings.copy(theme = it)) }
                                )
                            }
                        }
                        banner()
                    }

                    if (screen == Screen.MAP) {
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { legend() }
                    }
                }
            }

            SettingsSheet(
                open = sheetOpen,
                onOpen = { sheetOpen = it },
                expandedHeight = windowHeight * SHEET_SHARE,
                summary = if (busy) stage ?: "Generating…" else "Seed ${config.seed}"
            ) {
                header()
                settingsBody()
            }
        }
    }
}

/**
 * The panel, on a sheet that pulls up from the bottom edge.
 *
 * Hand-built rather than Material's `BottomSheetScaffold`, for two reasons and not for the usual
 * one. The first is that this sheet is *persistent and in the layout*: it takes height from the map
 * rather than covering it, so the legend and the toolbar stay where they are and the map simply
 * becomes the top third of the screen while the settings are open. A scaffold's sheet floats over
 * its content and would hide the cartouche of the very world being adjusted. The second is that the
 * whole of what a scaffold adds — the anchors, the velocity, the nested-scroll handoff — is more
 * machinery than a sheet with two positions needs, and all of it would have to be shown to work in
 * a browser on wasm before it could be relied on.
 *
 * Two positions, then: down, showing a handle and one line of summary, and up, showing the header
 * and the six sections in a scrolling column. The handle can be dragged either way or tapped to
 * toggle, because a grab handle that only responds to a drag is a control half the readers will
 * press once and give up on.
 */
@Composable
private fun ColumnScope.SettingsSheet(
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    expandedHeight: Dp,
    summary: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val height by animateDpAsState(if (open) expandedHeight else PEEK_HEIGHT, label = "sheet")
    // Which way the finger has gone since it went down. The sign at the end of the drag is the
    // whole decision: up opens, down closes, and a drag that ends where it started leaves it be.
    var travelled by remember { mutableStateOf(0f) }
    val drag = rememberDraggableState { delta -> travelled += delta }

    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.fillMaxSize().chromeWeave()) {
            Row(
                Modifier.fillMaxWidth()
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStarted = { travelled = 0f },
                        onDragStopped = {
                            if (travelled < -DRAG_TO_SETTLE) onOpen(true)
                            else if (travelled > DRAG_TO_SETTLE) onOpen(false)
                        }
                    )
                    .clickable { onOpen(!open) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The grip: a short rule, which is what every sheet on every phone uses to say
                    // "this comes up". Drawn rather than set as a glyph so it is exactly a rule.
                    Box(
                        Modifier.width(34.dp).height(3.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Text("Settings", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

/** How much of a compact window the settings sheet takes when it is up. */
private const val SHEET_SHARE = 0.72f

/** The sheet with the settings down: a handle, the word, and one line about the world. */
private val PEEK_HEIGHT = 44.dp

/** How far a finger has to travel before a drag counts as a pull rather than a wobble. */
private const val DRAG_TO_SETTLE = 24f

/**
 * The bar over the atlas and the library in the compact arrangement: what this is, and the way out.
 *
 * On a phone the map's toolbar and legend are the only chrome there is — the menu strip folds into
 * the toolbar's one glyph, and the panel's header, which carries Atlas and Show map, is inside the
 * pull-up sheet. That is right while the map is on screen and wrong the moment it is not: the two
 * strips belong to the picture, so over a page of text they are noise, and the one along the top
 * edge is worse than noise. William found it on his phone against 2.0.0 — the atlas opened
 * underneath a translucent band, and the only button that would have closed it was behind a sheet
 * he had no reason to think held it.
 *
 * So the strips go, and a reading surface says its own name and offers its own way back. This is
 * ordinary chrome rather than an annotation on a chart: the scheme's paper, the scheme's ink, and
 * the chrome's own [SectionRule] under it — Hallowed's doubled gold, Roman's meander, Hitchcock's
 * cut bar — which is how every other ruled edge in the application is drawn. It takes its height
 * out of the layout rather than lying over the pane, which is the whole difference between a bar
 * and a lid.
 *
 * The title is printed as written rather than through [ChromeDetail.heading]. A world's name is a
 * proper noun, and the cartouche in the map's legend does not letter it either: a heading is
 * uppercased, pointed and prompted, and a name is none of those.
 *
 * Nothing about the wide arrangement changes. There the header is always on screen beside the map,
 * its Atlas button already reads "Show map", and the strips were already withheld from anything
 * that is not the map.
 */
@Composable
private fun PaneTopBar(title: String, onMap: () -> Unit, menu: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().chromeWeave()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                menu()
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onMap,
                    contentPadding = TIGHT,
                    // Material's button is 40 dp tall, which is a mouse's target. The theme knows
                    // what a fingertip needs and says so in one place; a compact window driven by a
                    // pointer — a desktop dragged narrow — asks for nothing and keeps the 40.
                    modifier = Modifier.sizeIn(
                        minHeight = LocalTouchTargets.current.minTarget
                    )
                ) {
                    Text("Map", maxLines = 1)
                }
            }
            SectionRule()
        }
    }
}

/** One of the boxes the interface is built from: a ruled patch of paper with room to breathe. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        // No tonal elevation: a panel is a sheet of the same paper, told apart from the ground by
        // a ruled edge rather than by being tinted a shade of the accent.
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            // The weave, where the chrome is a cloth. Identity everywhere else — see [chromeWeave].
            Modifier.chromeWeave().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}


/**
 * Pan and zoom over the rendered map, with labels drawn on top.
 *
 * Labels are drawn in screen space rather than map space, so they stay readable at any zoom
 * instead of growing into the terrain.
 *
 * The touch gestures F5 asks for are, all three, gestures this already had or gets for nothing.
 * `detectTransformGestures` is the same handler for a two-finger pinch as for a drag — one pointer
 * reports a pan and no zoom, two report both — and Compose for Wasm delivers a browser's touch
 * events through the same pointer pipeline the desktop's mouse uses, so nothing here is
 * platform-specific and there is no touch-only branch to get wrong. Only [doubleTapToFit] is new,
 * and it is optional for the reason given at its call site.
 */
@Composable
private fun MapView(
    image: ImageBitmap?,
    labels: List<MapLabel>,
    labelMode: Boolean,
    camera: MapCamera,
    doubleTapToFit: Boolean,
    onPlace: (Float, Float) -> Unit,
    onLabelClick: (MapLabel) -> Unit
) {
    val zoom = camera.zoom
    val pan = camera.pan
    // Null rather than a no-op handler: passing one at all makes every *single* tap wait for the
    // double-tap window to expire before it fires, so a window that does not want the gesture must
    // not ask for it.
    val fitOnDoubleTap: ((Offset) -> Unit)? =
        if (doubleTapToFit) ({ _: Offset -> camera.fit() }) else null

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                // Drag to pan and pinch to zoom, in one handler: a single pointer reports a pan
                // and a zoom of 1, two pointers report both, and the centroid is the point the
                // zoom is taken about — which is what keeps whatever is between the fingers
                // between the fingers.
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    camera.about(centroid, zoomChange, panChange)
                }
            }
            .pointerInput(Unit) {
                // A wheel is not a gesture, so detectTransformGestures never sees it, and a mouse
                // is how most of this will be driven.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val scrolled = change.scrollDelta.y
                        if (scrolled == 0f) continue
                        // Scrolling down is positive, and should zoom out.
                        camera.about(
                            change.position,
                            if (scrolled < 0f) MapCamera.STEP else 1f / MapCamera.STEP
                        )
                        change.consume()
                    }
                }
            }
            .pointerInput(labelMode, labels, image, doubleTapToFit) {
                detectTapGestures(onDoubleTap = fitOnDoubleTap) { tap ->
                    val img = image ?: return@detectTapGestures
                    val fit = min(size.width.toFloat() / img.width, size.height.toFloat() / img.height)
                    val offsetX = (size.width - img.width * fit) / 2f
                    val offsetY = (size.height - img.height * fit) / 2f

                    fun toScreen(label: MapLabel) = Offset(
                        (label.x * img.width * fit + offsetX) * zoom + pan.x,
                        (label.y * img.height * fit + offsetY) * zoom + pan.y
                    )

                    val hit = labels.firstOrNull { (toScreen(it) - tap).getDistance() < 24f }
                    if (hit != null) {
                        onLabelClick(hit)
                        return@detectTapGestures
                    }
                    if (!labelMode) return@detectTapGestures

                    val unpanned = (tap - pan) / zoom
                    val nx = (unpanned.x - offsetX) / fit / img.width
                    val ny = (unpanned.y - offsetY) / fit / img.height
                    if (nx in 0f..1f && ny in 0f..1f) onPlace(nx, ny)
                }
            }
    ) {
        val img = image ?: return@Canvas
        val fit = min(size.width / img.width, size.height / img.height)
        val offsetX = (size.width - img.width * fit) / 2f
        val offsetY = (size.height - img.height * fit) / 2f

        withTransform({
            translate(pan.x, pan.y)
            scale(zoom, zoom, pivot = Offset.Zero)
            translate(offsetX, offsetY)
            scale(fit, fit, pivot = Offset.Zero)
        }) {
            drawImage(img)
        }

        labels.forEach { label ->
            val x = (label.x * img.width * fit + offsetX) * zoom + pan.x
            val y = (label.y * img.height * fit + offsetY) * zoom + pan.y
            drawCircle(OverMap.Ink, radius = 4f, center = Offset(x, y))
            drawCircle(OverMap.Parchment, radius = 2f, center = Offset(x, y))
        }
    }

    // Text has to go through the platform canvas, since DrawScope has no text primitive.
    Box(Modifier.fillMaxSize()) {
        labels.forEach { label ->
            LabelChip(label)
        }
    }

    // The zoom readout and its three buttons used to float here, over the bottom-right corner of
    // the map. They are the right-hand half of the legend now; see [ChartLegend].
}

@Composable
private fun LabelChip(label: MapLabel) {
    // Positioned by the same normalised coordinates the map uses, via a fraction-based offset.
    Box(Modifier.fillMaxSize()) {
        Text(
            label.text,
            style = MaterialTheme.typography.labelLarge,
            color = OverMap.Ink,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offsetFraction(label.x, label.y)
                .background(OverMap.ParchmentDim, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}


/**
 * The seed, shown and editable.
 *
 * Every world is a pure function of its seed, so the seed *is* the world: it is how you come back
 * to one, and how you tell someone else which one you mean. Until now the only way to change it was
 * "New world", which rolls a fresh one, and the only place it was ever displayed was the library
 * listing for worlds already saved - so a world you were looking at could not be named or returned
 * to without saving it first.
 *
 * Typed text is held locally and applied only on Enter or on the Go button, never on every
 * keystroke and never on losing focus: regenerating is expensive, applying as you type would
 * kick off a generation for each digit of a six-digit number, and applying on focus loss started
 * a world the moment the reader clicked elsewhere to change another setting (William, 2.0.1).
 */
@Composable
private fun SeedField(seed: Long, busy: Boolean, onSeed: (Long) -> Unit) {
    var text by remember(seed) { mutableStateOf(seed.toString()) }
    val parsed = text.trim().toLongOrNull()
    val changed = parsed != null && parsed != seed

    fun apply() {
        val value = text.trim().toLongOrNull() ?: return
        if (value != seed) onSeed(value)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            // Digits only, and a minus sign, since the seed is a Long. Filtering here rather than
            // rejecting on submit means the field cannot be put into a state it will not accept.
            onValueChange = { typed -> text = typed.filter { it.isDigit() || it == '-' }.take(19) },
            label = { Text("Seed") },
            singleLine = true,
            enabled = !busy,
            isError = text.isNotBlank() && parsed == null,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        apply()
                        true
                    } else {
                        false
                    }
                }
        )
        OutlinedButton(onClick = { apply() }, enabled = !busy && changed, contentPadding = TIGHT) {
            Text("Go", maxLines = 1)
        }
    }
}

/**
 * What the world is called.
 *
 * Filled in after every generation with a name in the language of the world's largest people, and
 * editable from that moment on. Unlike [SeedField] this applies as it is typed: a name costs
 * nothing to change, where a seed costs a generation, so there is nothing to defer to Enter.
 *
 * It is the same string the save is filed under, so what is typed here is what the library lists
 * and what comes back when the file is opened — the cartouche on the map, the library listing and
 * the save header are three views of this one field.
 */
@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onName(it.take(60)) },
        label = { Text("Name") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The slim header: which world, what it is called, at what size and on what hardware.
 *
 * Almost nothing here is a setting of the world — the seed is which world, the name is what it is
 * called, the resolution is how finely it is computed and the graphics-acceleration switch is what does
 * the computing — so it sits above the sections rather than inside one, and it is the only part of
 * the panel that never rolls up. The one knob it draws it draws through [KnobControl], the same
 * renderer the sections use, from the same declaration in [Knobs]: the header is a place a knob
 * can be, not a second way of writing one.
 */
@Composable
private fun PanelHeader(
    config: WorldGenConfig,
    busy: Boolean,
    status: String,
    hasWorld: Boolean,
    exportFormat: ExportFormat,
    exportCeiling: Int,
    exportSizes: List<Int>,
    headerKnobs: List<Knob>,
    worldName: String,
    platform: Platform,
    atlasLabel: String,
    libraryLabel: String,
    onWorldName: (String) -> Unit,
    onConfig: (WorldGenConfig) -> Unit,
    onSeed: (Long) -> Unit,
    onResolution: (Int) -> Unit,
    onNewWorld: () -> Unit,
    onGenerate: () -> Unit,
    onExportFormat: (ExportFormat) -> Unit,
    onExport: (Int) -> Unit,
    onToggleAtlas: () -> Unit,
    onToggleLibrary: () -> Unit
) {
    Text("Cartogenesis", style = MaterialTheme.typography.titleLarge)
    SeedField(seed = config.seed, busy = busy, onSeed = onSeed)
    NameField(name = worldName, onName = onWorldName)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // The one unambiguous "start" action - Go and New world both change the seed and so also
        // generate, but this is the button for someone who has touched nothing yet.
        Button(
            onClick = onGenerate,
            enabled = !busy,
            contentPadding = TIGHT,
            modifier = Modifier.weight(1f)
        ) { Text("Generate", maxLines = 1) }
        OutlinedButton(
            onClick = onNewWorld,
            enabled = !busy,
            contentPadding = TIGHT,
            modifier = Modifier.weight(1f)
        ) { Text("New world", maxLines = 1) }
    }

    Labelled("Working resolution", "${config.width} px") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Knobs.RESOLUTIONS.forEach { size ->
                FilterChip(
                    selected = config.width == size,
                    onClick = { onResolution(size) },
                    label = { Text("$size", maxLines = 1) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // What the two larger chips cost on the device this arrangement is drawn for, in seconds,
    // measured on William's phone rather than guessed: 18-23 s at 1024 and 92.7 s at 2048, on a
    // 2026 Qualcomm handset with WebGPU on. The last clause is the honest part — a browser has one
    // thread, so a long stage is a page that stops answering, and a reader owed no explanation of
    // that concludes the tab has died. Compact only: a desktop is not what this is about, and the
    // numbers are not its numbers.
    if (LocalWindowShape.current == WindowShape.COMPACT) {
        Text(
            "On a phone, 1024 takes about twenty seconds and 2048 about a minute and a half; " +
                "the screen may pause while it works.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Where the work runs, directly under how finely it is done. The only knob the header draws,
    // and it is drawn from the declaration rather than by hand — from the *arrangement's*
    // declaration since F5, which is how a host with no graphics API at all draws no switch here
    // rather than a disabled one. Nothing in this section is a [Mark] — a knob that writes
    // `RenderOptions` — which `PanelKnobsTest` holds to, so the options handed in here are never
    // read and the writer is never called.
    headerKnobs.forEach { knob ->
        KnobControl(knob, config, RenderOptions(), busy, platform, onConfig) {}
    }

    // Export, which had a 200dp column of its own on the far side of the map until F3.
    OutputOptions(busy, hasWorld, exportFormat, exportCeiling, exportSizes, onExportFormat, onExport)

    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onToggleLibrary,
            enabled = !busy,
            contentPadding = TIGHT,
            modifier = Modifier.weight(1f)
        ) { Text(libraryLabel, maxLines = 1) }
        OutlinedButton(
            onClick = onToggleAtlas,
            enabled = !busy,
            contentPadding = TIGHT,
            modifier = Modifier.weight(1f)
        ) { Text(atlasLabel, maxLines = 1) }
    }

    // Notices only — what an export or a save just did. The running commentary on the world moved
    // to the cartouche in the map's legend, so an empty line here means nothing has happened
    // rather than that there is nothing to say.
    if (status.isNotBlank()) {
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * The settings, in the order the generator applies them.
 *
 * Every control is declared in [Knobs] and drawn from there, so this composable decides how a
 * knob looks and never what a knob does. That is what lets `PanelKnobsTest` walk the panel: the
 * list it walks is the list this draws.
 */
@Composable
private fun SettingsPanel(
    config: WorldGenConfig,
    options: RenderOptions,
    busy: Boolean,
    platform: Platform,
    sections: SectionState,
    onConfig: (WorldGenConfig) -> Unit,
    onOptions: (RenderOptions) -> Unit
) {
    PANEL_SECTIONS.forEach { section ->
        Section(
            title = section.title,
            expanded = sections.isOpen(section),
            onToggle = { sections.toggle(section) }
        ) {
            Arrangements.knobsIn(section, platform).forEach { knob ->
                KnobControl(knob, config, options, busy, platform, onConfig, onOptions)
            }
        }
    }
}

/**
 * A division of the panel that rolls up, under a ruled heading in the theme's display face.
 *
 * A hairline under the title and a `+` or `–` at the right margin, and nothing else: the section
 * is told from its neighbours by the rule, exactly as a panel is told from the page by its border.
 * The whole row is the target, since a reader aiming at a 12dp glyph is a reader being tested.
 */
@Composable
private fun Section(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(vertical = 7.dp + LocalTouchTargets.current.extraRowPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lettered the way the chrome letters a heading: Allied's capitals, Matrix's prompt,
            // Roman's interpunct. The words are the panel's own either way — a heading is
            // uppercased, pointed and prompted, never rewritten. `panel = true` because this is the
            // one place Allied's capitals reach; see [HeadingCase].
            Text(
                LocalChromeDetail.current.heading(title, panel = true),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (expanded) "–" else "+",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionRule()
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content
            )
        }
    }
}

/** One declared knob, drawn as whatever kind of control it is. */
@Composable
private fun KnobControl(
    knob: Knob,
    config: WorldGenConfig,
    options: RenderOptions,
    busy: Boolean,
    platform: Platform,
    onConfig: (WorldGenConfig) -> Unit,
    onOptions: (RenderOptions) -> Unit
) {
    when (knob) {
        is Dial -> {
            val value = knob.read(config)
            Labelled(knob.label, knob.show(value)) {
                Slider(
                    value = value,
                    onValueChange = { onConfig(knob.set(config, it)) },
                    valueRange = knob.range,
                    enabled = !busy
                )
            }
        }

        is Stepper -> StepperRow(knob, config, busy, onConfig)

        is Latch -> {
            val available = !knob.needsAccelerator || platform.accelerator != null
            Toggle(knob.label, knob.read(config), enabled = available) {
                onConfig(knob.set(config, it))
            }
            if (knob.needsAccelerator) AcceleratorNote(platform, knob.read(config))
        }

        is Mark -> Toggle(knob.label, knob.read(options)) { onOptions(knob.set(options, it)) }
    }
}

/**
 * `Plates    –  14  +`.
 *
 * A slider was the wrong instrument for these two. Eleven plates and twelve plates are different
 * worlds rather than the same world adjusted, so what a reader wants is to ask for one more, not
 * to sweep through every count between here and there regenerating each in turn.
 */
@Composable
private fun StepperRow(
    knob: Stepper,
    config: WorldGenConfig,
    busy: Boolean,
    onConfig: (WorldGenConfig) -> Unit
) {
    val value = knob.read(config)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(knob.label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton("−", enabled = !busy && value > knob.range.first) {
                onConfig(knob.set(config, value - 1))
            }
            Text(
                "$value",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 22.dp)
            )
            StepButton("+", enabled = !busy && value < knob.range.last) {
                onConfig(knob.set(config, value + 1))
            }
        }
    }
}

/** Material's own button insists on being 58dp wide, which is four times what a `+` needs. */
@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        color = Color.Transparent,
        contentColor = if (enabled) scheme.onSurface else scheme.outline,
        border = BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outlineVariant),
        // A minus sign is nine pixels wide. Under a mouse the box around it is as tight as the
        // padding makes it; under a fingertip the theme says how big the target has to be.
        modifier = Modifier
            .sizeIn(
                minWidth = LocalTouchTargets.current.minTarget,
                minHeight = LocalTouchTargets.current.minTarget
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * What a machine with a graphics device can offer, or why it cannot.
 *
 * What the device is used for is the host's answer rather than this composable's: the desktop runs
 * the erosion sweeps and the export raster on it, a browser only the sweeps. See
 * [Platform.acceleratedWork].
 */
@Composable
private fun AcceleratorNote(platform: Platform, onGpu: Boolean) {
    val device = platform.accelerator?.name
    val note = when {
        device == null ->
            "Unavailable here: ${platform.accelerationUnavailableBecause}"
        onGpu -> platform.acceleratedWork(device)
        else -> "$device is available, and is many times faster at this."
    }
    Text(
        note,
        style = MaterialTheme.typography.labelSmall,
        color = if (onGpu) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Where a finished map goes.
 *
 * The graphics-acceleration switch used to head this panel, under "Acceleration". It has moved to World:
 * it decides how the world is *made*, and filing it beside the export buttons implied it was
 * something about the picture. F3 moved what was left of the panel into the header, so this is
 * three rows in a 320dp column rather than a 200dp column of its own — the heading and the two
 * format chips share a line, which is the row the narrower home cost it.
 */
@Composable
private fun OutputOptions(
    busy: Boolean,
    hasWorld: Boolean,
    exportFormat: ExportFormat,
    exportCeiling: Int,
    sizes: List<Int>,
    onExportFormat: (ExportFormat) -> Unit,
    onExport: (Int) -> Unit
) {
    // Which size the pointer is over, if it is over one that cannot be run. Only that case needs
    // remembering: the small print for a size that works is the format's own line.
    var reachingFor by remember { mutableStateOf<Int?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Export", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportFormat.entries.forEach { format ->
                FilterChip(
                    selected = exportFormat == format,
                    onClick = { onExportFormat(format) },
                    label = { Text(format.label, maxLines = 1) }
                )
            }
        }
    }
    // One line of small print, which the unreachable size borrows while the pointer is on it. In
    // the same slot rather than under the row, so nothing moves when it changes.
    val unreachable = reachingFor
    Text(
        if (unreachable != null) Exports.unreachableNote(unreachable) else exportFormat.detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sizes.forEach { size ->
            // A size this build cannot finish keeps its chip — the row would otherwise change
            // width when the ceiling moves, and a missing control says nothing about why it is
            // missing. It is drawn in the muted colour, it cannot be pressed, and hovering it
            // says what is wrong.
            val reachable = Exports.reachable(size, exportCeiling)
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            LaunchedEffect(hovered, reachable) {
                if (!reachable && hovered) reachingFor = size
                else if (reachingFor == size) reachingFor = null
            }
            Button(
                onClick = { onExport(Exports.clamp(size, exportCeiling)) },
                enabled = reachable && !busy && hasWorld,
                contentPadding = TIGHT,
                modifier = Modifier.weight(1f).hoverable(hover)
            ) { Text("$size", maxLines = 1) }
        }
    }
    if (!hasWorld) {
        Text(
            "Generate a world first.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Buttons here carry longer words than Material assumes, in narrower panels than it assumes. */
internal val TIGHT = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

@Composable
internal fun Labelled(label: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

@Composable
internal fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp + LocalTouchTargets.current.extraRowPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Chooses where to save. Kept out of the composable so it can be called from a background job. */

private enum class Screen { MAP, ATLAS, LIBRARY }

/** Random enough for a document id, without pulling in a UUID dependency. */

/**
 * Places a composable at a fraction of its parent, which is how labels stay put in map
 * coordinates while being laid out in screen space.
 */
private fun Modifier.offsetFraction(fx: Float, fy: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(
            x = (constraints.maxWidth * fx).toInt() - placeable.width / 2,
            y = (constraints.maxHeight * fy).toInt() - placeable.height / 2
        )
    }
}

@Composable
private fun NameLabelDialog(onDismiss: () -> Unit, onConfirm: (String, LabelKind) -> Unit) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LabelKind.REGION) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this place") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Label") },
                    singleLine = true
                )
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LabelKind.entries.forEach { option ->
                        FilterChip(
                            selected = option == kind,
                            onClick = { kind = option },
                            label = {
                                Text(
                                    option.name.lowercase().replace('_', ' ')
                                        .replaceFirstChar { it.uppercase() },
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim(), kind) },
                enabled = text.isNotBlank()
            ) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
