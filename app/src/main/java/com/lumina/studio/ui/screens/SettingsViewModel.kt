package com.lumina.studio.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.cache.CacheFileManager
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.data.store.ProjectStoreLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val cacheManager = CacheFileManager(application)

    val theme = repository.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")
    val gpuAcceleration = repository.gpuAcceleration.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val rawQuality = repository.rawQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "High")
    val previewQuality = repository.previewQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "High")
    val exportFormat = repository.exportFormat.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "JPEG")
    val exportQuality = repository.exportQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 90)
    val exportResolution = repository.exportResolution.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Original")
    val exportColorSpace = repository.exportColorSpace.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "sRGB")
    val exportIncludeMetadata = repository.exportIncludeMetadata.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val presetLibraryPath = repository.presetLibraryPath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val hapticEnabled = repository.hapticEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val animationsEnabled = repository.animationsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _cacheSize = MutableStateFlow(cacheManager.cacheSizeDisplay())
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    private val _originalsSize = MutableStateFlow("…")
    val originalsSize: StateFlow<String> = _originalsSize.asStateFlow()

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage: StateFlow<String?> = _cacheMessage.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = withContext(Dispatchers.IO) { cacheManager.cacheSizeDisplay() }
            _originalsSize.value = withContext(Dispatchers.IO) {
                ProjectStoreLayout.formatBytes(ProjectStore.projectsSize(getApplication()))
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cacheManager.clearCache() }
            _cacheSize.value = withContext(Dispatchers.IO) { cacheManager.cacheSizeDisplay() }
            _cacheMessage.value =
                if (ok) "Cache cleared — originals are never deleted"
                else "Could not clear everything"
        }
    }

    fun consumeCacheMessage() {
        _cacheMessage.value = null
    }

    fun setTheme(value: String) = launch { repository.setTheme(value) }
    fun setGpuAcceleration(value: Boolean) = launch { repository.setGpuAcceleration(value) }
    fun setRawQuality(value: String) = launch { repository.setRawQuality(value) }
    fun setPreviewQuality(value: String) = launch { repository.setPreviewQuality(value) }
    fun setExportFormat(value: String) = launch { repository.setExportFormat(value) }
    fun setExportQuality(value: Int) = launch { repository.setExportQuality(value) }
    fun setExportResolution(value: String) = launch { repository.setExportResolution(value) }
    fun setExportColorSpace(value: String) = launch { repository.setExportColorSpace(value) }
    fun setExportIncludeMetadata(value: Boolean) = launch { repository.setExportIncludeMetadata(value) }
    fun setPresetLibraryPath(value: String) = launch { repository.setPresetLibraryPath(value) }
    fun setHapticEnabled(value: Boolean) = launch { repository.setHapticEnabled(value) }
    fun setAnimationsEnabled(value: Boolean) = launch { repository.setAnimationsEnabled(value) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
