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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lumina.studio.core.edit.MaskTool
import kotlin.math.roundToInt

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
    modifier: Modifier = Modifier
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
            MaskTool.entries.forEach { tool ->
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
                "Add up to ${EditParams.MAX_MASKS} masks in v1. Tap a tool to add; tap a row to select.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        if (params.masks.isEmpty()) {
            Text(
                text = "No masks yet. Add a Brush, Linear or Radial mask to grade locally (exposure + temperature only in v1).",
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${mask.tool.label} • ${(mask.opacity * 100f).roundToInt()}% • ${if (mask.visible) "visible" else "hidden"}",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                            Text(
                                text = "exp ${formatMaskExp(mask.exposure)} • temp ${formatMaskTemp(mask.temperature)}${if (mask.inverted) " • inverted" else ""}",
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
            modifier = Modifier.fillMaxWidth(),
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
            }
            ProSlider(
                label = "Feather",
                value = mask.feather * 100f,
                onValueChange = { onFeather(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(mask.feather * 100f).roundToInt()}%"
            )
            ProSlider(
                label = "Opacity",
                value = mask.opacity * 100f,
                onValueChange = { onOpacity(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(mask.opacity * 100f).roundToInt()}%"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
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
