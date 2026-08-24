package com.cartogenesis.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.cartography.resolve
import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.cartography.StoredTerrain
import com.cartogenesis.cartography.TerrainSnapshot
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.min
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
    // Set when a world is opened from a save that carried its terrain, and cleared as soon as the
    // settings change, since a stored terrain only answers for the config it was stored under.
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
    var saved by remember { mutableStateOf(listOf<WorldDocument>()) }
    val store = platform.library

    LaunchedEffect(Unit) { saved = store.list() }

    // Regenerate whenever the settings change. No debounce: on desktop a generation is fast
    // enough that the settings panel uses explicit buttons rather than live-dragging sliders.
    LaunchedEffect(config) {
        busy = true
        val started = epochMillis()
        val generated = withContext(Dispatchers.Default) {
            // A stored terrain takes precedence: it is the world exactly as it was saved, and
            // recomputing it on this machine's hardware could only be a worse answer.
            val accelerator = storedTerrain?.let { StoredTerrain(it) } ?: accelerator
            WorldGenerationEngine.generate(config, accelerator = accelerator) {
                s: GenerationStage, _: Int, _: Int ->
                stage = s.label
            }
        }
        val rendered = withContext(Dispatchers.Default) { MapImage.render(generated, options) }
        world = generated
        image = rendered
        stage = null
        busy = false
        status = "${config.width}x${config.height} in ${epochMillis() - started} ms · " +
            "${generated.nations.nations.size} realms · ${generated.rivers.rivers.size} rivers"
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

    // The map is the point, so it takes the middle and the whole height, and the controls are
    // split either side of it rather than stacked in one long column. Grouping is by what a
    // control does: choosing what to look at on the left, choosing how to look at it on the right.
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp)) {

        Column(
            Modifier.width(320.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel {
                WorldActions(
                    busy = busy,
                    status = status,
                    labelMode = labelMode,
                    atlasLabel = if (screen == Screen.ATLAS) "Show map" else "Atlas",
                    libraryLabel = if (screen == Screen.LIBRARY) "Show map" else "Library",
                    onNewWorld = { config = config.copy(seed = Random.nextLong(1_000_000)) },
                    onToggleAtlas = {
                        screen = if (screen == Screen.ATLAS) Screen.MAP else Screen.ATLAS
                    },
                    onToggleLibrary = {
                        screen = if (screen == Screen.LIBRARY) Screen.MAP else Screen.LIBRARY
                    },
                    onToggleLabels = { labelMode = !labelMode; screen = Screen.MAP }
                )
            }

            Panel { ResolutionPicker(config, busy) { config = it } }

            // The tallest panel, and the one that will keep growing: roads and trade routes will
            // land here beside the rivers and the borders.
            Panel(Modifier.weight(1f)) {
                WorldSettings(config, options, busy, { config = it }, { options = it })
            }
        }

        // Only the map gets the dark backdrop. The atlas and library are ordinary reading
        // surfaces and must take their colour from the theme, or their (dark) text lands on
        // near-black and becomes invisible.
        val backdrop =
            if (screen == Screen.MAP) Color(0xFF14171A) else MaterialTheme.colorScheme.background

        Box(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp).background(backdrop)
        ) {
            val current = world
            if (screen == Screen.LIBRARY) {
                LibraryPane(
                    title = title,
                    worlds = saved,
                    location = platform.libraryLocation,
                    onTitleChange = { title = it },
                    onSave = {
                        store.save(
                            WorldDocument(
                                id = documentId,
                                title = title.ifBlank { "Untitled world" },
                                config = config,
                                overrides = overrides,
                                labels = labels,
                                // Only a GPU world needs its terrain preserved; a CPU world
                                // regenerates from the seed exactly, on any machine.
                                terrain = current
                                    ?.takeIf { config.erosion.acceleration == Acceleration.GPU }
                                    ?.let {
                                        TerrainSnapshot.of(
                                            it.width, it.height, it.erosion.height.data
                                        )
                                    },
                                savedAt = epochMillis()
                            )
                        )
                        saved = store.list()
                        status = "Saved \"$title\""
                    },
                    onOpen = { id ->
                        store.load(id)?.let { doc ->
                            documentId = doc.id
                            title = doc.title
                            overrides = doc.overrides
                            labels = doc.labels
                            nextLabelId = (doc.labels.maxOfOrNull { it.id } ?: 0L) + 1
                            storedTerrain = doc.terrain
                            config = doc.config
                            screen = Screen.MAP
                        }
                    },
                    onDelete = { id -> store.delete(id); saved = store.list() }
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
                    }
                )
            } else {
                MapView(
                    image = image,
                    labels = labels,
                    labelMode = labelMode,
                    onPlace = { x, y -> pendingLabel = x to y },
                    onLabelClick = { label -> labels = labels.filterNot { it.id == label.id } }
                )
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
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.width(20.dp), color = Color.White)
                        Text(stage ?: "Generating…", color = Color.White)
                    }
                }
            }
        }

        Column(
            Modifier.width(250.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel(Modifier.weight(1f)) { ViewOptions(options) { options = it } }
            Panel { OutputOptions(config, busy, platform, exportFormat, { config = it }, { exportFormat = it }) { pendingExport = it } }
        }
    }
}

