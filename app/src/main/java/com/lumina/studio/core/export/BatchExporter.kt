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
            val format = runCatching { mapStoredFormat(repo.exportFormat.first()) }.getOrDefault(ExportFormat.JPEG)
            val (preset, customQ) = runCatching { mapStoredQuality(repo.exportQuality.first()) }
                .getOrDefault(QualityPreset.HIGH to 90)
            val (mode, maxDim) = runCatching { mapStoredResolution(repo.exportResolution.first()) }
                .getOrDefault(ResolutionMode.ORIGINAL to 2048)
            ExportSettings(
                format = format,
                qualityPreset = preset,
                customQuality = customQ,
                resolutionMode = mode,
                customMaxDim = maxDim,
                colorSpace = coerced,
                includeLocation = includeLocation,
                preserveExif = preserveExif
            )
        } catch (_: Exception) {
            ExportSettings()
        }
    }

    private fun mapStoredFormat(stored: String): ExportFormat {
        return when (stored.trim().lowercase(Locale.US)) {
            "png" -> ExportFormat.PNG
            "webp" -> ExportFormat.WEBP
            "tiff", "tif" -> ExportFormat.TIFF
            else -> ExportFormat.JPEG
        }
    }

    private fun mapStoredQuality(stored: Int): Pair<QualityPreset, Int> {
        val q = stored.coerceIn(1, 100)
        return when (q) {
            100 -> QualityPreset.MAXIMUM to 100
            90 -> QualityPreset.HIGH to 90
            else -> QualityPreset.CUSTOM to q
        }
    }

    private fun mapStoredResolution(stored: String): Pair<ResolutionMode, Int> {
        return when (stored.trim().lowercase(Locale.US)) {
            "large" -> ResolutionMode.CUSTOM to 4096
            "medium", "2048px", "2048" -> ResolutionMode.CUSTOM to 2048
            "small" -> ResolutionMode.CUSTOM to 1024
            else -> ResolutionMode.ORIGINAL to 2048
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
            val baseW = project.width.takeIf { it > 0 }
            val baseH = project.height.takeIf { it > 0 }
            val requestedMaxDim = Exporter.requestedDecodeMaxDim(baseW, baseH, settings)
            full = decodeForExport(app, source, requestedMaxDim)
                ?: throw IllegalStateException("Could not decode “${project.name}”")
            ensureActive()
            val actualBaseW = baseW ?: full.width
            val actualBaseH = baseH ?: full.height
            val (targetW, targetH) = Exporter.targetDimensions(actualBaseW, actualBaseH, settings)
            val lut = com.lumina.studio.core.lut.LutRegistry.resolve(params.presetId)
            // M15 (§11): batch exports are export-final too — same GPU-first
            // opt-in as ExportScreen (toggle ON + GLES3), CPU otherwise.
            val gpuBackend = runCatching {
                val enabled = SettingsRepository(app).gpuAcceleration.first()
                if (enabled && com.lumina.studio.core.render.gpu.GpuSupport.isGles3(app)) {
                    RenderBackends.gpu()
                } else {
                    null
                }
            }.getOrNull()
            rendered = Exporter.renderForExport(full, params, lut, targetW, targetH, gpuBackend)
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
            private fun decodeForExport(
        appContext: Context,
        pathOrUri: String,
        maxDim: Int
    ): Bitmap? {
        return try {
            if (isDngPath(pathOrUri)) {
                val developed = runCatching {
                    RenderBackends.raw(appContext).develop(
                        RenderSource.of(pathOrUri),
                        maxDim.coerceAtLeast(0),
                        RawRecipe()
                    )
                }.getOrNull()
                if (developed != null) return developed
            }
            val file = File(pathOrUri)
            if (file.isFile) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                if (maxDim > 0) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    inSampleSize = com.lumina.studio.core.render.MemoryBudget.sampleFor(
                        maxOf(bounds.outWidth, bounds.outHeight), maxDim
                    )
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                ImageOrientation.normalizeBitmap(decoded, ImageOrientation.orientationOf(file))
            } else {
                val uri = pathOrUri.toUri()
                val resolver = appContext.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    if (maxDim > 0) {
                        inSampleSize = com.lumina.studio.core.render.MemoryBudget.sampleFor(
                            maxOf(bounds.outWidth, bounds.outHeight), maxDim
                        )
                    }
                }
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }tch (e: Exception) {
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
