package com.lumina.studio.core.render.gpu

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLUtils
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.render.MemoryBudget
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.render.RenderBackend
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.cpu.CpuRenderBackend
import com.lumina.studio.core.render.cpu.RenderBackends
import com.lumina.studio.core.render.qualityForTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlesUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * M15 GLES3 render backend (§11, real GPU, CPU fallback).
 *
 * Own EGL14 pbuffer context on a dedicated daemon thread; source upload as a
 * 2D texture, single-program fragment-shader color slice, FBO render target,
 * RGBA readback. Context + program + FBO are REUSED across renders
 * (create-per-render-context is forbidden); only small param textures
 * (LUT/curves, bounded KBs) are re-uploaded per render and deleted before
 * re-creation, so renders never leak.
 *
 * Hybrid pipeline (see [GpuStageMap]): the shader runs the contiguous color
 * slice LUT->adjusts->global sat->per-color HSL->curves->point->grading->
 * vignette->micro in exact CPU order; optics remap, luma NR, masks, retouch,
 * lens blur and geometry run on the CPU readback via the SAME
 * PreviewRenderer stage functions the CPU path uses.
 *
 * Fallback: ANY GLES failure (no EGL, shader compile error, OOM,
 * unsupported, timeout, interrupt) tears the GL state down (next render
 * re-inits lazily) and returns the CPU backend result for the same request.
 * Unavailable surfaces only when the CPU fallback also fails. Never crashes,
 * never returns a half-rendered frame.
 *
 * On-device only: CI compiles this file but cannot execute GLES (no
 * emulator); parity screenshots, timing and context-loss recovery need
 * on-device validation.
 *
 * M16 lifecycle audit (§3, verified, no change needed except docs):
 * - Per-render textures (LUT 3D/1D, curves) are created in
 *   renderOnGlLocked and deleted in its `finally` — never leak, even on
 *   shader failure. Reused-across-renders state is bounded: one srcTex, one
 *   fboTex, one FBO, one program, three tiny placeholders.
 * - Low-memory trim hook exists ([attachLowMemoryHook]: teardown on
 *   onLowMemory + TRIM_MEMORY_MODERATE) and ViewModel.onCleared calls
 *   releaseGpu(); EGL/GL objects are lazy re-inits afterwards.
 * - OOM anywhere on the GL thread surfaces as ExecutionException cause ->
 *   teardown + CPU fallback (OomBudget when the CPU also refuses) — never
 *   a crash, never a half-rendered frame.
 * - §54 boundary: future.get() blocking work cannot be preempted by
 *   coroutine cancel; superseded GPU renders are refused at entry via the
 *   `cancelled` set (wired from EditorViewModel) and dropped by the
 *   revision stale-check after.
 */