/** One of the boxes the interface is built from: a bordered surface with room to breathe. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
            drawCircle(Color(0xFF1A1A1A), radius = 4f, center = Offset(x, y))
            drawCircle(Color(0xFFF2E4C6), radius = 2f, center = Offset(x, y))
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
                color = Color(0xCCFFFFFF)
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
        color = Color(0x99000000),
        contentColor = Color.White
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
            color = Color(0xFF14171A),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offsetFraction(label.x, label.y)
                .background(Color(0xCCF2E4C6), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun WorldActions(
    busy: Boolean,
    status: String,
    labelMode: Boolean,
    atlasLabel: String,
    libraryLabel: String,
    onNewWorld: () -> Unit,
    onToggleAtlas: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleLabels: () -> Unit
) {
    Text("Cartogenesis", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onNewWorld, enabled = !busy, contentPadding = TIGHT) {
            Text("New world", maxLines = 1)
        }
        OutlinedButton(onClick = onToggleAtlas, enabled = !busy, contentPadding = TIGHT) {
            Text(atlasLabel, maxLines = 1)
        }
        OutlinedButton(onClick = onToggleLibrary, enabled = !busy, contentPadding = TIGHT) {
            Text(libraryLabel, maxLines = 1)
        }
    }
    OutlinedButton(onClick = onToggleLabels, enabled = !busy, contentPadding = TIGHT) {
        Text(if (labelMode) "Done labelling" else "Place a label", maxLines = 1)
    }
    Text(
        status.ifBlank { "Generating the first world…" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2
    )
}

@Composable
private fun ResolutionPicker(
    config: WorldGenConfig,
    busy: Boolean,
    onConfig: (WorldGenConfig) -> Unit
) {
    Labelled("Working resolution", "${config.width} px") {
        // Powers of two, because the terrain integrator is FFT-based.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(512, 1024, 2048, 4096).forEach { size ->
                FilterChip(
                    selected = config.width == size,
                    // atResolution, not a raw copy: settings measured in cells have to be
                    // rescaled with the grid or the world changes character instead of just
                    // gaining detail. A raw copy leaves mountain belts a fraction of their
                    // proper width, which surfaces plate edges as straight cliffs.
                    onClick = { onConfig(config.atResolution(size, size)) },
                    label = { Text("$size", maxLines = 1) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * What the world is made of, and which of its features are drawn.
 *
 * The two belong together: changing the number of realms and deciding whether to draw their
 * borders are the same question asked twice, and separating them would mean hunting in two places
 * for one answer.
 */
