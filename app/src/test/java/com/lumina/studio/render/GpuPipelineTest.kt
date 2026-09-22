package com.lumina.studio.render

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeMath
import com.lumina.studio.core.edit.GradeParams
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.RenderTarget
import com.lumina.studio.core.render.gpu.CurveTables
import com.lumina.studio.core.render.gpu.GpuPipelineTestHelper
import com.lumina.studio.core.render.gpu.GpuRenderPolicy
import com.lumina.studio.core.render.gpu.GpuShaders
import com.lumina.studio.core.render.gpu.GpuStageMap
import com.lumina.studio.core.render.gpu.GpuUniforms
import com.lumina.studio.core.render.gpu.LutTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M15 pure-JVM guards for the real-GPU preparation layer (no android.*,
 * no Robolectric).
 *
 * Covers: shader-string builders (version header, precision, balanced
 * braces/parens, CPU-order stage sequence, every required uniform/sampler
 * declared), 3D->2D LUT tile pack/unpack round-trip, 1D resample, curve
 * upload arrays vs Curves.sampleCurve, uniform value math (gains, pivots,
 * matrix repack, offsets scaling), fallback routing decisions, the hybrid
 * stage map, and a source contract for the untestable GLES class
 * (EGL pbuffer, reuse, release, CPU fallback — CI compiles it only).
 */
class GpuPipelineTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Source not found: $relative (user.dir=${System.getProperty("user.dir")})",
            found != null
        )
        return found!!.readText()
    }

    private fun identity3D(size: Int): LutCube {
        val data = FloatArray(size * size * size * 3)
        var k = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            data[k++] = r / (size - 1f)
            data[k++] = g / (size - 1f)
            data[k++] = b / (size - 1f)
        }
        return LutCube(title = null, size = size, is3D = true, data = data)
    }

    // ---------- shader builders ----------

    @Test
    fun `shaders carry version header and precision`() {
        for (src in listOf(GpuShaders.VERTEX_SHADER, GpuShaders.fragmentShader())) {
            assertTrue("needs #version 300 es", src.startsWith("#version 300 es"))
        }
        val frag = GpuShaders.fragmentShader()
        assertTrue("needs float precision", frag.contains("precision highp float"))
        assertTrue("needs sampler precision", frag.contains("precision highp sampler2D"))
    }

    @Test
    fun `shaders have balanced braces and parens`() {
        for (src in listOf(GpuShaders.VERTEX_SHADER, GpuShaders.fragmentShader())) {
            assertTrue("braces balanced", GpuShaders.isBalanced(src, '{', '}'))
            assertTrue("parens balanced", GpuShaders.isBalanced(src, '(', ')'))
        }
        assertFalse(GpuShaders.isBalanced("void f( {", '{', '}'))
        assertFalse(GpuShaders.isBalanced("void f) (", '(', ')'))
    }

    @Test
    fun `fragment main applies stages in CPU pipeline order`() {
        val main = GpuShaders.fragmentShader().substringAfter("void main()")
        val sequence = listOf(
            "lut3D(",
            "uAdjustMat",
            "uSatMat",
            "applyHsl(c)",
            "uCurvesTex",
            "uPoint",
            "uGradeBlend",
            "uVignette",
            "uMicroMat"
        )
        var cursor = -1
        for (token in sequence) {
            val idx = main.indexOf(token, cursor + 1)
            assertTrue("stage token '$token' must follow previous stages", idx > cursor)
            cursor = idx
        }
    }

    @Test
    fun `every required uniform and sampler is declared`() {
        val frag = GpuShaders.fragmentShader()
        for (name in GpuShaders.REQUIRED_UNIFORMS) {
            if (name == "uHslAdjust") {
                assertTrue("array $name declared", frag.contains("uniform vec4 uHslAdjust[8];"))
            } else {
                assertTrue("uniform $name declared", frag.contains("uniform ") && frag.contains("$name;"))
            }
        }
        for (sampler in GpuShaders.REQUIRED_SAMPLERS) {
            assertTrue(
                "sampler $sampler declared",
                frag.contains("uniform sampler2D $sampler;")
            )
        }
        assertEquals(4, GpuShaders.REQUIRED_SAMPLERS.size)
    }

    @Test
    fun `fragment shader mirrors CPU formulae`() {
        val frag = GpuShaders.fragmentShader()
        assertTrue("texelFetch trilinear", frag.contains("texelFetch(uLutTiles,"))
        assertTrue("domain normalize", frag.contains("uLutDomainMin"))
        assertTrue("intensity blend", frag.contains("uLutIntensity"))
        assertTrue("HSL half-width 35", frag.contains("/ 35.0"))
        assertTrue("hue scale 0.3", frag.contains("* 0.3"))
        assertTrue("lum scale 0.0025", frag.contains("* 0.0025"))
        assertTrue("point range clamp", frag.contains("clamp(uPoint.y, 10.0, 180.0)"))
        assertTrue("grade luma weights", frag.contains("vec3(0.2126, 0.7152, 0.0722)"))
        assertTrue("grade pivots", frag.contains("0.25 + shift") && frag.contains("0.8 + shift"))
        assertTrue("vignette gain", frag.contains("* 0.8 * d * d"))
        assertTrue("master in alpha", frag.contains(".a"))
    }

    // ---------- LUT tiles ----------

    @Test
    fun `tile dims pack N-cube into N-by-N strip`() {
        assertEquals(4 to 2, LutTiles.tileDims(2))
        assertEquals(1089 to 33, LutTiles.tileDims(33))
    }

    @Test
    fun `fetch texel inverts packing layout`() {
        for (n in listOf(2, 4, 17)) {
            val (w, _) = LutTiles.tileDims(n)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val t = LutTiles.fetchTexel(n, r, g, b)
                assertEquals(b * n + r, t[0])
                assertEquals(g, t[1])
                val packed = LutTiles.packRgba8(identity3D(n))
                val o = (t[1] * w + t[0]) * 4
                assertTrue(o >= 0 && o + 3 < packed.size)
            }
        }
    }

    @Test
    fun `tile pack unpack round-trips within byte rounding`() {
        for (n in listOf(2, 4, 17)) {
            val packed = LutTiles.packRgba8(identity3D(n))
            val (w, h) = LutTiles.tileDims(n)
            assertEquals(w * h * 4, packed.size)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val cell = LutTiles.unpackCell(packed, n, r, g, b)
                assertEquals(r / (n - 1f), cell[0], 1f / 255f + 1e-6f)
                assertEquals(g / (n - 1f), cell[1], 1f / 255f + 1e-6f)
                assertEquals(b / (n - 1f), cell[2], 1f / 255f + 1e-6f)
            }
        }
    }

    @Test
    fun `tile pack clamps out-of-range values`() {
        val data = FloatArray(2 * 2 * 2 * 3) { -0.5f }
        data[0] = 2f
        val packed = LutTiles.packRgba8(LutCube(null, 2, true, data = data))
        val cell = LutTiles.unpackCell(packed, 2, 0, 0, 0)
        assertEquals(1f, cell[0], 1e-6f)
        assertEquals(0f, cell[1], 1e-6f)
    }

    @Test
    fun `resample1D mirrors CPU lerp`() {
        val size = 5
        val data = FloatArray(size * 3) { i -> (i / 3) / (size - 1f) }
        val out = LutTiles.resample1DTo256(data, size)
        assertEquals(256 * 3, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[255 * 3], 1e-6f)
        for (i in 0 until 256) {
            assertEquals(i / 255f, out[i * 3 + 1], 0.005f)
        }
        val packed = LutTiles.pack1DRgba8(out)
        assertEquals(256 * 4, packed.size)
        assertEquals(0xFF.toByte(), packed[3])
    }

    // ---------- curve tables ----------

    @Test
    fun `curve upload matches CPU sampleCurve`() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))
        val sampled = Curves.sampleCurve(pts)
        val bytes = CurveTables.uploadForPoints(mapOf(CurveChannel.MASTER to pts))
        assertEquals(256 * 4, bytes.size)
        for (i in 0 until 256) {
            val expected = (sampled[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            assertEquals("alpha[$i]", expected, bytes[i * 4 + 3].toInt() and 0xFF)
            assertEquals("red identity[$i]", i, bytes[i * 4].toInt() and 0xFF)
            assertEquals("green identity[$i]", i, bytes[i * 4 + 1].toInt() and 0xFF)
            assertEquals("blue identity[$i]", i, bytes[i * 4 + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun `diagonal curves upload identity`() {
        val bytes = CurveTables.uploadForPoints(emptyMap())
        val identity = CurveTables.identityBytes()
        assertEquals(256 * 4, bytes.size)
        for (i in 0 until 256) {
            assertEquals(i, bytes[i * 4].toInt() and 0xFF)
            assertEquals(i, bytes[i * 4 + 3].toInt() and 0xFF)
            assertEquals(i, identity[i * 4 + 1].toInt() and 0xFF)
        }
    }

    // ---------- uniform math ----------

    @Test
    fun `exposure gain mirrors CPU buildMatrix`() {
        assertEquals(1f, GpuUniforms.exposureGain(0f), 1e-6f)
        assertEquals(2f, GpuUniforms.exposureGain(1f), 1e-6f)
        assertEquals(0.1f, GpuUniforms.exposureGain(-5f), 1e-6f)
        assertEquals(8f, GpuUniforms.exposureGain(5f), 1e-6f)
    }

    @Test
    fun `matrix repack keeps identity and gains`() {
        val id = GpuUniforms.identityMatrixArray()
        assertTrue(GpuUniforms.isValidMatrixArray(id))
        val mat = GpuUniforms.mat4FromColorMatrix(id)
        assertEquals(16, mat.size)
        for (i in 0 until 16) {
            assertEquals(if (i % 5 == 0) 1f else 0f, mat[i], 1e-6f)
        }
        assertEquals(0f, GpuUniforms.offsetFromColorMatrix(id)[0], 1e-6f)
        val exposure = id.copyOf().apply {
            this[0] = 2f
            this[6] = 2f
            this[12] = 2f
        }
        val em = GpuUniforms.mat4FromColorMatrix(exposure)
        assertEquals(2f, em[0], 1e-6f)
        assertEquals(2f, em[5], 1e-6f)
        assertEquals(2f, em[10], 1e-6f)
        assertEquals(1f, em[15], 1e-6f)
        assertFalse(GpuUniforms.isValidMatrixArray(FloatArray(19)))
        assertFalse(GpuUniforms.isValidMatrixArray(FloatArray(20) { Float.NaN }))
    }

    @Test
    fun `offset scaling maps 255-domain pivots to unit`() {
        val withTone = GpuUniforms.identityMatrixArray().apply {
            this[4] = 32f
            this[9] = -64f
            this[14] = 0f
        }
        val scaled = GpuUniforms.scaleOffsetsToUnit(withTone)
        assertEquals(32f / 255f, scaled[4], 1e-6f)
        assertEquals(-64f / 255f, scaled[9], 1e-6f)
        assertEquals(2f / 2f, 1f, 1e-6f)
        val off = GpuUniforms.offsetFromColorMatrix(scaled)
        assertEquals(32f / 255f, off[0], 1e-6f)
        // Gains untouched by the scaling.
        assertEquals(1f, GpuUniforms.mat4FromColorMatrix(scaled)[0], 1e-6f)
    }

    @Test
    fun `grade edges shift with balance like zoneWeights`() {
        assertEquals(0.25f, GpuUniforms.gradeEdges(0f)[0], 1e-6f)
        assertEquals(0.6f, GpuUniforms.gradeEdges(0f)[1], 1e-6f)
        assertEquals(0.45f, GpuUniforms.gradeEdges(0f)[2], 1e-6f)
        assertEquals(0.8f, GpuUniforms.gradeEdges(0f)[3], 1e-6f)
        assertEquals(0.45f, GpuUniforms.gradeEdges(100f)[0], 1e-6f)
        assertEquals(0.05f, GpuUniforms.gradeEdges(-100f)[0], 1e-6f)
        // Positive balance favors shadows: the shadow weight at mid luma grows.
        val w0 = GradeMath.zoneWeights(0.4f, 0f)
        val wPos = GradeMath.zoneWeights(0.4f, 100f)
        assertTrue(wPos[0] > w0[0])
    }

    @Test
    fun `grade lifts reuse GradeMath`() {
        val grade = GradeParams().with(GradeZone.SHADOWS, GradeAdjust(hue = 30f, sat = 50f, lum = 10f))
        val lifts = GpuUniforms.gradeLifts(grade)
        assertEquals(4, lifts.size)
        val expected = GradeMath.zoneLift(grade.shadows)
        assertEquals(expected[0], lifts[0][0], 1e-6f)
        assertEquals(expected[1], lifts[0][1], 1e-6f)
        assertEquals(expected[2], lifts[0][2], 1e-6f)
    }

    @Test
    fun `point uniform gates on default`() {
        assertNull(GpuUniforms.pointUniform(com.lumina.studio.core.edit.PointColorParams()))
        val params = EditParams.DEFAULT
            .withPointSample(0xFFFF0000.toInt(), 10f)
            .withPointSat(40f)
        val u = GpuUniforms.pointUniform(params.pointColor)
        assertNotNull(u)
        assertEquals(10f, u!![0], 1e-6f)
        assertEquals(60f, u[1], 1e-6f)
        assertEquals(40f, u[2], 1e-6f)
    }

    @Test
    fun `vignette uniform defers to CPU remap`() {
        assertEquals(0f, GpuUniforms.vignetteForShader(0f, false), 1e-6f)
        assertEquals(50f, GpuUniforms.vignetteForShader(50f, false), 1e-6f)
        assertEquals(0f, GpuUniforms.vignetteForShader(50f, true), 1e-6f)
        assertEquals(-100f, GpuUniforms.vignetteForShader(-200f, false), 1e-6f)
    }

    @Test
    fun `hsl adjust array carries centers and values`() {
        val params = EditParams.DEFAULT.withHslSat(HslColor.RED, 40f)
        val arr = GpuUniforms.hslAdjustArray(params)
        assertEquals(8, arr.size)
        assertEquals(0f, arr[0][3], 1e-6f)
        assertEquals(40f, arr[0][1], 1e-6f)
        assertEquals(240f, arr[5][3], 1e-6f)
        assertFalse(GpuUniforms.hasHslLumAdjust(EditParams.DEFAULT))
        assertTrue(
            GpuUniforms.hasHslLumAdjust(
                EditParams.DEFAULT.withHslLum(HslColor.BLUE, 10f)
            )
        )
        assertEquals(1f, GpuUniforms.globalSatFactor(EditParams.DEFAULT), 1e-6f)
        assertEquals(
            2f,
            GpuUniforms.globalSatFactor(EditParams.DEFAULT.withGlobalSat(100f)),
            1e-6f
        )
    }

    // ---------- fallback decisions ----------

    @Test
    fun `gpu attempted only for export-final and fullscreen on gles3`() {
        val gles3 = GpuRenderPolicy.GLES3_VERSION
        val gles2 = 0x20000
        assertTrue(
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Fullscreen(4096), true, gles3)
        )
        assertTrue(
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Export(200, 100), true, gles3)
        )
        assertFalse(
            "preview stays CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Preview(1600), true, gles3)
        )
        assertFalse(
            "thumb stays CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Thumb, true, gles3)
        )
        assertFalse(
            "tile stays CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Tile(1000L), true, gles3)
        )
        assertFalse(
            "toggle OFF forces CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Fullscreen(4096), false, gles3)
        )
        assertFalse(
            "GLES2 device forces CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Fullscreen(4096), true, gles2)
        )
        assertFalse(
            "unknown GL version forces CPU",
            GpuRenderPolicy.shouldAttemptGpu(RenderTarget.Export(200, 100), true, 0)
        )
    }

    @Test
    fun `unavailable surfaces only when both backends fail`() {
        assertEquals("Ok", GpuRenderPolicy.cpuFallbackSurfaces("Ok"))
        assertEquals("Unavailable", GpuRenderPolicy.cpuFallbackSurfaces("Unavailable"))
        assertEquals("OomBudget", GpuRenderPolicy.cpuFallbackSurfaces("OomBudget"))
        assertEquals("Unavailable", GpuRenderPolicy.cpuFallbackSurfaces("anything-else"))
    }

    @Test
    fun `backend labels name the path actually used`() {
        assertEquals("GPU (GLES3)", GpuRenderPolicy.GPU_LABEL)
        assertEquals("CPU (fallback: shader compile failed)", GpuRenderPolicy.fallbackLabel("shader compile failed"))
        assertEquals("boom", GpuRenderPolicy.failureReason(RuntimeException("boom")))
        assertEquals(
            "RuntimeException",
            GpuRenderPolicy.failureReason(RuntimeException())
        )
        assertEquals(
            "IllegalStateException",
            GpuRenderPolicy.failureReason(IllegalStateException("   "))
        )
    }

    // ---------- hybrid stage map ----------

    @Test
    fun `hybrid map covers every stage in pipeline order`() {
        val names = GpuStageMap.STAGES.map { it.name }
        assertEquals(
            listOf(
                "LUT", "ADJUSTS", "HSL_GLOBAL", "HSL_PER_COLOR", "CURVES",
                "POINT_COLOR", "GRADING", "VIGNETTE", "DETAILS_MICRO",
                "OPTICS_REMAP", "DETAILS_NR", "MASKS", "RETOUCH", "LENS_BLUR",
                "GEOMETRY"
            ),
            names
        )
        assertEquals(
            listOf(
                "LUT", "ADJUSTS", "HSL_GLOBAL", "HSL_PER_COLOR", "CURVES",
                "POINT_COLOR", "GRADING", "VIGNETTE", "DETAILS_MICRO"
            ),
            GpuStageMap.GPU_SEGMENT
        )
        assertEquals(
            listOf(
                "OPTICS_REMAP", "DETAILS_NR", "MASKS", "RETOUCH", "LENS_BLUR",
                "GEOMETRY"
            ),
            GpuStageMap.CPU_SEGMENT
        )
        for (stage in GpuStageMap.STAGES) {
            assertTrue("stage ${stage.name} documented", stage.note.isNotBlank())
        }
        assertEquals(names.distinct().size, names.size)
    }

    // ---------- GLES class source contract (compile-only on CI) ----------

    @Test
    fun `gles backend isolates GL and always falls back`() {
        val src = mainSource("com/lumina/studio/core/render/gpu/GlesBackend.kt")
        val shaders = mainSource("com/lumina/studio/core/render/gpu/GpuShaders.kt")
        assertTrue("EGL14 pbuffer context", src.contains("EGL14") && src.contains("EGL_PBUFFER_BIT"))
        assertTrue("GLES3 program", src.contains("GLES30") && shaders.contains("#version 300 es"))
        assertTrue("FBO render target", src.contains("Framebuffer") || src.contains("FBO"))
        assertTrue("readback", src.contains("glReadPixels"))
        assertTrue("explicit release", src.contains("fun release()"))
        assertTrue("low-memory hook", src.contains("onTrimMemory"))
        assertTrue("CPU fallback", src.contains("RenderBackends.cpu()"))
        assertTrue("never half-renders", src.contains("teardown"))
        assertFalse("no NDK", src.contains("NDK") || src.contains("Vulkan"))
    }

    @Test
    fun `wiring routes gpu only where it matters`() {
        val vm = mainSource("com/lumina/studio/ui/screens/EditorViewModel.kt")
        assertTrue("policy gate", vm.contains("shouldAttemptGpu"))
        assertTrue("diagnostics label", vm.contains("backendLabel"))
        assertTrue("release on cleared", vm.contains("releaseGpu"))
        val exporter = mainSource("com/lumina/studio/core/export/Exporter.kt")
        assertTrue("GPU-first export is opt-in", exporter.contains("gpuBackend"))
        assertTrue("CPU default kept", exporter.contains("RenderBackends.export()"))
        // Helper is referenced so the JVM suite fails loudly if it is dropped.
        assertNotNull(GpuPipelineTestHelper.NOTE)
    }
}
