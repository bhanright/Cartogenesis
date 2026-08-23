package com.cartogenesis.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.cartography.resolve
import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Cartogenesis",
        state = rememberWindowState(width = 1500.dp, height = 950.dp)
    ) {
        MaterialTheme { DesktopApp() }
    }
}

@Composable
private fun DesktopApp() {
    var config by remember {
        // Desktop starts at a resolution the phone build could not attempt.
        mutableStateOf(WorldGenConfig(seed = Random.nextLong(1_000_000), width = 1024, height = 1024))
    }
    var options by remember { mutableStateOf(RenderOptions()) }
    var world by remember { mutableStateOf<WorldMap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<Int?>(null) }
    var overrides by remember { mutableStateOf(WorldOverrides()) }
    var selectedNation by remember { mutableStateOf<Int?>(null) }
    var showAtlas by remember { mutableStateOf(false) }

    // Regenerate whenever the settings change. No debounce: on desktop a generation is fast
    // enough that the settings panel uses explicit buttons rather than live-dragging sliders.
    LaunchedEffect(config) {
        busy = true
        val started = System.currentTimeMillis()
        val generated = withContext(Dispatchers.Default) {
            WorldGenerationEngine.generate(config) { s: GenerationStage, _: Int, _: Int ->
                stage = s.label
            }
        }
        val rendered = withContext(Dispatchers.Default) { DesktopRenderer.render(generated, options) }
        world = generated
        image = rendered
        stage = null
        busy = false
        status = "${config.width}x${config.height} in ${System.currentTimeMillis() - started} ms · " +
            "${generated.nations.nations.size} realms · ${generated.rivers.rivers.size} rivers"
    }

    LaunchedEffect(options) {
        val current = world ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) { DesktopRenderer.render(current, options) }
    }

    LaunchedEffect(pendingExport) {
        val size = pendingExport ?: return@LaunchedEffect
        // The file dialog is native and must run on the UI thread; the work must not.
        val destination = chooseSaveFile(Exporter.defaultName(config, size))
        if (destination == null) {
            pendingExport = null
            return@LaunchedEffect
        }
        busy = true
        stage = "Rendering ${size}x$size"
        status = runCatching {
            withContext(Dispatchers.Default) { Exporter.export(config, options, size, destination) }
        }.fold(
            onSuccess = { "Saved ${it.file.name} - ${it.bytes / 1024 / 1024} MB in ${it.millis / 1000}s" },
            onFailure = { "Export failed: ${it::class.simpleName} ${it.message.orEmpty()}" }
        )
        stage = null
        busy = false
        pendingExport = null
    }

    Row(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.width(360.dp).fillMaxHeight(),
            tonalElevation = 2.dp
        ) {
            SettingsPanel(
                config = config,
                options = options,
                busy = busy,
                status = status,
                onConfig = { config = it },
                onOptions = { options = it },
                onExport = { size -> pendingExport = size },
                showAtlas = showAtlas,
                onToggleAtlas = { showAtlas = !showAtlas }
            )
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF14171A))) {
            val current = world
            if (showAtlas && current != null) {
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
                MapView(image)
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
    }
}

/** Pan and zoom over the rendered map. */
@Composable
private fun MapView(image: ImageBitmap?) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                val next = (zoom * zoomChange).coerceIn(0.2f, 40f)
                pan = (pan - centroid) * (next / zoom) + centroid + panChange
                zoom = next
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
    }
}

@Composable
private fun SettingsPanel(
    config: WorldGenConfig,
    options: RenderOptions,
    busy: Boolean,
    status: String,
    onConfig: (WorldGenConfig) -> Unit,
    onOptions: (RenderOptions) -> Unit,
    onExport: (Int) -> Unit,
    showAtlas: Boolean,
    onToggleAtlas: () -> Unit
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Cartogenesis", style = MaterialTheme.typography.headlineSmall)
        Text(
            status.ifBlank { "Generating the first world…" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(Modifier.padding(vertical = 10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onConfig(config.copy(seed = Random.nextLong(1_000_000))) },
                enabled = !busy
            ) { Text("New world") }
            OutlinedButton(onClick = onToggleAtlas, enabled = !busy) {
                Text(if (showAtlas) "Show map" else "Atlas")
            }
        }

        Labelled("Working resolution", "${config.width} px") {
            // Powers of two, because the terrain integrator is FFT-based.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(512, 1024, 2048, 4096).forEach { size ->
                    FilterChip(
                        selected = config.width == size,
                        onClick = { onConfig(config.copy(width = size, height = size)) },
                        label = { Text("$size", maxLines = 1) },
                        enabled = !busy
                    )
                }
            }
        }

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

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WildernessMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.nations.wilderness == mode,
                    onClick = { onConfig(config.copy(nations = config.nations.copy(wilderness = mode))) },
                    label = { Text(mode.label, maxLines = 1) },
                    enabled = !busy
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("View", style = MaterialTheme.typography.titleSmall)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MapView.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { view ->
                        FilterChip(
                            selected = options.view == view,
                            onClick = { onOptions(options.copy(view = view)) },
                            label = { Text(view.label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(2 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        Toggle("Rivers", options.showRivers) { onOptions(options.copy(showRivers = it)) }
        Toggle("Relief shading", options.showHillshade) { onOptions(options.copy(showHillshade = it)) }
        Toggle("Realm borders", options.bordersVisible) { onOptions(options.copy(showBorders = it)) }
        Toggle("Landmarks", options.showLandmarks) { onOptions(options.copy(showLandmarks = it)) }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Export", style = MaterialTheme.typography.titleSmall)
        Text(
            "Sizes the phone build could not reach — the heap here is 12GB.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2048, 4096, 8192).forEach { size ->
                Button(onClick = { onExport(size) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("$size", maxLines = 1)
                }
            }
        }
    }
}

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
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Chooses where to save. Kept out of the composable so it can be called from a background job. */
internal fun chooseSaveFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save map", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