@Composable
private fun WorldSettings(
    config: WorldGenConfig,
    options: RenderOptions,
    busy: Boolean,
    onConfig: (WorldGenConfig) -> Unit,
    onOptions: (RenderOptions) -> Unit
) {
    Text("World", style = MaterialTheme.typography.titleSmall)

    Labelled("Ocean coverage", "${(config.seaLevel * 100).roundToInt()}%") {
        Slider(
            value = config.seaLevel,
            onValueChange = { onConfig(config.copy(seaLevel = it)) },
            valueRange = 0.05f..0.95f,
            enabled = !busy
        )
    }

    Labelled("Plates", "${config.tectonics.plateCount}") {
        Slider(
            value = config.tectonics.plateCount.toFloat(),
            onValueChange = {
                onConfig(config.copy(tectonics = config.tectonics.copy(plateCount = it.roundToInt())))
            },
            valueRange = 3f..40f,
            enabled = !busy
        )
    }

    Labelled("Realms", "${config.nations.nationCount}") {
        Slider(
            value = config.nations.nationCount.toFloat(),
            onValueChange = {
                onConfig(config.copy(nations = config.nations.copy(nationCount = it.roundToInt())))
            },
            valueRange = 0f..40f,
            enabled = !busy
        )
    }

    Labelled("Points of interest", "${config.landmarks.count}") {
        Slider(
            value = config.landmarks.count.toFloat(),
            onValueChange = {
                onConfig(config.copy(landmarks = config.landmarks.copy(count = it.roundToInt())))
            },
            valueRange = 0f..200f,
            enabled = !busy
        )
    }

    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WildernessMode.entries.forEach { mode ->
            FilterChip(
                selected = config.nations.wilderness == mode,
                onClick = { onConfig(config.copy(nations = config.nations.copy(wilderness = mode))) },
                label = { Text(mode.label, maxLines = 1) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Features", style = MaterialTheme.typography.titleSmall)

    Toggle("Rivers", options.showRivers) { onOptions(options.copy(showRivers = it)) }
    Toggle("Relief shading", options.showHillshade) { onOptions(options.copy(showHillshade = it)) }
    Toggle("Realm borders", options.bordersVisible) { onOptions(options.copy(showBorders = it)) }
    Toggle("Landmarks", options.showLandmarks) { onOptions(options.copy(showLandmarks = it)) }
    Toggle("Lakes", options.showLakes) { onOptions(options.copy(showLakes = it)) }
}

/** Which layer of the world is on screen. One per row, since these are read rather than scanned. */
@Composable
private fun ViewOptions(options: RenderOptions, onOptions: (RenderOptions) -> Unit) {
    Text("View", style = MaterialTheme.typography.titleSmall)
    MapView.entries.forEach { view ->
        FilterChip(
            selected = options.view == view,
            onClick = { onOptions(options.copy(view = view)) },
            label = { Text(view.label, maxLines = 1) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Where the work happens, and where the result goes. */
@Composable
private fun OutputOptions(
    config: WorldGenConfig,
    busy: Boolean,
    platform: Platform,
    exportFormat: ExportFormat,
    onConfig: (WorldGenConfig) -> Unit,
    onExportFormat: (ExportFormat) -> Unit,
    onExport: (Int) -> Unit
) {
    Text("Acceleration", style = MaterialTheme.typography.titleSmall)
    val onGpu = config.erosion.acceleration == Acceleration.GPU
    Toggle("Graphics card", onGpu, enabled = platform.accelerator != null) { wanted ->
        onConfig(
            config.copy(
                erosion = config.erosion.copy(
                    acceleration = if (wanted) Acceleration.GPU else Acceleration.CPU
                )
            )
        )
    }
    val acceleratorNote = when {
        platform.accelerator == null ->
            "Unavailable here: ${platform.accelerationUnavailableBecause}"
        onGpu ->
            "Erosion runs on ${platform.accelerator?.name}. Graphics hardware rounds differently, " +
                "so saves carry their terrain rather than relying on the seed, and are larger."
        else ->
            "${platform.accelerator?.name} is available, and is many times faster at this."
    }
    Text(
        acceleratorNote,
        style = MaterialTheme.typography.labelSmall,
        color = if (onGpu) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
                enabled = !busy,
                contentPadding = TIGHT,
                modifier = Modifier.weight(1f)
            ) { Text("$size", maxLines = 1) }
        }
    }
}

/** Buttons here carry longer words than Material assumes, in narrower panels than it assumes. */
private val TIGHT = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

@Composable
private fun Labelled(label: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

@Composable
private fun Toggle(
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
