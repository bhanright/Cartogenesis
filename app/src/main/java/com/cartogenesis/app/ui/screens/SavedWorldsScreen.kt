package com.cartogenesis.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.cartogenesis.app.world.WorldDocument
import java.text.DateFormat
import java.util.Date

/**
 * Saving and reopening worlds. A save records the seed, the settings and the user's edits — the
 * map itself is rebuilt from those, so files stay tiny and reopening reproduces it exactly.
 */
@Composable
fun SavedWorldsScreen(
    title: String,
    worlds: List<WorldDocument>,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("This world", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Save")
            }
            Text(
                "Saving again under the same name updates it in place.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        item {
            Text(
                if (worlds.isEmpty()) "Nothing saved yet" else "Saved worlds",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(worlds, key = { it.id }) { world ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(world.id) }) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
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
                        if (!world.overrides.isEmpty) {
                            Text(
                                "${world.overrides.nations.size} realms edited · " +
                                    "${world.overrides.territory.size} cells redrawn",
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
