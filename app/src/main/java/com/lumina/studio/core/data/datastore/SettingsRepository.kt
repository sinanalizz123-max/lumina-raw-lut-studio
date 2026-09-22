package com.lumina.studio.core.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumina.studio.core.ai.AiResearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "lumina_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val GPU_ACCELERATION = booleanPreferencesKey("gpu_acceleration")
        val RAW_QUALITY = stringPreferencesKey("raw_quality")
        val PREVIEW_QUALITY = stringPreferencesKey("preview_quality")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val EXPORT_QUALITY = intPreferencesKey("export_quality")
        val EXPORT_RESOLUTION = stringPreferencesKey("export_resolution")
        val EXPORT_COLOR_SPACE = stringPreferencesKey("export_color_space")
        val EXPORT_INCLUDE_METADATA = booleanPreferencesKey("export_include_metadata")
        val EXPORT_INCLUDE_LOCATION = booleanPreferencesKey("export_include_location")
        val PRESET_LIBRARY_PATH = stringPreferencesKey("preset_library_path")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
        val STORAGE_MIGRATED = booleanPreferencesKey("storage_migrated_v1")
        // M12: selection backend id. Only "heuristic" exists (see AiResearch);
        // the setter sanitizes unknown values back to it.
        val AI_BACKEND = stringPreferencesKey("ai_backend")
    }

    val theme: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.THEME] ?: "dark" }

    val onboardingDone: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_DONE] ?: false }

    val gpuAcceleration: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.GPU_ACCELERATION] ?: true }

    val rawQuality: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.RAW_QUALITY] ?: "High" }

    val previewQuality: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.PREVIEW_QUALITY] ?: "High" }

    val exportFormat: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_FORMAT] ?: "JPEG" }

    val exportQuality: Flow<Int> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_QUALITY] ?: 90 }

    val exportResolution: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_RESOLUTION] ?: "Original" }

    val exportColorSpace: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_COLOR_SPACE] ?: "sRGB" }

    val exportIncludeMetadata: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_INCLUDE_METADATA] ?: true }

    val exportIncludeLocation: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.EXPORT_INCLUDE_LOCATION] ?: false }

    val presetLibraryPath: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.PRESET_LIBRARY_PATH] ?: "" }

    val hapticEnabled: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.HAPTIC_ENABLED] ?: true }

    val animationsEnabled: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.ANIMATIONS_ENABLED] ?: true }

    val storageMigrated: Flow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[Keys.STORAGE_MIGRATED] ?: false }

    val aiBackend: Flow<String> = context.settingsStore.data
        .map { prefs -> prefs[Keys.AI_BACKEND] ?: AiResearch.SELECTED_BACKEND }

    suspend fun setTheme(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.THEME] = value }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setGpuAcceleration(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.GPU_ACCELERATION] = enabled }
    }

    suspend fun setRawQuality(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.RAW_QUALITY] = value }
    }

    suspend fun setPreviewQuality(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.PREVIEW_QUALITY] = value }
    }

    suspend fun setExportFormat(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_FORMAT] = value }
    }

    suspend fun setExportQuality(value: Int) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_QUALITY] = value }
    }

    suspend fun setExportResolution(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_RESOLUTION] = value }
    }

    suspend fun setExportColorSpace(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_COLOR_SPACE] = value }
    }

    suspend fun setExportIncludeMetadata(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_INCLUDE_METADATA] = enabled }
    }

    suspend fun setExportIncludeLocation(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.EXPORT_INCLUDE_LOCATION] = enabled }
    }

    suspend fun setPresetLibraryPath(value: String) {
        context.settingsStore.edit { prefs -> prefs[Keys.PRESET_LIBRARY_PATH] = value }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.HAPTIC_ENABLED] = enabled }
    }

    suspend fun setAnimationsEnabled(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.ANIMATIONS_ENABLED] = enabled }
    }

    suspend fun setStorageMigrated(done: Boolean) {
        context.settingsStore.edit { prefs -> prefs[Keys.STORAGE_MIGRATED] = done }
    }

    suspend fun setAiBackend(value: String) {
        val v = if (value == AiResearch.SELECTED_BACKEND) value else AiResearch.SELECTED_BACKEND
        context.settingsStore.edit { prefs -> prefs[Keys.AI_BACKEND] = v }
    }
}
