package com.worldforge.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worldforge.app.export.ExportResolution
import com.worldforge.app.export.HdExporter
import com.worldforge.app.render.MapRenderer
import com.worldforge.app.render.MapView
import com.worldforge.app.render.RenderOptions
import com.worldforge.worldgen.WorldGenerationEngine
import com.worldforge.worldgen.model.LabelKind
import com.worldforge.worldgen.model.MapLabel
import com.worldforge.worldgen.model.WorldGenConfig
import com.worldforge.app.world.LandmarkOverride
import com.worldforge.app.world.NationOverride
import com.worldforge.app.world.ResolvedLandmark
import com.worldforge.app.world.ResolvedNation
import com.worldforge.app.world.WorldDocument
import com.worldforge.app.world.WorldOverrides
import com.worldforge.app.world.WorldStore
import com.worldforge.app.world.resolve
import com.worldforge.worldgen.model.WorldMap
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val uri: Uri? = null
)

data class MapUiState(
    val config: WorldGenConfig = WorldGenConfig(seed = Random.nextLong(1_000_000)),
    val overrides: WorldOverrides = WorldOverrides(),
    val documentId: String = java.util.UUID.randomUUID().toString(),
    val title: String = "Untitled world",
    val savedWorlds: List<WorldDocument> = emptyList(),
    val saveMessage: String? = null,
    val world: WorldMap? = null,
    val bitmap: Bitmap? = null,
    val renderOptions: RenderOptions = RenderOptions(),
    val labels: List<MapLabel> = emptyList(),
    val isGenerating: Boolean = false,
    val stageLabel: String? = null,
    val export: ExportState = ExportState()
)

