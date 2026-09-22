package com.lumina.studio.export

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.Sidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M10SidecarRoundTripTest {

    private fun richParams(): EditParams {
        var params = EditParams.DEFAULT.copy(
            exposure = -0.7f,
            contrast = 15f,
            temperature = -10f,
            saturation = 12f,
            presetId = "builtin_noir",
            presetIntensity = 0.8f,
            globalSat = 5f,
            texture = -8f,
            masks = listOf(EditMask(id = "m1", tool = MaskTool.RADIAL, exposure = 0.5f))
        )
        params = params.withHslSat(HslColor.BLUE, -25f)
        params = params.withGradeSat(GradeZone.SHADOWS, 30f)
        params = params.withPointSat(20f)
        params = params.withCurve(
            CurveChannel.RED,
            listOf(CurvePoint(0f, 0f), CurvePoint(0.4f, 0.5f), CurvePoint(1f, 1f))
        )
        params = params.withVignetteCorr(15f)
        params = params.withLensAmount(25f)
        params = params.addRetouchAt(com.lumina.studio.core.edit.RetouchKind.HEAL, 0.3f, 0.4f)
        return params
    }

    @Test
    fun `recipe survives sidecar round-trip`() {
        val original = richParams()
        val json = Exporter.buildSidecarJson(original, "1.0-test", "IMG_001.dng")
        assertTrue(json.contains("\"schemaVersion\":1"))
        val parsed = Sidecar.parse(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertEquals("IMG_001.dng", parsed.sourceFile)
        assertTrue(parsed.warnings.isEmpty())
        assertEquals(EditParamsJson.encode(original), EditParamsJson.encode(parsed.params))
    }

    @Test
    fun `sidecar keeps legacy honesty fields`() {
        val json = Exporter.buildSidecarJson(EditParams.DEFAULT, "9.9", null)
        assertTrue(json.contains("\"app\":\"Lumina RAW & LUT Studio\""))
        assertTrue(json.contains("\"workflow\":\"raw-compatible\""))
        assertTrue(json.contains("\"recipe\":"))
        assertTrue(json.contains("untouched"))
        assertTrue(json.contains("NOT baked in"))
    }

    @Test
    fun `missing schema version is tolerated as v0 with warning`() {
        val legacy = "{\"app\":\"Lumina\",\"sourceFile\":\"a.dng\"," +
            "\"recipe\":" + EditParamsJson.encode(EditParams.DEFAULT.copy(exposure = 2f)) + "}"
        val parsed = Sidecar.parse(legacy)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.schemaVersion)
        assertEquals(2f, parsed.params.exposure, 0.0001f)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `newer schema version warns but still applies`() {
        val json = Exporter.buildSidecarJson(EditParams.DEFAULT, "9.9", "a.dng")
            .replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        val parsed = Sidecar.parse(json)
        assertNotNull(parsed)
        assertEquals(99, parsed!!.schemaVersion)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `unparseable sidecars return null`() {
        assertNull(Sidecar.parse(null))
        assertNull(Sidecar.parse(""))
        assertNull(Sidecar.parse("not json at all"))
        assertNull(Sidecar.parse("{\"app\":\"Lumina\"}"))
    }

    @Test
    fun `missing lut path strips lut with honest warning`() {
        val withLut = richParams()
        val warning = Sidecar.missingLutWarning(withLut.presetId)
        assertTrue(warning.contains("builtin_noir"))
        val stripped = Sidecar.withoutLut(withLut)
        assertNull(stripped.presetId)
        assertEquals(1f, stripped.presetIntensity, 0.0001f)
        assertEquals(withLut.exposure, stripped.exposure, 0.0001f)
    }

    @Test
    fun `sibling base name matching`() {
        assertEquals("IMG_001", Sidecar.matchBaseName("IMG_001.lumina.json"))
        assertEquals("IMG_001", Sidecar.matchBaseName("/sdcard/Download/IMG_001.lumina.json"))
        assertNull(Sidecar.matchBaseName("IMG_001.jpg"))
        assertNull(Sidecar.matchBaseName(null))
        assertNull(Sidecar.matchBaseName(""))
    }
}
