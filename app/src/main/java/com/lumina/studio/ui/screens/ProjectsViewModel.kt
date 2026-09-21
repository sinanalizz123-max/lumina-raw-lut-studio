package com.lumina.studio.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class ProjectSort { DATE, NAME, TYPE }

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

    fun toggleFavorite(project: Project) {
        viewModelScope.launch {
            database.projectDao().upsert(
                project.copy(isFavorite = !project.isFavorite, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun delete(project: Project) {
        lastDeleted = project
        viewModelScope.launch {
            database.projectDao().deleteById(project.id)
        }
    }

    fun undoDelete(): Project? {
        val deleted = lastDeleted ?: return null
        lastDeleted = null
        viewModelScope.launch {
            database.projectDao().upsert(deleted.copy(updatedAt = System.currentTimeMillis()))
        }
        return deleted
    }

    fun duplicate(project: Project) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val copy = project.copy(
                id = UUID.randomUUID().toString(),
                name = project.name + " (copy)",
                createdAt = now,
                updatedAt = now
            )
            database.projectDao().upsert(copy)
        }
    }
}
