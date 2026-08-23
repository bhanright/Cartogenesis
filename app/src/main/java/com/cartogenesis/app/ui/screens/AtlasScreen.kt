package com.cartogenesis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.MapPalette
import com.cartogenesis.cartography.LandmarkOverride
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.ResolvedLandmark
import com.cartogenesis.cartography.ResolvedNation
import com.cartogenesis.worldgen.pipeline.Biome

/**
 * The atlas: everything the generator inferred about the world's realms and landmarks, presented
 * so any of it can be rewritten. Generated values are only ever a starting point here.
 */
@Composable
fun AtlasScreen(
    nations: List<ResolvedNation>,
    landmarks: List<ResolvedLandmark>,
    selectedNation: Int?,
    onSelectNation: (Int?) -> Unit,
    onEditNation: (Int, (NationOverride) -> NationOverride) -> Unit,
    onResetNation: (Int) -> Unit,
    onEditLandmark: (Int, (LandmarkOverride) -> LandmarkOverride) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = nations.firstOrNull { it.id == selectedNation }

    if (selected != null) {
        NationDetail(
            nation = selected,
            landmarks = landmarks,
            onBack = { onSelectNation(null) },
            onEdit = { transform -> onEditNation(selected.id, transform) },
            onReset = { onResetNation(selected.id) },
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (nations.isEmpty()) {
            item {
                Text(
                    "No realms yet. Raise the realm count in the map settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(nations, key = { it.id }) { nation ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelectNation(nation.id) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(Color(MapPalette.nation(nation.id)), CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(nation.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${nation.government} · ${formatPopulation(nation.population)} people",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Capital ${nation.capitalName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (nation.edited) {
                        Text(
                            "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
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
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items(landmarks, key = { "lm-${it.id}" }) { landmark ->
                LandmarkRow(landmark) { transform -> onEditLandmark(landmark.id, transform) }
            }
        }
    }
}

@Composable
private fun LandmarkRow(
    landmark: ResolvedLandmark,
    onEdit: ((LandmarkOverride) -> LandmarkOverride) -> Unit
) {
    val expanded = remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable { expanded.value = !expanded.value }) {
        Column(Modifier.padding(14.dp)) {
            Text(landmark.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${landmark.kind.label} · ${landmark.detail}" +
                    if (landmark.inWilderness) " · in the wild" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded.value) {
                EditableField("Name", landmark.name) { value ->
                    onEdit { it.copy(name = value) }
                }
                EditableField("What it is", landmark.detail) { value ->
                    onEdit { it.copy(detail = value) }
                }
                EditableField("Notes", landmark.notes, minLines = 3) { value ->
                    onEdit { it.copy(notes = value) }
                }
            }
        }
    }
}

@Composable
private fun NationDetail(
    nation: ResolvedNation,
    landmarks: List<ResolvedLandmark>,
    onBack: () -> Unit,
    onEdit: ((NationOverride) -> NationOverride) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            if (nation.edited) {
                OutlinedButton(onClick = onReset) { Text("Reset to generated") }
            }
        }

        EditableField("Realm", nation.name) { value -> onEdit { it.copy(name = value) } }
        EditableField("Government", nation.government) { value ->
            onEdit { it.copy(government = value) }
        }
        EditableField("Capital", nation.capitalName) { value ->
            onEdit { it.copy(capitalName = value) }
        }
        EditableField("Population", nation.population.toString()) { value ->
            val parsed = value.filter { it.isDigit() }.toLongOrNull()
            onEdit { it.copy(population = parsed) }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Straight from the map — these are facts about the terrain, not editable prose.
        Text("Geography", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            buildString {
                append(if (nation.isLandlocked) "Landlocked. " else "Has a coastline. ")
                append("Borders ${nation.neighbours.size} realm")
                append(if (nation.neighbours.size == 1) "." else "s.")
            },
            style = MaterialTheme.typography.bodyMedium
        )
        // Biome names get long, so let the row scroll rather than clipping the last chip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            nation.source.biomeShare.take(4).forEach { (biome, share) ->
                AssistChip(
                    onClick = {},
                    label = { Text("${biomeLabel(biome)} ${(share * 100).toInt()}%", maxLines = 1) }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Trade", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        EditableField("Exports", nation.exports.joinToString(", ")) { value ->
            onEdit { it.copy(exports = splitList(value)) }
        }
        EditableField("Imports", nation.imports.joinToString(", ")) { value ->
            onEdit { it.copy(imports = splitList(value)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        EditableField("Lore", nation.lore, minLines = 5) { value ->
            onEdit { it.copy(lore = value) }
        }

        val nearby = landmarks.filter { it.inWilderness }
        if (nearby.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "Wilderness beyond the borders",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            nearby.take(6).forEach {
                Text(
                    "· ${it.name} (${it.kind.label})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A text field that reports every change straight up. Edits are held in the override map rather
 * than local state, so what is shown is always the resolved value.
 */
@Composable
private fun EditableField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

private fun splitList(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun formatPopulation(population: Long): String = when {
    population >= 1_000_000 -> "%.1fM".format(population / 1_000_000.0)
    population >= 1_000 -> "%.0fk".format(population / 1_000.0)
    else -> population.toString()
}

private fun biomeLabel(biome: Biome): String =
    biome.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
