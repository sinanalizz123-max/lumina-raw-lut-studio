package com.lumina.studio.core.render.gpu

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeMath
import com.lumina.studio.core.edit.GradeParams
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.PointColorParams
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.RenderTarget
import kotlin.math.pow

/**
 * M15 real-GPU backend: JVM-testable preparation layer (pure Kotlin, no
 * android.*). GLES calls live only in [GlesBackend] (on-device, compile-only
 * on CI); everything here runs on the plain JVM and is pinned by
 * GpuPipelineTest.
 *
 * Hybrid pipeline (§11): the GPU fragment shader covers one contiguous color
 * slice in exact CPU stage order; stages that do not map to shaders run on
 * the CPU before/after the GPU segment (see [HYBRID_STAGE_MAP]). The CPU
 * path (PreviewRenderer/CpuRenderBackend) is untouched and stays default.
 */
enum class GpuStageWhere { GPU, CPU }

data class HybridStage(
    val name: String,
    val where: GpuStageWhere,
    val note: String
)

object GpuStageMap {
    const val LUT = "LUT"
    const val ADJUSTS = "ADJUSTS"
    const val HSL_GLOBAL = "HSL_GLOBAL"
    const val HSL_PER_COLOR = "HSL_PER_COLOR"
    const val CURVES = "CURVES"
    const val POINT_COLOR = "POINT_COLOR"
    const val GRADING = "GRADING"
    const val VIGNETTE = "VIGNETTE"
    const val DETAILS_MICRO = "DETAILS_MICRO"
    const val OPTICS_REMAP = "OPTICS_REMAP"
    const val DETAILS_NR = "DETAILS_NR"
    const val MASKS = "MASKS"
    const val RETOUCH = "RETOUCH"
    const val LENS_BLUR = "LENS_BLUR"
    const val GEOMETRY = "GEOMETRY"

    val STAGES: List<HybridStage> = listOf(
        HybridStage(LUT, GpuStageWhere.GPU, "3D LUT packed to 2D tiles (texelFetch trilinear) or 1D resampled table; same effective/full rule as CPU"),
        HybridStage(ADJUSTS, GpuStageWhere.GPU, "PreviewRenderer.buildMatrix array as mat4+offset uniform: exact same matrix the CPU applies"),
        HybridStage(HSL_GLOBAL, GpuStageWhere.GPU, "global sat/vib setSaturation matrix as mat4+offset uniform, separate op like the CPU stage"),
        HybridStage(HSL_PER_COLOR, GpuStageWhere.GPU, "rgb2hsl loop over 8 uniform adjusts; same centers, half-width, scales as PreviewRenderer"),
        HybridStage(CURVES, GpuStageWhere.GPU, "256x1 RGBA texture (rgb = channel curves, a = master), NEAREST fetch mirrors CPU direct indexing"),
        HybridStage(POINT_COLOR, GpuStageWhere.GPU, "hue falloff + sat/lum scales mirror PointColorMath; runs after curves, before grading"),
        HybridStage(GRADING, GpuStageWhere.GPU, "luma smoothstep zone weights mirror GradeMath.zoneWeights; lifts precomputed by GradeMath.zoneLift on CPU"),
        HybridStage(VIGNETTE, GpuStageWhere.GPU, "radial gain mirrors OpticsMath.vignetteGain; forced to 0 when remap stages run so CPU optics owns it"),
        HybridStage(DETAILS_MICRO, GpuStageWhere.GPU, "texture/clarity/dehaze contrast+sat matrix as mat4+offset uniform, same formula as applyDetails micro"),
        HybridStage(OPTICS_REMAP, GpuStageWhere.CPU, "distortion inverse-remap + CA shift need scattered sampling; PreviewRenderer.applyOptics on the readback"),
        HybridStage(DETAILS_NR, GpuStageWhere.CPU, "luma downscale-upscale blur passes; applyDetails with micro zeroed so the GPU matrix is not doubled"),
        HybridStage(MASKS, GpuStageWhere.CPU, "brush polylines + radial/linear analytic + range/AI alpha; PreviewRenderer.applyMasks on the readback"),
        HybridStage(RETOUCH, GpuStageWhere.CPU, "heal/clone/erase inpaint is iterative neighbor diffusion; PreviewRenderer.applyRetouch on the readback"),
        HybridStage(LENS_BLUR, GpuStageWhere.CPU, "spatially-varying 3-level box blur; PreviewRenderer.applyLensBlur on the readback"),
        HybridStage(GEOMETRY, GpuStageWhere.CPU, "perspective warp + rotate/straighten/flip + crop cut last; PreviewRenderer.cropBitmap on the readback")
    )

