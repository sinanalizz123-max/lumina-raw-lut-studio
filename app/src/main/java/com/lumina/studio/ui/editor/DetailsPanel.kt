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
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditParams
import kotlin.math.roundToInt

private fun formatDetail(value: Float): String = value.roundToInt().toString()

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = LuminaSectionHeaderTextStyle, color = LuminaOnSurface)
                Text(
                    text = subtitle,
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            TextButton(
                onClick = onToggle,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (expanded) "Hide" else "Show") }
        }
        if (expanded) content()
    }
}

@Composable
fun DetailsPanel(
    params: EditParams,
    onDetail: (DetailControl, Float) -> Unit,
    onResetDetail: (DetailControl) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sharpenExpanded by remember { mutableStateOf(false) }
    var lumaExpanded by remember { mutableStateOf(false) }
    var colorExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Details",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        ProSlider(
            label = DetailControl.TEXTURE.label,
            value = params.getDetail(DetailControl.TEXTURE),
            onValueChange = { onDetail(DetailControl.TEXTURE, it) },
            valueRange = DetailControl.TEXTURE.min..DetailControl.TEXTURE.max,
            displayValue = formatDetail(params.getDetail(DetailControl.TEXTURE)),
            onReset = { onResetDetail(DetailControl.TEXTURE) }
        )
        ProSlider(
            label = DetailControl.CLARITY_ADV.label,
            value = params.getDetail(DetailControl.CLARITY_ADV),
            onValueChange = { onDetail(DetailControl.CLARITY_ADV, it) },
            valueRange = DetailControl.CLARITY_ADV.min..DetailControl.CLARITY_ADV.max,
            displayValue = formatDetail(params.getDetail(DetailControl.CLARITY_ADV)),
            onReset = { onResetDetail(DetailControl.CLARITY_ADV) }
        )
        ProSlider(
            label = DetailControl.DEHAZE_ADV.label,
            value = params.getDetail(DetailControl.DEHAZE_ADV),
            onValueChange = { onDetail(DetailControl.DEHAZE_ADV, it) },
            valueRange = DetailControl.DEHAZE_ADV.min..DetailControl.DEHAZE_ADV.max,
            displayValue = formatDetail(params.getDetail(DetailControl.DEHAZE_ADV)),
            onReset = { onResetDetail(DetailControl.DEHAZE_ADV) }
        )
        Text(
            text = "Texture and Clarity add gentle micro-contrast. Start low for portraits.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        ExpandableSection(
            title = "Sharpening",
            subtitle = "Preview shows a soft approximation; full detail on export.",
            expanded = sharpenExpanded,
            onToggle = { sharpenExpanded = !sharpenExpanded }
        ) {
            ProSlider(
                label = "Sharpen amount",
                value = params.getDetail(DetailControl.SHARPEN_AMOUNT),
                onValueChange = { onDetail(DetailControl.SHARPEN_AMOUNT, it) },
                valueRange = DetailControl.SHARPEN_AMOUNT.min..DetailControl.SHARPEN_AMOUNT.max,
                displayValue = formatDetail(params.getDetail(DetailControl.SHARPEN_AMOUNT)),
                onReset = { onResetDetail(DetailControl.SHARPEN_AMOUNT) }
            )
            ProSlider(
                label = "Sharpen radius",
                value = params.getDetail(DetailControl.SHARPEN_RADIUS),
                onValueChange = { onDetail(DetailControl.SHARPEN_RADIUS, it) },
                valueRange = DetailControl.SHARPEN_RADIUS.min..DetailControl.SHARPEN_RADIUS.max,
                displayValue = formatDetail(params.getDetail(DetailControl.SHARPEN_RADIUS)),
                onReset = { onResetDetail(DetailControl.SHARPEN_RADIUS) }
            )
        }
        ExpandableSection(
            title = "Luminance noise reduction",
            subtitle = "Softens grain. Higher values blur fine detail.",
            expanded = lumaExpanded,
            onToggle = { lumaExpanded = !lumaExpanded }
        ) {
            ProSlider(
                label = DetailControl.NR_LUMA.label,
                value = params.getDetail(DetailControl.NR_LUMA),
                onValueChange = { onDetail(DetailControl.NR_LUMA, it) },
                valueRange = DetailControl.NR_LUMA.min..DetailControl.NR_LUMA.max,
                displayValue = formatDetail(params.getDetail(DetailControl.NR_LUMA)),
                onReset = { onResetDetail(DetailControl.NR_LUMA) }
            )
        }
        ExpandableSection(
            title = "Color noise reduction",
            subtitle = "Default 25 keeps colors clean without smudging.",
            expanded = colorExpanded,
            onToggle = { colorExpanded = !colorExpanded }
        ) {
            ProSlider(
                label = DetailControl.NR_COLOR.label,
                value = params.getDetail(DetailControl.NR_COLOR),
                onValueChange = { onDetail(DetailControl.NR_COLOR, it) },
                valueRange = DetailControl.NR_COLOR.min..DetailControl.NR_COLOR.max,
                displayValue = formatDetail(params.getDetail(DetailControl.NR_COLOR)),
                onReset = { onResetDetail(DetailControl.NR_COLOR) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset details") }
        }
    }
}
