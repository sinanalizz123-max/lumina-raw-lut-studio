package com.lumina.studio.core.library

import com.lumina.studio.core.data.local.Project

/**
 * M13 library filters (pure JVM, no android.*).
 *
 * The ProjectsViewModel combines every field below into a single
 * ProjectsUiState, so search + sort + favorites + the new type / preset /
 * recency / album filters always compose instead of fighting. All
 * predicates are pure functions of [Project] so JVM tests pin them.
 */
enum class TypeFilter { ALL, RAW, DNG, EDITED, OTHER }

enum class PresetFilter { ALL, WITH_PRESET, WITHOUT_PRESET }

enum class RecencyFilter { ALL, RECENT_IMPORT, RECENT_EDIT }

/** Window for the "recently imported / edited" filters (§43). */
const val RECENT_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L

data class LibraryFilterState(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val type: TypeFilter = TypeFilter.ALL,
    val preset: PresetFilter = PresetFilter.ALL,
    val recency: RecencyFilter = RecencyFilter.ALL,
    val albumId: String? = null
)

object LibraryFilter {
    fun hasPreset(project: Project): Boolean = !project.presetName.isNullOrBlank()

    fun isRaw(project: Project): Boolean = project.fileType == "RAW"

    fun isDng(project: Project): Boolean = project.fileType == "DNG"

    fun isEdited(project: Project): Boolean =
        hasPreset(project) || !project.editParamsJson.isNullOrBlank()

    fun matchesType(project: Project, type: TypeFilter): Boolean = when (type) {
        TypeFilter.ALL -> true
        TypeFilter.RAW -> isRaw(project)
        TypeFilter.DNG -> isDng(project)
        TypeFilter.EDITED -> isEdited(project)
        TypeFilter.OTHER -> !isRaw(project) && !isDng(project) && !isEdited(project)
    }

    fun matchesPreset(project: Project, preset: PresetFilter): Boolean = when (preset) {
        PresetFilter.ALL -> true
        PresetFilter.WITH_PRESET -> hasPreset(project)
        PresetFilter.WITHOUT_PRESET -> !hasPreset(project)
    }

    fun matchesRecency(project: Project, recency: RecencyFilter, nowMs: Long): Boolean =
        when (recency) {
            RecencyFilter.ALL -> true
            RecencyFilter.RECENT_IMPORT -> nowMs - project.createdAt <= RECENT_WINDOW_MS
            RecencyFilter.RECENT_EDIT -> nowMs - project.updatedAt <= RECENT_WINDOW_MS
        }

    fun matchesQuery(project: Project, query: String): Boolean {
        if (query.isBlank()) return true
        return project.name.contains(query.trim(), ignoreCase = true)
    }

    fun matches(
        project: Project,
        state: LibraryFilterState,
        nowMs: Long,
        isAlbumMember: (String) -> Boolean = { false }
    ): Boolean {
        if (state.favoritesOnly && !project.isFavorite) return false
        if (!matchesType(project, state.type)) return false
        if (!matchesPreset(project, state.preset)) return false
        if (!matchesRecency(project, state.recency, nowMs)) return false
        if (!matchesQuery(project, state.query)) return false
        val albumId = state.albumId
        if (albumId != null && !isAlbumMember(project.id)) return false
        return true
    }

    fun apply(
        projects: List<Project>,
        state: LibraryFilterState,
        nowMs: Long,
        isAlbumMember: (String) -> Boolean = { false }
    ): List<Project> = projects.filter { matches(it, state, nowMs, isAlbumMember) }
}