    val GPU_SEGMENT: List<String> = STAGES.filter { it.where == GpuStageWhere.GPU }.map { it.name }

    val CPU_SEGMENT: List<String> = STAGES.filter { it.where == GpuStageWhere.CPU }.map { it.name }
}

/**
 * M15 routing + fallback decisions (§11, pure). GPU is attempted only for
 * Export FINAL and Fullscreen when the performance toggle is ON and the
 * device reports GLES3; everything else (preview/thumb/tile) stays CPU.
 * Preview stays CPU by default (responsiveness proven); GPU covers
 * fullscreen/tile/export-final where it matters.
 */
object GpuRenderPolicy {
    const val GLES3_VERSION = 0x30000
    const val GPU_LABEL = "GPU (GLES3)"
    const val CPU_LABEL = "CpuRenderBackend"

    fun shouldAttemptGpu(target: RenderTarget, gpuEnabled: Boolean, glesVersion: Int): Boolean {
        if (!gpuEnabled) return false
        if (glesVersion < GLES3_VERSION) return false
        return target is RenderTarget.Fullscreen || target is RenderTarget.Export
    }

    fun fallbackLabel(reason: String): String = "CPU (fallback: $reason)"

    fun failureReason(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /**
     * Which result kind surfaces when a GPU attempt fails and the
     * same-request CPU rerun yields [cpuKind] ("Ok" / "Unavailable" /
     * "OomBudget"). Mirrors GlesBackend.cpuOutcome: the CPU result passes
     * through untouched, so Unavailable surfaces only when BOTH fail.
     */
    fun cpuFallbackSurfaces(cpuKind: String): String = when (cpuKind) {
        "Ok" -> "Ok"
        "OomBudget" -> "OomBudget"
        else -> "Unavailable"
    }
}

/**
 * M15 honest-coverage note (referenced by GpuPipelineTest so the suite fails
 * loudly if this contract is dropped): GLES calls live ONLY in GlesBackend,
 * which CI compiles but never executes (no emulator GLES). JVM tests pin
 * every pure builder above; shader MATH on-device needs parity screenshots,
 * timing runs and context-loss recovery validation.
 */
object GpuPipelineTestHelper {
    const val NOTE = "GlesBackend is compile-only on CI; on-device validation required"
}

/**
 * M15 uniform value math (pure). Matrix uniforms are packed from
 * android.graphics.ColorMatrix float[20] arrays produced at runtime by the
 * SAME builders the CPU uses (PreviewRenderer.buildMatrix, setSaturation),
 * so parity holds by construction; only the 20->16+3 repacking is tested
 * here. Scalar uniforms mirror the CPU formulae exactly.
 */
object GpuUniforms {
    fun exposureGain(ev: Float): Float = 2f.pow(ev).coerceIn(0.1f, 8f)

    fun isValidMatrixArray(a: FloatArray): Boolean =
        a.size == 20 && a.all { it.isFinite() }

    fun mat4FromColorMatrix(a: FloatArray): FloatArray {
        require(isValidMatrixArray(a)) { "need 20 finite floats" }
        val out = FloatArray(16)
        for (row in 0..3) {
            for (col in 0..3) {
                out[col * 4 + row] = a[row * 5 + col]
            }
        }
        return out
    }