class GlesBackend(
    private val cpuFallback: RenderBackend<Bitmap>? = null
) : RenderBackend<Bitmap> {

    data class Outcome(val result: RenderResult<Bitmap>, val backendLabel: String)

    @Volatile
    var lastLabel: String = GpuRenderPolicy.CPU_LABEL
        private set

    @Volatile
    var lastFallbackReason: String? = null
        private set

    private val cancelled: MutableSet<Long> =
        Collections.synchronizedSet(LinkedHashSet())

    private val glThread: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "GlesBackend").apply { isDaemon = true }
        }

    private val lock = Any()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfW = 0
    private var surfH = 0
    private var program = 0
    private val uniformLoc = LinkedHashMap<String, Int>()
    private var srcTex = 0
    private var srcTexW = 0
    private var srcTexH = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var placeholderLut = 0
    private var placeholderLut1D = 0
    private var placeholderCurves = 0

    private val hookInstalled = AtomicBoolean(false)

    override fun render(request: RenderRequest<Bitmap>): RenderResult<Bitmap> =
        renderWithLabel(request).result

    fun renderWithLabel(request: RenderRequest<Bitmap>): Outcome {
        val gen = request.generation
        if (gen != CpuRenderBackend.NO_GENERATION && cancelled.remove(gen)) {
            return Outcome(
                RenderResult.Unavailable("cancelled"),
                GpuRenderPolicy.CPU_LABEL
            )
        }
        val src = request.source
        if (runCatching { src.isRecycled }.getOrDefault(true)) {
            return Outcome(
                RenderResult.Unavailable("source recycled"),
                GpuRenderPolicy.CPU_LABEL
            )
        }
        val w = runCatching { src.width }.getOrDefault(0)
        val h = runCatching { src.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) {
            return Outcome(
                RenderResult.Unavailable("empty source"),
                GpuRenderPolicy.CPU_LABEL
            )
        }
        if (MemoryBudget.exceeds(w, h)) {
            return Outcome(
                RenderResult.Unavailable("too large"),
                GpuRenderPolicy.CPU_LABEL
            )
        }
        val params = request.params
        val steps = params.steps
        val useLut = request.lut != null && params.presetId != null &&
            params.presetIntensity > 0f &&
            steps.get(StepKey.PRESET) && steps.get(StepKey.LUT)
        if (!useLut && params.isDefault()) {
            return Outcome(RenderResult.Ok(src), GpuRenderPolicy.CPU_LABEL)
        }
        val future = try {
            glThread.submit(Callable { renderOnGl(request, w, h, useLut) })
        } catch (e: Exception) {
            return cpuOutcome(request, GpuRenderPolicy.failureReason(e))
        }
        val glBitmap = try {
            future.get(GL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            teardown()
            val cause = if (t is ExecutionException) (t.cause ?: t) else t
            return cpuOutcome(request, GpuRenderPolicy.failureReason(cause))
        }
        return try {
            val final = cpuPostStages(glBitmap, request)
            lastLabel = GpuRenderPolicy.GPU_LABEL
            lastFallbackReason = null
            Outcome(RenderResult.Ok(final), GpuRenderPolicy.GPU_LABEL)
        } catch (t: Throwable) {
            runCatching {
                if (!glBitmap.isRecycled) glBitmap.recycle()
            }
            teardown()
            cpuOutcome(request, GpuRenderPolicy.failureReason(t))
        }
    }

    override fun cancel(generation: Long) {
        if (generation == CpuRenderBackend.NO_GENERATION) return
        if (cancelled.size > MAX_TRACKED_CANCELLATIONS) cancelled.clear()
        cancelled.add(generation)
    }

    fun release() {
        teardown()
    }

    fun attachLowMemoryHook(context: Context) {
        if (!hookInstalled.compareAndSet(false, true)) return
        val ok = runCatching {
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    override fun onLowMemory() {
                        teardown()
                    }

                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) teardown()
                    }
                }
            )
            true
        }.getOrDefault(false)
        if (!ok) hookInstalled.set(false)
    }

    private fun cpu(): RenderBackend<Bitmap> = cpuFallback ?: RenderBackends.cpu()

    private fun cpuOutcome(request: RenderRequest<Bitmap>, reason: String): Outcome {
        val result = try {
            cpu().render(request)
        } catch (e: Exception) {
            RenderResult.Unavailable(e.message ?: "render failed")
        } catch (_: OutOfMemoryError) {
            RenderResult.OomBudget
        }
        val label = GpuRenderPolicy.fallbackLabel(reason)
        lastLabel = label
        lastFallbackReason = reason
        return Outcome(result, label)
    }

    /**
     * CPU post segment of the hybrid pipeline, in exact PreviewRenderer tail
     * order: optics-remap (only when a remap exists — the shader already ran
     * vignette-only optics), luma NR (micro zeroed so the GPU matrix is not
     * doubled), masks, retouch, lens blur, geometry last. [glBitmap] is never
     * the request source, so replaced intermediates are recycled safely.
     */
    private fun cpuPostStages(glBitmap: Bitmap, request: RenderRequest<Bitmap>): Bitmap {
        val params = request.params
        val steps = params.steps
        val quality = request.quality ?: qualityForTarget(request.target)
        var current = glBitmap
        fun replace(next: Bitmap) {
            if (next !== current) {
                runCatching {
                    if (!current.isRecycled) current.recycle()
                }
                current = next
            }
        }
        if (!params.optics.isDefault() && steps.get(StepKey.CROP) &&
            (params.optics.distortion != 0f || params.optics.caShift != 0f)
        ) {
            replace(PreviewRenderer.applyOptics(current, params.optics, quality))
        }
        if (!params.isDetailsDefault() && steps.get(StepKey.DETAILS)) {
            replace(
                PreviewRenderer.applyDetails(
                    current, current,
                    params.copy(texture = 0f, clarityAdv = 0f, dehazeAdv = 0f),
                    quality
                )
            )
        }
        if (params.masks.isNotEmpty() && steps.get(StepKey.MASKS)) {
            replace(PreviewRenderer.applyMasks(current, params, quality))
        }
        if (params.retouch.isNotEmpty()) {
            replace(PreviewRenderer.applyRetouch(current, params.retouch, quality))
        }
        if (!params.lensBlur.isDefault()) {
            replace(PreviewRenderer.applyLensBlur(current, params.lensBlur, quality))
        }
        if (!params.crop.isDefault() && steps.get(StepKey.CROP)) {
            replace(PreviewRenderer.cropBitmap(current, params.crop))
        }
        return current
    }

    private fun teardown() {
        synchronized(lock) { teardownLocked() }
    }

    private fun teardownLocked() {
        runCatching {
            if (display !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            }
        }
        if (program != 0) {
            runCatching { GLES30.glDeleteProgram(program) }
            program = 0
        }
        uniformLoc.clear()
        for (id in listOf(srcTex, fboTex, placeholderLut, placeholderLut1D, placeholderCurves)) {
            if (id != 0) runCatching {
                GLES30.glDeleteTextures(1, intArrayOf(id), 0)
            }
        }
        srcTex = 0
        srcTexW = 0
        srcTexH = 0
        fboTex = 0
        fboW = 0
        fboH = 0
        placeholderLut = 0
        placeholderLut1D = 0
        placeholderCurves = 0
        if (fbo != 0) {
            runCatching { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0) }
            fbo = 0
        }
        if (surface !== EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglDestroySurface(display, surface) }
            surface = EGL14.EGL_NO_SURFACE
        }
        surfW = 0
        surfH = 0
        if (context !== EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglDestroyContext(display, context) }
            context = EGL14.EGL_NO_CONTEXT
        }
        config = null
        if (display !== EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglTerminate(display) }
            display = EGL14.EGL_NO_DISPLAY
        }
        runCatching { EGL14.eglReleaseThread() }
    }

    private fun renderOnGl(
        request: RenderRequest<Bitmap>,
        w: Int,
        h: Int,
        useLut: Boolean
    ): Bitmap {
        synchronized(lock) {
            try {
                return renderOnGlLocked(request, w, h, useLut)
            } catch (t: Throwable) {
                teardownLocked()
                throw t
            }
        }
    }

    private fun renderOnGlLocked(
        request: RenderRequest<Bitmap>,
        w: Int,
        h: Int,
        useLut: Boolean
    ): Bitmap {
        ensureInit(w, h)
        val params = request.params
        val steps = params.steps
        uploadSource(request.source)

        var lutTex = 0
        var lut1DTex = 0
        var curveTex = 0
        try {
            var useLutF = 0f
            var lutIs3D = 1f
            var lutSize = 2f
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            var lutIntensity = 0f
            val lut = request.lut
            if (useLut && lut != null) {
                val table = if (request.fullLut) lut else LutRenderer.effectiveTable(lut)
                if (table.is3D) {
                    val bytes = LutTiles.packRgba8(table)
                    val (tw, th) = LutTiles.tileDims(table.size)
                    lutTex = uploadRgba(bytes, tw, th, GLES30.GL_NEAREST)
                    lutIs3D = 1f
                    lutSize = table.size.toFloat()
                } else {
                    val resampled = LutTiles.resample1DTo256(table.data, table.size)
                    lut1DTex = uploadRgba(LutTiles.pack1DRgba8(resampled), 256, 1, GLES30.GL_LINEAR)
                    lutIs3D = 0f
                    lutSize = table.size.toFloat()
                }
                domainMin = table.domainMin.copyOf()
                domainMax = table.domainMax.copyOf()
                lutIntensity = params.presetIntensity.coerceIn(0f, 1f)
                useLutF = 1f
            }

            var useAdjustF = 0f
            var adjustMat = IDENTITY_MAT4
            var adjustOffset = ZERO_VEC3
            if (!params.isAdjustsDefault() && steps.get(StepKey.ADJUSTS)) {
                val scaled = GpuUniforms.scaleOffsetsToUnit(PreviewRenderer.buildMatrix(params).array)
                adjustMat = GpuUniforms.mat4FromColorMatrix(scaled)
                adjustOffset = GpuUniforms.offsetFromColorMatrix(scaled)
                useAdjustF = 1f
            }

            var useSatF = 0f
            var satMat = IDENTITY_MAT4
            var satOffset = ZERO_VEC3
            val satFactor = GpuUniforms.globalSatFactor(params)
            if (!params.isHslDefault() && steps.get(StepKey.COLOR) &&
                (params.globalSat != 0f || params.globalVib != 0f) && satFactor != 1f
            ) {
                val scaled = GpuUniforms.scaleOffsetsToUnit(
                    ColorMatrix().apply { setSaturation(satFactor) }.array
                )
                satMat = GpuUniforms.mat4FromColorMatrix(scaled)
                satOffset = GpuUniforms.offsetFromColorMatrix(scaled)
                useSatF = 1f
            }

            val useHslF =
                if (params.hasPerColorHsl() && steps.get(StepKey.COLOR)) 1f else 0f
            val hslTables = GpuUniforms.hslAdjustArray(params)
            val hslHasLumF =
                if (useHslF > 0.5f && GpuUniforms.hasHslLumAdjust(params)) 1f else 0f

            val useCurvesF =
                if (!params.isCurvesDefault() && steps.get(StepKey.CURVES)) 1f else 0f
            if (useCurvesF > 0.5f) {
                val tables = PreviewRenderer.curveLutsFor(params)
                val bytes = CurveTables.uploadBytes(
                    tables[com.lumina.studio.core.edit.CurveChannel.MASTER],
                    tables[com.lumina.studio.core.edit.CurveChannel.RED],
                    tables[com.lumina.studio.core.edit.CurveChannel.GREEN],
                    tables[com.lumina.studio.core.edit.CurveChannel.BLUE]
                )
                curveTex = uploadRgba(bytes, 256, 1, GLES30.GL_NEAREST)
            }

            val pointU = if (!params.isPointColorDefault() && steps.get(StepKey.COLOR)) {
                GpuUniforms.pointUniform(params.pointColor)
            } else {
                null
            }

            val useGradeF =
                if (!params.isGradeDefault() && steps.get(StepKey.COLOR)) 1f else 0f
            var liftS = ZERO_VEC3
            var liftM = ZERO_VEC3
            var liftH = ZERO_VEC3
            var liftG = ZERO_VEC3
            var gradeBlend = 0f
            var gradeBalance = 0f
            if (useGradeF > 0.5f) {
                val lifts = GpuUniforms.gradeLifts(params.grade)
                liftS = lifts[0]
                liftM = lifts[1]
                liftH = lifts[2]
                liftG = lifts[3]
                gradeBlend = params.grade.blending.coerceIn(0f, 100f) / 100f
                gradeBalance = params.grade.balance
            }

            val hasRemap = params.optics.distortion != 0f || params.optics.caShift != 0f
            val vignette = if (!params.optics.isDefault() && steps.get(StepKey.CROP)) {
                GpuUniforms.vignetteForShader(params.optics.vignetteCorr, hasRemap)
            } else {
                0f
            }

            var useMicroF = 0f
            var microMat = IDENTITY_MAT4
            var microOffset = ZERO_VEC3
            if (!params.isDetailsDefault() && steps.get(StepKey.DETAILS) &&
                (params.texture != 0f || params.clarityAdv != 0f || params.dehazeAdv != 0f)
            ) {
                val scaled = GpuUniforms.scaleOffsetsToUnit(buildMicroMatrix(params).array)
                microMat = GpuUniforms.mat4FromColorMatrix(scaled)
                microOffset = GpuUniforms.offsetFromColorMatrix(scaled)
                useMicroF = 1f
            }

            bindTarget(w, h)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(program)
            setFloat("uUseLut", useLutF)
            setFloat("uLutIs3D", lutIs3D)
            setFloat("uLutSize", lutSize)
            setVec3("uLutDomainMin", domainMin)
            setVec3("uLutDomainMax", domainMax)
            setFloat("uLutIntensity", lutIntensity)
            setFloat("uUseAdjust", useAdjustF)
            setMat4("uAdjustMat", adjustMat)
            setVec3("uAdjustOffset", adjustOffset)
            setFloat("uUseGlobalSat", useSatF)
            setMat4("uSatMat", satMat)
            setVec3("uSatOffset", satOffset)
            setFloat("uUseHsl", useHslF)
            setFloat("uHslHasLum", hslHasLumF)
            for (k in 0..7) {
                setVec4("uHslAdjust[$k]", hslTables[k])
            }
            setFloat("uUseCurves", useCurvesF)
            setFloat("uUsePoint", if (pointU != null) 1f else 0f)
            setVec4("uPoint", pointU ?: floatArrayOf(0f, 60f, 0f, 0f))
            setFloat("uUseGrade", useGradeF)
            setFloat("uGradeBlend", gradeBlend)
            setFloat("uGradeBalance", gradeBalance)
            setVec3("uLiftShadow", liftS)
            setVec3("uLiftMid", liftM)
            setVec3("uLiftHigh", liftH)
            setVec3("uLiftGlobal", liftG)
            setFloat("uVignette", vignette)
            setVec2("uImageSize", floatArrayOf(w.toFloat(), h.toFloat()))
            setFloat("uUseMicro", useMicroF)
            setMat4("uMicroMat", microMat)
            setVec3("uMicroOffset", microOffset)
            bindSampler(GpuShaders.SAMPLER_SRC, 0, srcTex)
            bindSampler(
                GpuShaders.SAMPLER_LUT_TILES, 1,
                if (lutTex != 0) lutTex else placeholderLut
            )
            bindSampler(
                GpuShaders.SAMPLER_LUT_1D, 2,
                if (lut1DTex != 0) lut1DTex else placeholderLut1D
            )
            bindSampler(
                GpuShaders.SAMPLER_CURVES, 3,
                if (curveTex != 0) curveTex else placeholderCurves
            )
            drawQuad()
            checkGl("draw")
            val out = readback(w, h)
            checkGl("readback")
            return out
        } finally {
            for (id in listOf(lutTex, lut1DTex, curveTex)) {
                if (id != 0) runCatching {
                    GLES30.glDeleteTextures(1, intArrayOf(id), 0)
                }
            }
        }
    }

    private fun buildMicroMatrix(params: com.lumina.studio.core.edit.EditParams): ColorMatrix {
        val contrastFactor = (1f + (params.texture * 0.15f + params.clarityAdv * 0.25f + params.dehazeAdv * 0.35f) / 100f)
            .coerceIn(0f, 4f)
        val saturation = (1f + params.dehazeAdv / 300f).coerceIn(0f, 3f)
        val result = ColorMatrix()
        val pivot = (1f - contrastFactor) * 128f
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrastFactor, 0f, 0f, 0f, pivot,
                    0f, contrastFactor, 0f, 0f, pivot,
                    0f, 0f, contrastFactor, 0f, pivot,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        if (saturation != 1f) {
            result.postConcat(ColorMatrix().apply { setSaturation(saturation) })
        }
        return result
    }

    private fun ensureInit(w: Int, h: Int) {
        if (display === EGL14.EGL_NO_DISPLAY) {
            fullEglInit()
            program = linkProgram(GpuShaders.VERTEX_SHADER, GpuShaders.fragmentShader())
            cacheUniformLocations()
            placeholderLut = uploadRgba(ByteArray(2 * 2 * 4) { 0xFF.toByte() }, 2, 2, GLES30.GL_NEAREST)
            placeholderLut1D = uploadRgba(ByteArray(2 * 1 * 4) { 0xFF.toByte() }, 2, 1, GLES30.GL_LINEAR)
            placeholderCurves = uploadRgba(ByteArray(2 * 1 * 4) { 0xFF.toByte() }, 2, 1, GLES30.GL_NEAREST)
            fbo = createFramebuffer()
        }
        if (surface === EGL14.EGL_NO_SURFACE || surfW != w || surfH != h) {
            recreateSurface(w, h)
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw GlesUnavailable("eglMakeCurrent failed")
        }
    }

    private fun fullEglInit() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) {
            throw GlesUnavailable("no EGL display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw GlesUnavailable("eglInitialize failed")
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            runCatching { EGL14.eglTerminate(display) }
            throw GlesUnavailable("no EGL config")
        }
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context === EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglTerminate(display) }
            throw GlesUnavailable("eglCreateContext failed")
        }
        this.display = display
        this.config = configs[0]
        this.context = context
    }

    private fun recreateSurface(w: Int, h: Int) {
        val display = this.display
        val config = this.config
        if (display === EGL14.EGL_NO_DISPLAY || config == null) {
            throw GlesUnavailable("EGL not initialized")
        }
        if (surface !== EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglDestroySurface(display, surface) }
            surface = EGL14.EGL_NO_SURFACE
        }
        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val created = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)
        if (created === EGL14.EGL_NO_SURFACE) {
            throw GlesUnavailable("eglCreatePbufferSurface failed for ${w}x${h}")
        }
        surface = created
        surfW = w
        surfH = h
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw GlesUnavailable("eglMakeCurrent failed after surface recreate")
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) throw GlesUnavailable("glCreateShader failed")
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = runCatching { GLES30.glGetShaderInfoLog(shader) }.getOrDefault("unknown")
            runCatching { GLES30.glDeleteShader(shader) }
            throw GlesUnavailable("shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        var fs = 0
        try {
            fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES30.glCreateProgram()
            if (program == 0) throw GlesUnavailable("glCreateProgram failed")
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = runCatching { GLES30.glGetProgramInfoLog(program) }.getOrDefault("unknown")
                runCatching { GLES30.glDeleteProgram(program) }
                throw GlesUnavailable("program link failed: $log")
            }
            return program
        } finally {
            runCatching { GLES30.glDeleteShader(vs) }
            if (fs != 0) runCatching { GLES30.glDeleteShader(fs) }
        }
    }

    private fun cacheUniformLocations() {
        uniformLoc.clear()
        for (name in GpuShaders.REQUIRED_UNIFORMS) {
            val loc = GLES30.glGetUniformLocation(program, name)
            if (loc < 0 && !name.startsWith("uHslAdjust")) {
                throw GlesUnavailable("uniform not found: $name")
            }
            uniformLoc[name] = loc
        }
        for (k in 0..7) {
            val name = "uHslAdjust[$k]"
            val loc = GLES30.glGetUniformLocation(program, name)
            if (loc < 0) throw GlesUnavailable("uniform not found: $name")
            uniformLoc[name] = loc
        }
        for (sampler in GpuShaders.REQUIRED_SAMPLERS) {
            val loc = GLES30.glGetUniformLocation(program, sampler)
            if (loc < 0) throw GlesUnavailable("sampler not found: $sampler")
            uniformLoc[sampler] = loc
        }
    }

    private fun loc(name: String): Int =
        uniformLoc[name] ?: throw GlesUnavailable("uniform not cached: $name")

    private fun setFloat(name: String, value: Float) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform1f(l, value)
    }

    private fun setVec2(name: String, v: FloatArray) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform2fv(l, 1, v, 0)
    }

    private fun setVec3(name: String, v: FloatArray) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform3fv(l, 1, v, 0)
    }

    private fun setVec4(name: String, v: FloatArray) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform4fv(l, 1, v, 0)
    }

    private fun setMat4(name: String, v: FloatArray) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, v, 0)
    }

    private fun bindSampler(samplerName: String, unit: Int, texture: Int) {
        if (texture == 0) throw GlesUnavailable("no texture for $samplerName")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(loc(samplerName), unit)
    }

    private fun uploadSource(src: Bitmap) {
        val w = src.width
        val h = src.height
        if (srcTex == 0 || srcTexW != w || srcTexH != h) {
            if (srcTex != 0) runCatching {
                GLES30.glDeleteTextures(1, intArrayOf(srcTex), 0)
            }
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            srcTex = ids[0]
            if (srcTex == 0) throw GlesUnavailable("glGenTextures failed")
            srcTexW = w
            srcTexH = h
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, src, 0)
        checkGl("uploadSource")
    }

    private fun uploadRgba(bytes: ByteArray, w: Int, h: Int, filter: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        if (tex == 0) throw GlesUnavailable("glGenTextures failed")
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.position(0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
            checkGl("uploadRgba")
            return tex
        } catch (t: Throwable) {
            runCatching { GLES30.glDeleteTextures(1, intArrayOf(tex), 0) }
            throw t
        }
    }

    private fun createFramebuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        if (ids[0] == 0) throw GlesUnavailable("glGenFramebuffers failed")
        return ids[0]
    }

    private fun bindTarget(w: Int, h: Int) {
        if (fboTex == 0 || fboW != w || fboH != h) {
            if (fboTex != 0) runCatching {
                GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            fboTex = ids[0]
            if (fboTex == 0) throw GlesUnavailable("glGenTextures failed for target")
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            checkGl("bindTarget alloc")
            fboW = w
            fboH = h
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, fboTex, 0
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) !=
            GLES30.GL_FRAMEBUFFER_COMPLETE
        ) {
            throw GlesUnavailable("framebuffer incomplete")
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawQuad() {
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, QUAD_POS)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, QUAD_UV)
        GLES30.glEnableVertexAttribArray(1)
        try {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            runCatching { GLES30.glDisableVertexAttribArray(0) }
            runCatching { GLES30.glDisableVertexAttribArray(1) }
            runCatching { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
        }
    }

    private fun readback(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        checkGl("glReadPixels")
        val pixels = IntArray(w * h)
        var o = 0
        for (i in pixels.indices) {
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            val a = buf.get(o + 3).toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            o += 4
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun checkGl(op: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) {
            throw GlesUnavailable("$op failed (glError=$e)")
        }
    }

    companion object {
        const val GL_TIMEOUT_MS = 120_000L
        private const val MAX_TRACKED_CANCELLATIONS = 512

        private val IDENTITY_MAT4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        private val ZERO_VEC3 = floatArrayOf(0f, 0f, 0f)

        private val QUAD_POS: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        private val QUAD_UV: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
                position(0)
            }

    }
}
