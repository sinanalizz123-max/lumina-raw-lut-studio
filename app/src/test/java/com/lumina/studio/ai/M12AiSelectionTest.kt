package com.lumina.studio.ai

import com.lumina.studio.core.ai.AiHeuristics
import com.lumina.studio.core.ai.AiMaskCache
import com.lumina.studio.core.ai.AiMaskFieldStore
import com.lumina.studio.core.ai.AiMaskResample
import com.lumina.studio.core.ai.AiProcessor
import com.lumina.studio.core.ai.AiResearch
import com.lumina.studio.core.ai.AiSelectMessages
import com.lumina.studio.core.ai.AiSelectOutcome
import com.lumina.studio.core.ai.HeuristicAiProcessor
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.MaskOp
import com.lumina.studio.core.edit.MaskTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * M12 pure-JVM guards (§§24-25, no android.*, no Robolectric).
 *
 * Covers: AiProcessor interface contract (fake processor), cache key
 * stability + save/load round-trip, null -> honest-message fallback path,
 * heuristic sky/subject math, field resampling, AI mask JSON round-trip +
 * old-JSON defaults, and the no-new-dependency verdict pin. Bitmap loops and
 * ViewModel coroutines stay on-device-only; they delegate to the pure math
 * pinned here.
 */
class M12AiSelectionTest {

    private class FakeProcessor(
        private val subject: FloatArray?,
        private val sky: FloatArray?
    ) : AiProcessor {
        override val name = "fake"
        override val status = "fake"
        override val diagnostics = "fake"
        var subjectCalls = 0
        var skyCalls = 0

        override fun subjectMask(argb: IntArray, width: Int, height: Int): FloatArray? {
            subjectCalls++
            return subject
        }

        override fun skyMask(argb: IntArray, width: Int, height: Int): FloatArray? {
            skyCalls++
            return sky
        }
    }

    // ---------- interface contract ----------

    @Test
    fun `fake processor returns canned fields verbatim`() {
        val subject = FloatArray(6) { it / 6f }
        val sky = FloatArray(6) { 1f - it / 6f }
        val fake = FakeProcessor(subject, sky)
        val argb = IntArray(6) { -1 }
        assertArrayEquals(subject, fake.subjectMask(argb, 3, 2)!!, 0f)
        assertArrayEquals(sky, fake.skyMask(argb, 3, 2)!!, 0f)
        assertEquals(1, fake.subjectCalls)
        assertEquals(1, fake.skyCalls)
    }

    @Test
    fun `heuristic processor rejects bad input, serves valid input`() {
        assertNull(HeuristicAiProcessor.subjectMask(IntArray(0), 0, 0))
        assertNull(HeuristicAiProcessor.skyMask(IntArray(3), 2, 2))
        assertNull(HeuristicAiProcessor.subjectMask(IntArray(4), 3, 3))
        val argb = IntArray(4) { -1 }
        assertNotNull(HeuristicAiProcessor.subjectMask(argb, 2, 2))
        assertNotNull(HeuristicAiProcessor.skyMask(argb, 2, 2))
    }

    // ---------- fallback behavior ----------

    @Test
    fun `null field maps to unavailable with manual fallback`() {
        val outcome = AiSelectMessages.fromProcessorResult(null, "k", "subject")
        assertTrue(outcome is AiSelectOutcome.Unavailable)
        val message = outcome.userMessage()
        assertTrue("manual fallback suggested: $message", message.contains("manual"))
        assertTrue("names the failing kind: $message", message.contains("subject"))
    }

    @Test
    fun `non-null field maps to ready with key`() {
        val outcome = AiSelectMessages.fromProcessorResult(FloatArray(4), "ai_subject_h_2x2_v1.bin", "sky")
        assertTrue(outcome is AiSelectOutcome.Ready)
        assertEquals("ai_subject_h_2x2_v1.bin", (outcome as AiSelectOutcome.Ready).cacheKey)
    }

    @Test
    fun `no outcome message claims AI`() {
        val messages = listOf(
            AiSelectOutcome.Ready("k").userMessage(),
            AiSelectOutcome.Unavailable("r").userMessage(),
            AiSelectOutcome.Failed("r").userMessage()
        )
        for (message in messages) {
            assertFalse("honesty guard, never AI: $message", message.contains("AI"))
        }
        assertTrue(messages[0].contains("Heuristic"))
    }

    // ---------- cache keys ----------

