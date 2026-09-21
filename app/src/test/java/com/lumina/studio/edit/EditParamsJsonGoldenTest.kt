package com.lumina.studio.edit

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.StepKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditParamsJsonGoldenTest {

    @Test
    fun `default encoding keeps stable keys`() {
        val json = EditParamsJson.encode(EditParams.DEFAULT)
        assertTrue(json.contains("\"exposure\":0.0"))
        assertTrue(json.contains("\"presetId\":null"))
        assertTrue(json.contains("\"crop_ratio\":\"free\""))
        assertTrue(json.contains("\"masks\":[]"))
    }

    @Test
    fun `rich params round-trip unchanged`() {
        val params = EditParams.DEFAULT
            .with(com.lumina.studio.core.edit.AdjustControl.EXPOSURE, 1.5f)
            .copy(presetId = "a\"b\\c", presetIntensity = 0.5f)
            .withHslHue(HslColor.RED, 10f)
            .withCurve(
                CurveChannel.MASTER,
                listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))
            )
            .withDetail(com.lumina.studio.core.edit.DetailControl.TEXTURE, 20f)
            .withStraighten(2f)
            .addMask(MaskTool.BRUSH)
            .copy(steps = EditParams.DEFAULT.steps.with(StepKey.PRESET, false))
        assertEquals(params, EditParamsJson.decode(EditParamsJson.encode(params)))
    }

    @Test
    fun `minimal legacy json decodes with defaults`() {
        val params = EditParamsJson.decode("{\"exposure\":2.0,\"contrast\":-10.0}")
        assertEquals(2f, params.exposure, 0.0f)
        assertEquals(-10f, params.contrast, 0.0f)
        assertNull(params.presetId)
        assertEquals(1f, params.presetIntensity, 0.0f)
        assertTrue(params.masks.isEmpty())
        assertTrue(params.isCurvesDefault())
    }

    @Test
    fun `mask codec preserves tool and grade`() {
        val mask = EditMask(id = "m1", tool = MaskTool.RADIAL, exposure = 1f)
        val params = EditParams.DEFAULT.copy(masks = listOf(mask))
        val decoded = EditParamsJson.decode(EditParamsJson.encode(params))
        assertEquals(1, decoded.masks.size)
        assertEquals(MaskTool.RADIAL, decoded.masks[0].tool)
        assertEquals(1f, decoded.masks[0].exposure, 0.0f)
    }
}
