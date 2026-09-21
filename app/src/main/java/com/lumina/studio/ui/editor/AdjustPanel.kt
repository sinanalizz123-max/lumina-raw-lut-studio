package com.lumina.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.ProSlider
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Adjust",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
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
