package com.lumina.studio.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.EditHistoryLog
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.util.ExifInfo
import com.lumina.studio.core.util.ExifReader
import com.lumina.studio.core.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class ImportUiState(
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val rawDetected: Boolean = false,
    val lastExif: ExifInfo? = null,
    val lastProjectId: String? = null
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(isImporting = false, errorMessage = message)
    }

    fun clearNavigation() {
        _uiState.value = _uiState.value.copy(lastProjectId = null, rawDetected = false, lastExif = null)
    }

    fun importUri(uri: Uri) {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, errorMessage = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { importUriBlocking(uri) }.getOrElse { e -> ImportResult.Error(e.message ?: "Import failed") }
            }
            when (result) {
                is ImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        rawDetected = result.isRaw,
                        lastExif = result.exif,
                        lastProjectId = result.projectId
                    )
                }
                is ImportResult.Error -> {
                    _uiState.value = _uiState.value.copy(isImporting = false, errorMessage = result.message)
                }
            }
        }
    }

    fun importBitmap(bitmap: Bitmap, baseName: String = "camera_capture") {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, errorMessage = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val projectId = UUID.randomUUID().toString()
                    val file = ProjectStore.saveBitmapToOriginal(app, projectId, bitmap, baseName)
                        ?: throw IllegalStateException("Could not save camera photo")
                    val bounds = ImageFiles.decodeBounds(file)
                    val now = System.currentTimeMillis()
                    val project = Project(
                        id = projectId,
                        name = "$baseName.jpg",
                        photoUri = file.absolutePath,
                        createdAt = now,
                        updatedAt = now,
                        fileType = "JPEG",
                        mimeType = "image/jpeg",
                        width = bounds.width,
                        height = bounds.height
                    )
                    database.projectDao().upsert(project)
                    EditHistoryLog.log(database, project.id, EditHistoryLog.IMPORT)
                    ImportResult.Success(project.id, false, ExifInfo())
                }.getOrElse { e -> ImportResult.Error(e.message ?: "Import failed") }
            }
            when (result) {
                is ImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        rawDetected = false,
                        lastExif = result.exif,
                        lastProjectId = result.projectId
                    )
                }
                is ImportResult.Error -> {
                    _uiState.value = _uiState.value.copy(isImporting = false, errorMessage = result.message)
                }
            }
        }
    }

    private sealed interface ImportResult {
        data class Success(val projectId: String, val isRaw: Boolean, val exif: ExifInfo) : ImportResult
        data class Error(val message: String) : ImportResult
    }

    private suspend fun importUriBlocking(uri: Uri): ImportResult {
        val context = getApplication<Application>()
        val displayName = ImageFiles.displayNameOf(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
        val mime = ImageFiles.mimeOf(context, uri)
        var extension = ImageFiles.extensionOf(displayName)
        if (extension.isEmpty() && mime != null) {
            extension = when (mime.lowercase(Locale.US)) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/tiff" -> "tiff"
                "image/x-tiff" -> "tiff"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                "image/avif" -> "avif"
                "image/bmp" -> "bmp"
                "image/gif" -> "gif"
                "image/x-adobe-dng" -> "dng"
                "image/x-canon-cr2" -> "cr2"
                else -> ""
            }
        }
        if (!ImageFiles.isSupported(extension, mime)) {
            val label = extension.ifEmpty { mime ?: "unknown" }
            return ImportResult.Error("Unsupported format: $label. Supported: JPG, PNG, WebP, TIFF, HEIC, AVIF, BMP, GIF, DNG and RAW.")
        }
        val projectId = UUID.randomUUID().toString()
        val cached: File = ProjectStore.copyUriToOriginal(context, projectId, uri, displayName)
            ?: return ImportResult.Error("Could not read that file.")
        val bounds = ImageFiles.decodeBounds(cached)
        val exif = ExifReader.read(cached)
        val isRaw = ImageFiles.isRaw(extension)
        val now = System.currentTimeMillis()
        val project = Project(
            id = projectId,
            name = displayName.ifBlank { cached.name },
            photoUri = cached.absolutePath,
            createdAt = now,
            updatedAt = now,
            fileType = ImageFiles.typeBadge(extension, mime),
            mimeType = mime,
            width = bounds.width,
            height = bounds.height
        )
        database.projectDao().upsert(project)
        EditHistoryLog.log(database, project.id, EditHistoryLog.IMPORT)
        return ImportResult.Success(project.id, isRaw, exif)
    }
}
