package com.worldforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worldforge.app.export.ExportResolution
import com.worldforge.app.render.MapView
import com.worldforge.app.ui.MapUiState
import com.worldforge.worldgen.model.WildernessMode
import com.worldforge.worldgen.model.WorldGenConfig
import kotlin.math.roundToInt

@Composable
fun ControlsPanel(
    state: MapUiState,
    onConfigChange: ((WorldGenConfig) -> WorldGenConfig) -> Unit,
    onRandomizeSeed: () -> Unit,
    onViewChange: (MapView) -> Unit,
    onToggleRivers: () -> Unit,
    onToggleHillshade: () -> Unit,
    onToggleBorders: () -> Unit,
    onToggleLandmarks: () -> Unit,
    onExport: (ExportResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader("World")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Seed ${config.seed}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRandomizeSeed) { Text("New world") }
        }

        LabeledSlider(
            label = "Ocean coverage",
            value = config.seaLevel,
            valueText = "${(config.seaLevel * 100).roundToInt()}%",
            range = 0.05f..0.95f
        ) { value -> onConfigChange { it.copy(seaLevel = value) } }

        LabeledSlider(
            label = "Preview detail",
            value = resolutionToSlider(config.width),
            valueText = "${config.width} px",
            range = 0f..3f,
            steps = 2
        ) { value ->
            val size = sliderToResolution(value)
            onConfigChange { it.copy(width = size, height = size) }
        }

        SectionHeader("Tectonics")

        LabeledSlider(
            label = "Plates",
            value = config.tectonics.plateCount.toFloat(),
            valueText = "${config.tectonics.plateCount}",
            range = 3f..40f,
            steps = 36
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(plateCount = value.roundToInt())) }
        }

        LabeledSlider(
            label = "Mountain height",
            value = config.tectonics.mountainHeight,
            range = 0f..1.2f
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(mountainHeight = value)) }
        }

        LabeledSlider(
            label = "Mountain spread",
            value = config.tectonics.boundaryFalloff,
            valueText = "${config.tectonics.boundaryFalloff.roundToInt()} cells",
            range = 5f..70f
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(boundaryFalloff = value)) }
        }

        LabeledSlider(
            label = "Ocean trench depth",
            value = config.tectonics.trenchDepth,
            range = 0f..0.8f
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(trenchDepth = value)) }
        }

        LabeledSlider(
            label = "Oceanic plates",
            value = config.tectonics.oceanicFraction,
            valueText = "${(config.tectonics.oceanicFraction * 100).roundToInt()}%",
            range = 0f..1f
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(oceanicFraction = value)) }
        }

        LabeledSlider(
            label = "Tectonic influence",
            value = config.tectonics.tectonicWeight,
            range = 0f..1f
        ) { value ->
            onConfigChange { it.copy(tectonics = it.tectonics.copy(tectonicWeight = value)) }
        }

        SectionHeader("Terrain")

        LabeledSlider(
            label = "Detail octaves",
            value = config.terrain.octaves.toFloat(),
            valueText = "${config.terrain.octaves}",
            range = 1f..9f,
            steps = 7
        ) { value ->
            onConfigChange { it.copy(terrain = it.terrain.copy(octaves = value.roundToInt())) }
        }

        LabeledSlider(
            label = "Continent size",
            value = config.terrain.baseFrequency.toFloat(),
            valueText = if (config.terrain.baseFrequency <= 3) "Large" else
                if (config.terrain.baseFrequency <= 6) "Medium" else "Small",
            range = 2f..10f,
            steps = 7
        ) { value ->
            onConfigChange { it.copy(terrain = it.terrain.copy(baseFrequency = value.roundToInt())) }
        }

        LabeledSlider(
            label = "Relief strength",
            value = config.terrain.gradientStrength,
            range = 0.2f..3f
        ) { value ->
            onConfigChange { it.copy(terrain = it.terrain.copy(gradientStrength = value)) }
        }

        LabeledSlider(
            label = "Smoothing",
            value = config.terrain.smoothing,
            range = 0f..1f
        ) { value ->
            onConfigChange { it.copy(terrain = it.terrain.copy(smoothing = value)) }
        }

        SectionHeader("Climate")

        LabeledSlider(
            label = "Equator temperature",
            value = config.climate.equatorTemperatureC,
            valueText = "${config.climate.equatorTemperatureC.roundToInt()} °C",
            range = 10f..45f
        ) { value ->
            onConfigChange { it.copy(climate = it.climate.copy(equatorTemperatureC = value)) }
        }

        LabeledSlider(
            label = "Pole temperature",
            value = config.climate.poleTemperatureC,
            valueText = "${config.climate.poleTemperatureC.roundToInt()} °C",
            range = -50f..5f
        ) { value ->
            onConfigChange { it.copy(climate = it.climate.copy(poleTemperatureC = value)) }
        }

        LabeledSlider(
            label = "Rain shadow strength",
            value = config.climate.orographicStrength,
            range = 0f..8f
        ) { value ->
            onConfigChange { it.copy(climate = it.climate.copy(orographicStrength = value)) }
        }

        SectionHeader("Rivers")

        LabeledSlider(
            label = "River density",
            // Inverted: a lower accumulation threshold means more, smaller rivers.
            value = -kotlin.math.log10(config.rivers.sourceThreshold),
            valueText = describeRiverDensity(config.rivers.sourceThreshold),
            range = 2f..4.5f
        ) { value ->
            val threshold = Math.pow(10.0, -value.toDouble()).toFloat()
            onConfigChange { it.copy(rivers = it.rivers.copy(sourceThreshold = threshold)) }
        }

        LabeledSlider(
            label = "Maximum rivers",
            value = config.rivers.maxRivers.toFloat(),
            valueText = "${config.rivers.maxRivers}",
            range = 20f..1500f
        ) { value ->
            onConfigChange { it.copy(rivers = it.rivers.copy(maxRivers = value.roundToInt())) }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("Realms")

        LabeledSlider(
            label = "Realms",
            value = config.nations.nationCount.toFloat(),
            valueText = "${config.nations.nationCount}",
            range = 0f..40f
        ) { value ->
            onConfigChange { it.copy(nations = it.nations.copy(nationCount = value.roundToInt())) }
        }

        Text(
            "Unclaimed land",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WildernessMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.nations.wilderness == mode,
                    onClick = {
                        onConfigChange { it.copy(nations = it.nations.copy(wilderness = mode)) }
                    },
                    label = { Text(mode.label, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            if (config.nations.wilderness == WildernessMode.CLAIM_ALL_LAND) {
                "Every cell of land belongs to a realm."
            } else {
                "Harsh and remote country is left wild — and is where the landmarks go."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        if (config.nations.wilderness == WildernessMode.LEAVE_WILDERNESS) {
            LabeledSlider(
                label = "How far realms reach",
                value = config.nations.reach,
                valueText = "%.1f".format(config.nations.reach),
                range = 0.6f..5f
            ) { value ->
                onConfigChange { it.copy(nations = it.nations.copy(reach = value)) }
            }
        }

        SectionHeader("Landmarks")

        LabeledSlider(
            label = "Points of interest",
            value = config.landmarks.count.toFloat(),
            valueText = "${config.landmarks.count}",
            range = 0f..120f
        ) { value ->
            onConfigChange { it.copy(landmarks = it.landmarks.copy(count = value.roundToInt())) }
        }

        ToggleRow("Wilderness sites only", config.landmarks.wildernessOnly) {
            onConfigChange {
                it.copy(landmarks = it.landmarks.copy(wildernessOnly = !it.landmarks.wildernessOnly))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("Display")

        MapViewSelector(current = state.renderOptions.view, onSelect = onViewChange)

        ToggleRow("Rivers", state.renderOptions.showRivers, onToggleRivers)
        ToggleRow("Relief shading", state.renderOptions.showHillshade, onToggleHillshade)
        ToggleRow("Realm borders", state.renderOptions.bordersVisible, onToggleBorders)
        ToggleRow("Landmarks", state.renderOptions.showLandmarks, onToggleLandmarks)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("Export")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportResolution.entries.forEach { resolution ->
                Button(
                    onClick = { onExport(resolution) },
                    enabled = !state.export.inProgress && state.world != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(resolution.size.toString(), maxLines = 1)
                }
            }
        }

        // The snackbar that reports the result sits behind this sheet, so without a line here an
        // export — which regenerates the whole world and takes a while — looks like nothing
        // happened at all.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.export.inProgress) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Rendering at full size — this takes a moment",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    state.export.message ?: "Saves a PNG to Pictures/Worldforge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MapViewSelector(current: MapView, onSelect: (MapView) -> Unit) {
    // Two per row rather than three: at three, the longer names ("Temperature", "Normal map")
    // get clipped mid-word on a phone-width drawer.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MapView.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { view ->
                    FilterChip(
                        selected = view == current,
                        onClick = { onSelect(view) },
                        label = { Text(view.label, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keeps a short final row's chips the same width as the rows above it.
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String? = null,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText ?: String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

private fun describeRiverDensity(threshold: Float): String = when {
    threshold > 0.003f -> "Sparse"
    threshold > 0.0008f -> "Moderate"
    threshold > 0.0002f -> "Dense"
    else -> "Very dense"
}

private fun resolutionToSlider(size: Int): Float = when (size) {
    256 -> 0f
    512 -> 1f
    1024 -> 2f
    else -> 3f
}

private fun sliderToResolution(value: Float): Int = when (value.roundToInt()) {
    0 -> 256
    1 -> 512
    2 -> 1024
    else -> 2048
}
