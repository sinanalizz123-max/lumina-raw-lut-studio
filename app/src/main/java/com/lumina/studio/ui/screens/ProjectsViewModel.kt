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
import com.lumina.studio.core.library.Album
import com.lumina.studio.core.library.AlbumRepository
import com.lumina.studio.core.library.AlbumStore
import com.lumina.studio.core.library.CullEngine
import com.lumina.studio.core.library.CullFlags
import com.lumina.studio.core.library.CullScore
import com.lumina.studio.core.library.CullScoring
import com.lumina.studio.core.library.LibraryFilter
import com.lumina.studio.core.library.LibraryFilterState
import com.lumina.studio.core.library.PresetFilter
import com.lumina.studio.core.library.RecencyFilter
import com.lumina.studio.core.library.TypeFilter
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.presets.PresetShare
import com.lumina.studio.core.presets.SettingGroups
import com.lumina.studio.core.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    val editCounts: Map<String, Int> = emptyMap(),
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val presetFilter: PresetFilter = PresetFilter.ALL,
    val recencyFilter: RecencyFilter = RecencyFilter.ALL,
    val activeAlbumId: String? = null,
    val albums: List<Album> = emptyList(),
    val albumCounts: Map<String, Int> = emptyMap()
)

/**
 * M13 assisted-culling review state. Scores arrive incrementally while
 * [running]; [duplicates] maps a project id to the earlier project id it
 * resembles. [checked] is the user's explicit delete shortlist — review
 * never deletes on its own.
 */
