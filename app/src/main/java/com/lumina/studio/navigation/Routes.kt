package com.lumina.studio.navigation

object Routes {
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val PROJECT_DETAIL = "projectDetail/{projectId}"
    const val PRESETS = "presets"
    const val PACK_DETAIL = "packDetail/{packId}"
    const val PRESET_DETAIL = "presetDetail/{presetId}"
    const val SETTINGS = "settings"
    const val APPEARANCE = "appearance"
    const val STORAGE = "storage"
    const val ABOUT = "about"
    const val IMPORT = "import"
    const val EDITOR = "editor"
    const val EXPORT = "export"
    const val HISTORY = "history"

    fun projectDetail(projectId: String) = "projectDetail/$projectId"
    fun packDetail(packId: String) = "packDetail/$packId"
    fun presetDetail(presetId: String) = "presetDetail/$presetId"
    fun editor(projectId: String? = null) =
        if (projectId.isNullOrBlank()) EDITOR else "$EDITOR?projectId=$projectId"
}

val BottomTabs = listOf(
    Routes.HOME,
    Routes.PROJECTS,
    Routes.PRESETS,
    Routes.SETTINGS
)