    fun offsetFromColorMatrix(a: FloatArray): FloatArray {
        require(isValidMatrixArray(a)) { "need 20 finite floats" }
        return floatArrayOf(a[4], a[9], a[14], a[19])
    }

    /**
     * CPU ColorMatrixColorFilter math runs on 0..255-encoded values (offsets
     * like the contrast pivot 128 live in that domain); the shader samples
     * normalized 0..1 floats. The 4x4 gain block is scale-free, so dividing
     * only the offset column by 255 makes the uploaded matrix exact.
     */
    fun scaleOffsetsToUnit(a: FloatArray): FloatArray {
        require(isValidMatrixArray(a)) { "need 20 finite floats" }
        val out = a.copyOf()
        out[4] = out[4] / 255f
        out[9] = out[9] / 255f
        out[14] = out[14] / 255f
        out[19] = out[19] / 255f
        return out
    }

    fun identityMatrixArray(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    fun gradeEdges(balance: Float): FloatArray {
        val shift = (balance.coerceIn(-100f, 100f) / 100f) * 0.2f
        return floatArrayOf(0.25f + shift, 0.6f + shift, 0.45f + shift, 0.8f + shift)
    }

    fun gradeLifts(grade: GradeParams): Array<FloatArray> = arrayOf(
        GradeMath.zoneLift(grade.shadows),
        GradeMath.zoneLift(grade.midtones),
        GradeMath.zoneLift(grade.highlights),
        GradeMath.zoneLift(grade.global)
    )

    fun pointUniform(point: PointColorParams): FloatArray? {
        if (point.isDefault()) return null
        return floatArrayOf(point.hueCenter, point.hueRange, point.satAdjust, point.lumAdjust)
    }

    fun vignetteForShader(amount: Float, hasRemap: Boolean): Float {
        if (hasRemap) return 0f
        if (amount == 0f) return 0f
        return amount.coerceIn(-100f, 100f)
    }

    fun hslAdjustArray(params: EditParams): Array<FloatArray> =
        Array(8) { k ->
            val color = HslColor.entries[k]
            val adjust = params.getHsl(color)
            floatArrayOf(adjust.hue, adjust.sat, adjust.lum, color.centerHueDeg)
        }

    fun hasHslLumAdjust(params: EditParams): Boolean =
        HslColor.entries.any { params.getHsl(it).lum != 0f }

    fun globalSatFactor(params: EditParams): Float =
        (1f + params.globalSat / 100f + params.globalVib / 200f).coerceIn(0f, 3f)
}

/**
 * M15 3D->2D LUT tile packing (pure). A size-N cube becomes an (N*N)xN RGBA8
 * image; cell (r,g,b) lands at texel x = b*N+r, y = g. The shader fetches
 * exact texels with texelFetch and mixes manually, i.e. CPU trilinear
 * without cross-tile filtering bleed. Byte rounding (<=1/255 per channel)
 * is the only divergence vs the CPU float table.
 */
object LutTiles {
    fun tileDims(size: Int): Pair<Int, Int> {
        require(size >= 2) { "lut size must be >= 2" }
        return (size * size) to size
    }

    fun fetchTexel(size: Int, r: Int, g: Int, b: Int): IntArray {
        require(size >= 2) { "lut size must be >= 2" }
        return intArrayOf(b * size + r, g)
    }

