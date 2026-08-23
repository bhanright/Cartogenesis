package com.cartogenesis.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.WorldDocument
import java.text.DateFormat
import java.util.Date

/**
 * Saving and reopening worlds.
 *
 * A file holds the seed, the settings and the user's edits — not the map, which is rebuilt from
 * them. That keeps a whole world at a few kilobytes, and because the format is shared with the
 * Android build, a file written on either opens on the other.
 */
@Composable
fun LibraryPane(
    title: String,
    worlds: List<WorldDocument>,
    location: String,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("This world", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.width(420.dp)
                )
                Button(onClick = onSave) { Text("Save") }
            }
            Text(
                "Saving under the same name updates it in place. Files live in $location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
        }

        item {
            Text(
                if (worlds.isEmpty()) "Nothing saved yet" else "Saved worlds",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(worlds, key = { it.id }) { world ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(world.id) }) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(world.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "seed ${world.config.seed} · ${world.config.width}px · " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(world.savedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val edits = buildList {
                            if (world.overrides.nations.isNotEmpty()) {
                                add("${world.overrides.nations.size} realms edited")
                            }
                            if (world.overrides.landmarks.isNotEmpty()) {
                                add("${world.overrides.landmarks.size} landmarks edited")
                            }
                            if (world.labels.isNotEmpty()) add("${world.labels.size} labels")
                        }
                        if (edits.isNotEmpty()) {
                            Text(
                                edits.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    TextButton(onClick = { onDelete(world.id) }) { Text("Delete") }
                }
            }
        }
    }
}
