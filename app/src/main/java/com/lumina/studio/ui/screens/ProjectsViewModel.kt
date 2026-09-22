package com.lumina.studio.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.batch.BatchItemResult
import com.lumina.studio.core.batch.BatchOps
import com.lumina.studio.core.batch.SettingsClipboard
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.edit.withEditParams
import com.lumina.studio.core.export.BatchExporter
import com.lumina.studio.core.export.ExportSettings
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.presets.PresetShare
import com.lumina.studio.core.presets.SettingGroups
import com.lumina.studio.core.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

enum class ProjectSort { DATE, NAME, TYPE }

data class BatchUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val progress: Float = 0f,
    val results: List<BatchItemResult> = emptyList(),
    val summary: String? = null
)

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val sort: ProjectSort = ProjectSort.DATE,
    val favoritesOnly: Boolean = false,
    val editCounts: Map<String, Int> = emptyMap()
)

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(ProjectSort.DATE)
    val sort: StateFlow<ProjectSort> = _sort.asStateFlow()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    private val _editCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val editCounts: StateFlow<Map<String, Int>> = _editCounts.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _batchState = MutableStateFlow(BatchUiState())
    val batchState: StateFlow<BatchUiState> = _batchState.asStateFlow()

    private var batchJob: Job? = null

    private var lastDeleted: Project? = null

    val uiState: StateFlow<ProjectsUiState> = combine(
        database.projectDao().observeProjects(),
        _query,
        _sort,
        _favoritesOnly,
        _editCounts
    ) { projects, query, sort, favoritesOnly, counts ->
        var filtered = projects
        if (favoritesOnly) filtered = filtered.filter { it.isFavorite }
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        }
        filtered = when (sort) {
            ProjectSort.NAME -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ProjectSort.TYPE -> filtered.sortedBy { it.fileType }
            ProjectSort.DATE -> filtered.sortedByDescending { it.updatedAt }
        }
        ProjectsUiState(filtered, query, sort, favoritesOnly, counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectsUiState())

    init {
        viewModelScope.launch {
            database.projectDao().observeProjects().collect { projects ->
                val counts = projects.associate { project ->
                    project.id to database.editHistoryDao().countForProject(project.id)
                }
                _editCounts.value = counts
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(value: ProjectSort) {
        _sort.value = value
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun toggleSelect(projectId: String) {
        val current = _selectedIds.value.toMutableSet()
        if (!current.add(projectId)) current.remove(projectId)
        _selectedIds.value = current
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll(ids: List<String>) {
        _selectedIds.value = ids.toSet()
    }

    fun applyPresetToMany(ids: List<String>, presetId: String) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val preset = database.presetDao().getById(presetId)
                val lutsDir = ProjectStore.lutsDir(app)
                var applied = 0
                for (id in ids) {
                    val project = database.projectDao().getById(id) ?: continue
                    val base = project.toEditParams()
                    val merged = if (preset == null) {
                        base
                    } else {
                        val recipe = PresetShare.readRecipeParams(lutsDir, preset.id)
                            ?: EditParams.DEFAULT.copy(
                                presetId = preset.id,
                                presetIntensity = preset.defaultIntensity.coerceIn(0f, 1f)
                            )
                        var next = SettingGroups.applyCopy(recipe, base, SettingGroups.PRESET_GROUPS)
                        if (recipe.presetId != null && LutRegistry.resolve(recipe.presetId) == null) {
                            next = next.copy(presetId = null, presetIntensity = 1f)
                        }
                        next
                    }
                    if (merged == base) continue
                    database.projectDao().upsert(
                        project.withEditParams(merged).copy(updatedAt = System.currentTimeMillis())
                    )
                    applied++
                }
                _notice.value = if (applied == 0) "Nothing changed" else "Applied to $applied photo(s)"
            } catch (e: Exception) {
                _notice.value = e.message ?: "Could not apply preset"
            }
        }
    }

    fun pasteToMany(ids: List<String>) {
        if (ids.isEmpty()) return
        if (!SettingsClipboard.hasContent()) {
            _notice.value = "Copy settings first (History screen)"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var applied = 0
                for (id in ids) {
                    val project = database.projectDao().getById(id) ?: continue
                    val merged = SettingsClipboard.paste(project.toEditParams()) ?: continue
                    if (merged == project.toEditParams()) continue
                    database.projectDao().upsert(
                        project.withEditParams(merged).copy(updatedAt = System.currentTimeMillis())
                    )
                    applied++
                }
                _notice.value = if (applied == 0) "Nothing changed" else "Pasted to $applied photo(s)"
            } catch (e: Exception) {
                _notice.value = e.message ?: "Could not paste settings"
            }
        }
    }

    fun startBatchExport(ids: List<String>, settings: ExportSettings? = null) {
        if (ids.isEmpty() || _batchState.value.running) return
        batchJob?.cancel()
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _batchState.value = BatchUiState(running = true, total = ids.size)
            val resolved = settings ?: BatchExporter.batchSettings(app)
            runCatching { BatchExporter.ensureLutsLoaded(app) }
            val results = ArrayList<BatchItemResult>(ids.size)
            for (id in ids) {
                try {
                    withContext(Dispatchers.Default) {
                        BatchExporter.exportProject(app, id, resolved)
                    }
                    results.add(BatchItemResult(id, true))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    results.add(BatchItemResult(id, false, e.message ?: "Export failed"))
                }
                _batchState.value = BatchUiState(
                    running = true,
                    done = results.size,
                    total = ids.size,
                    progress = BatchOps.progress(results.size, ids.size),
                    results = results.toList()
                )
            }
            _batchState.value = BatchUiState(
                running = false,
                done = results.size,
                total = ids.size,
                progress = 1f,
                results = results.toList(),
                summary = BatchOps.summarize(results)
            )
            _notice.value = BatchOps.summarize(results)
        }
    }

    fun cancelBatchExport() {
        batchJob?.cancel()
        batchJob = null
        _batchState.value = _batchState.value.copy(running = false)
    }

    fun clearBatchState() {
        _batchState.value = BatchUiState()
    }

    fun toggleFavorite(project: Project) {
        viewModelScope.launch {
            database.projectDao().upsert(
                project.copy(isFavorite = !project.isFavorite, updatedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * Safe delete order: DB row first, then owned files.
     *
     * Row-first guarantees the UI can never show a project whose bytes are
     * half-deleted: a crash between the two steps leaves at worst orphaned
     * files (reclaimed by the orphan scan), never a visible row pointing at
     * missing bytes. Undo therefore only restores the row while the original
     * file still exists; deleted source bytes are never resurrected.
     */
    fun delete(project: Project) {
        lastDeleted = project
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            database.projectDao().deleteById(project.id)
            runCatching { database.editHistoryDao().clearForProject(project.id) }
            runCatching { ProjectStore.deleteProjectFiles(app, project.id) }
            runCatching { ProjectStore.deleteOwnedFile(app, project.photoUri) }
        }
    }

    fun undoDelete(): Project? {
        val deleted = lastDeleted ?: return null
        lastDeleted = null
        if (!ProjectStore.originalExists(deleted.photoUri)) return null
        viewModelScope.launch {
            database.projectDao().upsert(deleted.copy(updatedAt = System.currentTimeMillis()))
        }
        return deleted
    }

    fun duplicate(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val srcPath = project.photoUri
            val src = if (srcPath.isNullOrBlank()) null else java.io.File(srcPath).takeIf { it.isFile }
            if (src == null) {
                _notice.value = "Original file is missing — cannot duplicate ${project.name}"
                return@launch
            }
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()
            val dest = ProjectStore.copyFileIntoOriginal(app, newId, src, project.name)
            if (dest == null) {
                _notice.value = "Could not duplicate ${project.name}"
                return@launch
            }
            val bounds = ImageFiles.decodeBounds(dest)
            val copy = project.copy(
                id = newId,
                name = project.name + " (copy)",
                photoUri = dest.absolutePath,
                createdAt = now,
                updatedAt = now,
                width = bounds.width.takeIf { it > 0 } ?: project.width,
                height = bounds.height.takeIf { it > 0 } ?: project.height
            )
            database.projectDao().upsert(copy)
            _notice.value = "Duplicated ${project.name}"
        }
    }
}
