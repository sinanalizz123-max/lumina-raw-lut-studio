package com.lumina.studio.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.edit.withEditParams
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.CubeParseResult
import com.lumina.studio.core.lut.CubeParser
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.PresetCategory
import com.lumina.studio.ui.presets.decodeSampledFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class PresetsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    val presets: StateFlow<List<Preset>> =
        database.presetDao().observePresets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentProject: StateFlow<Project?> =
        database.projectDao().observeRecent(1)
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<PresetCategory?>(null)
    val category: StateFlow<PresetCategory?> = _category.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _thumbSource = MutableStateFlow<Bitmap?>(null)
    val thumbSource: StateFlow<Bitmap?> = _thumbSource.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { seedIfEmpty() }
        viewModelScope.launch {
            currentProject.collect { project ->
                _thumbSource.value = withContext(Dispatchers.IO) { decodeThumbSource(project) }
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCategory(value: PresetCategory?) {
        _category.value = value
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun filtered(presets: List<Preset>, query: String, category: PresetCategory?): List<Preset> {
        val q = query.trim().lowercase(Locale.getDefault())
        return presets.filter { preset ->
            (category == null || preset.category.equals(category.label, ignoreCase = true)) &&
                (q.isEmpty() || preset.name.lowercase(Locale.getDefault()).contains(q))
        }
    }

    fun toggleFavorite(preset: Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.presetDao().upsert(preset.copy(isFavorite = !preset.isFavorite))
            } catch (_: Exception) {
            }
        }
    }

    fun importCube(displayName: String?, text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val baseName = displayName?.substringAfterLast('/')?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() } ?: "Imported LUT"
            when (val result = CubeParser.parse(text, baseName)) {
                is CubeParseResult.Err -> {
                    _importError.value = result.reason
                }
                is CubeParseResult.Ok -> {
                    _importError.value = null
                    val preset = Preset(
                        id = java.util.UUID.randomUUID().toString(),
                        name = result.lut.title?.takeIf { it.isNotBlank() } ?: baseName,
                        category = PresetCategory.FILM.label,
                        isFavorite = false,
                        defaultIntensity = 1f,
                        cubeText = text,
                        createdAt = System.currentTimeMillis()
                    )
                    try {
                        withContext(Dispatchers.IO) { database.presetDao().upsert(preset) }
                        LutRegistry.register(preset.id, result.lut)
                        _message.value = "Imported “${preset.name}”"
                    } catch (e: Exception) {
                        _importError.value = e.message ?: "Could not save the imported preset"
                    }
                }
            }
        }
    }

    fun readCubeText(uri: Uri, maxChars: Int = 8 * 1024 * 1024): String? {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > maxChars) return null
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun applyToProject(projectId: String, preset: Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = database.projectDao().getById(projectId) ?: return@launch
                val intensity = BuiltInPresets.byId(preset.id)?.defaultIntensity
                    ?: preset.defaultIntensity.coerceIn(0f, 1f)
                val updated = project.withEditParams(
                    project.toEditParams().copy(presetId = preset.id, presetIntensity = intensity)
                ).copy(updatedAt = System.currentTimeMillis())
                database.projectDao().upsert(updated)
                _message.value = "Applied “${preset.name}”"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not apply preset"
            }
        }
    }

    fun setProjectIntensity(projectId: String, value: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = database.projectDao().getById(projectId) ?: return@launch
                val params = project.toEditParams()
                if (params.presetId == null) return@launch
                val updated = project.withEditParams(
                    params.copy(presetIntensity = value.coerceIn(0f, 1f))
                ).copy(updatedAt = System.currentTimeMillis())
                database.projectDao().upsert(updated)
            } catch (_: Exception) {
            }
        }
    }

    fun clearProjectPreset(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = database.projectDao().getById(projectId) ?: return@launch
                val updated = project.withEditParams(
                    project.toEditParams().copy(presetId = null, presetIntensity = 1f)
                ).copy(updatedAt = System.currentTimeMillis())
                database.projectDao().upsert(updated)
                _message.value = "Preset removed"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not remove preset"
            }
        }
    }

    private suspend fun seedIfEmpty() {
        try {
            if (database.presetDao().count() > 0) return
            val now = System.currentTimeMillis()
            for (demo in BuiltInPresets.list) {
                database.presetDao().upsert(
                    Preset(
                        id = demo.id,
                        name = demo.name,
                        category = demo.category.label,
                        isFavorite = false,
                        defaultIntensity = demo.defaultIntensity,
                        cubeText = null,
                        createdAt = now
                    )
                )
                LutRegistry.register(demo.id, demo.lut)
            }
        } catch (_: Exception) {
        }
    }

    private fun decodeThumbSource(project: Project?): Bitmap? {
        val uri = project?.photoUri ?: return null
        return try {
            val file = File(uri)
            if (file.exists()) {
                decodeSampledFile(file.absolutePath, 512)
            } else {
                getApplication<Application>().contentResolver
                    .openInputStream(uri.toUri())?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }?.let { bmp ->
                        if (maxOf(bmp.width, bmp.height) > 512) {
                            val scale = 512 / maxOf(bmp.width, bmp.height).toFloat()
                            Bitmap.createScaledBitmap(
                                bmp,
                                (bmp.width * scale + 0.5f).toInt().coerceAtLeast(1),
                                (bmp.height * scale + 0.5f).toInt().coerceAtLeast(1),
                                true
                            )
                        } else {
                            bmp
                        }
                    }
            }
        } catch (_: Exception) {
            null
        }
    }
}
