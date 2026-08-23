package com.worldforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.worldforge.app.ui.MapViewModel
import com.worldforge.worldgen.model.LabelKind
import com.worldforge.worldgen.model.MapLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldforgeApp(viewModel: MapViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(Screen.MAP) }
    var selectedNation by remember { mutableStateOf<Int?>(null) }
    var labelMode by remember { mutableStateOf(false) }
    var pendingLabelPosition by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var labelToDelete by remember { mutableStateOf<MapLabel?>(null) }

    // A side drawer rather than a bottom sheet: the settings list is long and keeps growing, and a
    // drawer gets the full height of the screen for it instead of half.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.export.message) {
        state.export.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissExportMessage()
        }
    }

    LaunchedEffect(state.saveMessage) {
        state.saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSaveMessage()
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshSavedWorlds() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(360.dp)) {
                ControlsPanel(
                    state = state,
                    onConfigChange = viewModel::updateConfig,
                    onRandomizeSeed = viewModel::randomizeSeed,
                    onViewChange = viewModel::setView,
                    onToggleRivers = viewModel::toggleRivers,
                    onToggleHillshade = viewModel::toggleHillshade,
                    onToggleBorders = viewModel::toggleBorders,
                    onToggleLandmarks = viewModel::toggleLandmarks,
                    onExport = viewModel::export
                )
            }
        }
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title.ifBlank { "Worldforge" }, maxLines = 1)
                        state.world?.let { world ->
                            // Four icons share this bar, so the summary has to hold one line.
                            Text(
                                "${(world.landFraction() * 100).toInt()}% land · " +
                                    "${world.nations.nations.size} realms · " +
                                    "${world.rivers.rivers.size} rivers",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Map settings")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        screen = if (screen == Screen.LIBRARY) Screen.MAP else Screen.LIBRARY
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Saved worlds")
                    }
                    IconButton(onClick = {
                        selectedNation = null
                        screen = if (screen == Screen.ATLAS) Screen.MAP else Screen.ATLAS
                    }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Atlas")
                    }
                    IconButton(onClick = viewModel::randomizeSeed) {
                        Icon(Icons.Default.Casino, contentDescription = "Generate a new world")
                    }
                }
            )
        },
        floatingActionButton = {
            if (screen != Screen.MAP) return@Scaffold
            FloatingActionButton(
                onClick = { labelMode = !labelMode },
                containerColor = if (labelMode) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Label,
                    contentDescription = if (labelMode) "Finish labelling" else "Place a label"
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.ATLAS -> AtlasScreen(
                    nations = viewModel.resolvedNations(),
                    landmarks = viewModel.resolvedLandmarks(),
                    selectedNation = selectedNation,
                    onSelectNation = { selectedNation = it },
                    onEditNation = viewModel::editNation,
                    onResetNation = viewModel::resetNation,
                    onEditLandmark = viewModel::editLandmark
                )

                Screen.LIBRARY -> SavedWorldsScreen(
                    title = state.title,
                    worlds = state.savedWorlds,
                    onTitleChange = viewModel::setTitle,
                    onSave = { viewModel.saveWorld() },
                    onOpen = { id -> viewModel.openWorld(id); screen = Screen.MAP },
                    onDelete = viewModel::deleteWorld
                )

                Screen.MAP -> MapBody(
                    state = state,
                    labelMode = labelMode,
                    onMapTap = { x, y -> pendingLabelPosition = x to y },
                    onLabelTap = { labelToDelete = it }
                )
            }
        }
    }
    }

    pendingLabelPosition?.let { (x, y) ->
        AddLabelDialog(
            onDismiss = { pendingLabelPosition = null },
            onConfirm = { text, kind ->
                viewModel.addLabel(text, x, y, kind)
                pendingLabelPosition = null
            }
        )
    }

    labelToDelete?.let { label ->
        DeleteLabelDialog(
            label = label,
            onDismiss = { labelToDelete = null },
            onConfirm = {
                viewModel.removeLabel(label.id)
                labelToDelete = null
            }
        )
    }
}

private enum class Screen { MAP, ATLAS, LIBRARY }

@Composable
private fun MapBody(
    state: com.worldforge.app.ui.MapUiState,
    labelMode: Boolean,
    onMapTap: (Float, Float) -> Unit,
    onLabelTap: (MapLabel) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
            MapCanvas(
                bitmap = state.bitmap,
                labels = state.labels,
                labelPlacementMode = labelMode,
                onMapTap = onMapTap,
                onLabelTap = onLabelTap,
                modifier = Modifier.fillMaxSize()
            )

            if (state.isGenerating) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            state.stageLabel ?: "Generating…",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (labelMode) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    Text(
                        "Tap the map to place a label",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
@Composable
private fun AddLabelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, LabelKind) -> Unit
) {
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
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LabelKind.entries.take(3).forEach { option ->
                        FilterChip(
                            selected = option == kind,
                            onClick = { kind = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, kind) },
                enabled = text.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteLabelDialog(
    label: MapLabel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"${label.text}\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
