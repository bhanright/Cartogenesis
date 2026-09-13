package com.cartogenesis.ui

import kotlin.math.roundToLong
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.LandmarkOverride
import com.cartogenesis.cartography.MapPalette
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.ResolvedLandmark
import com.cartogenesis.cartography.ResolvedNation
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome

/**
 * The atlas, in whichever arrangement the window is.
 *
 * Where there is room, the list sits beside the detail, so picking a realm keeps its neighbours in
 * view — which is most of the point of an atlas. Where there is not, it drills down from the list
 * into the realm and back, because 320 dp of list on a 390 dp screen leaves 70 dp for the realm's
 * own page, and 70 dp is not a page.
 */
@Composable
fun AtlasPane(
    nations: List<ResolvedNation>,
    landmarks: List<ResolvedLandmark>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onEditNation: (Int, (NationOverride) -> NationOverride) -> Unit,
    onResetNation: (Int) -> Unit,
    onEditLandmark: (Int, (LandmarkOverride) -> LandmarkOverride) -> Unit,
    config: WorldGenConfig,
    onConfig: (WorldGenConfig) -> Unit,
    options: RenderOptions,
    onOptions: (RenderOptions) -> Unit,
    busy: Boolean,
    labelMode: Boolean,
    onToggleLabels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        AtlasSettings(config, options, busy, labelMode, onConfig, onOptions, onToggleLabels)
        HorizontalDivider()
        // The list beside the detail where there is room for both, and one or the other where
        // there is not. A 320 dp column on a 390 dp screen leaves 70 dp for the realm's own page,
        // which is narrow enough that "Pick a realm." wraps onto two lines and nothing else fits
        // at all; so on a phone the list is the whole width until a realm is picked, and then the
        // realm is, with the list one press away.
        val drillDown = LocalWindowShape.current == WindowShape.COMPACT
        val chosen = nations.firstOrNull { it.id == selected }
        Row(Modifier.fillMaxSize()) {
            if (!drillDown || chosen == null) Surface(
                if (drillDown) Modifier.weight(1f).fillMaxHeight()
                else Modifier.width(320.dp).fillMaxHeight(),
                tonalElevation = 1.dp
            ) {
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    item {
                        Text(
                            "Realms",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(nations, key = { it.id }) { nation ->
                        val active = nation.id == selected
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { onSelect(nation.id) }
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier.size(14.dp)
                                        .background(Color(MapPalette.nation(nation.id)), CircleShape)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(nation.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${nation.government} · " +
                                            populationLabel(nation.population),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (nation.edited) {
                                    Text(
                                        "•",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }

                    if (landmarks.isNotEmpty()) {
                        item {
                            Text(
                                "Landmarks",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        items(landmarks, key = { "lm-${it.id}" }) { landmark ->
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(landmark.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${landmark.kind.label} · ${landmark.detail}" +
                                        if (landmark.inWilderness) " · wild" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (chosen == null) {
                // Nothing to show beside the list — and nothing beside it to show anything in,
                // when the list is the whole width, so this is the wide window's half only.
                if (!drillDown) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (nations.isEmpty()) "No realms — raise the realm count."
                        else "Pick a realm.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                NationDetail(
                    nation = chosen,
                    // The way back to the list, and only where the list has gone away. On the
                    // desktop it is still there on the left and a button saying so would be a
                    // button that does nothing visible.
                    onBack = if (drillDown) ({ onSelect(null) }) else null,
                    onEdit = onEditNation,
                    onReset = onResetNation
                )
            }
        }
    }
}

/**
 * Everything about the atlas that is not the map itself: how many points of interest to generate,
 * whether to draw them, and placing the reader's own labels. These used to sit in the main panel
 * beside the settings that shape the map, which made the atlas look load-bearing when it is really
 * a half-finished side feature — so they live here instead, under the button that opens this pane.
 */
@Composable
private fun AtlasSettings(
    config: WorldGenConfig,
    options: RenderOptions,
    busy: Boolean,
    labelMode: Boolean,
    onConfig: (WorldGenConfig) -> Unit,
    onOptions: (RenderOptions) -> Unit,
    onToggleLabels: () -> Unit
) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Atlas settings", style = MaterialTheme.typography.titleMedium)
            // Declared alongside the panel's own knobs, in the atlas's section of [Knobs], so that
            // the guard walking the interface's settings can see these two as well: "still
            // settable" has to include the ones that moved out of the panel.
            val count = Knobs.landmarkCount
            Labelled(count.label, count.show(count.read(config))) {
                Slider(
                    value = count.read(config),
                    onValueChange = { onConfig(count.set(config, it)) },
                    valueRange = count.range,
                    enabled = !busy
                )
            }
            val landmarks = Knobs.landmarks
            Toggle(landmarks.label, landmarks.read(options)) {
                onOptions(landmarks.set(options, it))
            }
            OutlinedButton(onClick = onToggleLabels, enabled = !busy, contentPadding = TIGHT) {
                Text(if (labelMode) "Done labelling" else "Place a label", maxLines = 1)
            }
        }
    }
}

@Composable
private fun NationDetail(
    nation: ResolvedNation,
    /** Null where the list of realms is still on screen beside this. */
    onBack: (() -> Unit)?,
    onEdit: (Int, (NationOverride) -> NationOverride) -> Unit,
    onReset: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack, contentPadding = TIGHT) {
                Text("← Realms", maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(nation.name, style = MaterialTheme.typography.headlineSmall)
            if (nation.edited) {
                OutlinedButton(onClick = { onReset(nation.id) }) { Text("Reset to generated") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Realm", nation.name, Modifier.weight(1f)) { typed ->
                onEdit(nation.id) { it.copy(name = typed) }
            }
            Field("Government", nation.government, Modifier.weight(1f)) { typed ->
                onEdit(nation.id) { it.copy(government = typed) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Capital", nation.capitalName, Modifier.weight(1f)) { typed ->
                onEdit(nation.id) { it.copy(capitalName = typed) }
            }
            Field("Population", nation.population.toString(), Modifier.weight(1f)) { typed ->
                onEdit(nation.id) {
                    it.copy(population = typed.filter(Char::isDigit).toLongOrNull())
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("Geography", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            buildString {
                append(if (nation.isLandlocked) "Landlocked. " else "Has a coastline. ")
                append("Borders ${nation.neighbours.size} realm")
                append(if (nation.neighbours.size == 1) "." else "s.")
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            nation.source.biomeShare.take(BIOME_CHIPS).forEach { (biome, share) ->
                AssistChip(
                    onClick = {},
                    label = { Text("${biomeLabel(biome)} ${(share * 100).toInt()}%", maxLines = 1) }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("Trade", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Field("Exports", nation.exports.joinToString(", "), Modifier.fillMaxWidth()) { typed ->
            onEdit(nation.id) { it.copy(exports = commaSeparated(typed)) }
        }
        Field("Imports", nation.imports.joinToString(", "), Modifier.fillMaxWidth()) { typed ->
            onEdit(nation.id) { it.copy(imports = commaSeparated(typed)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Field("Lore", nation.lore, Modifier.fillMaxWidth(), minLines = LORE_LINES) { typed ->
            onEdit(nation.id) { it.copy(lore = typed) }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        singleLine = minLines == 1,
        modifier = modifier.padding(top = 8.dp)
    )
}

/** A comma-separated list as the reader typed it: `iron, salt, timber`. */
private fun commaSeparated(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Room for a paragraph of lore without the field growing under the reader as they type. */
private const val LORE_LINES = 5

// String.format is a JVM convenience and does not exist in the browser, so the rounding is
// spelled out. Only ever a millions or thousands figure, so one decimal place is the lot.
private fun populationLabel(population: Long): String = when {
    population >= 1_000_000 -> oneDecimal(population / 1_000_000.0) + "M"
    population >= 1_000 -> (population / 1_000.0).roundToLong().toString() + "k"
    else -> population.toString()
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** How many biomes a realm's page names: enough to characterise it, few enough to read. */
private const val BIOME_CHIPS = 4

private fun biomeLabel(biome: Biome): String =
    biome.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
