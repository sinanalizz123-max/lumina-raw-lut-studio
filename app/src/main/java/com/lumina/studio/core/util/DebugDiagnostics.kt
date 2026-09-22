package com.lumina.studio.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DebugDiagnostics {
    private val _backendName = MutableStateFlow("CpuRenderBackend")
    val backendName: StateFlow<String> = _backendName.asStateFlow()

    private val _decoderName = MutableStateFlow("BitmapFactory")
    val decoderName: StateFlow<String> = _decoderName.asStateFlow()

    private val _lastRenderMs = MutableStateFlow(-1L)
    val lastRenderMs: StateFlow<Long> = _lastRenderMs.asStateFlow()

    private val _imageDims = MutableStateFlow("—")
    val imageDims: StateFlow<String> = _imageDims.asStateFlow()

    private val _paramsRevision = MutableStateFlow(0L)
    val paramsRevision: StateFlow<Long> = _paramsRevision.asStateFlow()

    private val _zoomTileInfo = MutableStateFlow("idle")
    val zoomTileInfo: StateFlow<String> = _zoomTileInfo.asStateFlow()

    // M15: GLES3 hybrid backend exists (Export FINAL + fullscreen when the
    // performance toggle is ON and the device reports GLES3, CPU fallback
    // otherwise). Per-render truth still comes from reportRender's backend
    // argument ("GPU (GLES3)" vs "CPU (fallback: reason)").
    const val GPU_INFO = "GLES3 hybrid (export + fullscreen; CPU fallback)"
    const val COLOR_SPACE = "sRGB (working)"

    fun memorySummary(): String {
        return try {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            "$usedMb MB / $maxMb MB"
        } catch (_: Exception) {
            "—"
        }
    }

    fun reportRender(backend: String, decoder: String, renderMs: Long, dims: String, revision: Long) {
        _backendName.value = backend
        _decoderName.value = decoder
        _lastRenderMs.value = renderMs
        _imageDims.value = dims
        _paramsRevision.value = revision
    }

    fun reportZoom(info: String) {
        _zoomTileInfo.value = info
    }
}