    fun packRgba8(lut: LutCube): ByteArray {
        require(lut.is3D) { "tile packing needs a 3D LUT" }
        val n = lut.size
        require(n >= 2) { "lut size must be >= 2" }
        val (w, h) = tileDims(n)
        val out = ByteArray(w * h * 4)
        val data = lut.data
        require(data.size == n * n * n * 3) { "lut data mismatch" }
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val cell = (b * n + g) * n + r
                    val x = b * n + r
                    val o = (g * w + x) * 4
                    out[o] = quantize(data[cell * 3])
                    out[o + 1] = quantize(data[cell * 3 + 1])
                    out[o + 2] = quantize(data[cell * 3 + 2])
                    out[o + 3] = 0xFF.toByte()
                }
            }
        }
        return out
    }

    fun unpackCell(packed: ByteArray, size: Int, r: Int, g: Int, b: Int): FloatArray {
        val (w, _) = tileDims(size)
        val t = fetchTexel(size, r, g, b)
        val o = (t[1] * w + t[0]) * 4
        return floatArrayOf(
            (packed[o].toInt() and 0xFF) / 255f,
            (packed[o + 1].toInt() and 0xFF) / 255f,
            (packed[o + 2].toInt() and 0xFF) / 255f
        )
    }

    private fun quantize(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    /**
     * 1D LUT resample to a fixed 256-entry RGB table (mirrors
     * LutRenderer.sample1D so the shader's LINEAR sample matches CPU lerp).
     */
    fun resample1DTo256(data: FloatArray, size: Int): FloatArray {
        require(size >= 2) { "lut size must be >= 2" }
        require(data.size == size * 3) { "1D lut data mismatch" }
        val out = FloatArray(256 * 3)
        for (i in 0 until 256) {
            val x = i / 255f
            val pos = (x * (size - 1)).coerceIn(0f, (size - 1).toFloat())
            val i0 = kotlin.math.floor(pos).toInt().coerceIn(0, size - 1)
            val i1 = (i0 + 1).coerceAtMost(size - 1)
            val f = pos - i0
            for (c in 0..2) {
                val v0 = data[i0 * 3 + c]
                val v1 = data[i1 * 3 + c]
                out[i * 3 + c] = (v0 + (v1 - v0) * f).coerceIn(0f, 1f)
            }
        }
        return out
    }

    fun pack1DRgba8(resampled256: FloatArray): ByteArray {
        require(resampled256.size == 256 * 3) { "need 256x3 resampled table" }
        val out = ByteArray(256 * 4)
        for (i in 0 until 256) {
            out[i * 4] = quantize(resampled256[i * 3])
            out[i * 4 + 1] = quantize(resampled256[i * 3 + 1])
            out[i * 4 + 2] = quantize(resampled256[i * 3 + 2])
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }
}

/**
 * M15 curve table upload arrays (pure). One 256x1 RGBA8 texture carries
 * rgb = per-channel curves and a = master curve; diagonal channels upload
 * identity. NEAREST sampling in the shader mirrors the CPU's direct
 * 8-bit indexing (no interpolation on either side).
 */
object CurveTables {
    fun uploadBytes(
        master: FloatArray?,
        red: FloatArray?,
        green: FloatArray?,
        blue: FloatArray?
    ): ByteArray {
        val out = ByteArray(256 * 4)
        for (i in 0 until 256) {
            val f = i / 255f
            out[i * 4] = quantize(red?.get(i) ?: f)
            out[i * 4 + 1] = quantize(green?.get(i) ?: f)
            out[i * 4 + 2] = quantize(blue?.get(i) ?: f)
            out[i * 4 + 3] = quantize(master?.get(i) ?: f)
        }
        return out
    }

    fun uploadForPoints(points: Map<CurveChannel, List<CurvePoint>>): ByteArray {
        fun table(channel: CurveChannel): FloatArray? {
            val pts = points[channel] ?: Curves.DEFAULT_POINTS
            if (Curves.isDiagonal(Curves.sanitize(pts))) return null
            return Curves.sampleCurve(pts)
        }
        return uploadBytes(
            table(CurveChannel.MASTER),
            table(CurveChannel.RED),
            table(CurveChannel.GREEN),
            table(CurveChannel.BLUE)
        )
    }

    fun identityBytes(): ByteArray = uploadBytes(null, null, null, null)

    private fun quantize(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
}
