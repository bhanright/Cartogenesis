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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cartogenesis.cartography.ResolvedLandmark
import com.cartogenesis.cartography.ResolvedNation
import com.cartogenesis.worldgen.pipeline.Biome

/**
 * The atlas, laid out for a desktop window.
 *
 * The phone build drills down from a list into a detail screen because there is no room for both.
 * Here the list sits beside the detail, so picking a realm keeps its neighbours in view — which is
 * most of the point of an atlas.
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
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxSize()) {
        Surface(Modifier.width(320.dp).fillMaxHeight(), tonalElevation = 1.dp) {
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
                                    "${nation.government} · ${people(nation.population)}",
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

        val nation = nations.firstOrNull { it.id == selected }
        if (nation == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (nations.isEmpty()) "No realms — raise the realm count."
                    else "Pick a realm.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            NationDetail(nation, onEditNation, onResetNation)
        }
    }
}

@Composable
private fun NationDetail(
    nation: ResolvedNation,
    onEdit: (Int, (NationOverride) -> NationOverride) -> Unit,
    onReset: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
            Field("Realm", nation.name, Modifier.weight(1f)) { v ->
                onEdit(nation.id) { it.copy(name = v) }
            }
            Field("Government", nation.government, Modifier.weight(1f)) { v ->
                onEdit(nation.id) { it.copy(government = v) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Capital", nation.capitalName, Modifier.weight(1f)) { v ->
                onEdit(nation.id) { it.copy(capitalName = v) }
            }
            Field("Population", nation.population.toString(), Modifier.weight(1f)) { v ->
                onEdit(nation.id) { it.copy(population = v.filter(Char::isDigit).toLongOrNull()) }
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
            nation.source.biomeShare.take(4).forEach { (biome, share) ->
                AssistChip(
                    onClick = {},
                    label = { Text("${biomeLabel(biome)} ${(share * 100).toInt()}%", maxLines = 1) }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("Trade", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Field("Exports", nation.exports.joinToString(", "), Modifier.fillMaxWidth()) { v ->
            onEdit(nation.id) { it.copy(exports = splitList(v)) }
        }
        Field("Imports", nation.imports.joinToString(", "), Modifier.fillMaxWidth()) { v ->
            onEdit(nation.id) { it.copy(imports = splitList(v)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Field("Lore", nation.lore, Modifier.fillMaxWidth(), minLines = 5) { v ->
            onEdit(nation.id) { it.copy(lore = v) }
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

private fun splitList(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

// String.format is a JVM convenience and does not exist in the browser, so the rounding is
// spelled out. Only ever a millions or thousands figure, so one decimal place is the lot.
private fun people(population: Long): String = when {
    population >= 1_000_000 -> oneDecimal(population / 1_000_000.0) + "M"
    population >= 1_000 -> (population / 1_000.0).roundToLong().toString() + "k"
    else -> population.toString()
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

private fun biomeLabel(biome: Biome): String =
    biome.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
