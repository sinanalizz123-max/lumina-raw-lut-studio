package com.lumina.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.EditParams
import kotlin.math.roundToInt

@Composable
fun BlurPanel(
    params: EditParams,
    focusArmed: Boolean,
    showDepth: Boolean,
    onArmFocus: (Boolean) -> Unit,
    onAmount: (Float) -> Unit,
    onTransition: (Float) -> Unit,
    onFocusRadius: (Float) -> Unit,
    onToggleDepth: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blur = params.lensBlur
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Lens blur",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Text(
            text = "Heuristic background blur from a focus point — not AI, no depth sensor is used. " +
                "The sharp ellipse stays in focus; blur grows with distance.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap photo to set focus",
                modifier = Modifier.weight(1f),
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Switch(checked = focusArmed, onCheckedChange = onArmFocus)
        }
        Text(
            text = "Focus at ${(blur.focusX * 100f).roundToInt()}%, ${(blur.focusY * 100f).roundToInt()}%",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        ProSlider(
            label = "Amount",
            value = blur.amount,
            onValueChange = onAmount,
            valueRange = 0f..100f,
            displayValue = "${blur.amount.roundToInt()}%"
        )
        ProSlider(
            label = "Transition",
            value = blur.transition * 100f,
            onValueChange = { onTransition(it / 100f) },
            valueRange = 0f..100f,
            displayValue = "${(blur.transition * 100f).roundToInt()}%"
        )
        Text(
            text = "Transition softens the sharp-to-blurred edge.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        ProSlider(
            label = "Focus radius",
            value = blur.focusRadius * 100f,
            onValueChange = { onFocusRadius(it / 100f) },
            valueRange = 0f..100f,
            displayValue = "${(blur.focusRadius * 100f).roundToInt()}%"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Show depth map",
                modifier = Modifier.weight(1f),
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Switch(checked = showDepth, onCheckedChange = onToggleDepth)
        }
        Text(
            text = "Grayscale preview of the heuristic depth field (dark = focus, bright = blurred).",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onReset,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset blur") }
        }
    }
}
