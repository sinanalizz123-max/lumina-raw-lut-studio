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
import com.lumina.studio.core.data.local.EditHistoryLog
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.ai.AiBitmaps
import com.lumina.studio.core.ai.AiMaskCache
import com.lumina.studio.core.ai.AiMaskFieldStore
import com.lumina.studio.core.ai.AiProcessor
import com.lumina.studio.core.ai.AiSelectMessages
import com.lumina.studio.core.ai.AiSelectOutcome
import com.lumina.studio.core.ai.HeuristicAiProcessor
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.AutoLevels
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeHsl
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.HslAdjust
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.DustCandidate
import com.lumina.studio.core.edit.MaskPoint
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.RetouchKind
import com.lumina.studio.core.edit.RetouchOp
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.edit.withEditParams
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.render.GenerationTracker
import com.lumina.studio.core.render.ImageDecoder
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.RenderSource
import com.lumina.studio.core.render.RenderTarget
import com.lumina.studio.core.render.cpu.RenderBackends
import com.lumina.studio.core.util.ExifOrientation
import com.lumina.studio.core.util.ImageOrientation
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

/** M12: computed-field result vs. honest outcome for the selection flow. */
private sealed interface AiBuild {
    data class Ready(val cacheKey: String) : AiBuild
    data class Outcome(val outcome: AiSelectOutcome) : AiBuild
}

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

    private val _selectedGradeZone = MutableStateFlow(GradeZone.GLOBAL)
    val selectedGradeZone: StateFlow<GradeZone> = _selectedGradeZone.asStateFlow()

    private val _pointEyedropperArmed = MutableStateFlow(false)
    val pointEyedropperArmed: StateFlow<Boolean> = _pointEyedropperArmed.asStateFlow()

    private val _showPointAffected = MutableStateFlow(false)
    val showPointAffected: StateFlow<Boolean> = _showPointAffected.asStateFlow()

    private val _pointColorMask = MutableStateFlow<Bitmap?>(null)
    val pointColorMask: StateFlow<Bitmap?> = _pointColorMask.asStateFlow()

    private val _selectedCurve = MutableStateFlow(CurveChannel.MASTER)
    val selectedCurve: StateFlow<CurveChannel> = _selectedCurve.asStateFlow()

    private val _selectedMaskId = MutableStateFlow<String?>(null)
    val selectedMaskId: StateFlow<String?> = _selectedMaskId.asStateFlow()

    private val _showMaskOverlay = MutableStateFlow(false)
    val showMaskOverlay: StateFlow<Boolean> = _showMaskOverlay.asStateFlow()

    private val _maskSampleArmedId = MutableStateFlow<String?>(null)
    val maskSampleArmedId: StateFlow<String?> = _maskSampleArmedId.asStateFlow()

    // M12 heuristic select (§§24-25): kind label ("subject"/"sky") while a
    // selection job runs, else null; transient honest result/failure message.
    private val _aiWorkingKind = MutableStateFlow<String?>(null)
    val aiWorkingKind: StateFlow<String?> = _aiWorkingKind.asStateFlow()

    private val _aiMessage = MutableStateFlow<String?>(null)
    val aiMessage: StateFlow<String?> = _aiMessage.asStateFlow()

    // M8 retouch (§26): per-op spots + placement/source arms. Drag moves
    // coalesce like curves (beginRetouchDrag pushes one undo, live moves skip
    // the push); discrete edits push per op.
    private val _selectedRetouchId = MutableStateFlow<String?>(null)
    val selectedRetouchId: StateFlow<String?> = _selectedRetouchId.asStateFlow()

    private val _retouchMode = MutableStateFlow(RetouchKind.HEAL)
    val retouchMode: StateFlow<RetouchKind> = _retouchMode.asStateFlow()

    private val _retouchPlaceArmed = MutableStateFlow(false)
    val retouchPlaceArmed: StateFlow<Boolean> = _retouchPlaceArmed.asStateFlow()

    private val _cloneSourceArmedId = MutableStateFlow<String?>(null)
    val cloneSourceArmedId: StateFlow<String?> = _cloneSourceArmedId.asStateFlow()

    // M8 lens blur (§27): heuristic depth blur (NOT AI). Focus arm reuses the
    // eyedropper-arm pattern; depth preview reuses the overlay pattern.
    private val _lensFocusArmed = MutableStateFlow(false)
    val lensFocusArmed: StateFlow<Boolean> = _lensFocusArmed.asStateFlow()

    private val _showLensDepth = MutableStateFlow(false)
    val showLensDepth: StateFlow<Boolean> = _showLensDepth.asStateFlow()

    private val _lensDepthPreview = MutableStateFlow<Bitmap?>(null)
    val lensDepthPreview: StateFlow<Bitmap?> = _lensDepthPreview.asStateFlow()

    // M8 dust (§28): transient inspection list (NOT persisted in the recipe).
    // Confirmed candidates become HEAL ops via healConfirmedDust().
    private val _dustCandidates = MutableStateFlow<List<DustCandidate>>(emptyList())
    val dustCandidates: StateFlow<List<DustCandidate>> = _dustCandidates.asStateFlow()

    private val _dustSensitivity = MutableStateFlow(50f)
    val dustSensitivity: StateFlow<Float> = _dustSensitivity.asStateFlow()

    private val _zoomTile = MutableStateFlow<ZoomTile?>(null)
    val zoomTile: StateFlow<ZoomTile?> = _zoomTile.asStateFlow()

    private val _zoomWarning = MutableStateFlow<String?>(null)
    val zoomWarning: StateFlow<String?> = _zoomWarning.asStateFlow()

    private val _zoomEnhancing = MutableStateFlow(false)
    val zoomEnhancing: StateFlow<Boolean> = _zoomEnhancing.asStateFlow()

    private val _paramsRevision = MutableStateFlow(0L)
    val paramsRevision: StateFlow<Long> = _paramsRevision.asStateFlow()

    private val _isDevelopedRaw = MutableStateFlow(false)
    val isDevelopedRaw: StateFlow<Boolean> = _isDevelopedRaw.asStateFlow()

    private val _rawRecipe = MutableStateFlow(com.lumina.studio.core.render.RawRecipe())
    val rawRecipe: StateFlow<com.lumina.studio.core.render.RawRecipe> = _rawRecipe.asStateFlow()

    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var baseBitmap: Bitmap? = null
    private var persistJob: Job? = null
    // M12: cancellable heuristic-selection job + swappable backend
    // (tests/future vetted backends; default is the honest heuristic).
    private var aiJob: Job? = null
    var aiProcessor: AiProcessor = HeuristicAiProcessor
    private val pendingHistoryTags = LinkedHashSet<String>()
    private var renderJob: Job? = null
    private var histogramJob: Job? = null
    private var loadJob: Job? = null
    private var fullscreenJob: Job? = null
    private var tileJob: Job? = null
    private var regionDecoder: BitmapRegionDecoder? = null
    private var regionSourceKey: String? = null
    private var regionWidth = 0
    private var regionHeight = 0
    private var regionOrientation = 1
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
    private var useFullDevelop = true
    private val previewGenerations = GenerationTracker()
    private val fullscreenGenerations = GenerationTracker()
    // M16 (§54): last issued backend generations so a superseded render can
    // be refused at backend entry (Cpu/Gles `cancelled` sets) in addition to
    // the post-render stale-drop below. Bitmap work itself is blocking and
    // cannot be preempted — the stale-drop stays authoritative.
    private var lastPreviewBackendGen = 0L
    private var lastFullscreenBackendGen = 0L

    private fun appDecoder(): ImageDecoder<Bitmap> =
        RenderBackends.decoder(getApplication())

    // M15 (§11): preview/tile/thumb stay CPU (responsiveness proven); Export
    // FINAL + Fullscreen attempt the GLES3 hybrid backend when the
    // performance toggle is ON and the device reports GLES3, with automatic
    // CPU fallback inside GlesBackend. The returned label names the backend
    // actually used ("GPU (GLES3)" vs "CPU (fallback: reason)") for
    // DebugDiagnostics. CPU behavior is otherwise byte-identical.
    private fun gradeThroughBackend(
        base: Bitmap,
        params: EditParams,
        lut: LutCube?,
        target: RenderTarget,
        generation: Long
    ): Pair<Bitmap?, String> {
        val request = RenderRequest(
            params = params,
            lut = lut,
            source = base,
            target = target,
            generation = generation
        )
        if (
            com.lumina.studio.core.render.gpu.GpuRenderPolicy.shouldAttemptGpu(
                target, gpuEnabled,
                com.lumina.studio.core.render.gpu.GpuSupport.glesVersion(getApplication())
            )
        ) {
            val backend = RenderBackends.gpu()
            if (backend is com.lumina.studio.core.render.gpu.GlesBackend) {
                val outcome = runCatching { backend.renderWithLabel(request) }.getOrNull()
                if (outcome != null) {
                    return when (val result = outcome.result) {
                        is RenderResult.Ok -> result.bitmap to outcome.backendLabel
                        is RenderResult.Unavailable -> null to outcome.backendLabel
                        RenderResult.OomBudget -> null to outcome.backendLabel
                    }
                }
            }
        }
        return when (
            val result = RenderBackends.cpu().render(request)
        ) {
            is RenderResult.Ok -> result.bitmap to
                com.lumina.studio.core.render.gpu.GpuRenderPolicy.CPU_LABEL
            is RenderResult.Unavailable -> null to
                com.lumina.studio.core.render.gpu.GpuRenderPolicy.CPU_LABEL
            RenderResult.OomBudget -> null to
                com.lumina.studio.core.render.gpu.GpuRenderPolicy.CPU_LABEL
        }
    }

    init {
        runCatching { RenderBackends.attachGpuMemoryHook(getApplication()) }
        if (!projectId.isNullOrBlank()) load(projectId) else _loading.value = false
    }

    fun setPerformanceFlags(gpu: Boolean, maxDim: Int) {
        gpuEnabled = gpu
        previewMaxDim = maxDim.coerceAtLeast(1)
    }

    fun setRawQuality(quality: String) {
        val full = quality.trim().equals("High", ignoreCase = true)
        if (useFullDevelop == full) return
        useFullDevelop = full
        if (_isDevelopedRaw.value) redevelopRaw()
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
                // A reload/project switch must not expose pixels from the previous
                // project. Stop consumers before releasing their bitmaps so a
                // blocking render cannot race a recycle.
                renderJob?.cancel()
                fullscreenJob?.cancel()
                histogramJob?.cancel()
                tileJob?.cancel()
                runCatching { renderJob?.join() }
                runCatching { fullscreenJob?.join() }
                runCatching { histogramJob?.join() }
                runCatching { tileJob?.join() }
                renderJob = null
                fullscreenJob = null
                histogramJob = null
                tileJob = null
                baseBitmap?.let { old -> runCatching { if (!old.isRecycled) old.recycle() } }
                baseBitmap = null
                _preview.value?.let { old -> runCatching { if (!old.isRecycled) old.recycle() } }
                _preview.value = null
                _fullscreenPreview.value?.let { old -> runCatching { if (!old.isRecycled) old.recycle() } }
                _fullscreenPreview.value = null
                recycleMaskBitmap(_pointColorMask.value)
                _pointColorMask.value = null
                recycleMaskBitmap(_lensDepthPreview.value)
                _lensDepthPreview.value = null
                _loadCancelled.value = false
                _largeDecoding.value = false
                _isLargeImage.value = false
                clearZoomForProjectChange()
                val loaded = withContext(Dispatchers.IO) { database.projectDao().getById(id) }
                _project.value = loaded
                _params.value = loaded?.toEditParams() ?: EditParams.DEFAULT
                _selectedMaskId.value = _params.value.masks.lastOrNull()?.id
                _selectedRetouchId.value = _params.value.retouch.lastOrNull()?.id
                // M12: prime cached heuristic fields into memory so reopen and
                // export reuse them with no recompute.
                primeAiFields(_params.value)
                undoStack.clear()
                redoStack.clear()
                syncUndoRedo()
                if (loaded?.photoUri != null) {
                    val flags = withContext(Dispatchers.IO) { readPerformanceFlags() }
                    gpuEnabled = flags.first
                    previewMaxDim = flags.second
                    useFullDevelop = withContext(Dispatchers.IO) { readRawDevelopFlag() }
                    _isDevelopedRaw.value = false
                    _rawRecipe.value = com.lumina.studio.core.render.RawRecipe()
                    baseBitmap = withContext(Dispatchers.IO) { decodeBase(loaded.photoUri, previewMaxDim) }
                    ensureActive()
                    _isDevelopedRaw.value = withContext(Dispatchers.IO) {
                        probeDevelopedRaw(loaded.photoUri)
                    }
                    preloadImportedLuts()
                }
                renderPreview()
                _loading.value = false
            } catch (e: Exception) {
                Log.w("EditorViewModel", "project load failed: ${e.message}")
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
        } catch (e: Exception) {
            Log.w("EditorViewModel", "performance flags unreadable, using defaults")
            true to PreviewRenderer.MAX_PREVIEW_DIM
        }
    }

    private suspend fun readRawDevelopFlag(): Boolean {
        return try {
            val repo = SettingsRepository(getApplication())
            repo.rawQuality.first().trim().equals("High", ignoreCase = true)
        } catch (e: Exception) {
            Log.w("EditorViewModel", "RAW quality unreadable, assuming High")
            true
        }
    }

    private suspend fun preloadImportedLuts() {
        try {
            val presets = database.presetDao().observePresets().first()
            withContext(Dispatchers.IO) {
                com.lumina.studio.core.lut.LutRehydrator.rehydrate(
                    presets,
                    com.lumina.studio.core.data.store.ProjectStore.lutsDir(getApplication())
                )
            }
        } catch (_: Exception) {
        }
    }

    fun applyExternalParams(params: EditParams, historyTag: String? = EditHistoryLog.EDIT) {
        val current = _params.value
        if (current == params) return
        pushUndo(current)
        redoStack.clear()
        _params.value = params
        syncUndoRedo()
        renderPreview()
        schedulePersist(historyTag)
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
            schedulePersist(EditHistoryLog.PRESET)
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
        schedulePersist(EditHistoryLog.PRESET)
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
        } catch (_: OutOfMemoryError) {
            // M16: huge full-res decode refuses gracefully (export shows
            // "could not decode") instead of crashing.
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private fun probeDevelopedRaw(pathOrUri: String): Boolean {
        return try {
            if (!isDngPath(pathOrUri)) return false
            RenderBackends.raw(getApplication()).isDevelopedRaw(RenderSource.of(pathOrUri))
        } catch (_: Exception) {
            false
        }
    }

    private fun isDngPath(pathOrUri: String): Boolean {
        return try {
            val lower = pathOrUri.substringAfterLast('.', "").substringBefore('?')
                .lowercase(java.util.Locale.US)
            lower == "dng"
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeFull(pathOrUri: String): Bitmap? {
        return try {
            if (useFullDevelop && isDngPath(pathOrUri)) {
                val developed = try {
                    RenderBackends.raw(getApplication()).develop(
                        RenderSource.of(pathOrUri), 0, _rawRecipe.value
                    )
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (developed != null) return developed
            }
            val file = File(pathOrUri)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                // Same chokepoint as previews: normalize to orientation 1 so edits,
                // crop and export all see DISPLAYED pixels. Original file untouched.
                ImageOrientation.normalizeBitmap(decoded, ImageOrientation.orientationOf(file))
            } else {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(pathOrUri.toUri())?.use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (_: OutOfMemoryError) {
            // M16: full-res decode OOM refuses gracefully (never a crash).
            null
        } catch (e: Exception) {
            // M11 (§39): never swallow cancellation on export decode paths.
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private fun decodeBase(pathOrUri: String, maxDim: Int = previewMaxDim): Bitmap? {
        return try {
            if (useFullDevelop && isDngPath(pathOrUri)) {
                val developed = try {
                    RenderBackends.raw(getApplication()).develop(
                        RenderSource.of(pathOrUri), maxDim, _rawRecipe.value
                    )
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (developed != null) return developed
            }
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
                    appDecoder().decode(RenderSource.File(file.absolutePath), maxDim)
                } finally {
                    if (large) _largeDecoding.value = false
                }
            } else {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(pathOrUri.toUri())?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: OutOfMemoryError) {
            // M16: preview-decode OOM refuses gracefully (blank preview +
            // retry affordance) instead of crashing; the flag reset below
            // keeps the "Processing large image…" indicator honest.
            _largeDecoding.value = false
            null
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
        if (_isDevelopedRaw.value) {
            try {
                val gains = com.lumina.studio.core.raw.DngDevelop.wbGainsFromPickerPixel(argb)
                val current = _rawRecipe.value
                val next = current.copy(
                    tempGain = gains[0].coerceIn(0.2f, 5f),
                    tintGain = gains[1].coerceIn(0.2f, 5f)
                )
                if (next != current) updateRawRecipe(next)
            } catch (_: Exception) {
            }
        }
    }

    fun updateRawRecipe(recipe: com.lumina.studio.core.render.RawRecipe) {
        val clamped = recipe.copy(
            exposureEv = recipe.exposureEv.coerceIn(-5f, 5f),
            tempGain = recipe.tempGain.coerceIn(0.2f, 5f),
            tintGain = recipe.tintGain.coerceIn(0.2f, 5f)
        )
        if (_rawRecipe.value == clamped) return
        if (!_isDevelopedRaw.value) {
            _rawRecipe.value = clamped
            return
        }
        _rawRecipe.value = clamped
        redevelopRaw()
    }

    fun resetRawRecipe() {
        updateRawRecipe(com.lumina.studio.core.render.RawRecipe())
    }

    private var rawJob: Job? = null

    private fun redevelopRaw() {
        val path = _project.value?.photoUri ?: return
        rawJob?.cancel()
        rawJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val developed = decodeBase(path, previewMaxDim) ?: return@launch
                ensureActive()
                val old = baseBitmap
                baseBitmap = developed
                try {
                    if (old != null && old !== developed && !old.isRecycled) old.recycle()
                } catch (_: Exception) {
                }
                renderPreview()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
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

    fun selectGradeZone(zone: GradeZone) {
        _selectedGradeZone.value = zone
    }

    fun updateGradeZone(zone: GradeZone, adjust: GradeAdjust) {
        val current = _params.value
        val next = current.withGrade(zone, adjust)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updateGradeHue(zone: GradeZone, value: Float) {
        updateGradeZone(zone, _params.value.getGrade(zone).copy(hue = GradeAdjust.wrapHue(value)))
    }

    fun updateGradeSat(zone: GradeZone, value: Float) {
        updateGradeZone(zone, _params.value.getGrade(zone).copy(sat = value.coerceIn(0f, 100f)))
    }

    fun updateGradeLum(zone: GradeZone, value: Float) {
        updateGradeZone(zone, _params.value.getGrade(zone).copy(lum = value.coerceIn(-100f, 100f)))
    }

    fun resetGradeZone(zone: GradeZone) {
        val current = _params.value
        val next = current.resetGradeZone(zone)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updateGradeBlending(value: Float) {
        val current = _params.value
        val next = current.withGradeBlending(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updateGradeBalance(value: Float) {
        val current = _params.value
        val next = current.withGradeBalance(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun resetGradeAll() {
        val current = _params.value
        if (current.isGradeDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetGradeAll()
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun beginGradeEdit() {
        pushUndo(_params.value)
        redoStack.clear()
        syncUndoRedo()
    }

    fun moveGradeZoneLive(zone: GradeZone, adjust: GradeAdjust) {
        val current = _params.value
        val next = current.withGrade(zone, adjust)
        if (next == current) return
        _params.value = next
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun setPointEnabled(enabled: Boolean) {
        val current = _params.value
        val next = current.withPointEnabled(enabled)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun setPointEyedropperArmed(armed: Boolean) {
        _pointEyedropperArmed.value = armed
    }

    fun pointEyedropperPick(argb: Int) {
        val hue = GradeHsl.argbToHueDeg(argb)
        val current = _params.value
        val next = current.withPointSample(argb, hue)
        _pointEyedropperArmed.value = false
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updatePointHueCenter(value: Float) {
        val current = _params.value
        val next = current.withPointHueCenter(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updatePointHueRange(value: Float) {
        val current = _params.value
        val next = current.withPointHueRange(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updatePointSat(value: Float) {
        val current = _params.value
        val next = current.withPointSat(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun updatePointLum(value: Float) {
        val current = _params.value
        val next = current.withPointLum(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun resetPointColor() {
        val current = _params.value
        if (current.pointColor == com.lumina.studio.core.edit.PointColorParams()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetPointColor()
        syncUndoRedo()
        renderPreview()
        schedulePersist(EditHistoryLog.COLOR)
    }

    fun setShowPointAffected(show: Boolean) {
        _showPointAffected.value = show
        if (show) {
            refreshPointColorMask()
        } else {
            clearPointColorMask()
        }
    }

    private fun refreshPointColorMask() {
        viewModelScope.launch(Dispatchers.Default) {
            val src = _preview.value ?: baseBitmap ?: return@launch
            if (src.isRecycled) return@launch
            val mask = try {
                PreviewRenderer.buildPointColorMask(src, _params.value)
            } catch (_: Exception) {
                null
            }
            val old = _pointColorMask.value
            _pointColorMask.value = mask
            recycleMaskBitmap(old)
        }
    }

    private fun clearPointColorMask() {
        val old = _pointColorMask.value
        _pointColorMask.value = null
        recycleMaskBitmap(old)
    }

    private fun recycleMaskBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    fun autoLight() {
        viewModelScope.launch {
            val suggestion = withContext(Dispatchers.Default) {
                val base = baseBitmap ?: return@withContext null
                if (runCatching { base.isRecycled }.getOrDefault(true)) return@withContext null
                try {
                    AutoLevels.suggest(PreviewRenderer.computeHistogram(base))
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            val current = _params.value
            val next = current.copy(
                exposure = AdjustControl.EXPOSURE.clamp(suggestion.exposure),
                contrast = AdjustControl.CONTRAST.clamp(suggestion.contrast)
            )
            if (next == current) return@launch
            pushUndo(current)
            redoStack.clear()
            _params.value = next
            syncUndoRedo()
            renderPreview()
            schedulePersist(EditHistoryLog.ADJUST)
        }
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

    fun setMaskOp(id: String, op: com.lumina.studio.core.edit.MaskOp) =
        updateMask(id) { it.withOp(op) }

    fun setMaskSaturation(id: String, value: Float) = updateMask(id) { it.withSaturation(value) }

    fun setMaskClarity(id: String, value: Float) = updateMask(id) { it.withClarity(value) }

    fun setMaskBlur(id: String, value: Float) = updateMask(id) { it.withBlur(value) }

    fun setMaskHueCenter(id: String, value: Float) = updateMask(id) { it.withHueCenter(value) }

    fun setMaskHueRange(id: String, value: Float) = updateMask(id) { it.withHueRange(value) }

    fun setMaskLumaLo(id: String, value: Float) = updateMask(id) { it.withLumaLo(value) }

    fun setMaskLumaHi(id: String, value: Float) = updateMask(id) { it.withLumaHi(value) }

    fun setMaskLumaFeather(id: String, value: Float) = updateMask(id) { it.withLumaFeather(value) }

    fun armMaskSample(id: String?) {
        if (id != null && _params.value.getMask(id) == null) return
        _maskSampleArmedId.value = id
    }

    fun maskSamplePick(argb: Int) {
        val id = _maskSampleArmedId.value ?: return
        val hue = com.lumina.studio.core.edit.GradeHsl.argbToHueDeg(argb)
        val current = _params.value
        val next = current.updateMask(id) {
            it.withHueCenter(hue).withSampledRgb(argb)
        }
        _maskSampleArmedId.value = null
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    /**
     * M12 heuristic select (§§24-25): "Select subject"/"Select sky" flow.
     * Compute (or disk-cache hit) runs on Default and is cancellable via
     * [cancelAiSelect]; the mask-row append stays on the Main scope like
     * every other mutator, so undo/persist ordering is unchanged. A second
     * tap for the same source reuses the existing row (no duplicate masks,
     * no recompute). Failures surface the honest [AiSelectOutcome] message
     * plus the manual-mask fallback — never a silent empty mask.
     */
    fun selectAiSubject() = requestAiMask(MaskTool.AI_SUBJECT)

    fun selectAiSky() = requestAiMask(MaskTool.AI_SKY)

    fun consumeAiMessage() {
        _aiMessage.value = null
    }

    fun cancelAiSelect() {
        aiJob?.cancel()
        aiJob = null
        _aiWorkingKind.value = null
    }

    fun requestAiMask(tool: MaskTool) {
        if (tool != MaskTool.AI_SUBJECT && tool != MaskTool.AI_SKY) return
        if (aiJob?.isActive == true) return
        val projectIdValue = projectId
        if (projectIdValue.isNullOrBlank()) {
            _aiMessage.value = AiSelectOutcome.Failed("no project open").userMessage()
            return
        }
        if (_params.value.masks.size >= EditParams.MAX_MASKS) {
            _aiMessage.value = AiSelectOutcome.Failed(
                "mask limit reached (${EditParams.MAX_MASKS}) — delete a mask first"
            ).userMessage()
            return
        }
        val kindLabel = if (tool == MaskTool.AI_SKY) AiMaskCache.KIND_SKY else AiMaskCache.KIND_SUBJECT
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _aiWorkingKind.value = kindLabel
            _aiMessage.value = null
            try {
                val built = withContext(Dispatchers.Default) {
                    buildAiField(projectIdValue, tool, kindLabel)
                }
                when (built) {
                    is AiBuild.Ready -> {
                        val current = _params.value
                        if (current.masks.size >= EditParams.MAX_MASKS) {
                            _aiMessage.value = AiSelectOutcome.Failed(
                                "mask limit reached (${EditParams.MAX_MASKS}) — delete a mask first"
                            ).userMessage()
                        } else {
                            val reuse = current.masks.firstOrNull {
                                it.tool == tool && it.cacheKey == built.cacheKey
                            }
                            if (reuse != null) {
                                _selectedMaskId.value = reuse.id
                                _aiMessage.value = "Already added — reused the cached selection."
                            } else {
                                pushUndo(current)
                                redoStack.clear()
                                val next = current.addAiMask(tool, built.cacheKey)
                                _params.value = next
                                _selectedMaskId.value = next.masks.lastOrNull()?.id
                                syncUndoRedo()
                                renderPreview()
                                schedulePersist()
                                _aiMessage.value =
                                    AiSelectOutcome.Ready(built.cacheKey).userMessage()
                            }
                        }
                    }
                    is AiBuild.Outcome -> _aiMessage.value = built.outcome.userMessage()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _aiMessage.value = AiSelectOutcome.Failed("unexpected error").userMessage()
            } finally {
                _aiWorkingKind.value = null
            }
        }
    }

    private suspend fun buildAiField(
        projectIdValue: String,
        tool: MaskTool,
        kindLabel: String
    ): AiBuild {
        val photoUri = _project.value?.photoUri
        val analysis = _preview.value ?: baseBitmap
        if (photoUri.isNullOrBlank() || analysis == null ||
            runCatching { analysis.isRecycled }.getOrDefault(true)
        ) {
            return AiBuild.Outcome(AiSelectOutcome.Failed("no image loaded"))
        }
        val metadataDir = try {
            ProjectStore.pathsFor(getApplication(), projectIdValue).metadataDir
        } catch (_: Exception) {
            return AiBuild.Outcome(AiSelectOutcome.Failed("project storage unavailable"))
        }
        val srcFile = runCatching { File(photoUri) }.getOrNull()
        val length = if (srcFile != null && srcFile.isFile) srcFile.length() else -1L
        val lastModified = if (srcFile != null && srcFile.isFile) srcFile.lastModified() else -1L
        val hash = AiMaskCache.sourceHash(photoUri, length, lastModified)
        val scaled = AiBitmaps.analysisBitmap(analysis)
            ?: return AiBuild.Outcome(AiSelectOutcome.Failed("could not read preview"))
        try {
            val w = scaled.width
            val h = scaled.height
            if (w <= 0 || h <= 0) {
                return AiBuild.Outcome(AiSelectOutcome.Failed("could not read preview"))
            }
            val cacheKey = AiMaskCache.cacheKey(kindLabel, hash, w, h)
            if (AiMaskFieldStore.get(cacheKey) != null) return AiBuild.Ready(cacheKey)
            AiMaskCache.load(AiMaskCache.cacheFile(metadataDir, cacheKey))?.let {
                AiMaskFieldStore.put(cacheKey, it.mask, it.width, it.height)
                return AiBuild.Ready(cacheKey)
            }
            val argb = AiBitmaps.argbOf(scaled)
                ?: return AiBuild.Outcome(AiSelectOutcome.Failed("could not read preview"))
            currentCoroutineContext().ensureActive()
            val field = if (tool == MaskTool.AI_SKY) aiProcessor.skyMask(argb, w, h)
            else aiProcessor.subjectMask(argb, w, h)
            val mapped = AiSelectMessages.fromProcessorResult(field, cacheKey, kindLabel)
            if (mapped !is AiSelectOutcome.Ready || field == null) {
                return AiBuild.Outcome(mapped)
            }
            AiMaskFieldStore.put(cacheKey, field, w, h)
            // Disk-cache failure is non-fatal: the in-memory field still
            // serves this session; next open recomputes.
            AiMaskCache.save(AiMaskCache.cacheFile(metadataDir, cacheKey), field, w, h)
            return AiBuild.Ready(cacheKey)
        } finally {
            try {
                if (scaled !== analysis && !scaled.isRecycled) scaled.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun primeAiFields(params: EditParams) {
        val id = projectId
        val aiMasks = params.masks.filter { it.isAiTool() && !it.cacheKey.isNullOrBlank() }
        if (id.isNullOrBlank() || aiMasks.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadataDir = ProjectStore.pathsFor(getApplication(), id).metadataDir
                for (mask in aiMasks) {
                    val key = mask.cacheKey ?: continue
                    if (AiMaskFieldStore.get(key) != null) continue
                    ensureActive()
                    AiMaskCache.load(AiMaskCache.cacheFile(metadataDir, key))?.let {
                        AiMaskFieldStore.put(key, it.mask, it.width, it.height)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun setPerspectiveV(value: Float) {
        val current = _params.value
        val next = current.withPerspectiveV(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setPerspectiveH(value: Float) {
        val current = _params.value
        val next = current.withPerspectiveH(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setCustomAspect(w: Float, h: Float) {
        val current = _params.value
        val next = current.withCustomAspect(w, h)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setVignetteCorr(value: Float) {
        val current = _params.value
        val next = current.withVignetteCorr(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setCaShift(value: Float) {
        val current = _params.value
        val next = current.withCaShift(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setDistortion(value: Float) {
        val current = _params.value
        val next = current.withDistortion(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetOptics() {
        val current = _params.value
        if (current.isOpticsDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetOptics()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

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

    fun setRetouchMode(mode: RetouchKind) {
        _retouchMode.value = mode
    }

    fun setRetouchPlaceArmed(armed: Boolean) {
        _retouchPlaceArmed.value = armed
    }

    fun selectRetouch(id: String?) {
        if (id != null && _params.value.getRetouch(id) == null) return
        _selectedRetouchId.value = id
    }

    fun placeRetouchAt(cx: Float, cy: Float) {
        val current = _params.value
        if (current.retouch.size >= RetouchOp.MAX_OPS) return
        pushUndo(current)
        redoStack.clear()
        val next = current.addRetouchAt(_retouchMode.value, cx, cy)
        _params.value = next
        _selectedRetouchId.value = next.retouch.lastOrNull()?.id
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun removeRetouch(id: String) {
        val current = _params.value
        if (current.getRetouch(id) == null) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.removeRetouch(id)
        if (_selectedRetouchId.value == id) {
            _selectedRetouchId.value = _params.value.retouch.lastOrNull()?.id
        }
        if (_cloneSourceArmedId.value == id) _cloneSourceArmedId.value = null
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun updateRetouch(id: String, transform: (RetouchOp) -> RetouchOp) {
        val current = _params.value
        val next = current.updateRetouch(id, transform)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setRetouchCenter(id: String, x: Float, y: Float) =
        updateRetouch(id) { it.withCenter(x, y) }

    fun setRetouchRadius(id: String, radius: Float) =
        updateRetouch(id) { it.withRadius(radius) }

    fun setRetouchFeather(id: String, feather: Float) =
        updateRetouch(id) { it.withFeather(feather) }

    fun setRetouchOpacity(id: String, opacity: Float) =
        updateRetouch(id) { it.withOpacity(opacity) }

    fun beginRetouchDrag() {
        pushUndo(_params.value)
        redoStack.clear()
        syncUndoRedo()
    }

    fun moveRetouchCenterLive(id: String, x: Float, y: Float) {
        val current = _params.value
        val next = current.updateRetouch(id) { it.withCenter(x, y) }
        if (next == current) return
        _params.value = next
        renderPreview()
        schedulePersist()
    }

    fun armCloneSource(id: String?) {
        if (id != null && _params.value.getRetouch(id) == null) return
        _cloneSourceArmedId.value = id
    }

    fun setCloneSource(id: String, sx: Float, sy: Float) {
        val current = _params.value
        val next = current.updateRetouch(id) { it.withSource(sx, sy) }
        _cloneSourceArmedId.value = null
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetRetouch() {
        val current = _params.value
        if (current.isRetouchDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.clearRetouch()
        _selectedRetouchId.value = null
        _cloneSourceArmedId.value = null
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setLensFocusArmed(armed: Boolean) {
        _lensFocusArmed.value = armed
    }

    fun setLensFocus(x: Float, y: Float) {
        val current = _params.value
        val next = current.withLensFocus(x, y)
        _lensFocusArmed.value = false
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setLensAmount(value: Float) {
        val current = _params.value
        val next = current.withLensAmount(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setLensTransition(value: Float) {
        val current = _params.value
        val next = current.withLensTransition(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setLensFocusRadius(value: Float) {
        val current = _params.value
        val next = current.withLensFocusRadius(value)
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        _params.value = next
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun resetLensBlur() {
        val current = _params.value
        if (current.isLensBlurDefault()) return
        pushUndo(current)
        redoStack.clear()
        _params.value = current.resetLensBlur()
        syncUndoRedo()
        renderPreview()
        schedulePersist()
    }

    fun setShowLensDepth(show: Boolean) {
        _showLensDepth.value = show
        if (show) {
            refreshLensDepthPreview()
        } else {
            clearLensDepthPreview()
        }
    }

    private fun refreshLensDepthPreview() {
        viewModelScope.launch(Dispatchers.Default) {
            val src = _preview.value ?: baseBitmap ?: return@launch
            if (src.isRecycled) return@launch
            val depth = try {
                PreviewRenderer.buildLensDepthPreview(src, _params.value.lensBlur)
            } catch (_: Exception) {
                null
            }
            val old = _lensDepthPreview.value
            _lensDepthPreview.value = depth
            recycleMaskBitmap(old)
        }
    }

    private fun clearLensDepthPreview() {
        val old = _lensDepthPreview.value
        _lensDepthPreview.value = null
        recycleMaskBitmap(old)
    }

    fun setDustSensitivity(value: Float) {
        _dustSensitivity.value = value.coerceIn(0f, 100f)
    }

    fun scanDust() {
        viewModelScope.launch(Dispatchers.Default) {
            val src = _preview.value ?: baseBitmap ?: return@launch
            if (src.isRecycled) return@launch
            val found = try {
                PreviewRenderer.detectDustCandidates(src, _dustSensitivity.value)
            } catch (_: Exception) {
                emptyList()
            }
            _dustCandidates.value = found
        }
    }

    fun toggleDustConfirmed(id: String) {
        val current = _dustCandidates.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = current.toMutableList()
        val candidate = updated[index]
        updated[index] = candidate.copy(confirmed = !candidate.confirmed)
        _dustCandidates.value = updated
    }

    fun removeDustCandidate(id: String) {
        val current = _dustCandidates.value
        if (current.none { it.id == id }) return
        _dustCandidates.value = current.filterNot { it.id == id }
    }

    fun clearDustCandidates() {
        if (_dustCandidates.value.isEmpty()) return
        _dustCandidates.value = emptyList()
    }

    fun healConfirmedDust() {
        val confirmed = _dustCandidates.value.filter { it.confirmed }
        if (confirmed.isEmpty()) return
        val current = _params.value
        val room = RetouchOp.MAX_OPS - current.retouch.size
        if (room <= 0) return
        pushUndo(current)
        redoStack.clear()
        var next = current
        for (candidate in confirmed.take(room)) {
            if (next.retouch.size >= RetouchOp.MAX_OPS) break
            var added = next.addRetouchAt(
                RetouchKind.HEAL, candidate.cx, candidate.cy
            )
            val id = added.retouch.lastOrNull()?.id
            if (id != null) {
                added = added.updateRetouch(id) {
                    it.withRadius((candidate.radius * 3f).coerceIn(RetouchOp.MIN_RADIUS, 0.2f))
                }
            }
            next = added
        }
        _params.value = next
        _selectedRetouchId.value = next.retouch.lastOrNull()?.id
        _dustCandidates.value = _dustCandidates.value.filterNot { it.confirmed }
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
        val generation = previewGenerations.next()
        // M16 (§54): refuse the superseded generation at backend entry when
        // it has not started yet (stale-drop below stays authoritative for
        // already-running blocking Bitmap work).
        val supersededPreview = lastPreviewBackendGen
        lastPreviewBackendGen = generation
        if (supersededPreview != 0L) {
            runCatching { RenderBackends.cpu().cancel(supersededPreview) }
            runCatching { RenderBackends.gpu().cancel(supersededPreview) }
        }
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val base = baseBitmap ?: return@launch
            val params = _params.value
            val lut = LutRegistry.resolve(params.presetId)
            val renderStart = SystemClock.elapsedRealtime()
            val (out, backendLabel) = gradeThroughBackend(
                base, params, lut, RenderTarget.Preview(previewMaxDim), generation
            )
            val renderMs = SystemClock.elapsedRealtime() - renderStart
            // M16 (buffer discipline + timing): stale renders are recycled,
            // the replaced preview is recycled (peak = 1 live preview-size
            // bitmap, never 2), and every render logs to LuminaRender for
            // on-device measurement (see PERFORMANCE.md).
            if (previewGenerations.isStale(generation)) {
                if (out != null && out !== base) {
                    runCatching { if (!out.isRecycled) out.recycle() }
                }
                return@launch
            }
            val old = _preview.value
            _preview.value = out
            if (old !== out && old !== base) {
                runCatching { if (old != null && !old.isRecycled) old.recycle() }
            }
            runCatching {
                val frame = out ?: base
                val dims = if (frame != null) "${frame.width}×${frame.height}" else "—"
                Log.d(
                    TAG_RENDER,
                    "preview $dims ${renderMs}ms backend=$backendLabel rev=${_paramsRevision.value}"
                )
                com.lumina.studio.core.util.DebugDiagnostics.reportRender(
                    backendLabel, appDecoder().name, renderMs, dims, _paramsRevision.value
                )
            }
            // Phase 4B: histogram auto-recompute on every render runs only with GPU
            // acceleration ON. When OFF, the histogram updates solely via explicit
            // requestHistogram()/toggleHistogram() calls.
            // M5 (§51): the histogram reads the RENDERED preview bitmap
            // (post-pipeline) via computeHistogram, never the source — except
            // when the render itself failed and only base exists. Auto updates
            // are debounced (scheduleHistogram) so slider ticks never recompute
            // per tick; explicit requests stay immediate.
            if (_showHistogram.value && PreviewRenderer.shouldAutoHistogram(gpuEnabled)) {
                val graded = out ?: base
                if (graded != null) scheduleHistogram(graded)
            }
            // M6 point-color affected overlay: rebuilt from the latest rendered
            // frame while the toggle is on (cheap small-bitmap approx); cleared
            // otherwise so a stale selection never lingers.
            if (_showPointAffected.value && !params.isPointColorDefault()) {
                val graded = out ?: base
                val mask = if (graded != null && !graded.isRecycled) {
                    try {
                        PreviewRenderer.buildPointColorMask(graded, params)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val old = _pointColorMask.value
                _pointColorMask.value = mask
                recycleMaskBitmap(old)
            } else if (_pointColorMask.value != null) {
                val old = _pointColorMask.value
                _pointColorMask.value = null
                recycleMaskBitmap(old)
            }
            // M8 lens depth overlay: rebuilt from the latest rendered frame
            // while the toggle is on; cleared otherwise so a stale field
            // never lingers.
            if (_showLensDepth.value && !params.isLensBlurDefault()) {
                val graded = out ?: base
                val depth = if (graded != null && !graded.isRecycled) {
                    try {
                        PreviewRenderer.buildLensDepthPreview(graded, params.lensBlur)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val old = _lensDepthPreview.value
                _lensDepthPreview.value = depth
                recycleMaskBitmap(old)
            } else if (_lensDepthPreview.value != null) {
                val old = _lensDepthPreview.value
                _lensDepthPreview.value = null
                recycleMaskBitmap(old)
            }
        }
        if (_fullscreen.value) scheduleFullscreenRender(immediate = false)
        refreshZoomTileForParamsChange()
    }

    private fun scheduleFullscreenRender(immediate: Boolean) {
        if (!_fullscreen.value) return
        fullscreenJob?.cancel()
        val generation = fullscreenGenerations.next()
        // M16 (§54): same superseded-generation refusal as the preview path.
        val supersededFullscreen = lastFullscreenBackendGen
        lastFullscreenBackendGen = generation
        if (supersededFullscreen != 0L) {
            runCatching { RenderBackends.cpu().cancel(supersededFullscreen) }
            runCatching { RenderBackends.gpu().cancel(supersededFullscreen) }
        }
        fullscreenJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!immediate) delay(FULLSCREEN_DEBOUNCE_MS)
                val path = _project.value?.photoUri ?: return@launch
                val params = _params.value
                val lut = LutRegistry.resolve(params.presetId)
                val decodeStart = SystemClock.elapsedRealtime()
                val decoded = withContext(Dispatchers.IO) { decodeFullscreenBitmap(path) }
                    ?: return@launch
                ensureActive()
                val (rendered, _) = gradeThroughBackend(
                    decoded, params, lut,
                    RenderTarget.Fullscreen(FULLSCREEN_MAX_DIM), generation
                )
                ensureActive()
                Log.d(
                    TAG_RENDER,
                    "fullscreen ${decoded.width}×${decoded.height} " +
                        "decode+render=${SystemClock.elapsedRealtime() - decodeStart}ms rev=$generation"
                )
                if (rendered == null) {
                    try {
                        if (!decoded.isRecycled) decoded.recycle()
                    } catch (_: Exception) {
                    }
                    return@launch
                }
                // §53: same stale-drop as the preview path — a superseded
                // fullscreen render must never overwrite the current one.
                if (fullscreenGenerations.isStale(generation)) {
                    try {
                        if (!rendered.isRecycled) rendered.recycle()
                    } catch (_: Exception) {
                    }
                    if (rendered !== decoded) {
                        try {
                            if (!decoded.isRecycled) decoded.recycle()
                        } catch (_: Exception) {
                        }
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
                    appDecoder().decode(RenderSource.File(file.absolutePath), FULLSCREEN_MAX_DIM)
                }.getOrNull()
                if (hiRes != null) return hiRes
                runCatching {
                    appDecoder().decode(
                        RenderSource.File(file.absolutePath),
                        PreviewRenderer.MAX_PREVIEW_DIM
                    )
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
                if (file.exists()) {
                    appDecoder().decode(
                        RenderSource.File(file.absolutePath),
                        PreviewRenderer.MAX_PREVIEW_DIM
                    )
                } else {
                    null
                }
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
        // Viewport fractions are DISPLAYED (orientation-normalized) coordinates.
        // Map them back to the decoder's RAW pixel space, then re-orient the
        // decoded tile so it matches the preview frame.
        val rawRect = ExifOrientation.mapDisplayRectToRaw(l, t, r, b, w, h, regionOrientation)
        val leftPx = rawRect[0]
        val topPx = rawRect[1]
        val rightPx = rawRect[2]
        val bottomPx = rawRect[3]
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
        val decodedRegion: Bitmap? = try {
            decoder.decodeRegion(rect, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        val raw: Bitmap? = try {
            if (decodedRegion == null) null
            else ImageOrientation.normalizeBitmap(decodedRegion, regionOrientation)
        } catch (_: Exception) {
            decodedRegion
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
        // The params revision captured above is this tile's generation token:
        // a params change bumps _paramsRevision, so a tile that finishes late
        // is dropped below and never overwrites the current viewport (§53).
        val (rendered, _) = gradeThroughBackend(
            raw, paramsSnap, lut,
            RenderTarget.Tile(ZOOM_TILE_MAX_PIXELS.toLong()), revision
        )
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
        runCatching {
            com.lumina.studio.core.util.DebugDiagnostics.reportZoom(
                "${rendered.width}×${rendered.height} rev=$revision ${decodeMs + renderMs}ms"
            )
        }
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
        // Decoder dimensions are RAW pixels; viewport math uses DISPLAYED pixels
        // and maps back via ExifOrientation.mapDisplayRectToRaw per tile.
        regionWidth = dw
        regionHeight = dh
        regionOrientation = try {
            val file = File(source)
            if (file.exists()) ImageOrientation.orientationOf(file) else 1
        } catch (_: Exception) {
            1
        }
        return created
    }

    private fun closeRegionDecoder() {
        regionSourceKey = null
        regionWidth = 0
        regionHeight = 0
        regionOrientation = 1
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
            histogramJob?.cancel()
        } catch (_: Exception) {
        }
        histogramJob = null
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
        recycleMaskBitmap(_pointColorMask.value)
        _pointColorMask.value = null
        recycleMaskBitmap(_lensDepthPreview.value)
        _lensDepthPreview.value = null
        closeRegionDecoder()
        try {
            _fullscreenPreview.value?.takeIf { !it.isRecycled }?.recycle()
        } catch (_: Exception) {
        }
        _fullscreenPreview.value = null
        try {
            baseBitmap?.takeIf { !it.isRecycled }?.recycle()
        } catch (_: Exception) {
        }
        baseBitmap = null
        try {
            _preview.value?.takeIf { !it.isRecycled }?.recycle()
        } catch (_: Exception) {
        }
        _preview.value = null
        // M15: drop GL context/textures/FBO on owner teardown (lazy re-init
        // on next GPU render; in-flight renders already hold their bitmaps).
        // A shared ComponentCallbacks2 trim hook is attached in init for
        // low-memory teardown between ViewModel lifetimes.
        runCatching { RenderBackends.releaseGpu() }
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

    // M5: debounced auto-histogram for the render path. Superseded ticks are
    // dropped via histogramJob cancel so rapid slider movement recomputes at
    // most once per HISTOGRAM_DEBOUNCE_MS from the latest rendered frame.
    private fun scheduleHistogram(source: Bitmap) {
        histogramJob?.cancel()
        histogramJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(HISTOGRAM_DEBOUNCE_MS)
                _histogram.value = PreviewRenderer.computeHistogram(source)
            } catch (_: Exception) {
            }
        }
    }

    private fun schedulePersist(historyTag: String? = EditHistoryLog.EDIT) {
        if (historyTag != null) pendingHistoryTags.add(historyTag)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val current = _project.value ?: return@launch
            val params = _params.value
            val now = System.currentTimeMillis()
            val updated = current.withEditParams(params).copy(updatedAt = now)
            val rows = withContext(Dispatchers.IO) {
                database.projectDao().updateEditState(
                    id = current.id,
                    updatedAt = updated.updatedAt,
                    presetName = updated.presetName,
                    editParamsJson = updated.editParamsJson
                )
            }
            // Autosave must never recreate a project that was deleted while
            // the debounce timer was waiting. Room UPDATE returns 0 when the
            // row no longer exists, so discard the pending history as well.
            if (rows == 0) {
                pendingHistoryTags.clear()
                return@launch
            }
            _project.value = updated
            val tags = pendingHistoryTags.toList()
            pendingHistoryTags.clear()
            if (tags.isNotEmpty()) {
                val projectId = current.id
                withContext(Dispatchers.IO) {
                    for (tag in tags) EditHistoryLog.log(database, projectId, tag)
                }
            }
        }
    }

    companion object {
        const val TAG_ZOOM_TILE = "EditorZoomTile"
        // M16: render timing tag for on-device measurement. Preview renders
        // log `preview WxH Nms backend=... rev=...`, fullscreen renders log
        // decode+render, tiles keep TAG_ZOOM_TILE. Filter:
        // `adb logcat -s LuminaRender EditorZoomTile`.
        const val TAG_RENDER = "LuminaRender"
        const val MAX_STACK = 50
        const val PERSIST_DEBOUNCE_MS = 300L
        const val HISTOGRAM_DEBOUNCE_MS = 150L
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
