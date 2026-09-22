package com.lumina.studio.core.data.store

import android.content.Context
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.local.ProjectDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

object StorageMigration {

    suspend fun runIfNeeded(context: Context) {
        val app = context.applicationContext
        val repository = SettingsRepository(app)
        try {
            if (repository.storageMigrated.first()) return
        } catch (_: Exception) {
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val database = DatabaseProvider.get(app)
                val dao = database.projectDao()
                val projects = dao.getAll()
                val cachePath = app.cacheDir.absolutePath
                var complete = true
                for (project in projects) {
                    val migrated = runCatching {
                        migrateProject(app, dao, project, cachePath)
                    }.getOrDefault(false)
                    if (!migrated) {
                        val uri = project.photoUri
                        // Projects that are already on persistent storage need
                        // no migration. A missing/failed cache source must keep
                        // the migration pending so a transient I/O failure can
                        // be retried on the next app start.
                        val alreadyPersistent = !ProjectStoreLayout.isCachePath(cachePath, uri)
                        if (!alreadyPersistent && !uri.isNullOrBlank()) complete = false
                    }
                }
                if (complete) runCatching { repository.setStorageMigrated(true) }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun migrateProject(
        app: Context,
        dao: ProjectDao,
        project: Project,
        cachePath: String
    ): Boolean {
        val uri = project.photoUri ?: return true
        if (!ProjectStoreLayout.isCachePath(cachePath, uri)) return true
        val src = File(uri)
        if (!src.isFile) return false
        val dest = ProjectStore.copyFileIntoOriginal(app, project.id, src, project.name) ?: return false
        dao.upsert(project.copy(photoUri = dest.absolutePath, updatedAt = System.currentTimeMillis()))
        if (!src.delete() && src.exists()) return false
        return true
    }
}
