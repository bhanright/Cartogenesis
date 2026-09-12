package com.cartogenesis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.cartography.resolve
import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.cartography.StoredTerrain
import com.cartogenesis.cartography.TerrainSnapshot
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.min
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
fun CartogenesisApp(platform: Platform) {
    var config by remember {
        mutableStateOf(
            WorldGenConfig(seed = Random.nextLong(1_000_000), width = 512, height = 512)
                .atResolution(platform.defaultResolution, platform.defaultResolution)
        )
    }
    var options by remember { mutableStateOf(RenderOptions()) }
    var world by remember { mutableStateOf<WorldMap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<Int?>(null) }
    var exportFormat by remember { mutableStateOf(ExportFormat.PNG) }

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
    var title by remember { mutableStateOf("Untitled world") }
    var saved by remember { mutableStateOf(listOf<LibraryEntry>()) }
    val store = platform.library
    // Nothing generates until this is armed - by Go, New world, or Generate. Opening a save from
    // the library arms it too, since a world is then on screen and later edits should live-update
    // it exactly as if it had been generated here.
    val gate = remember { GenerationGate() }
    // Which of the panel's sections are unrolled. Remembered here rather than inside the panel so
    // that a trip to the atlas or the library and back does not roll them all up again.
    val sections = remember { SectionState() }
    // Click handlers are plain callbacks, not suspend functions, but the library now is - it
    // lives in IndexedDB on the web build, which is asynchronous throughout. This is how a
    // button press reaches a suspend call without making the composable itself suspend.
    val scope = rememberCoroutineScope()

    // What opening a save amounts to, whether it came from the library or from an uploaded file:
    // hand the world back to the engine as the world to reuse, which recomputes nothing.
    fun openSave(save: WorldSave) {
        val doc = save.document
        documentId = doc.id
        title = doc.title
        overrides = doc.overrides
        labels = doc.labels
        nextLabelId = (doc.labels.maxOfOrNull { it.id } ?: 0L) + 1
        storedTerrain = doc.terrain
        world = save.world
        config = doc.config
        gate.request()
        screen = Screen.MAP
    }

    LaunchedEffect(Unit) { saved = store.list() }

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
            WorldGenerationEngine.generate(config, reusable, accelerator) {
                s: GenerationStage, _: Int, _: Int ->
                stage = s.label
            }
        }
        val rendered = withContext(Dispatchers.Default) { MapImage.render(generated, options) }
        world = generated
        image = rendered
        stage = null
        busy = false
        status = "Seed ${config.seed} · ${config.width}x${config.height} in " +
            "${epochMillis() - started} ms · ${generated.nations.nations.size} realms · " +
            "${generated.rivers.rivers.size} rivers"
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

    // The map is the point, so it takes the middle and the whole height. Everything about the
    // world is on the left, in the order the generator makes it; the right is what happens to a
    // finished map.
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp)) {

        Column(
            Modifier.width(320.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel {
                PanelHeader(
                    config = config,
                    busy = busy,
                    status = status,
                    atlasLabel = if (screen == Screen.ATLAS) "Show map" else "Atlas",
                    libraryLabel = if (screen == Screen.LIBRARY) "Show map" else "Library",
                    onSeed = { config = Knobs.withSeed(config, it); gate.request() },
                    onResolution = { config = Knobs.atResolution(config, it) },
                    onNewWorld = {
                        config = Knobs.withSeed(config, Random.nextLong(1_000_000))
                        gate.request()
                    },
                    onGenerate = { gate.request() },
                    onToggleAtlas = {
                        screen = if (screen == Screen.ATLAS) Screen.MAP else Screen.ATLAS
                    },
                    onToggleLibrary = {
                        screen = if (screen == Screen.LIBRARY) Screen.MAP else Screen.LIBRARY
                    }
                )
            }

            // Six sections in pipeline order, five of them rolled up. The panel it replaced was
            // one undivided column of every control there was, ordered by nothing.
            Panel(Modifier.weight(1f)) {
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
        }

        // Only the map gets the dark backdrop. The atlas and library are ordinary reading
        // surfaces and must take their colour from the theme, or their (dark) text lands on
        // near-black and becomes invisible.
        val backdrop =
            if (screen == Screen.MAP) Color(options.style.backdrop)
            else MaterialTheme.colorScheme.background

        Box(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp).background(backdrop)
        ) {
            val current = world
            if (screen == Screen.LIBRARY) {
                LibraryPane(
                    title = title,
                    worlds = saved,
                    location = platform.libraryLocation,
                    supportsFileTransfer = platform.supportsFileTransfer,
                    onTitleChange = { title = it },
                    onSave = {
                        // The world goes in the file, not the recipe for it. Nothing here depends
                        // on this machine reproducing the same world from the same seed, which is
                        // what the whole format was changed for.
                        //
                        // A save is tens of megabytes now, so it can fail where it never used to —
                        // a browser's storage quota, a full disk. That is a message, not a crash.
                        scope.launch {
                            status = runCatching {
                                store.save(
                                    WorldDocument(
                                        id = documentId,
                                        title = title.ifBlank { "Untitled world" },
                                        config = config,
                                        overrides = overrides,
                                        labels = labels,
                                        savedAt = epochMillis()
                                    ),
                                    current
                                )
                                saved = store.list()
                                "Saved \"$title\""
                            }.getOrElse {
                                "Could not save \"$title\": ${it.message ?: it::class.simpleName}"
                            }
                        }
                    },
                    onDownload = {
                        // Handing over the same bytes a save would have written - the format is
                        // shared, so this is the whole of moving a world to the other front end.
                        scope.launch {
                            status = runCatching {
                                platform.downloadWorld(
                                    WorldDocument(
                                        id = documentId,
                                        title = title.ifBlank { "Untitled world" },
                                        config = config,
                                        overrides = overrides,
                                        labels = labels,
                                        savedAt = epochMillis()
                                    ),
                                    current
                                )
                                "Downloaded \"$title\""
                            }.getOrElse {
                                "Could not download \"$title\": ${it.message ?: it::class.simpleName}"
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
                    onPlace = { x, y -> pendingLabel = x to y },
                    onLabelClick = { label -> labels = labels.filterNot { it.id == label.id } }
                )
                if (image == null && !busy) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Pick a seed and settings, then Generate.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (labelMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    ) {
                        Text(
                            "Click the map to place a label. Click an existing one to remove it.",
                            Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (busy) {
                Surface(
                    color = OverMap.Veil,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                ) {
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

        // Style and View used to live here. They are neither settings of the world nor things
        // done to a finished one, they are how the map on screen is drawn, so F2 files them under
        // Cartography and F3 lifts them onto the map itself. What is left on this side is the one
        // thing that genuinely leaves the application: a rendered file.
        Column(
            Modifier.width(200.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel {
                OutputOptions(busy, world != null, exportFormat, { exportFormat = it }) {
                    pendingExport = it
                }
            }
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
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
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
 */
@Composable
private fun MapView(
    image: ImageBitmap?,
    labels: List<MapLabel>,
    labelMode: Boolean,
    onPlace: (Float, Float) -> Unit,
    onLabelClick: (MapLabel) -> Unit
) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Zooming about a point rather than about the origin: whatever is under the cursor, or between
    // the fingers, has to stay under it, or the map slides away from whatever is being examined.
    fun zoomAbout(anchor: Offset, factor: Float, panChange: Offset = Offset.Zero) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = (pan - anchor) * (next / zoom) + anchor + panChange
        zoom = next
    }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    zoomAbout(centroid, zoomChange, panChange)
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
                        zoomAbout(change.position, if (scrolled < 0f) WHEEL_STEP else 1f / WHEEL_STEP)
                        change.consume()
                    }
                }
            }
            .pointerInput(labelMode, labels, image) {
                detectTapGestures { tap ->
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

    // The wheel and the pinch are both invisible, so the same thing is offered where it can be
    // seen. Zooming from here uses the middle of the view as the anchor, there being no cursor
    // position to work from.
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = OverMap.ParchmentDim
            )
            ZoomButton("-") { zoom = (zoom / WHEEL_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM) }
            ZoomButton("+") { zoom = (zoom * WHEEL_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM) }
            ZoomButton("Fit") { zoom = 1f; pan = Offset.Zero }
        }
    }
}

/** Deliberately plain: these sit over the map and should not compete with it. */
@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = OverMap.Veil,
        contentColor = OverMap.Parchment
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 40f

/** One wheel notch, or one press of a button. Compounds, so it should be a modest step. */
private const val WHEEL_STEP = 1.15f

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
 * Typed text is held locally and only applied on Enter or on losing focus, rather than on every
 * keystroke: regenerating is expensive, and applying as you type would kick off a generation for
 * each digit of a six-digit number.
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
                .onFocusChanged { if (!it.isFocused) apply() }
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
 * The slim header: which world, at what size, and the four things one can do with it.
 *
 * Nothing here is a setting of the world — the seed is which world, the resolution is how finely
 * it is computed — so it sits above the sections rather than inside one, and it is the only part
 * of the panel that never rolls up.
 */
@Composable
private fun PanelHeader(
    config: WorldGenConfig,
    busy: Boolean,
    status: String,
    atlasLabel: String,
    libraryLabel: String,
    onSeed: (Long) -> Unit,
    onResolution: (Int) -> Unit,
    onNewWorld: () -> Unit,
    onGenerate: () -> Unit,
    onToggleAtlas: () -> Unit,
    onToggleLibrary: () -> Unit
) {
    Text("Cartogenesis", style = MaterialTheme.typography.titleLarge)
    SeedField(seed = config.seed, busy = busy, onSeed = onSeed)
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

    Text(
        status.ifBlank {
            if (busy) "Generating the first world…" else "Pick a seed and settings, then Generate."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.padding(top = 8.dp)
    )
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
            Knobs.inSection(section).forEach { knob ->
                KnobControl(knob, config, options, busy, platform, onConfig, onOptions)
            }
            // The two long lists of choices. They are drawn by hand rather than declared, because
            // a list of nine styles is not a knob; F3 lifts both onto the map itself.
            if (section == PanelSection.CARTOGRAPHY) StyleAndView(options, onOptions)
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
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (expanded) "–" else "+",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
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
        border = BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outlineVariant)
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/** What a machine with a graphics device can offer, or why it cannot. */
@Composable
private fun AcceleratorNote(platform: Platform, onGpu: Boolean) {
    val note = when {
        platform.accelerator == null ->
            "Unavailable here: ${platform.accelerationUnavailableBecause}"
        onGpu ->
            "Erosion runs on ${platform.accelerator?.name}, which is many times faster at it."
        else ->
            "${platform.accelerator?.name} is available, and is many times faster at this."
    }
    Text(
        note,
        style = MaterialTheme.typography.labelSmall,
        color = if (onGpu) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** How the finished map is drawn, and which layer of it is on screen. */
@Composable
private fun StyleAndView(options: RenderOptions, onOptions: (RenderOptions) -> Unit) {
    Text(
        "Style",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
    MapStyle.entries.forEach { style ->
        FilterChip(
            selected = options.style == style,
            onClick = { onOptions(options.copy(style = style)) },
            label = { Text(style.label, maxLines = 1) },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Text(
        options.style.detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Only the fantasy and political views are drawn in a style; the rest carry meaning in their
    // colours, so it would be a lie to restyle them.
    if (!options.view.showsTerrain) {
        Text(
            "The ${options.view.label.lowercase()} view ignores the style, since its colours mean something.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Text(
        "View",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
    MapView.entries.forEach { view ->
        FilterChip(
            selected = options.view == view,
            onClick = { onOptions(options.copy(view = view)) },
            label = { Text(view.label, maxLines = 1) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Where a finished map goes.
 *
 * The graphics-card switch used to head this panel, under "Acceleration". It has moved to World:
 * it decides how the world is *made*, and filing it beside the export buttons implied it was
 * something about the picture.
 */
@Composable
private fun OutputOptions(
    busy: Boolean,
    hasWorld: Boolean,
    exportFormat: ExportFormat,
    onExportFormat: (ExportFormat) -> Unit,
    onExport: (Int) -> Unit
) {
    Text("Export", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExportFormat.entries.forEach { format ->
            FilterChip(
                selected = exportFormat == format,
                onClick = { onExportFormat(format) },
                label = { Text(format.label, maxLines = 1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
    Text(
        exportFormat.detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(2048, 4096, 8192).forEach { size ->
            Button(
                onClick = { onExport(size) },
                enabled = !busy && hasWorld,
                contentPadding = TIGHT,
                modifier = Modifier.weight(1f)
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
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
