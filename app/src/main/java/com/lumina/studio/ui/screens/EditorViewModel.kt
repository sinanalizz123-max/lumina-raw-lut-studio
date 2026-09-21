package com.lumina.studio.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.HslAdjust
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskPoint
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.edit.withEditParams
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.ui.editor.EditorTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.currentCoroutineContext

data class ZoomTile(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val revision: Long
)

class EditorViewModel(application: Application, private val projectId: String?) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _params = MutableStateFlow(EditParams.DEFAULT)
    val params: StateFlow<EditParams> = _params.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _histogram = MutableStateFlow<Array<IntArray>?>(null)
    val histogram: StateFlow<Array<IntArray>?> = _histogram.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // Phase 4B system state: large-image decode (>8MP per PreviewRenderer.LARGE_IMAGE_PIXELS).
    // largeDecoding is true only while the actual decode job for a large source runs, so the
    // "Processing large image…" indicator plus Cancel button track real work, not a timer.
    private val _largeDecoding = MutableStateFlow(false)
    val largeDecoding: StateFlow<Boolean> = _largeDecoding.asStateFlow()

    private val _isLargeImage = MutableStateFlow(false)
    val isLargeImage: StateFlow<Boolean> = _isLargeImage.asStateFlow()

    private val _loadCancelled = MutableStateFlow(false)
    val loadCancelled: StateFlow<Boolean> = _loadCancelled.asStateFlow()

    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _pressingOriginal = MutableStateFlow(false)
    val pressingOriginal: StateFlow<Boolean> = _pressingOriginal.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _fullscreenPreview = MutableStateFlow<Bitmap?>(null)
    val fullscreenPreview: StateFlow<Bitmap?> = _fullscreenPreview.asStateFlow()

    private val _showHistogram = MutableStateFlow(false)
    val showHistogram: StateFlow<Boolean> = _showHistogram.asStateFlow()

    private val _activeTool = MutableStateFlow(EditorTool.ADJUST)
    val activeTool: StateFlow<EditorTool> = _activeTool.asStateFlow()

    private val _selectedHsl = MutableStateFlow(HslColor.RED)
    val selectedHsl: StateFlow<HslColor> = _selectedHsl.asStateFlow()

    private val _eyedropperArmed = MutableStateFlow(false)
    val eyedropperArmed: StateFlow<Boolean> = _eyedropperArmed.asStateFlow()

    private val _selectedCurve = MutableStateFlow(CurveChannel.MASTER)
    val selectedCurve: StateFlow<CurveChannel> = _selectedCurve.asStateFlow()

    private val _selectedMaskId = MutableStateFlow<String?>(null)
    val selectedMaskId: StateFlow<String?> = _selectedMaskId.asStateFlow()

    private val _showMaskOverlay = MutableStateFlow(false)
    val showMaskOverlay: StateFlow<Boolean> = _showMaskOverlay.asStateFlow()

    private val _zoomTile = MutableStateFlow<ZoomTile?>(null)
    val zoomTile: StateFlow<ZoomTile?> = _zoomTile.asStateFlow()

    private val _zoomWarning = MutableStateFlow<String?>(null)
    val zoomWarning: StateFlow<String?> = _zoomWarning.asStateFlow()

    private val _zoomEnhancing = MutableStateFlow(false)
    val zoomEnhancing: StateFlow<Boolean> = _zoomEnhancing.asStateFlow()

    private val _paramsRevision = MutableStateFlow(0L)
    val paramsRevision: StateFlow<Long> = _paramsRevision.asStateFlow()

    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var baseBitmap: Bitmap? = null
    private var persistJob: Job? = null
    private var renderJob: Job? = null
    private var loadJob: Job? = null
    private var fullscreenJob: Job? = null
    private var tileJob: Job? = null
    private var regionDecoder: BitmapRegionDecoder? = null
    private var regionSourceKey: String? = null
    private var regionWidth = 0
    private var regionHeight = 0
    private var lastViewLeft = 0f
    private var lastViewTop = 0f
    private var lastViewRight = 0f
    private var lastViewBottom = 0f
    private var lastZoom = 1f
    private var hasViewport = false
    private var zoomWarningDismissed = false
    // Phase 4B perf flags: gpuAcceleration caps preview decode at 1200px and skips histogram
    // auto-compute when OFF; previewQuality maps to decodePreview maxDim (High 1600 /
    // Medium 1200 / Low 800). Loaded from SettingsRepository in load(), refreshable live.
    private var gpuEnabled = true
    private var previewMaxDim = PreviewRenderer.MAX_PREVIEW_DIM

    init {
        if (!projectId.isNullOrBlank()) load(projectId) else _loading.value = false
    }

    fun setPerformanceFlags(gpu: Boolean, maxDim: Int) {
        gpuEnabled = gpu
        previewMaxDim = maxDim.coerceAtLeast(1)
    }

    fun cancelLoad() {
        loadJob?.cancel()
    }

    fun retryLoad() {
        val id = projectId
        if (!id.isNullOrBlank()) load(id)
    }

    private fun load(id: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                _loading.value = true
                _loadCancelled.value = false
                _largeDecoding.value = false
                _isLargeImage.value = false
                clearZoomForProjectChange()
                val loaded = withContext(Dispatchers.IO) { database.projectDao().getById(id) }
                _project.value = loaded
                _params.value = loaded?.toEditParams() ?: EditParams.DEFAULT
                _selectedMaskId.value = _params.value.masks.lastOrNull()?.id
                undoStack.clear()
                redoStack.clear()
                syncUndoRedo()
                if (loaded?.photoUri != null) {
                    val flags = withContext(Dispatchers.IO) { readPerformanceFlags() }
                    gpuEnabled = flags.first
                    previewMaxDim = flags.second
                    baseBitmap = withContext(Dispatchers.IO) { decodeBase(loaded.photoUri, previewMaxDim) }
                    ensureActive()
                    preloadImportedLuts()
                }
                renderPreview()
                _loading.value = false
            } catch (e: Exception) {
                _largeDecoding.value = false
                _loading.value = false
                if (e is CancellationException) {
                    _loadCancelled.value = true
                    throw e
                }
            }
        }
    }

    private suspend fun readPerformanceFlags(): Pair<Boolean, Int> {
        return try {
            val repo = SettingsRepository(getApplication())
            val gpu = repo.gpuAcceleration.first()
            val quality = repo.previewQuality.first()
            gpu to PreviewRenderer.effectivePreviewMaxDim(quality, gpu)
        } catch (_: Exception) {
            true to PreviewRenderer.MAX_PREVIEW_DIM
        }
    }

    private suspend fun preloadImportedLuts() {
        try {
            database.presetDao().observePresets().first().forEach { preset ->
                LutRegistry.registerParsed(preset.id, preset.cubeText)
            }
        } catch (_: Exception) {
        }
    }

    fun currentLut(): LutCube? = LutRegistry.resolve(_params.value.presetId)

    fun baseSourcePath(): String? = _project.value?.photoUri

    fun applyPreset(presetId: String?, intensity: Float? = null) {
        val current = _params.value
        if (current.presetId == presetId && intensity == null) return
        viewModelScope.launch {
            val resolved = intensity ?: defaultIntensityFor(presetId)
            val latest = _params.value
            if (latest.presetId == presetId && latest.presetIntensity == resolved) return@launch
            pushUndo(latest)
            redoStack.clear()
            _params.value = latest.copy(
                presetId = presetId,
                presetIntensity = resolved.coerceIn(0f, 1f)
            )
            syncUndoRedo()
            renderPreview()
            schedulePersist()
        }
    }

    fun setPresetIntensity(value: Float) {
        val current = _params.value
        if (current.presetId == null) return
        val clamped = value.coerceIn(0f, 1f)
        if (current.presetIntensity == clamped) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.copy(presetIntensity = clamped)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun clearPreset() {
        applyPreset(null, 1f)
    }

    private suspend fun defaultIntensityFor(presetId: String?): Float {
        if (presetId == null) return 1f
        BuiltInPresets.byId(presetId)?.let { return it.defaultIntensity }
        return try {
            withContext(Dispatchers.IO) {
                database.presetDao().getById(presetId)?.defaultIntensity ?: 1f
            }
        } catch (_: Exception) {
            1f
        }
    }

    suspend fun renderFullBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        val project = _project.value ?: return@withContext null
        val source = project.photoUri ?: return@withContext null
        try {
            decodeFull(source)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private fun decodeFull(pathOrUri: String): Bitmap? {
        return try {
            val file = File(pathOrUri)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(pathOrUri.toUri())?.use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase(pathOrUri: String, maxDim: Int = previewMaxDim): Bitmap? {
        return try {
            val file = File(pathOrUri)
            if (file.exists()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                val large = bounds.outWidth > 0 && bounds.outHeight > 0 &&
                    pixels > PreviewRenderer.LARGE_IMAGE_PIXELS
                if (large) {
                    _isLargeImage.value = true
                    _largeDecoding.value = true
                }
                try {
                    PreviewRenderer.decodePreview(file.absolutePath, maxDim) ?: run {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    }
                } finally {
                    if (large) _largeDecoding.value = false
                }
            } else {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(pathOrUri.toUri())?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: Exception) {
            _largeDecoding.value = false
            null
        }
    }

    fun updateControl(control: AdjustControl, value: Float) {
        val current = _params.value
        val clamped = control.clamp(value)
        if (current.get(control) == clamped) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.with(control, clamped)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetControl(control: AdjustControl) {
        val current = _params.value
        if (current.get(control) == control.default) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetControl(control)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetAll() {
        val current = _params.value
        if (current.isDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetAll()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun selectHslColor(color: HslColor) {
        _selectedHsl.value = color
    }

    fun setEyedropperArmed(armed: Boolean) {
        _eyedropperArmed.value = armed
    }

    fun eyedropperPick(argb: Int) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hsl = PreviewRenderer.rgbToHsl(r, g, b)
        _selectedHsl.value = HslColor.nearestForHue(hsl[0])
        _eyedropperArmed.value = false
    }

    fun updateHsl(color: HslColor, adjust: HslAdjust) {
        val current = _params.value
        val next = current.withHsl(color, adjust)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateHslHue(color: HslColor, value: Float) {
        updateHsl(color, _params.value.getHsl(color).copy(hue = value.coerceIn(-100f, 100f)))
    }

    fun updateHslSat(color: HslColor, value: Float) {
        updateHsl(color, _params.value.getHsl(color).copy(sat = value.coerceIn(-100f, 100f)))
    }

    fun updateHslLum(color: HslColor, value: Float) {
        updateHsl(color, _params.value.getHsl(color).copy(lum = value.coerceIn(-100f, 100f)))
    }

    fun resetHslColor(color: HslColor) {
        val current = _params.value
        if (current.getHsl(color) == HslAdjust()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetHslColor(color)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateGlobalSat(value: Float) {
        val current = _params.value
        val next = current.withGlobalSat(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateGlobalVib(value: Float) {
        val current = _params.value
        val next = current.withGlobalVib(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetHslAll() {
        val current = _params.value
        if (current.isHslDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetHslAll()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun selectCurveChannel(channel: CurveChannel) {
        _selectedCurve.value = channel
    }

    fun setCurvePoints(channel: CurveChannel, points: List<CurvePoint>, pushUndoStep: Boolean = true) {
        val sanitized = Curves.sanitize(points)
        val current = _params.value
        if (current.getCurve(channel) == sanitized) return
        if (pushUndoStep) {
            pushUndo(current)
            redoStack.clear()
        }
        _params.value = current.withCurve(channel, sanitized)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun beginCurveEdit() {
        pushUndo(_params.value)
        redoStack.clear()
        syncUndoRedo()
    }

    fun moveCurvePointLive(channel: CurveChannel, points: List<CurvePoint>) {
        val sanitized = Curves.sanitize(points)
        val current = _params.value
        if (current.getCurve(channel) == sanitized) return
        _params.value = current.withCurve(channel, sanitized)
        renderPreview()
        schedulePersist()
    }

    fun resetCurve(channel: CurveChannel) {
        val current = _params.value
        if (current.isCurveDiagonal(channel)) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetCurve(channel)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetCurvesAll() {
        val current = _params.value
        if (current.isCurvesDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetCurvesAll()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateDetail(control: DetailControl, value: Float) {
        val current = _params.value
        val next = current.withDetail(control, value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetDetail(control: DetailControl) {
        val current = _params.value
        if (current.getDetail(control) == control.default) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetDetail(control)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetDetailsAll() {
        val current = _params.value
        if (current.isDetailsDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetDetails()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setCropRatio(ratio: CropRatio) {
        val current = _params.value
        val next = current.withCropRatio(ratio)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun rotateCrop90() {
        val current = _params.value
        pushUndo(current)
        redoStack.clear()
        _params.value = current.rotateCrop90()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setStraighten(deg: Float) {
        val current = _params.value
        val next = current.withStraighten(deg)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun toggleFlipH() {
        val current = _params.value
        pushUndo(current)
        redoStack.clear()
        _params.value = current.toggleFlipH()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun toggleFlipV() {
        val current = _params.value
        pushUndo(current)
        redoStack.clear()
        _params.value = current.toggleFlipV()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetCrop() {
        val current = _params.value
        if (current.isCropDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetCrop()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun beginCustomCropEdit() {
        pushUndo(_params.value)
        redoStack.clear()
        syncUndoRedo()
    }

    fun moveCustomCropLive(left: Float, top: Float, right: Float, bottom: Float) {
        val current = _params.value
        val next = current.withCustomCropRect(left, top, right, bottom)
        if (next == current) return
        _params.value = next
        renderPreview()
        schedulePersist()
    }

    fun addMask(tool: MaskTool) {
        val current = _params.value
        if (current.masks.size >= EditParams.MAX_MASKS) return
        pushUndo(current)
        redoStack.clear()
        val next = current.addMask(tool)
        _params.value = next
        _selectedMaskId.value = next.masks.lastOrNull()?.id
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun removeMask(id: String) {
        val current = _params.value
        if (current.getMask(id) == null) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.removeMask(id)
        if (_selectedMaskId.value == id) {
            _selectedMaskId.value = _params.value.masks.lastOrNull()?.id
        }
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun selectMask(id: String?) {
        if (id != null && _params.value.getMask(id) == null) return
        _selectedMaskId.value = id
    }

    fun toggleMaskVisible(id: String) {
        val current = _params.value
        if (current.getMask(id) == null) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.toggleMaskVisible(id)
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateMask(id: String, transform: (EditMask) -> EditMask) {
        val current = _params.value
        val next = current.updateMask(id, transform)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setMaskSize(id: String, sizePx: Float) = updateMask(id) { it.withSizePx(sizePx) }

    fun setMaskFeather(id: String, feather: Float) = updateMask(id) { it.withFeather(feather) }

    fun setMaskOpacity(id: String, opacity: Float) = updateMask(id) { it.withOpacity(opacity) }

    fun setMaskInverted(id: String, inverted: Boolean) = updateMask(id) { it.copy(inverted = inverted) }

    fun setMaskCenter(id: String, x: Float, y: Float) = updateMask(id) {
        val centered = it.withCenter(x, y)
        // Stroke tools render from points, not centerX/Y. v1 has no paint
        // canvas (single seeded dot), so keep a single-dot stroke's point glued
        // to the Center sliders; multi-point strokes are left untouched.
        if ((it.tool == MaskTool.BRUSH || it.tool == MaskTool.ERASER) && it.points.size <= 1) {
            centered.copy(points = listOf(MaskPoint(centered.centerX, centered.centerY)))
        } else {
            centered
        }
    }

    fun setMaskRadius(id: String, radius: Float) = updateMask(id) { it.withRadius(radius) }

    fun setMaskAngle(id: String, angle: Float) = updateMask(id) { it.withAngle(angle) }

    fun setMaskPosition(id: String, position: Float) = updateMask(id) { it.withPosition(position) }

    fun setMaskExposure(id: String, exposure: Float) = updateMask(id) { it.withExposure(exposure) }

    fun setMaskTemperature(id: String, temperature: Float) = updateMask(id) { it.withTemperature(temperature) }

    fun resetMasks() {
        val current = _params.value
        if (current.isMasksDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.clearMasks()
        _selectedMaskId.value = null
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setShowMaskOverlay(show: Boolean) {
        _showMaskOverlay.value = show
    }

    fun toggleMaskOverlay() {
        _showMaskOverlay.value = !_showMaskOverlay.value
    }

    fun setStepEnabled(key: StepKey, enabled: Boolean) {
        val current = _params.value
        val next = current.withStep(key, enabled)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetAdjustsGroup() {
        val current = _params.value
        if (current.isAdjustsDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetAdjusts()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetPresetGroup() {
        val current = _params.value
        if (current.presetId == null) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.clearPreset()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetLutGroup() {
        val current = _params.value
        if (current.presetId == null || current.presetIntensity == 1f) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetLutIntensity()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun requestHistogram() {
        computeHistogram()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_params.value)
        if (redoStack.size > MAX_STACK) redoStack.removeFirst()
        _params.value = undoStack.removeLast()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        pushUndoNoClear(_params.value)
        _params.value = redoStack.removeLast()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = _project.value ?: return
        if (current.name == trimmed) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
            database.projectDao().upsert(updated)
            _project.value = updated
        }
    }

    fun setZoom(value: Float) {
        _zoom.value = value.coerceIn(1f, 5f)
    }

    fun setPressingOriginal(pressing: Boolean) {
        _pressingOriginal.value = pressing
    }

    fun toggleFullscreen() {
        val entering = !_fullscreen.value
        _fullscreen.value = entering
        if (entering) {
            scheduleFullscreenRender(immediate = true)
        } else {
            clearFullscreenPreview()
        }
    }

    fun toggleHistogram() {
        _showHistogram.value = !_showHistogram.value
        if (_showHistogram.value) computeHistogram()
    }

    fun setActiveTool(tool: EditorTool) {
        _activeTool.value = tool
    }

    private fun pushUndo(params: EditParams) {
        undoStack.addLast(params)
        if (undoStack.size > MAX_STACK) undoStack.removeFirst()
    }

    private fun pushUndoNoClear(params: EditParams) {
        undoStack.addLast(params)
        if (undoStack.size > MAX_STACK) undoStack.removeFirst()
    }

    private fun syncUndoRedo() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun renderPreview() {
        renderJob?.cancel()
        _paramsRevision.value = _paramsRevision.value + 1
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val base = baseBitmap ?: return@launch
            val params = _params.value
            val lut = LutRegistry.resolve(params.presetId)
            val out = try {
                PreviewRenderer.render(base, params, lut)
            } catch (_: Exception) {
                null
            }
            _preview.value = out
            // Phase 4B: histogram auto-recompute on every render runs only with GPU
            // acceleration ON. When OFF, the histogram updates solely via explicit
            // requestHistogram()/toggleHistogram() calls.
            if (_showHistogram.value && PreviewRenderer.shouldAutoHistogram(gpuEnabled)) {
                if (out != null) computeHistogram(out) else computeHistogram(base)
            }
        }
        if (_fullscreen.value) scheduleFullscreenRender(immediate = false)
        refreshZoomTileForParamsChange()
    }

    private fun scheduleFullscreenRender(immediate: Boolean) {
        if (!_fullscreen.value) return
        fullscreenJob?.cancel()
        fullscreenJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!immediate) delay(FULLSCREEN_DEBOUNCE_MS)
                val path = _project.value?.photoUri ?: return@launch
                val params = _params.value
                val lut = LutRegistry.resolve(params.presetId)
                val decoded = withContext(Dispatchers.IO) { decodeFullscreenBitmap(path) }
                    ?: return@launch
                ensureActive()
                val rendered = try {
                    PreviewRenderer.render(decoded, params, lut)
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                ensureActive()
                if (rendered == null) {
                    try {
                        if (!decoded.isRecycled) decoded.recycle()
                    } catch (_: Exception) {
                    }
                    return@launch
                }
                if (rendered !== decoded) {
                    try {
                        if (!decoded.isRecycled) decoded.recycle()
                    } catch (_: Exception) {
                    }
                }
                val old = _fullscreenPreview.value
                _fullscreenPreview.value = rendered
                // RAM-safe: only one hi-res bitmap alive at a time.
                if (old !== rendered) {
                    try {
                        if (old != null && !old.isRecycled) old.recycle()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun decodeFullscreenBitmap(pathOrUri: String): Bitmap? {
        return try {
            val file = File(pathOrUri)
            if (file.exists()) {
                val hiRes = runCatching {
                    PreviewRenderer.decodePreview(file.absolutePath, FULLSCREEN_MAX_DIM)
                }.getOrNull()
                if (hiRes != null) return hiRes
                runCatching {
                    PreviewRenderer.decodePreview(file.absolutePath, PreviewRenderer.MAX_PREVIEW_DIM)
                }.getOrNull()
            } else {
                runCatching {
                    val full = decodeFull(pathOrUri) ?: return null
                    val longest = maxOf(full.width, full.height)
                    if (longest <= FULLSCREEN_MAX_DIM) return full
                    val scale = FULLSCREEN_MAX_DIM.toFloat() / longest.toFloat()
                    val targetW = (full.width * scale + 0.5f).toInt().coerceAtLeast(1)
                    val targetH = (full.height * scale + 0.5f).toInt().coerceAtLeast(1)
                    try {
                        val scaled = Bitmap.createScaledBitmap(full, targetW, targetH, true)
                        if (scaled !== full) {
                            try {
                                if (!full.isRecycled) full.recycle()
                            } catch (_: Exception) {
                            }
                        }
                        scaled
                    } catch (_: OutOfMemoryError) {
                        full
                    } catch (_: Exception) {
                        full
                    }
                }.getOrNull()
            }
        } catch (_: OutOfMemoryError) {
            runCatching {
                val file = File(pathOrUri)
                if (file.exists()) PreviewRenderer.decodePreview(file.absolutePath, PreviewRenderer.MAX_PREVIEW_DIM) else null
            }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun clearFullscreenPreview() {
        fullscreenJob?.cancel()
        fullscreenJob = null
        val old = _fullscreenPreview.value
        _fullscreenPreview.value = null
        try {
            if (old != null && !old.isRecycled) old.recycle()
        } catch (_: Exception) {
        }
    }

    fun onViewportChanged(left: Float, top: Float, right: Float, bottom: Float, zoomScale: Float) {
        val zoom = zoomScale.coerceIn(0f, 5f)
        if (zoom <= ZOOM_TILE_THRESHOLD) {
            hasViewport = false
            lastZoom = zoom
            tileJob?.cancel()
            tileJob = null
            recycleZoomTile()
            _zoomWarning.value = null
            _zoomEnhancing.value = false
            return
        }
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        if (r <= l || b <= t) {
            hasViewport = false
            tileJob?.cancel()
            tileJob = null
            recycleZoomTile()
            _zoomEnhancing.value = false
            return
        }
        lastViewLeft = l
        lastViewTop = t
        lastViewRight = r
        lastViewBottom = b
        lastZoom = zoom
        hasViewport = true
        scheduleTile(clearStale = false)
    }

    fun dismissZoomWarning() {
        zoomWarningDismissed = true
        _zoomWarning.value = null
    }

    private fun refreshZoomTileForParamsChange() {
        if (!hasViewport || lastZoom <= ZOOM_TILE_THRESHOLD) return
        scheduleTile(clearStale = true)
    }

    private fun scheduleTile(clearStale: Boolean) {
        tileJob?.cancel()
        if (clearStale) recycleZoomTile()
        _zoomEnhancing.value = true
        tileJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(ZOOM_TILE_DEBOUNCE_MS)
                renderZoomTile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _zoomEnhancing.value = false
            }
        }
    }

    private suspend fun renderZoomTile() {
        currentCoroutineContext().ensureActive()
        val l = lastViewLeft
        val t = lastViewTop
        val r = lastViewRight
        val b = lastViewBottom
        if (!hasViewport || r <= l || b <= t) {
            _zoomEnhancing.value = false
            return
        }
        val revision = _paramsRevision.value
        val paramsSnap = _params.value
        val lut = LutRegistry.resolve(paramsSnap.presetId)
        val source = _project.value?.photoUri
        if (source.isNullOrBlank()) {
            recycleZoomTile()
            setZoomWarning("unreadable source")
            _zoomEnhancing.value = false
            return
        }
        val decoder = ensureRegionDecoder(source)
        if (decoder == null) {
            recycleZoomTile()
            setZoomWarning("unsupported format")
            _zoomEnhancing.value = false
            return
        }
        val w = regionWidth
        val h = regionHeight
        if (w <= 0 || h <= 0) {
            recycleZoomTile()
            setZoomWarning("unsupported format")
            _zoomEnhancing.value = false
            return
        }
        if (w.toLong() * h.toLong() > ZOOM_MAX_SOURCE_PIXELS) {
            recycleZoomTile()
            setZoomWarning("image too large")
            _zoomEnhancing.value = false
            return
        }
        val leftPx = (l * w).toInt().coerceIn(0, w - 1)
        val topPx = (t * h).toInt().coerceIn(0, h - 1)
        val rightPx = (r * w).toInt().coerceIn(1, w)
        val bottomPx = (b * h).toInt().coerceIn(1, h)
        if (rightPx <= leftPx || bottomPx <= topPx) {
            _zoomEnhancing.value = false
            return
        }
        var sample = 1
        val regionPixels = (rightPx - leftPx).toLong() * (bottomPx - topPx).toLong()
        while (regionPixels / (sample.toLong() * sample.toLong()) > ZOOM_TILE_MAX_PIXELS) sample *= 2
        val rect = Rect(leftPx, topPx, rightPx, bottomPx)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodeStartMs = SystemClock.elapsedRealtime()
        val raw: Bitmap? = try {
            decoder.decodeRegion(rect, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        val decodeMs = SystemClock.elapsedRealtime() - decodeStartMs
        currentCoroutineContext().ensureActive()
        if (raw == null) {
            recycleZoomTile()
            setZoomWarning("detail decode failed")
            _zoomEnhancing.value = false
            return
        }
        val renderStartMs = SystemClock.elapsedRealtime()
        val rendered: Bitmap? = try {
            PreviewRenderer.render(raw, paramsSnap, lut)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        val renderMs = SystemClock.elapsedRealtime() - renderStartMs
        Log.d(
            TAG_ZOOM_TILE,
            "tile ${raw.width}x${raw.height} sample=$sample " +
                "decode=${decodeMs}ms render=${renderMs}ms total=${decodeMs + renderMs}ms rev=$revision"
        )
        currentCoroutineContext().ensureActive()
        if (rendered == null) {
            try {
                if (!raw.isRecycled) raw.recycle()
            } catch (_: Exception) {
            }
            recycleZoomTile()
            setZoomWarning("detail decode failed")
            _zoomEnhancing.value = false
            return
        }
        if (rendered !== raw) {
            try {
                if (!raw.isRecycled) raw.recycle()
            } catch (_: Exception) {
            }
        }
        if (revision != _paramsRevision.value) {
            try {
                if (!rendered.isRecycled) rendered.recycle()
            } catch (_: Exception) {
            }
            _zoomEnhancing.value = false
            return
        }
        val old = _zoomTile.value
        _zoomTile.value = ZoomTile(rendered, l, t, r, b, revision)
        if (old !== _zoomTile.value) {
            try {
                val bmp = old?.bitmap
                if (bmp != null && bmp !== rendered && !bmp.isRecycled) bmp.recycle()
            } catch (_: Exception) {
            }
        }
        _zoomWarning.value = null
        _zoomEnhancing.value = false
    }

    private fun ensureRegionDecoder(source: String): BitmapRegionDecoder? {
        val cached = regionDecoder
        if (cached != null && regionSourceKey == source) {
            val alive = runCatching { !cached.isRecycled }.getOrDefault(false)
            if (alive) return cached
        }
        closeRegionDecoder()
        val created: BitmapRegionDecoder? = try {
            runCatching {
                val file = File(source)
                if (file.exists()) {
                    BitmapRegionDecoder.newInstance(source, false)
                } else {
                    val context = getApplication<Application>()
                    context.contentResolver.openInputStream(source.toUri())?.use { stream ->
                        BitmapRegionDecoder.newInstance(stream, false)
                    }
                }
            }.getOrNull()
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        if (created == null) return null
        val dw = runCatching { created.width }.getOrDefault(0)
        val dh = runCatching { created.height }.getOrDefault(0)
        if (dw <= 0 || dh <= 0) {
            runCatching { created.recycle() }
            return null
        }
        regionDecoder = created
        regionSourceKey = source
        regionWidth = dw
        regionHeight = dh
        return created
    }

    private fun closeRegionDecoder() {
        regionSourceKey = null
        regionWidth = 0
        regionHeight = 0
        val d = regionDecoder
        regionDecoder = null
        if (d != null) {
            runCatching {
                if (!d.isRecycled) d.recycle()
            }
        }
    }

    private fun setZoomWarning(reason: String) {
        if (zoomWarningDismissed) return
        _zoomWarning.value = reason
    }

    private fun recycleZoomTile() {
        val old = _zoomTile.value
        _zoomTile.value = null
        if (old != null) {
            try {
                if (!old.bitmap.isRecycled) old.bitmap.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun clearZoomForProjectChange() {
        tileJob?.cancel()
        tileJob = null
        hasViewport = false
        lastZoom = 1f
        zoomWarningDismissed = false
        recycleZoomTile()
        _zoomWarning.value = null
        _zoomEnhancing.value = false
        closeRegionDecoder()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            fullscreenJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            tileJob?.cancel()
        } catch (_: Exception) {
        }
        tileJob = null
        try {
            _zoomTile.value?.takeIf { !it.bitmap.isRecycled }?.bitmap?.recycle()
        } catch (_: Exception) {
        }
        _zoomTile.value = null
        closeRegionDecoder()
        try {
            _fullscreenPreview.value?.takeIf { !it.isRecycled }?.recycle()
        } catch (_: Exception) {
        }
        _fullscreenPreview.value = null
    }

    private fun computeHistogram(source: Bitmap? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val src = source ?: _preview.value ?: baseBitmap ?: return@launch
                _histogram.value = PreviewRenderer.computeHistogram(src)
            } catch (_: Exception) {
            }
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val current = _project.value ?: return@launch
            val params = _params.value
            val now = System.currentTimeMillis()
            val updated = current.withEditParams(params).copy(updatedAt = now)
            withContext(Dispatchers.IO) { database.projectDao().upsert(updated) }
            _project.value = updated
        }
    }

    companion object {
        const val TAG_ZOOM_TILE = "EditorZoomTile"
        const val MAX_STACK = 50
        const val PERSIST_DEBOUNCE_MS = 300L
        const val FULLSCREEN_MAX_DIM = 4096
        const val FULLSCREEN_DEBOUNCE_MS = 500L
        const val ZOOM_TILE_THRESHOLD = 1.25f
        const val ZOOM_TILE_DEBOUNCE_MS = 350L
        const val ZOOM_TILE_MAX_PIXELS = 1_500_000
        const val ZOOM_MAX_SOURCE_PIXELS = 120_000_000L
    }
}

class EditorViewModelFactory(
    private val application: Application,
    private val projectId: String?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EditorViewModel(application, projectId) as T
    }
}
