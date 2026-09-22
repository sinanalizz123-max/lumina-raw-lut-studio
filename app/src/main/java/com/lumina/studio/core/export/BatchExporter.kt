package com.lumina.studio.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.EditHistoryLog
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.lut.LutRehydrator
import com.lumina.studio.core.render.RawRecipe
import com.lumina.studio.core.render.RenderSource
import com.lumina.studio.core.render.cpu.RenderBackends
import com.lumina.studio.core.util.ImageOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object BatchExporter {
    suspend fun batchSettings(context: Context): ExportSettings = withContext(Dispatchers.IO) {
        return@withContext try {
            val repo = SettingsRepository(context)
            val stored = repo.exportColorSpace.first()
            val coerced = ExportColorSpace.coerceForDisplay(
                ExportColorSpace.fromKey(stored),
                Exporter.isWideGamutDisplay(context)
            )
            val includeLocation = runCatching { repo.exportIncludeLocation.first() }.getOrDefault(false)
            val preserveExif = runCatching { repo.exportIncludeMetadata.first() }.getOrDefault(true)
            ExportSettings(colorSpace = coerced, includeLocation = includeLocation, preserveExif = preserveExif)
        } catch (_: Exception) {
            ExportSettings()
        }
    }

    suspend fun ensureLutsLoaded(context: Context) = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val db = DatabaseProvider.get(app)
            val presets = db.presetDao().observePresets().first()
            LutRehydrator.rehydrate(presets, ProjectStore.lutsDir(app))
        } catch (_: Exception) {
        }
    }

    suspend fun exportProject(
        context: Context,
        projectId: String,
        settings: ExportSettings
    ): Uri = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        val db = DatabaseProvider.get(app)
        val project = withContext(Dispatchers.IO) { db.projectDao().getById(projectId) }
            ?: throw IllegalStateException("Project not found")
        val source = project.photoUri?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Photo is missing for “${project.name}”")
        val params = project.toEditParams()
        var full: Bitmap? = null
        try {
            full = decodeFull(app, source)
                ?: throw IllegalStateException("Could not decode “${project.name}”")
            val baseW = project.width.takeIf { it > 0 } ?: full.width
            val baseH = project.height.takeIf { it > 0 } ?: full.height
            val (targetW, targetH) = Exporter.targetDimensions(baseW, baseH, settings)
            val lut = com.lumina.studio.core.lut.LutRegistry.resolve(params.presetId)
            val rendered = Exporter.renderForExport(full, params, lut, targetW, targetH)
            val spaced = Exporter.withColorSpace(rendered, settings.colorSpace)
            if (spaced !== rendered && rendered !== full) {
                runCatching { rendered.recycle() }
            }
            val bytes = if (settings.format == ExportFormat.TIFF) {
                Exporter.encodeTiff(spaced)
            } else {
                Exporter.compress(spaced, settings)
            }
            if (spaced !== full) runCatching { spaced.recycle() }
            val withExif = Exporter.withSourceExif(app, bytes, settings, source)
            val name = Exporter.displayName(settings.format)
            val uri = Exporter.saveToGallery(app, withExif, settings.format, name)
            runCatching {
                EditHistoryLog.log(db, projectId, EditHistoryLog.EXPORT)
            }
            uri
        } finally {
            runCatching { full?.recycle() }
        }
    }

    private fun decodeFull(appContext: Context, pathOrUri: String): Bitmap? {
        return try {
            if (isDngPath(pathOrUri)) {
                val developed = runCatching {
                    RenderBackends.raw(appContext).develop(
                        RenderSource.of(pathOrUri), 0, RawRecipe()
                    )
                }.getOrNull()
                if (developed != null) return developed
            }
            val file = File(pathOrUri)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                ImageOrientation.normalizeBitmap(decoded, ImageOrientation.orientationOf(file))
            } else {
                appContext.contentResolver.openInputStream(pathOrUri.toUri())?.use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDngPath(pathOrUri: String): Boolean {
        return try {
            pathOrUri.substringAfterLast('.', "").substringBefore('?')
                .lowercase(Locale.US) == "dng"
        } catch (_: Exception) {
            false
        }
    }
}
