package com.cartogenesis.ui

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

/**
 * Saving and reopening worlds.
 *
 * A save carries the world itself, not just the seed and settings it took to generate one, and
 * the format is shared between every front end - so a file written on one opens on the other.
 * [supportsFileTransfer] is what makes that literal rather than theoretical: where it is true,
 * this offers a download of the current world and an upload of one, which is currently the only
 * way a save crosses between a browser tab (library in IndexedDB, invisible outside the page) and
 * the desktop (library as ordinary files, which a user can already move by hand and so does not
 * need the button).
 */
@Composable
fun LibraryPane(
    title: String,
    worlds: List<WorldDocument>,
    location: String,
    supportsFileTransfer: Boolean,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
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
                if (supportsFileTransfer) {
                    TextButton(onClick = onDownload) { Text("Download") }
                    TextButton(onClick = onUpload) { Text("Upload a file") }
                }
            }
            Text(
                "Saving under the same name updates it in place. Files live in $location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            if (supportsFileTransfer) {
                Text(
                    "Download saves the current world as a .cgw file you can move to the desktop " +
                        "build, or keep as a backup outside this browser. Upload opens one back up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
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
                                formatTimestamp(world.savedAt),
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
