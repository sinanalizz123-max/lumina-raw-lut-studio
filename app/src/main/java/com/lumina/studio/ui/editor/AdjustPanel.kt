package com.lumina.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.EditParams
import kotlin.math.roundToInt

@Composable
fun AdjustPanel(
    params: EditParams,
    onControl: (AdjustControl, Float) -> Unit,
    onResetControl: (AdjustControl) -> Unit,
    modifier: Modifier = Modifier,
    onAuto: () -> Unit = {},
    isDevelopedRaw: Boolean = false,
    rawRecipe: com.lumina.studio.core.render.RawRecipe? = null,
    onRawRecipe: (com.lumina.studio.core.render.RawRecipe) -> Unit = {},
    onRawEyedropper: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Adjust",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onAuto,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Auto") }
        }
        Text(
            text = "Auto sets exposure + contrast from a simple levels analysis — not AI.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        if (isDevelopedRaw && rawRecipe != null) {
            Text(
                text = "RAW (linear develop)",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Text(
                text = "Developed in linear light — not a filter.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            ProSlider(
                label = "RAW exposure (EV)",
                value = rawRecipe.exposureEv,
                onValueChange = { onRawRecipe(rawRecipe.copy(exposureEv = it.coerceIn(-5f, 5f))) },
                valueRange = -5f..5f,
                displayValue = (if (rawRecipe.exposureEv > 0) "+" else "") + ((rawRecipe.exposureEv * 10).roundToInt() / 10f).toString(),
                onReset = { onRawRecipe(rawRecipe.copy(exposureEv = 0f)) }
            )
            ProSlider(
                label = "RAW WB red gain",
                value = rawRecipe.tempGain,
                onValueChange = { onRawRecipe(rawRecipe.copy(tempGain = it.coerceIn(0.2f, 5f))) },
                valueRange = 0.2f..5f,
                displayValue = ((rawRecipe.tempGain * 100).roundToInt() / 100f).toString(),
                onReset = { onRawRecipe(rawRecipe.copy(tempGain = 1f)) }
            )
            ProSlider(
                label = "RAW WB blue gain",
                value = rawRecipe.tintGain,
                onValueChange = { onRawRecipe(rawRecipe.copy(tintGain = it.coerceIn(0.2f, 5f))) },
                valueRange = 0.2f..5f,
                displayValue = ((rawRecipe.tintGain * 100).roundToInt() / 100f).toString(),
                onReset = { onRawRecipe(rawRecipe.copy(tintGain = 1f)) }
            )
            TextButton(
                onClick = onRawEyedropper,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Pick WB from image") }
            TextButton(
                onClick = { onRawRecipe(com.lumina.studio.core.render.RawRecipe()) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset RAW") }
            HorizontalDivider(color = LuminaSurfaceContainerHigh)
        }
        AdjustControl.entries.forEach { control ->
            var fine by remember(control) { mutableStateOf(false) }
            val step = if (fine) control.step / 10f else control.step
            ProSlider(
                label = control.label,
                value = params.get(control),
                onValueChange = { raw ->
                    val snapped = if (fine && step > 0f) {
                        (raw / step).roundToInt() * step
                    } else {
                        raw
                    }
                    onControl(control, snapped.coerceIn(control.min, control.max))
                },
                valueRange = control.min..control.max,
                displayValue = control.format(params.get(control)),
                onReset = { onResetControl(control) },
                fineActive = fine,
                onFineChange = { fine = it }
            )
        }
    }
}