    @Test
    fun `cache key is stable and discriminates kind hash dims`() {
        val dir = Files.createTempDirectory("m12_ai").toFile()
        try {
            val a = AiMaskCache.cacheKey("subject", "abc123", 256, 144)
            assertEquals(a, AiMaskCache.cacheKey("subject", "abc123", 256, 144))
            assertFalse(a == AiMaskCache.cacheKey("sky", "abc123", 256, 144))
            assertFalse(a == AiMaskCache.cacheKey("subject", "def456", 256, 144))
            assertFalse(a == AiMaskCache.cacheKey("subject", "abc123", 128, 144))
            assertFalse(AiMaskCache.cacheFile(dir, a).name.contains("/"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kind mapping covers AI tools only`() {
        assertEquals("subject", AiMaskCache.kindFor("AI_SUBJECT"))
        assertEquals("sky", AiMaskCache.kindFor("AI_SKY"))
        assertNull(AiMaskCache.kindFor("BRUSH"))
        assertNull(AiMaskCache.kindFor(null))
    }

    @Test
    fun `source hash is stable and non-blank`() {
        val a = AiMaskCache.sourceHash("/p/photo.jpg", 100L, 200L)
        assertEquals(a, AiMaskCache.sourceHash("/p/photo.jpg", 100L, 200L))
        assertTrue(a.isNotBlank())
        assertFalse(a == AiMaskCache.sourceHash("/p/other.jpg", 100L, 200L))
        assertTrue(AiMaskCache.sourceHash(null, -1L, -1L).isNotBlank())
    }

    @Test
    fun `cache save load round-trips and rejects garbage`() {
        val dir = Files.createTempDirectory("m12_ai").toFile()
        try {
            val mask = floatArrayOf(0f, 0.5f, 1f, 0.25f)
            val file = AiMaskCache.cacheFile(dir, AiMaskCache.cacheKey("subject", "abc123", 2, 2))
            assertTrue(AiMaskCache.save(file, mask, 2, 2))
            val loaded = AiMaskCache.load(file)
            assertNotNull(loaded)
            assertEquals(2, loaded!!.width)
            assertEquals(2, loaded.height)
            assertArrayEquals(mask, loaded.mask, 0f)
            // Size mismatch never writes.
            assertFalse(AiMaskCache.save(file, FloatArray(3), 2, 2))
            // Missing and corrupt files load as null.
            assertNull(AiMaskCache.load(java.io.File(dir, "nope.bin")))
            val garbage = java.io.File(dir, "garbage.bin")
            garbage.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            assertNull(AiMaskCache.load(garbage))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- heuristic math ----------

    @Test
    fun `sky blueness is one for blue zero for grey green red`() {
        assertEquals(1f, AiHeuristics.skyBlueness(0f, 0f, 1f), 0f)
        assertEquals(0f, AiHeuristics.skyBlueness(0.5f, 0.5f, 0.5f), 0f)
        assertEquals(0f, AiHeuristics.skyBlueness(0f, 1f, 0f), 0f)
        assertEquals(0f, AiHeuristics.skyBlueness(1f, 0f, 0f), 0f)
    }

    @Test
    fun `sky weight favors bright blue tops over green ground`() {
        val skyTop = AiHeuristics.skyWeight(0.2f, 0.4f, 1f, 0.5f, 0f)
        assertTrue("bright blue top selected: $skyTop", skyTop > 0.3f)
        assertEquals(0f, AiHeuristics.skyWeight(0f, 1f, 0f, 0.5f, 1f), 0f)
        assertEquals(0f, AiHeuristics.skyWeight(0.1f, 0.1f, 0.1f, 0.5f, 0f), 0f)
    }

    @Test
    fun `subject weight favors colorful center over grey corner`() {
        val center = AiHeuristics.subjectWeight(1f, 0.2f, 0.2f, 0.5f, 0.5f)
        val corner = AiHeuristics.subjectWeight(0.5f, 0.5f, 0.5f, 0f, 0f)
        assertTrue("center $center beats corner $corner", center > corner)
        assertTrue(center in 0f..1f)
        assertTrue(corner in 0f..1f)
    }

    @Test
    fun `computed fields match input size and stay in range`() {
        val blue = (0xFF shl 24) or (0x33 shl 16) or (0x66 shl 8) or 0xFF
        val argb = IntArray(6) { blue }
        val sky = AiHeuristics.computeSkyMask(argb, 3, 2)!!
        val subject = AiHeuristics.computeSubjectMask(argb, 3, 2)!!
        assertEquals(6, sky.size)
        assertEquals(6, subject.size)
        assertTrue(sky.all { it in 0f..1f })
        assertTrue(subject.all { it in 0f..1f })
        assertTrue("blue frame has sky response", sky.any { it > 0f })
    }

    // ---------- resampling ----------

    @Test
    fun `resample identity copies and uniform upscales flat`() {
        val src = floatArrayOf(0f, 0.25f, 0.5f, 1f)
        val same = AiMaskResample.resample(src, 2, 2, 2, 2)
        assertNotSame(src, same)
        assertArrayEquals(src, same, 0f)
        val flat = AiMaskResample.resample(floatArrayOf(0.7f), 1, 1, 3, 3)
        assertEquals(9, flat.size)
        assertTrue(flat.all { it == 0.7f })
    }

    // ---------- field store ----------

    @Test
    fun `store round-trips resampled alpha and rejects bad puts`() {
        AiMaskFieldStore.clear()
        val key = "test_key_2x2"
        AiMaskFieldStore.put(key, floatArrayOf(0f, 1f, 1f, 0f), 2, 2)
        val field = AiMaskFieldStore.get(key)
        assertNotNull(field)
        val up = AiMaskFieldStore.resampledAlpha(key, 4, 4)
        assertNotNull(up)
        assertEquals(16, up!!.size)
        assertTrue(up.all { it in 0f..1f })
        assertNull(AiMaskFieldStore.resampledAlpha("missing", 4, 4))
        AiMaskFieldStore.put("bad", FloatArray(3), 2, 2)
        assertNull(AiMaskFieldStore.get("bad"))
        AiMaskFieldStore.remove(key)
        assertNull(AiMaskFieldStore.get(key))
    }

    // ---------- model JSON ----------

    @Test
    fun `ai mask entries round-trip with grades and ops`() {
        val withAi = EditParams.DEFAULT
            .addAiMask(MaskTool.AI_SUBJECT, "ai_subject_h1_256x144_v1.bin")
            .addAiMask(MaskTool.AI_SKY, "ai_sky_h1_256x144_v1.bin")
        assertEquals(2, withAi.masks.size)
        val tuned = withAi.updateMask(withAi.masks[0].id) {
            it.withFeather(0.2f).withOpacity(0.8f).copy(inverted = true, op = MaskOp.SUBTRACT)
        }
        val decoded = EditParamsJson.decode(EditParamsJson.encode(tuned))
        assertEquals(2, decoded.masks.size)
        val first = decoded.masks[0]
        assertEquals(MaskTool.AI_SUBJECT, first.tool)
        assertEquals("ai_subject_h1_256x144_v1.bin", first.cacheKey)
        assertEquals(0.2f, first.feather, 1e-6f)
        assertEquals(0.8f, first.opacity, 1e-6f)
        assertTrue(first.inverted)
        assertEquals(MaskOp.SUBTRACT, first.op)
        assertEquals(MaskTool.AI_SKY, decoded.masks[1].tool)
        assertTrue(decoded.masks[0].isAiTool())
        assertFalse(EditMask(id = "x").isAiTool())
    }

    @Test
    fun `old json without cacheKey defaults to null and parses`() {
        val decoded = EditParamsJson.decode(
            "{\"masks\":[" +
                "{\"id\":\"m1\",\"tool\":\"AI_SKY\",\"op\":\"SUBTRACT\"}," +
                "{\"id\":\"m2\",\"tool\":\"BRUSH\"}]}"
        )
        assertEquals(2, decoded.masks.size)
        assertEquals(MaskTool.AI_SKY, decoded.masks[0].tool)
        assertEquals(MaskOp.SUBTRACT, decoded.masks[0].op)
        assertNull(decoded.masks[0].cacheKey)
        assertNull(decoded.masks[1].cacheKey)
        assertEquals(MaskTool.BRUSH, decoded.masks[1].tool)
    }

    @Test
    fun `addAiMask guards blank keys caps and non-ai tools`() {
        assertEquals(EditParams.DEFAULT, EditParams.DEFAULT.addAiMask(MaskTool.AI_SUBJECT, "  "))
        val viaNonAi = EditParams.DEFAULT.addAiMask(MaskTool.BRUSH, "k")
        assertEquals(1, viaNonAi.masks.size)
        assertEquals(MaskTool.BRUSH, viaNonAi.masks[0].tool)
        assertNull(viaNonAi.masks[0].cacheKey)
        var full = EditParams.DEFAULT
        repeat(EditParams.MAX_MASKS) { full = full.addMask(MaskTool.BRUSH) }
        assertEquals(full, full.addAiMask(MaskTool.AI_SKY, "k"))
        val capped = EditMask(id = "x").withCacheKey("y".repeat(200))
        assertEquals(EditMask.MAX_CACHE_KEY_LEN, capped.cacheKey!!.length)
    }

    // ---------- verdict pin ----------

    @Test
    fun `verdict pins heuristic backend with zero model`() {
        assertEquals("heuristic", AiResearch.SELECTED_BACKEND)
        assertEquals(AiResearch.BACKEND_NAME, HeuristicAiProcessor.name)
        assertTrue(HeuristicAiProcessor.status.contains("heuristic"))
        assertTrue(AiResearch.MODEL_SIZE_NOTE.startsWith("0 MB"))
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i], delta)
        }
    }
}
