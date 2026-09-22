package com.lumina.studio.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.MaskOp
import com.lumina.studio.core.edit.MaskTool
import kotlin.math.roundToInt

private fun maskTypeIcon(tool: MaskTool): ImageVector = when (tool) {
    MaskTool.BRUSH -> Icons.Filled.Brush
    MaskTool.ERASER -> Icons.Filled.Contrast
    MaskTool.LINEAR -> Icons.Filled.Gradient
    MaskTool.RADIAL -> Icons.Filled.RadioButtonChecked
    MaskTool.COLOR -> Icons.Filled.Palette
    MaskTool.LUMINANCE -> Icons.Filled.Contrast
    MaskTool.AI_SUBJECT -> Icons.Filled.Person
    MaskTool.AI_SKY -> Icons.Filled.Cloud
}

@Composable
fun MaskPanel(
    params: EditParams,
    selectedMaskId: String?,
    showOverlay: Boolean,
    onAddMask: (MaskTool) -> Unit,
    onSelectMask: (String) -> Unit,
    onRemoveMask: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onSize: (String, Float) -> Unit,
    onFeather: (String, Float) -> Unit,
    onOpacity: (String, Float) -> Unit,
    onInvert: (String, Boolean) -> Unit,
    onCenter: (String, Float, Float) -> Unit,
    onRadius: (String, Float) -> Unit,
    onAngle: (String, Float) -> Unit,
    onPosition: (String, Float) -> Unit,
    onExposure: (String, Float) -> Unit,
    onTemperature: (String, Float) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    onOp: (String, MaskOp) -> Unit = { _, _ -> },
    onSaturation: (String, Float) -> Unit = { _, _ -> },
    onClarity: (String, Float) -> Unit = { _, _ -> },
    onBlur: (String, Float) -> Unit = { _, _ -> },
    onHueCenter: (String, Float) -> Unit = { _, _ -> },
    onHueRange: (String, Float) -> Unit = { _, _ -> },
    onLumaLo: (String, Float) -> Unit = { _, _ -> },
    onLumaHi: (String, Float) -> Unit = { _, _ -> },
    onLumaFeather: (String, Float) -> Unit = { _, _ -> },
    maskSampleArmedId: String? = null,
    onArmSample: (String?) -> Unit = {},
    // M12 heuristic select: working kind label ("subject"/"sky") or null,
    // plus the transient honest result/failure message.
    aiWorkingKind: String? = null,
    aiMessage: String? = null,
    onSelectSubject: () -> Unit = {},
    onSelectSky: () -> Unit = {},
    onCancelAi: () -> Unit = {},
    onDismissAiMessage: () -> Unit = {}
) {
    val selected = params.masks.firstOrNull { it.id == selectedMaskId } ?: params.masks.lastOrNull()
    val maskCardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Mask",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // M12: heuristic tools have dedicated select buttons below (they
            // need a cached field, not an empty row), so chips stay manual.
            MaskTool.entries.filter { !it.isAi() }.forEach { tool ->
                val canAdd = params.masks.size < EditParams.MAX_MASKS
                CategoryChip(
                    label = tool.label,
                    selected = selected?.tool == tool,
                    // At the cap the chip stays live: tapping selects the newest
                    // mask of that tool instead of silently doing nothing.
                    onClick = {
                        if (canAdd) {
                            onAddMask(tool)
                        } else {
                            val fallback = params.masks.lastOrNull { it.tool == tool }
                                ?: params.masks.lastOrNull()
                            if (fallback != null) onSelectMask(fallback.id)
                        }
                    }
                )
            }
        }
        Text(
            text = if (params.masks.size >= EditParams.MAX_MASKS)
                "Limit reached (${params.masks.size}/${EditParams.MAX_MASKS}) — delete a mask to add another. Tapping a tool selects it."
            else
                "Add up to ${EditParams.MAX_MASKS} masks. Tap a tool to add; tap a row to select. " +
                    "5–6 masks may exceed the preview budget on low-end devices.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        // M12 heuristic select: result becomes a mask row (cached per
        // project, reused on reopen). Copy says "heuristic", never "AI".
        Text(
            text = "Heuristic select (not AI) — runs offline. Result becomes a mask row.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onSelectSubject,
                enabled = aiWorkingKind == null,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Select subject") }
            TextButton(
                onClick = onSelectSky,
                enabled = aiWorkingKind == null,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Select sky") }
        }
        if (aiWorkingKind != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Selecting $aiWorkingKind…",
                    modifier = Modifier.weight(1f),
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                TextButton(
                    onClick = onCancelAi,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
        }
        if (aiMessage != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = aiMessage,
                    modifier = Modifier.weight(1f),
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                TextButton(
                    onClick = onDismissAiMessage,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Dismiss") }
            }
        }
        if (params.masks.isEmpty()) {
            Text(
                text = "No masks yet. Add a Brush, Linear, Radial, Color-range or Luma-range mask to grade locally.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        } else {
            params.masks.forEach { mask ->
                val isSelected = mask.id == selected?.id
                Card(
                    onClick = { onSelectMask(mask.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) Modifier.border(2.dp, LuminaAmber, maskCardShape)
                            else Modifier
                        ),
                    shape = maskCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            maskTypeIcon(mask.tool),
                            contentDescription = null,
                            tint = LuminaOnSurface
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "${mask.tool.label}${if (mask.isAiTool()) " (heuristic)" else ""} • ${mask.op.label} • ${(mask.opacity * 100f).roundToInt()}% • ${if (mask.visible) "visible" else "hidden"}",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                            Text(
                                text = "exp ${formatMaskExp(mask.exposure)} • temp ${formatMaskTemp(mask.temperature)}" +
                                    " • sat ${formatMaskTemp(mask.saturation)} • cla ${formatMaskTemp(mask.clarity)}" +
                                    (if (mask.inverted) " • inverted" else ""),
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        IconButton(
                            onClick = { onToggleVisible(mask.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                if (mask.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (mask.visible) "Hide" else "Show"
                            )
                        }
                        IconButton(
                            onClick = { onRemoveMask(mask.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete mask")
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Red overlay",
                modifier = Modifier.weight(1f),
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Switch(checked = showOverlay, onCheckedChange = onToggleOverlay)
        }
        Text(
            text = "Shows mask alpha as a red tint on the preview.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        selected?.let { mask ->
            val id = mask.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaskOp.entries.forEach { op ->
                    CategoryChip(
                        label = op.label,
                        selected = mask.op == op,
                        onClick = { onOp(id, op) }
                    )
                }
            }
            Text(
                text = "Combine against previous masks in order: Add unions, Subtract erases, Intersect keeps overlap.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            when (mask.tool) {
                MaskTool.BRUSH, MaskTool.ERASER -> {
                    ProSlider(
                        label = "Size",
                        value = mask.sizePx,
                        onValueChange = { onSize(id, it) },
                        valueRange = EditMask.MIN_SIZE_PX..EditMask.MAX_SIZE_PX,
                        displayValue = "${mask.sizePx.roundToInt()} px"
                    )
                    // v1 has no paint canvas: a brush/eraser mask is a single dot
                    // (seeded at center on add). Center X/Y move that dot via
                    // setMaskCenter, which syncs the lone stroke point.
                    val dotX = mask.points.firstOrNull()?.x ?: mask.centerX
                    val dotY = mask.points.firstOrNull()?.y ?: mask.centerY
                    ProSlider(
                        label = "Center X",
                        value = dotX * 100f,
                        onValueChange = { onCenter(id, it / 100f, dotY) },
                        valueRange = 0f..100f,
                        displayValue = "${(dotX * 100f).roundToInt()}%"
                    )
                    ProSlider(
                        label = "Center Y",
                        value = dotY * 100f,
                        onValueChange = { onCenter(id, dotX, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(dotY * 100f).roundToInt()}%"
                    )
                    Text(
                        text = "Stroke points: ${mask.points.size} (cap ${EditMask.MAX_POINTS})",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                }
                MaskTool.RADIAL -> {
                    ProSlider(
                        label = "Size (radius)",
                        value = mask.radius * 100f,
                        onValueChange = { onRadius(id, it / 100f) },
                        valueRange = 5f..100f,
                        displayValue = "${(mask.radius * 100f).roundToInt()}%"
                    )
                    ProSlider(
                        label = "Center X",
                        value = mask.centerX * 100f,
                        onValueChange = { onCenter(id, it / 100f, mask.centerY) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.centerX * 100f).roundToInt()}%"
                    )
                    ProSlider(
                        label = "Center Y",
                        value = mask.centerY * 100f,
                        onValueChange = { onCenter(id, mask.centerX, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.centerY * 100f).roundToInt()}%"
                    )
                }
                MaskTool.LINEAR -> {
                    ProSlider(
                        label = "Angle",
                        value = mask.angleDeg,
                        onValueChange = { onAngle(id, it) },
                        valueRange = 0f..360f,
                        displayValue = "${mask.angleDeg.roundToInt()}°"
                    )
                    ProSlider(
                        label = "Position",
                        value = mask.position * 100f,
                        onValueChange = { onPosition(id, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.position * 100f).roundToInt()}%"
                    )
                }
                MaskTool.COLOR -> {
                    Text(
                        text = "Color range selects by hue across the frame (eyedropper seeds the center).",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    TextButton(
                        onClick = {
                            onArmSample(if (maskSampleArmedId == id) null else id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (maskSampleArmedId == id) "Tap photo to sample… (tap to cancel)"
                            else "Pick from photo"
                        )
                    }
                    ProSlider(
                        label = "Hue center",
                        value = mask.hueCenter,
                        onValueChange = { onHueCenter(id, it) },
                        valueRange = 0f..360f,
                        displayValue = "${mask.hueCenter.roundToInt()}°"
                    )
                    ProSlider(
                        label = "Hue range",
                        value = mask.hueRange,
                        onValueChange = { onHueRange(id, it) },
                        valueRange = 10f..180f,
                        displayValue = "${mask.hueRange.roundToInt()}°"
                    )
                }
                MaskTool.LUMINANCE -> {
                    Text(
                        text = "Luma range selects by brightness across the frame.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    ProSlider(
                        label = "Luma lo",
                        value = mask.lumaLo * 100f,
                        onValueChange = { onLumaLo(id, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.lumaLo * 100f).roundToInt()}%"
                    )
                    ProSlider(
                        label = "Luma hi",
                        value = mask.lumaHi * 100f,
                        onValueChange = { onLumaHi(id, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.lumaHi * 100f).roundToInt()}%"
                    )
                    ProSlider(
                        label = "Luma feather",
                        value = mask.lumaFeather * 100f,
                        onValueChange = { onLumaFeather(id, it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(mask.lumaFeather * 100f).roundToInt()}%"
                    )
                }
                MaskTool.AI_SUBJECT, MaskTool.AI_SKY -> {
                    // Heuristic rows have no geometry: feather/opacity/invert
                    // plus the local grades below do the refining. Manual
                    // refine = add a Brush Subtract mask on top (existing ops).
                    Text(
                        text = if (mask.tool == MaskTool.AI_SUBJECT)
                            "Heuristic subject guess — refine with Feather or a Brush Subtract mask."
                        else
                            "Heuristic sky guess — refine with Feather or a Brush Subtract mask.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    if (mask.cacheKey == null) {
                        Text(
                            text = "No cached selection — delete this row and select again.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                }
            }
            ProSlider(
                label = "Feather",
                value = mask.feather * 100f,
                onValueChange = { onFeather(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(mask.feather * 100f).roundToInt()}%"
            )
            ProSlider(
                label = "Blur (alpha)",
                value = mask.blur,
                onValueChange = { onBlur(id, it) },
                valueRange = 0f..EditMask.MAX_BLUR,
                displayValue = if (mask.blur <= 0.05f) "Off" else "${mask.blur.roundToInt()}"
            )
            Text(
                text = "Blur softens the mask edge.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            ProSlider(
                label = "Opacity",
                value = mask.opacity * 100f,
                onValueChange = { onOpacity(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(mask.opacity * 100f).roundToInt()}%"
            )
            Text(
                text = "Opacity acts as Density.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Invert mask",
                    modifier = Modifier.weight(1f),
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface
                )
                Switch(checked = mask.inverted, onCheckedChange = { onInvert(id, it) })
            }
            ProSlider(
                label = "Exposure (local)",
                value = mask.exposure,
                onValueChange = { onExposure(id, it) },
                valueRange = -5f..5f,
                displayValue = formatMaskExp(mask.exposure)
            )
            ProSlider(
                label = "Temperature (local)",
                value = mask.temperature,
                onValueChange = { onTemperature(id, it) },
                valueRange = -100f..100f,
                displayValue = formatMaskTemp(mask.temperature)
            )
            ProSlider(
                label = "Saturation (local)",
                value = mask.saturation,
                onValueChange = { onSaturation(id, it) },
                valueRange = -100f..100f,
                displayValue = formatMaskTemp(mask.saturation)
            )
            ProSlider(
                label = "Clarity (local)",
                value = mask.clarity,
                onValueChange = { onClarity(id, it) },
                valueRange = -100f..100f,
                displayValue = formatMaskTemp(mask.clarity)
            )
            Text(
                text = "Same rendering as the global stages.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset masks") }
        }
    }
}

private fun formatMaskExp(v: Float): String {
    val rounded = (v * 10).roundToInt() / 10f
    return (if (rounded > 0) "+" else "") + rounded.toString()
}

private fun formatMaskTemp(v: Float): String =
    (if (v > 0) "+" else "") + v.roundToInt().toString()
