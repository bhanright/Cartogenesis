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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.LibraryEntry

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
    worlds: List<LibraryEntry>,
    location: String,
    supportsFileTransfer: Boolean,
    /** Whether there is a world on screen to save or download. */
    hasWorld: Boolean,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    /** Opens the entry with this key. See [LibraryEntry.key]. */
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

            // A 420 dp field and three buttons need something over 700 dp of row, and a Row that
            // is handed less does not wrap — it draws its children past the edge of the window and
            // they are simply gone. On a phone that took Save, Download and Upload off the screen
            // with no sign they were ever there. So the field takes the width it is given up to the
            // 420 dp it wanted, and where that leaves no room for the buttons they go underneath.
            val stacked = LocalWindowShape.current == WindowShape.COMPACT
            val name: @Composable (Modifier) -> Unit = { fieldModifier ->
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = fieldModifier
                )
            }
            val actions: @Composable () -> Unit = {
                Button(onClick = onSave, enabled = hasWorld) { Text("Save") }
                if (supportsFileTransfer) {
                    TextButton(onClick = onDownload, enabled = hasWorld) { Text("Download") }
                    TextButton(onClick = onUpload) { Text("Upload a file") }
                }
            }

            if (stacked) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    name(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    name(Modifier.width(420.dp))
                    actions()
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

        // Keyed by the file, not by the world's id: two copies of one world — the pair a sync
        // client leaves when two machines edit it — are two entries, and a list keyed by id would
        // have been handed the same key twice.
        items(worlds, key = { it.key }) { entry ->
            val world = entry.document
            Card(Modifier.fillMaxWidth().clickable { onOpen(entry.key) }) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        if (world == null) {
                            // A file that will not open is listed, with the reason, rather than left
                            // out: the reader can see it is there, and delete it.
                            Text(entry.key, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Will not open: ${entry.refusal?.message.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            return@Column
                        }
                        Text(world.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "seed ${world.config.seed} · ${world.config.width}px · " +
                                "${formatTimestamp(world.savedAt)} · ${entry.key}",
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
                    TextButton(onClick = { onDelete(entry.key) }) { Text("Delete") }
                }
            }
        }
    }
}
