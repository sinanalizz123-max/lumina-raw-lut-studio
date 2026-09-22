package com.lumina.studio.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.edit.withEditParams
import com.lumina.studio.core.export.Sidecar
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.CubeParseResult
import com.lumina.studio.core.lut.CubeParser
import com.lumina.studio.core.lut.LutLimits
import com.lumina.studio.core.lut.LutRehydrator
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.PresetCategory
import com.lumina.studio.core.presets.PresetShare
import com.lumina.studio.core.presets.SettingGroup
import com.lumina.studio.core.presets.SettingGroups
import com.lumina.studio.ui.presets.decodeSampledFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        viewModelScope.launch(Dispatchers.IO) {
            seedIfEmpty()
            rehydrateLuts()
        }
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
                    val id = java.util.UUID.randomUUID().toString()
                    val stored = withContext(Dispatchers.IO) { storeCubeText(id, text) }
                    if (stored == null) {
                        _importError.value = "Could not save the imported LUT file"
                        return@launch
                    }
                    val preset = Preset(
                        id = id,
                        name = result.lut.title?.takeIf { it.isNotBlank() } ?: baseName,
                        category = PresetCategory.FILM.label,
                        isFavorite = false,
                        defaultIntensity = 1f,
                        cubeText = stored,
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

    fun readCubeText(uri: Uri, maxChars: Int = LutLimits.MAX_FILE_BYTES): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val cap = maxChars.coerceIn(1024, LutLimits.MAX_FILE_BYTES)
            try {
                resolver.openFileDescriptor(uri, "r")?.use { fd ->
                    if (fd.statSize > cap) {
                        Log.w("PresetsViewModel", "cube import rejected: statSize over cap")
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.w("PresetsViewModel", "cube statSize unreadable: ${e.message}")
            }
            resolver.openInputStream(uri)?.use { input ->
                LutLimits.readBounded(input, cap)?.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w("PresetsViewModel", "cube read failed: ${e.message}")
            null
        }
    }

    fun createPresetFromCurrent(name: String, category: PresetCategory, params: EditParams) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "Give the preset a name first"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val id = java.util.UUID.randomUUID().toString()
            val lutsDir = ProjectStore.lutsDir(getApplication())
            PresetShare.writeRecipeParams(lutsDir, id, params)
            val preset = Preset(
                id = id,
                name = trimmed,
                category = category.label,
                isFavorite = false,
                defaultIntensity = params.presetIntensity.coerceIn(0f, 1f),
                cubeText = null,
                createdAt = System.currentTimeMillis()
            )
            try {
                database.presetDao().upsert(preset)
                _message.value = "Saved preset “$trimmed”"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not save preset"
            }
        }
    }

    fun renamePreset(preset: Preset, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == preset.name) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.presetDao().upsert(preset.copy(name = trimmed))
                _message.value = "Renamed to “$trimmed”"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not rename preset"
            }
        }
    }

    fun duplicatePreset(preset: Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val lutsDir = ProjectStore.lutsDir(app)
                val newId = java.util.UUID.randomUUID().toString()
                var stored = preset.cubeText
                val fileName = LutLimits.fileNameFromRef(preset.cubeText)
                if (fileName != null) {
                    val src = File(lutsDir, fileName)
                    val dstName = LutLimits.fileNameFor(newId)
                    val bytes = LutLimits.readBoundedFile(src)?.toByteArray(Charsets.UTF_8)
                    if (bytes != null && LutLimits.writeAtomically(lutsDir, dstName, bytes) != null) {
                        stored = LutLimits.fileRefFor(dstName)
                    } else {
                        _message.value = "Could not duplicate the LUT file"
                        return@launch
                    }
                }
                database.presetDao().upsert(
                    preset.copy(
                        id = newId,
                        name = preset.name + " (copy)",
                        isFavorite = false,
                        cubeText = stored,
                        createdAt = System.currentTimeMillis()
                    )
                )
                val recipe = PresetShare.readRecipeParams(lutsDir, preset.id)
                if (recipe != null) PresetShare.writeRecipeParams(lutsDir, newId, recipe)
                else if (stored == null) {
                    PresetShare.writeRecipeParams(
                        lutsDir, newId,
                        EditParams.DEFAULT.copy(
                            presetId = preset.id,
                            presetIntensity = preset.defaultIntensity.coerceIn(0f, 1f)
                        )
                    )
                }
                LutRegistry.resolve(newId) ?: rehydrateSingle(newId, stored)
                _message.value = "Duplicated “${preset.name}”"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not duplicate preset"
            }
        }
    }

    fun deletePreset(preset: Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.presetDao().deleteById(preset.id)
                LutRegistry.unregister(preset.id)
                val lutsDir = ProjectStore.lutsDir(getApplication())
                LutLimits.fileNameFromRef(preset.cubeText)?.let { File(lutsDir, it).delete() }
                PresetShare.deleteRecipeParams(lutsDir, preset.id)
                _message.value = "Deleted “${preset.name}”"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not delete preset"
            }
        }
    }

    data class ShareExport(val json: String, val fileName: String, val warning: String?)

    fun buildShareFor(preset: Preset, onResult: (ShareExport?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lutsDir = ProjectStore.lutsDir(getApplication())
                val recipe = PresetShare.readRecipeParams(lutsDir, preset.id)
                    ?: EditParams.DEFAULT.copy(
                        presetId = preset.id,
                        presetIntensity = preset.defaultIntensity.coerceIn(0f, 1f)
                    )
                var lutInline: String? = null
                var warning: String? = null
                val cubeText = preset.cubeText
                if (!cubeText.isNullOrEmpty() && !LutLimits.isFileRef(cubeText)) {
                    lutInline = cubeText
                } else {
                    LutLimits.fileNameFromRef(cubeText)?.let { fileName ->
                        val text = LutLimits.readBoundedFile(File(lutsDir, fileName))
                        if (text != null && text.toByteArray(Charsets.UTF_8).size <= LutLimits.SHARE_EMBED_LUT_BYTES) {
                            lutInline = text
                        } else {
                            warning = "LUT file too large to embed; sharing settings only."
                        }
                    }
                }
                val json = PresetShare.buildShareJson(
                    preset.name, preset.category, preset.defaultIntensity,
                    SettingGroups.PRESET_GROUPS, recipe, lutInline
                )
                withContext(Dispatchers.Main) {
                    onResult(ShareExport(json, PresetShare.shareFileName(preset.name), warning))
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun importShareJson(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val shared = PresetShare.parseShareJson(text)
            if (shared == null) {
                _importError.value = "That file is not a Lumina preset"
                return@launch
            }
            val id = java.util.UUID.randomUUID().toString()
            var stored: String? = null
            if (!shared.lutInline.isNullOrBlank()) {
                if (CubeParser.parse(shared.lutInline) is CubeParseResult.Err) {
                    _importError.value = "The embedded LUT in that preset is invalid"
                    return@launch
                }
                stored = withContext(Dispatchers.IO) { storeCubeText(id, shared.lutInline) }
                if (stored == null) {
                    _importError.value = "Could not save the preset LUT file"
                    return@launch
                }
            }
            val preset = Preset(
                id = id,
                name = shared.name,
                category = shared.category,
                isFavorite = false,
                defaultIntensity = shared.intensity,
                cubeText = stored,
                createdAt = System.currentTimeMillis()
            )
            try {
                withContext(Dispatchers.IO) {
                    database.presetDao().upsert(preset)
                    PresetShare.writeRecipeParams(
                        ProjectStore.lutsDir(getApplication()),
                        id,
                        shared.recipe.copy(
                            presetId = shared.recipe.presetId ?: stored?.let { id },
                            presetIntensity = shared.intensity
                        )
                    )
                }
                if (stored != null) rehydrateSingle(id, stored)
                _importError.value = null
                _message.value = "Imported preset “${shared.name}”"
            } catch (e: Exception) {
                _importError.value = e.message ?: "Could not import preset"
            }
        }
    }

    suspend fun resolveRecipeParams(preset: Preset): EditParams = withContext(Dispatchers.IO) {
        val recipe = PresetShare.readRecipeParams(ProjectStore.lutsDir(getApplication()), preset.id)
        if (recipe != null) return@withContext recipe
        EditParams.DEFAULT.copy(
            presetId = preset.id,
            presetIntensity = preset.defaultIntensity.coerceIn(0f, 1f)
        )
    }

    fun applyToProject(projectId: String, preset: Preset) {
        applyWithGroups(projectId, preset, setOf(SettingGroup.LUT))
    }

    fun applyWithGroups(projectId: String, preset: Preset, groups: Set<SettingGroup>) {
        val effective = groups.ifEmpty { SettingGroups.PRESET_GROUPS }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = database.projectDao().getById(projectId) ?: return@launch
                val base = project.toEditParams()
                val fileRecipe = PresetShare.readRecipeParams(
                    ProjectStore.lutsDir(getApplication()), preset.id
                )
                val recipe = fileRecipe ?: EditParams.DEFAULT.copy(
                    presetId = preset.id,
                    presetIntensity = preset.defaultIntensity.coerceIn(0f, 1f)
                )
                var merged = SettingGroups.applyCopy(recipe, base, effective)
                if (fileRecipe == null) merged = merged.copy(steps = base.steps)
                var suffix = ""
                if (SettingGroup.LUT in effective && recipe.presetId != null &&
                    LutRegistry.resolve(recipe.presetId) == null
                ) {
                    merged = merged.copy(presetId = null, presetIntensity = 1f)
                    suffix = " (" + Sidecar.missingLutWarning(recipe.presetId) + ")"
                }
                val intensity = BuiltInPresets.byId(preset.id)?.defaultIntensity
                    ?: preset.defaultIntensity.coerceIn(0f, 1f)
                if (recipe.presetId == preset.id && SettingGroup.LUT in effective && suffix.isEmpty()) {
                    merged = merged.copy(presetIntensity = intensity)
                }
                val updated = project.withEditParams(merged).copy(updatedAt = System.currentTimeMillis())
                database.projectDao().upsert(updated)
                _message.value = "Applied “${preset.name}”$suffix"
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

    private fun storeCubeText(presetId: String, text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > LutLimits.MAX_FILE_BYTES) return null
        if (LutLimits.shouldInline(bytes.size)) return text
        val lutsDir = ProjectStore.lutsDir(getApplication())
        val fileName = LutLimits.fileNameFor(presetId)
        val written = LutLimits.writeAtomically(lutsDir, fileName, bytes) ?: return null
        if (!written.isFile) return null
        return LutLimits.fileRefFor(fileName)
    }

    private fun rehydrateSingle(presetId: String, stored: String?) {
        if (stored.isNullOrBlank() || !LutLimits.isFileRef(stored)) {
            LutRegistry.registerParsed(presetId, stored)
            return
        }
        val fileName = LutLimits.fileNameFromRef(stored) ?: return
        val text = LutLimits.readBoundedFile(
            File(ProjectStore.lutsDir(getApplication()), fileName)
        ) ?: return
        when (val result = CubeParser.parse(text)) {
            is CubeParseResult.Ok -> LutRegistry.register(presetId, result.lut)
            is CubeParseResult.Err -> Unit
        }
    }

    private suspend fun rehydrateLuts() {
        try {
            val presets = database.presetDao().observePresets().first()
            LutRehydrator.rehydrate(presets, ProjectStore.lutsDir(getApplication()))
        } catch (_: Exception) {
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
