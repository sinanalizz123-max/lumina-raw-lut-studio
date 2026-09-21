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
                for (project in projects) {
                    runCatching { migrateProject(app, dao, project, cachePath) }
                }
            }
            runCatching { repository.setStorageMigrated(true) }
        } catch (_: Exception) {
        }
    }

    private suspend fun migrateProject(
        app: Context,
        dao: ProjectDao,
        project: Project,
        cachePath: String
    ) {
        val uri = project.photoUri ?: return
        if (!ProjectStoreLayout.isCachePath(cachePath, uri)) return
        val src = File(uri)
        if (!src.isFile) return
        val dest = ProjectStore.copyFileIntoOriginal(app, project.id, src, project.name) ?: return
        dao.upsert(project.copy(photoUri = dest.absolutePath, updatedAt = System.currentTimeMillis()))
        runCatching { src.delete() }
    }
}
