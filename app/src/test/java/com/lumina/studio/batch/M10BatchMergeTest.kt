package com.lumina.studio.batch

import com.lumina.studio.core.batch.BatchItemResult
import com.lumina.studio.core.batch.BatchOps
import com.lumina.studio.core.batch.SettingsClipboard
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.presets.SettingGroup
import com.lumina.studio.core.presets.SettingGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M10BatchMergeTest {

    private fun source(): EditParams = EditParams.DEFAULT.copy(
        exposure = 2f,
        saturation = -10f,
        presetId = "builtin_mono",
        presetIntensity = 0.7f,
        masks = listOf(EditMask(id = "m1", tool = MaskTool.BRUSH))
    )

    @Test
    fun `clipboard copy pastes everything except masks by default`() {
        SettingsClipboard.clear()
        try {
            assertFalse(SettingsClipboard.hasContent())
            SettingsClipboard.copy(source())
            assertTrue(SettingsClipboard.hasContent())
            val pasted = SettingsClipboard.paste(EditParams.DEFAULT)
            assertNotNull(pasted)
            assertEquals(2f, pasted!!.exposure, 0.0001f)
            assertEquals(-10f, pasted.saturation, 0.0001f)
            assertEquals("builtin_mono", pasted.presetId)
            assertTrue(pasted.masks.isEmpty())
        } finally {
            SettingsClipboard.clear()
        }
    }

    @Test
    fun `clipboard paste includes masks only when explicitly copied`() {
        SettingsClipboard.clear()
        try {
            SettingsClipboard.copy(source(), SettingGroups.ALL)
            val pasted = SettingsClipboard.paste(EditParams.DEFAULT)
            assertNotNull(pasted)
            assertEquals(1, pasted!!.masks.size)
            assertEquals(SettingGroups.ALL, SettingsClipboard.groups())
        } finally {
            SettingsClipboard.clear()
        }
    }

    @Test
    fun `paste with no content returns null`() {
        SettingsClipboard.clear()
        assertNull(SettingsClipboard.paste(EditParams.DEFAULT))
    }

    @Test
    fun `batch merge respects group mask`() {
        val merged = BatchOps.mergeForBatch(
            source(), EditParams.DEFAULT, setOf(SettingGroup.LIGHT)
        )
        assertEquals(2f, merged.exposure, 0.0001f)
        assertEquals(0f, merged.saturation, 0.0001f)
        assertNull(merged.presetId)
        assertTrue(merged.masks.isEmpty())
    }

    @Test
    fun `progress math boundaries`() {
        assertEquals(0f, BatchOps.progress(0, 4), 0.0001f)
        assertEquals(0.5f, BatchOps.progress(2, 4), 0.0001f)
        assertEquals(1f, BatchOps.progress(4, 4), 0.0001f)
        assertEquals(1f, BatchOps.progress(9, 4), 0.0001f)
        assertEquals(0f, BatchOps.progress(1, 0), 0.0001f)
    }

    @Test
    fun `summarize counts per-item errors`() {
        assertEquals("Nothing to export.", BatchOps.summarize(emptyList()))
        val allOk = listOf(BatchItemResult("a", true), BatchItemResult("b", true))
        assertEquals("Exported 2 photos.", BatchOps.summarize(allOk))
        assertEquals("Exported 1 photo.", BatchOps.summarize(listOf(BatchItemResult("a", true))))
        val mixed = listOf(
            BatchItemResult("a", true),
            BatchItemResult("b", false, "boom"),
            BatchItemResult("c", false, "bang")
        )
        assertEquals("Exported 1 of 3; 2 failed.", BatchOps.summarize(mixed))
        assertEquals(2, BatchOps.failures(mixed).size)
        assertTrue(BatchOps.failures(allOk).isEmpty())
    }
}
