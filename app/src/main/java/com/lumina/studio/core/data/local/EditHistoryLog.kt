package com.lumina.studio.core.data.local

import java.util.UUID

object EditHistoryLog {
    const val IMPORT = "import"
    const val PRESET = "preset"
    const val EXPORT = "export"
    const val EDIT = "edit"
    const val ADJUST = "adjust"
    const val COLOR = "color"
    const val CURVES = "curves"
    const val DETAILS = "details"
    const val MASKS = "masks"
    const val CROP = "crop"
    const val STEP = "step"

    suspend fun log(database: LuminaDatabase, projectId: String?, tool: String) {
        if (projectId.isNullOrBlank()) return
        runCatching {
            database.editHistoryDao().insert(
                EditHistory(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    toolName = tool,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
