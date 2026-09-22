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
import kotlinx.coroutines.ensureActive
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
        var rendered: Bitmap? = null
        var spaced: Bitmap? = null
        var sharpened: Bitmap? = null
        try {
            ensureActive()
            full = decodeFull(app, source)
                ?: throw IllegalStateException("Could not decode “${project.name}”")
            ensureActive()
            val baseW = project.width.takeIf { it > 0 } ?: full.width
            val baseH = project.height.takeIf { it > 0 } ?: full.height
            val (targetW, targetH) = Exporter.targetDimensions(baseW, baseH, settings)
            val lut = com.lumina.studio.core.lut.LutRegistry.resolve(params.presetId)
            rendered = Exporter.renderForExport(full, params, lut, targetW, targetH)
            ensureActive()
            // M11: explicit upscale (renderer never upscales), per-format
            // colorspace (TIFF coerces to sRGB), output sharpen post-resize.
            val scaled = Exporter.upscaleIfAllowed(rendered, targetW, targetH, settings.allowUpscale)
            if (scaled !== rendered && rendered !== full) runCatching { rendered.recycle() }
            rendered = scaled
            val effectiveSpace = Exporter.colorSpaceForFormat(settings.format, settings.colorSpace)
            spaced = Exporter.withColorSpace(rendered, effectiveSpace)
            if (spaced !== rendered && rendered !== full) {
                runCatching { rendered.recycle() }
                rendered = null
            }
            sharpened = Exporter.applyOutputSharpen(spaced, settings.outputSharpen)
            if (sharpened !== spaced && spaced !== full) {
                runCatching { spaced.recycle() }
                spaced = null
            }
            ensureActive()
            val finalFrame = sharpened
            val bytes = if (settings.format == ExportFormat.TIFF) {
                Exporter.encodeTiff(finalFrame)
            } else if (settings.format == ExportFormat.HEIC) {
                Exporter.encodeHeic(finalFrame, settings.effectiveQuality(), app.cacheDir)
            } else {
                Exporter.compress(finalFrame, settings)
            }
            ensureActive()
            val withExif = Exporter.withSourceExif(app, bytes, settings, source)
            val name = Exporter.displayName(settings.format)
            // M11: validated publish transaction (temp -> validate ->
            // metadata -> MediaStore pending -> finalize). HEIC validates
            // the even-padded frame the decoder reopens.
            val (expW, expH) = if (settings.format == ExportFormat.HEIC) {
                Exporter.evenDims(finalFrame.width, finalFrame.height)
            } else {
                finalFrame.width to finalFrame.height
            }
            val uri = Exporter.publishBytes(
                app, withExif, settings.format, name, expW, expH
            )
            runCatching {
                EditHistoryLog.log(db, projectId, EditHistoryLog.EXPORT)
            }
            uri
        } finally {
            runCatching { sharpened?.takeIf { it !== full }?.recycle() }
            runCatching { spaced?.takeIf { it !== full }?.recycle() }
            runCatching { rendered?.takeIf { it !== full }?.recycle() }
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
        } catch (e: Exception) {
            // M11 (§39): never swallow cancellation on export paths.
            if (e is kotlinx.coroutines.CancellationException) throw e
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
