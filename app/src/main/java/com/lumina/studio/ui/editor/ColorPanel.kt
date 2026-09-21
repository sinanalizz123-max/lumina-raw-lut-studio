package com.lumina.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.HslColor
import kotlin.math.roundToInt

private fun hslDisplayColor(color: HslColor): Color = when (color) {
    HslColor.RED -> Color(0xFFE53935)
    HslColor.ORANGE -> Color(0xFFFB8C00)
    HslColor.YELLOW -> Color(0xFFFDD835)
    HslColor.GREEN -> Color(0xFF43A047)
    HslColor.AQUA -> Color(0xFF00ACC1)
    HslColor.BLUE -> Color(0xFF1E88E5)
    HslColor.PURPLE -> Color(0xFF8E24AA)
    HslColor.MAGENTA -> Color(0xFFD81B60)
}

private fun formatHsl(value: Float): String =
    (if (value > 0) "+" else "") + value.roundToInt().toString()

@Composable
fun ColorPanel(
    params: EditParams,
    selected: HslColor,
    eyedropperArmed: Boolean,
    onSelectColor: (HslColor) -> Unit,
    onToggleEyedropper: (Boolean) -> Unit,
    onHue: (HslColor, Float) -> Unit,
    onSat: (HslColor, Float) -> Unit,
    onLum: (HslColor, Float) -> Unit,
    onResetColor: (HslColor) -> Unit,
    onGlobalSat: (Float) -> Unit,
    onGlobalVib: (Float) -> Unit,
    onResetGlobalSat: () -> Unit,
    onResetGlobalVib: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val adjust = params.getHsl(selected)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Color",
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
            HslColor.entries.forEach { color ->
                val isSelected = color == selected
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = color.label }
                        .clickable { onSelectColor(color) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(hslDisplayColor(color))
                            .then(
                                if (isSelected) Modifier.border(2.dp, LuminaAmber, CircleShape)
                                else Modifier.border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.35f),
                                    CircleShape
                                )
                            )
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onToggleEyedropper(!eyedropperArmed) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (eyedropperArmed) "● Picking… tap photo" else "Eyedropper")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { onResetColor(selected) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset ${selected.label}") }
        }
        if (eyedropperArmed) {
            Text(
                text = "Tap the photo to pick the nearest color.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        }
        ProSlider(
            label = "${selected.label} hue",
            value = adjust.hue,
            onValueChange = { onHue(selected, it) },
            valueRange = -100f..100f,
            displayValue = formatHsl(adjust.hue),
            onReset = { onResetColor(selected) }
        )
        ProSlider(
            label = "${selected.label} saturation",
            value = adjust.sat,
            onValueChange = { onSat(selected, it) },
            valueRange = -100f..100f,
            displayValue = formatHsl(adjust.sat),
            onReset = { onResetColor(selected) }
        )
        ProSlider(
            label = "${selected.label} luminance",
            value = adjust.lum,
            onValueChange = { onLum(selected, it) },
            valueRange = -100f..100f,
            displayValue = formatHsl(adjust.lum),
            onReset = { onResetColor(selected) }
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Text(
            text = "Global",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        ProSlider(
            label = "Global saturation",
            value = params.globalSat,
            onValueChange = onGlobalSat,
            valueRange = -100f..100f,
            displayValue = formatHsl(params.globalSat),
            onReset = onResetGlobalSat
        )
        ProSlider(
            label = "Global vibrance",
            value = params.globalVib,
            onValueChange = onGlobalVib,
            valueRange = -100f..100f,
            displayValue = formatHsl(params.globalVib),
            onReset = onResetGlobalVib
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset HSL") }
        }
    }
}
