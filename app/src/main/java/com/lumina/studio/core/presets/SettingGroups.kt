package com.lumina.studio.core.presets

import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.HslColor

enum class SettingGroup(val label: String) {
    LIGHT("Light"),
    COLOR("Color"),
    GRADE("Grade"),
    CURVES("Curves"),
    EFFECTS("Effects"),
    DETAIL("Detail"),
    OPTICS("Optics"),
    GEOMETRY("Geometry"),
    MASKS("Masks"),
    LUT("LUT");

    companion object {
        fun fromKey(key: String?): SettingGroup? =
            entries.firstOrNull { it.name == key }
    }
}

object SettingGroups {
    val ALL: Set<SettingGroup> = SettingGroup.entries.toSet()

    val PRESET_GROUPS: Set<SettingGroup> = setOf(
        SettingGroup.LIGHT,
        SettingGroup.COLOR,
        SettingGroup.GRADE,
        SettingGroup.CURVES,
        SettingGroup.MASKS,
        SettingGroup.LUT
    )

    val BATCH_GROUPS: Set<SettingGroup> = ALL

    val COPY_DEFAULT: Set<SettingGroup> = ALL - SettingGroup.MASKS

    fun applyCopy(source: EditParams, base: EditParams, enabled: Set<SettingGroup>): EditParams {
        var next = base
        if (SettingGroup.LIGHT in enabled) {
            next = next.copy(
                exposure = source.exposure,
                contrast = source.contrast,
                highlights = source.highlights,
                shadows = source.shadows,
                whites = source.whites,
                blacks = source.blacks
            )
        }
        if (SettingGroup.COLOR in enabled) {
            var colored = next.copy(
                temperature = source.temperature,
                tint = source.tint,
                saturation = source.saturation,
                vibrance = source.vibrance,
                clarity = source.clarity,
                dehaze = source.dehaze,
                sharpness = source.sharpness,
                globalSat = source.globalSat,
                globalVib = source.globalVib,
                hsl = source.hsl
            )
            for (color in HslColor.entries) {
                colored = colored.withHsl(color, source.getHsl(color))
            }
            next = colored
        }
        if (SettingGroup.GRADE in enabled) {
            next = next.copy(grade = source.grade, pointColor = source.pointColor)
        }
        if (SettingGroup.CURVES in enabled) {
            var curved = next
            for (channel in CurveChannel.entries) {
                curved = curved.withCurve(channel, source.getCurve(channel))
            }
            next = curved
        }
        if (SettingGroup.EFFECTS in enabled) {
            next = next.copy(retouch = source.retouch, lensBlur = source.lensBlur)
        }
        if (SettingGroup.DETAIL in enabled) {
            var detailed = next
            for (control in DetailControl.entries) {
                detailed = detailed.withDetail(control, source.getDetail(control))
            }
            next = detailed
        }
        if (SettingGroup.OPTICS in enabled) {
            next = next.withOptics(source.optics)
        }
        if (SettingGroup.GEOMETRY in enabled) {
            next = next.withCrop(source.crop)
        }
        if (SettingGroup.MASKS in enabled) {
            next = next.copy(masks = source.masks)
        }
        if (SettingGroup.LUT in enabled) {
            next = next.copy(
                presetId = source.presetId,
                presetIntensity = source.presetIntensity,
                steps = source.steps
            )
        }
        return next
    }

    fun sanitizeKeys(keys: Collection<String>?): Set<SettingGroup> {
        if (keys.isNullOrEmpty()) return emptySet()
        return keys.mapNotNull { SettingGroup.fromKey(it) }.toSet()
    }

    fun keysOf(groups: Set<SettingGroup>): List<String> = groups.map { it.name }.sorted()

    @Suppress("unused")
    fun lightControls(): List<AdjustControl> = listOf(
        AdjustControl.EXPOSURE,
        AdjustControl.CONTRAST,
        AdjustControl.HIGHLIGHTS,
        AdjustControl.SHADOWS,
        AdjustControl.WHITES,
        AdjustControl.BLACKS
    )
}
