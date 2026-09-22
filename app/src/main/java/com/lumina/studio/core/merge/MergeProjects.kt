package com.lumina.studio.core.merge

import android.content.Context
import android.graphics.Bitmap
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.EditHistoryLog
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.util.ImageFiles
import java.util.UUID

/**
 * M17 merged-project persistence (Android-only).
 *
 * Stores a merge result as a NEW project whose original IS the merged
 * pixels (JPEG q95 via [ProjectStore.saveBitmapToOriginal], same path as
 * camera captures in ImportViewModel) with the factory-default recipe and
 * a MERGE history entry. Validation failures never reach here — the
 * mergers return Unavailable/OomBudget first, so no corrupt project can
 * be written. Returns null (with no side effects) when the save fails.
 */
object MergeProjects {
    suspend fun storeResult(
        app: Context,
        bitmap: Bitmap,
        kind: MergeKind,
        now: Long = System.currentTimeMillis()
    ): Project? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
            val database = DatabaseProvider.get(app)
            val projectId = UUID.randomUUID().toString()
            val baseName = MergedRecipe.baseNameFor(kind)
            val file = ProjectStore.saveBitmapToOriginal(app, projectId, bitmap, baseName)
                ?: return null
            val bounds = ImageFiles.decodeBounds(file)
            val project = MergedRecipe.mergedProject(
                id = projectId,
                photoUri = file.absolutePath,
                width = bounds.width.takeIf { it > 0 } ?: bitmap.width,
                height = bounds.height.takeIf { it > 0 } ?: bitmap.height,
                kind = kind,
                now = now
            )
            database.projectDao().upsert(project)
            runCatching { EditHistoryLog.log(database, project.id, EditHistoryLog.MERGE) }
            project
        } catch (_: Exception) {
            null
        }
    }
}
