package com.lumina.studio.presets

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.presets.PresetShare
import com.lumina.studio.core.presets.SettingGroup
import com.lumina.studio.core.presets.SettingGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class M10PresetGroupsTest {

    private fun richSource(): EditParams {
        var params = EditParams.DEFAULT.copy(
            exposure = 1.5f,
            saturation = 20f,
            presetId = "some_lut",
            presetIntensity = 0.5f,
            texture = 10f,
            masks = listOf(EditMask(id = "m1", tool = MaskTool.BRUSH))
        )
        params = params.withHslSat(HslColor.RED, 30f)
        params = params.withGradeSat(GradeZone.GLOBAL, 40f)
        params = params.withCurve(CurveChannel.MASTER, listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f)))
        params = params.withDetail(com.lumina.studio.core.edit.DetailControl.TEXTURE, 10f)
        params = params.withVignetteCorr(-20f)
        params = params.withLensAmount(30f)
        return params
    }

    @Test
    fun `copy default excludes masks but includes everything else`() {
        assertFalse(SettingGroup.MASKS in SettingGroups.COPY_DEFAULT)
        assertTrue(SettingGroup.LIGHT in SettingGroups.COPY_DEFAULT)
        assertTrue(SettingGroup.LUT in SettingGroups.COPY_DEFAULT)
        assertTrue(SettingGroup.GEOMETRY in SettingGroups.COPY_DEFAULT)
    }

    @Test
    fun `light group copies only light fields`() {
        val merged = SettingGroups.applyCopy(richSource(), EditParams.DEFAULT, setOf(SettingGroup.LIGHT))
        assertEquals(1.5f, merged.exposure, 0.0001f)
        assertEquals(0f, merged.saturation, 0.0001f)
        assertEquals(0f, merged.texture, 0.0001f)
        assertNull(merged.presetId)
    }

    @Test
    fun `color group copies hsl`() {
        val merged = SettingGroups.applyCopy(richSource(), EditParams.DEFAULT, setOf(SettingGroup.COLOR))
        assertEquals(20f, merged.saturation, 0.0001f)
        assertEquals(30f, merged.getHsl(HslColor.RED).sat, 0.0001f)
        assertEquals(0f, merged.exposure, 0.0001f)
    }

    @Test
    fun `grade and curves groups copy their stages`() {
        val merged = SettingGroups.applyCopy(
            richSource(), EditParams.DEFAULT, setOf(SettingGroup.GRADE, SettingGroup.CURVES)
        )
        assertEquals(40f, merged.getGrade(GradeZone.GLOBAL).sat, 0.0001f)
        assertFalse(merged.isCurvesDefault())
        assertEquals(0f, merged.exposure, 0.0001f)
    }

    @Test
    fun `masks excluded unless explicitly enabled`() {
        val default = SettingGroups.applyCopy(richSource(), EditParams.DEFAULT, SettingGroups.COPY_DEFAULT)
        assertEquals(1.5f, default.exposure, 0.0001f)
        assertTrue(default.masks.isEmpty())
        val withMasks = SettingGroups.applyCopy(
            richSource(), EditParams.DEFAULT, SettingGroups.COPY_DEFAULT + SettingGroup.MASKS
        )
        assertEquals(1, withMasks.masks.size)
    }

    @Test
    fun `lut group copies identity and steps`() {
        val base = EditParams.DEFAULT
        val merged = SettingGroups.applyCopy(richSource(), base, setOf(SettingGroup.LUT))
        assertEquals("some_lut", merged.presetId)
        assertEquals(0.5f, merged.presetIntensity, 0.0001f)
        assertEquals(0f, merged.exposure, 0.0001f)
    }

    @Test
    fun `empty mask changes nothing`() {
        val base = richSource()
        assertEquals(base, SettingGroups.applyCopy(EditParams.DEFAULT, base, emptySet()))
    }

    @Test
    fun `full copy round-trips`() {
        val source = richSource()
        assertEquals(source, SettingGroups.applyCopy(source, EditParams.DEFAULT, SettingGroups.ALL))
    }

    @Test
    fun `key helpers round-trip and drop unknowns`() {
        val groups = setOf(SettingGroup.LIGHT, SettingGroup.MASKS)
        val keys = SettingGroups.keysOf(groups)
        assertEquals(groups, SettingGroups.sanitizeKeys(keys))
        assertEquals(setOf(SettingGroup.LUT), SettingGroups.sanitizeKeys(listOf("LUT", "NOPE")))
        assertTrue(SettingGroups.sanitizeKeys(null).isEmpty())
    }

    @Test
    fun `share json round-trips recipe and groups`() {
        val recipe = richSource().copy(masks = emptyList())
        val json = PresetShare.buildShareJson(
            "Test", "Film", 0.8f,
            setOf(SettingGroup.LIGHT, SettingGroup.LUT), recipe, "TITLE T\nLUT_1D_SIZE 2\n"
        )
        assertTrue(json.contains("\"kind\":\"lumina-preset\""))
        val parsed = PresetShare.parseShareJson(json)
        assertNotNull(parsed)
        assertEquals("Test", parsed!!.name)
        assertEquals(0.8f, parsed.intensity, 0.0001f)
        assertEquals(setOf(SettingGroup.LIGHT, SettingGroup.LUT), parsed.groups)
        assertEquals(
            com.lumina.studio.core.edit.EditParamsJson.encode(recipe),
            com.lumina.studio.core.edit.EditParamsJson.encode(parsed.recipe)
        )
        assertNotNull(parsed.lutInline)
    }

    @Test
    fun `share parse rejects bad payloads`() {
        assertNull(PresetShare.parseShareJson(null))
        assertNull(PresetShare.parseShareJson(""))
        assertNull(PresetShare.parseShareJson("{\"kind\":\"other\",\"recipe\":{}}"))
        assertNull(PresetShare.parseShareJson("{\"kind\":\"lumina-preset\",\"v\":99,\"name\":\"x\",\"recipe\":{}}"))
        assertNull(PresetShare.parseShareJson("{\"kind\":\"lumina-preset\",\"v\":1,\"recipe\":{}}"))
        assertNull(PresetShare.parseShareJson("{\"kind\":\"lumina-preset\",\"v\":1,\"name\":\"x\"}"))
    }

    @Test
    fun `recipe file round-trips through temp dir`() {
        val dir = Files.createTempDirectory("m10_recipe").toFile()
        try {
            val recipe = richSource()
            assertTrue(PresetShare.writeRecipeParams(dir, "p1", recipe))
            val loaded = PresetShare.readRecipeParams(dir, "p1")
            assertNotNull(loaded)
            assertEquals(
                com.lumina.studio.core.edit.EditParamsJson.encode(recipe),
                com.lumina.studio.core.edit.EditParamsJson.encode(loaded!!)
            )
            assertNull(PresetShare.readRecipeParams(dir, "missing"))
            assertTrue(PresetShare.deleteRecipeParams(dir, "p1"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