@OptIn(FlowPreview::class)
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val configRequests = MutableStateFlow(_state.value.config)
    private var nextLabelId = 1L
    private val store = WorldStore(application)

    init {
        viewModelScope.launch {
            // Debounced so dragging a slider does not queue a generation per frame.
            configRequests.debounce(160).collectLatest { config -> regenerate(config) }
        }
    }

    fun updateConfig(transform: (WorldGenConfig) -> WorldGenConfig) {
        val updated = transform(_state.value.config)
        _state.update { it.copy(config = updated) }
        configRequests.value = updated
    }

    fun randomizeSeed() = updateConfig { it.copy(seed = Random.nextLong(1_000_000)) }

    fun setView(view: MapView) = updateRenderOptions { it.copy(view = view) }

    fun toggleRivers() = updateRenderOptions { it.copy(showRivers = !it.showRivers) }

    fun toggleHillshade() = updateRenderOptions { it.copy(showHillshade = !it.showHillshade) }

    fun toggleBorders() = updateRenderOptions { it.copy(showBorders = !it.bordersVisible) }

    fun toggleLandmarks() = updateRenderOptions { it.copy(showLandmarks = !it.showLandmarks) }

    fun addLabel(text: String, x: Float, y: Float, kind: LabelKind) {
        if (text.isBlank()) return
        val label = MapLabel(nextLabelId++, text.trim(), x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), kind)
        _state.update { it.copy(labels = it.labels + label) }
    }

    fun removeLabel(id: Long) {
        _state.update { current -> current.copy(labels = current.labels.filterNot { it.id == id }) }
    }

    fun export(resolution: ExportResolution) {
        if (_state.value.export.inProgress) return
        val current = _state.value

        viewModelScope.launch {
            _state.update { it.copy(export = ExportState(inProgress = true, message = "Rendering…")) }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    HdExporter.export(
                        context = getApplication<Application>(),
                        config = current.config,
                        labels = current.labels,
                        renderOptions = current.renderOptions,
                        resolution = resolution
                    )
                }
            }
            _state.update {
                it.copy(
                    export = result.fold(
                        onSuccess = { uri -> ExportState(false, "Saved to Pictures/Worldforge", uri) },
                        onFailure = { error -> ExportState(false, "Export failed: ${error.message}") }
                    )
                )
            }
        }
    }

    // -- realms, landmarks and the atlas ----------------------------------------------------

    /** Realms with the user's edits layered over the generated values. */
    fun resolvedNations(): List<ResolvedNation> {
        val world = _state.value.world ?: return emptyList()
        val overrides = _state.value.overrides
        return world.nations.nations.map { it.resolve(overrides.forNation(it.id)) }
    }

    fun resolvedLandmarks(): List<ResolvedLandmark> {
        val world = _state.value.world ?: return emptyList()
        val overrides = _state.value.overrides
        return world.landmarks.landmarks.map { it.resolve(overrides.forLandmark(it.id)) }
    }

    fun editNation(id: Int, transform: (NationOverride) -> NationOverride) {
        _state.update { current ->
            current.copy(
                overrides = current.overrides.withNation(id, transform(current.overrides.forNation(id)))
            )
        }
    }

    fun editLandmark(id: Int, transform: (LandmarkOverride) -> LandmarkOverride) {
        _state.update { current ->
            current.copy(
                overrides = current.overrides.withLandmark(
                    id, transform(current.overrides.forLandmark(id))
                )
            )
        }
    }

    /** Drops every edit for one realm, putting it back to what the generator produced. */
    fun resetNation(id: Int) {
        _state.update { it.copy(overrides = it.overrides.withNation(id, NationOverride())) }
    }

    // -- saved worlds ------------------------------------------------------------------------

    fun refreshSavedWorlds() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(savedWorlds = saved) }
        }
    }

    fun setTitle(title: String) = _state.update { it.copy(title = title) }

    fun saveWorld() {
        val current = _state.value
        viewModelScope.launch {
            val document = WorldDocument(
                id = current.documentId,
                title = current.title.ifBlank { "Untitled world" },
                config = current.config,
                overrides = current.overrides,
                labels = current.labels
            )
            withContext(Dispatchers.IO) { store.save(document) }
            _state.update { it.copy(saveMessage = "Saved \"${document.title}\"") }
            refreshSavedWorlds()
        }
    }

    fun openWorld(id: String) {
        viewModelScope.launch {
            val document = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            nextLabelId = (document.labels.maxOfOrNull { it.id } ?: 0L) + 1
            _state.update {
                it.copy(
                    config = document.config,
                    overrides = document.overrides,
                    labels = document.labels,
                    documentId = document.id,
                    title = document.title
                )
            }
            configRequests.value = document.config
        }
    }

    fun deleteWorld(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshSavedWorlds()
        }
    }

    fun dismissSaveMessage() = _state.update { it.copy(saveMessage = null) }

    fun dismissExportMessage() {
        _state.update { it.copy(export = it.export.copy(message = null)) }
    }

    private fun updateRenderOptions(transform: (RenderOptions) -> RenderOptions) {
        val options = transform(_state.value.renderOptions)
        _state.update { it.copy(renderOptions = options) }
        val world = _state.value.world ?: return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { MapRenderer.render(world, options) }
            _state.update { it.copy(bitmap = bitmap) }
        }
    }

    private fun applyTerritoryOverrides(world: WorldMap, overrides: WorldOverrides) {
        if (overrides.territory.isEmpty()) return
        val owners = world.nations.nationId
        overrides.territory.forEach { (cell, owner) ->
            if (cell in owners.indices) owners[cell] = owner
        }
    }

    private suspend fun regenerate(config: WorldGenConfig) {
        _state.update { it.copy(isGenerating = true) }
        val previous = _state.value.world

        val world = withContext(Dispatchers.Default) {
            WorldGenerationEngine.generate(config, previous) { stage, _, _ ->
                _state.update { it.copy(stageLabel = stage.label) }
            }
        }
        // Territory the user reassigned by hand is layered back over the freshly generated map.
        applyTerritoryOverrides(world, _state.value.overrides)

        val bitmap = withContext(Dispatchers.Default) {
            MapRenderer.render(world, _state.value.renderOptions)
        }

        _state.update {
            it.copy(world = world, bitmap = bitmap, isGenerating = false, stageLabel = null)
        }
    }
}