data class CullUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val scores: Map<String, CullScore> = emptyMap(),
    val duplicates: Map<String, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val checked: Set<String> = emptySet()
) {
    fun flagsFor(projectId: String): CullFlags? {
        val score = scores[projectId] ?: return null
        return score.flags.copy(duplicateOf = duplicates[projectId])
    }
}

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)
    private val albumRepository = AlbumRepository(application)

    private val _sort = MutableStateFlow(ProjectSort.DATE)
    val sort: StateFlow<ProjectSort> = _sort.asStateFlow()

    /**
     * Single filter state for search + favorites + type + preset +
     * recency + album. Combined with sort below into one ProjectsUiState.
     */
    private val _filters = MutableStateFlow(LibraryFilterState())
    val filters: StateFlow<LibraryFilterState> = _filters.asStateFlow()

    private val _editCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val editCounts: StateFlow<Map<String, Int>> = _editCounts.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _batchState = MutableStateFlow(BatchUiState())
    val batchState: StateFlow<BatchUiState> = _batchState.asStateFlow()

    private val _cullState = MutableStateFlow(CullUiState())
    val cullState: StateFlow<CullUiState> = _cullState.asStateFlow()

    val albumStore: StateFlow<AlbumStore> = albumRepository.store
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumStore.EMPTY)

    private var batchJob: Job? = null
    private var cullJob: Job? = null

    private var lastDeleted: Project? = null

    val uiState: StateFlow<ProjectsUiState> = combine(
        database.projectDao().observeProjects(),
        _sort,
        _editCounts,
        _filters,
        albumStore
    ) { projects, sort, counts, filters, albums ->
        val now = System.currentTimeMillis()
        val members = filters.albumId?.let { id ->
            albums.entries.filter { it.albumId == id }.map { it.projectId }.toSet()
        }.orEmpty()
        var filtered = LibraryFilter.apply(projects, filters, now) { it in members }
        filtered = when (sort) {
            ProjectSort.NAME -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ProjectSort.TYPE -> filtered.sortedBy { it.fileType }
            ProjectSort.DATE -> filtered.sortedByDescending { it.updatedAt }
        }
        ProjectsUiState(
            projects = filtered,
            query = filters.query,
            sort = sort,
            favoritesOnly = filters.favoritesOnly,
            editCounts = counts,
            typeFilter = filters.type,
            presetFilter = filters.preset,
            recencyFilter = filters.recency,
            activeAlbumId = filters.albumId,
            albums = albums.albums,
            albumCounts = albums.entries.groupingBy { it.albumId }.eachCount()
        )
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
        _filters.value = _filters.value.copy(query = value)
    }

    fun setSort(value: ProjectSort) {
        _sort.value = value
    }

    fun toggleFavoritesOnly() {
        _filters.value = _filters.value.copy(favoritesOnly = !_filters.value.favoritesOnly)
    }

    fun setTypeFilter(value: TypeFilter) {
        _filters.value = _filters.value.copy(type = value)
    }

    fun setPresetFilter(value: PresetFilter) {
        _filters.value = _filters.value.copy(preset = value)
    }

    fun setRecencyFilter(value: RecencyFilter) {
        _filters.value = _filters.value.copy(recency = value)
    }

    fun setActiveAlbum(albumId: String?) {
        _filters.value = _filters.value.copy(albumId = albumId)
    }

    fun clearFilters() {
        _filters.value = LibraryFilterState()
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

    // ---------- albums (membership only — never deletes photos) ----------

    fun createAlbum(name: String, onDone: (Album?) -> Unit = {}) {
        viewModelScope.launch {
            val album = withContext(Dispatchers.IO) { albumRepository.createAlbum(name) }
            _notice.value = if (album == null) "Could not create album" else "Created ${album.name}"
            onDone(album)
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.deleteAlbum(albumId)
            withContext(Dispatchers.Main) {
                if (_filters.value.albumId == albumId) {
                    _filters.value = _filters.value.copy(albumId = null)
                }
                _notice.value = "Deleted album (photos kept)"
            }
        }
    }

    fun addToAlbum(albumId: String, projectIds: List<String>) {
        if (projectIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.addToAlbum(albumId, projectIds)
            _notice.value = "Added ${projectIds.size} photo(s) to album"
        }
    }

    fun removeFromAlbum(albumId: String, projectIds: List<String>) {
        if (projectIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.removeFromAlbum(albumId, projectIds)
            _notice.value = "Removed ${projectIds.size} photo(s) from album"
        }
    }

    // ---------- assisted culling (flags only — user decides) ----------

    fun startCullReview() {
        if (_cullState.value.running) return
        cullJob?.cancel()
        val snapshot = uiState.value.projects
        _cullState.value = CullUiState(
            running = true,
            total = snapshot.size,
            names = snapshot.associate { it.id to it.name }
        )
        cullJob = viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val scores = LinkedHashMap<String, CullScore>()
            val ordered = snapshot.sortedBy { it.createdAt }.map { it.id }
            for (project in snapshot) {
                ensureActive()
                val score = CullEngine.analyzeProject(app, project)
                if (score != null) scores[project.id] = score
                _cullState.value = CullUiState(
                    running = true,
                    done = scores.size,
                    total = snapshot.size,
                    scores = scores.toMap(),
                    duplicates = CullScoring.assignDuplicates(
                        ordered.filter { it in scores },
                        scores.mapValues { it.value.hash }
                    ),
                    names = snapshot.associate { it.id to it.name },
                    checked = _cullState.value.checked
                )
            }
            _cullState.value = _cullState.value.copy(
                running = false,
                checked = _cullState.value.checked.filter { it in scores }.toSet()
            )
        }
    }

    fun cancelCullReview() {
        cullJob?.cancel()
        cullJob = null
        if (_cullState.value.running) {
            _cullState.value = _cullState.value.copy(running = false)
        }
    }

    fun toggleCullChecked(projectId: String) {
        val current = _cullState.value.checked.toMutableSet()
        if (!current.add(projectId)) current.remove(projectId)
        _cullState.value = _cullState.value.copy(checked = current)
    }

    fun selectFlaggedCull() {
        val flagged = _cullState.value.scores.keys.filter { id ->
            _cullState.value.flagsFor(id)?.hasAny == true
        }
        _cullState.value = _cullState.value.copy(checked = flagged.toSet())
    }

    fun clearCullChecked() {
        _cullState.value = _cullState.value.copy(checked = emptySet())
    }

    // ---------- batch ----------

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
            deleteProjectRow(project.id)
            runCatching { ProjectStore.deleteOwnedFile(getApplication(), project.photoUri) }
        }
    }

    /**
     * Batch safe-delete: same row-first path per project, album membership
     * pruned alongside. No undo for batches — the confirm dialog is the
     * safety gate; single deletes keep their Undo snackbar.
     */
    fun deleteMany(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            var deleted = 0
            for (id in ids) {
                val project = database.projectDao().getById(id)
                deleteProjectRow(id)
                if (project != null) {
                    runCatching { ProjectStore.deleteOwnedFile(app, project.photoUri) }
                    deleted++
                }
            }
            _selectedIds.value = _selectedIds.value - ids.toSet()
            _notice.value = if (deleted == 0) "Nothing deleted" else "Deleted $deleted photo(s)"
        }
    }

    private suspend fun deleteProjectRow(projectId: String) {
        val app = getApplication<Application>()
        database.projectDao().deleteById(projectId)
        runCatching { database.editHistoryDao().clearForProject(projectId) }
        runCatching { ProjectStore.deleteProjectFiles(app, projectId) }
        runCatching { albumRepository.removeProjectFromAll(projectId) }
        CullEngine.invalidate(projectId)
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
