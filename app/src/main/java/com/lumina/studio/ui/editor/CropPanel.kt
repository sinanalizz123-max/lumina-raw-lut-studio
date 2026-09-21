package com.lumina.studio.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.EditParams
import kotlin.math.roundToInt

@Composable
fun CropPanel(
    params: EditParams,
    onRatio: (CropRatio) -> Unit,
    onRotate: () -> Unit,
    onStraighten: (Float) -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onPerspectiveV: (Float) -> Unit = {},
    onPerspectiveH: (Float) -> Unit = {},
    onCustomAspect: (Float, Float) -> Unit = { _, _ -> },
    onVignette: (Float) -> Unit = {},
    onCa: (Float) -> Unit = {},
    onDistortion: (Float) -> Unit = {},
    onResetOptics: () -> Unit = {}
) {
    val crop = params.crop
    val optics = params.optics
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Crop",
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
            CropRatio.entries.forEach { ratio ->
                CategoryChip(
                    label = ratio.label,
                    selected = crop.ratio == ratio,
                    onClick = { onRatio(ratio) }
                )
            }
        }
        Text(
            text = "Crop applies to the preview and the export. The frame shows the kept area.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        if (crop.ratio == CropRatio.FREE) {
            Text(
                text = "Drag the rectangle on the photo",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        }
        if (crop.ratio == CropRatio.CUSTOM) {
            var wText by remember(crop.customW) { mutableStateOf(trimNum(crop.customW)) }
            var hText by remember(crop.customH) { mutableStateOf(trimNum(crop.customH)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = wText,
                    onValueChange = { raw ->
                        wText = raw
                        raw.toFloatOrNull()?.let { w ->
                            crop.customH.takeIf { it > 0f }?.let { h ->
                                if (w in 0.1f..99f) onCustomAspect(w, h)
                            }
                        }
                    },
                    label = { Text("W") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Text(text = ":", style = LuminaSectionHeaderTextStyle, color = LuminaOnSurface)
                OutlinedTextField(
                    value = hText,
                    onValueChange = { raw ->
                        hText = raw
                        raw.toFloatOrNull()?.let { h ->
                            if (h in 0.1f..99f) onCustomAspect(crop.customW, h)
                        }
                    },
                    label = { Text("H") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Text(
                text = "Custom aspect ${trimNum(crop.customW)}:${trimNum(crop.customH)} (0.1–99).",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onRotate,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Rotate 90°") }
            TextButton(
                onClick = onFlipH,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (crop.flipH) "● Flip H" else "Flip H") }
            TextButton(
                onClick = onFlipV,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (crop.flipV) "● Flip V" else "Flip V") }
        }
        ProSlider(
            label = "Straighten",
            value = crop.straightenDeg,
            onValueChange = onStraighten,
            valueRange = -45f..45f,
            displayValue = "${crop.straightenDeg.roundToInt()}°",
            onReset = { onStraighten(0f) }
        )
        Text(
            text = "Perspective (manual)",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        ProSlider(
            label = "Perspective vertical",
            value = crop.perspectiveV,
            onValueChange = onPerspectiveV,
            valueRange = -100f..100f,
            displayValue = "${crop.perspectiveV.roundToInt()}",
            onReset = { onPerspectiveV(0f) }
        )
        ProSlider(
            label = "Perspective horizontal",
            value = crop.perspectiveH,
            onValueChange = onPerspectiveH,
            valueRange = -100f..100f,
            displayValue = "${crop.perspectiveH.roundToInt()}",
            onReset = { onPerspectiveH(0f) }
        )
        Text(
            text = "Projective warp before the crop cut (Matrix.setPolyToPoly approx). " +
                "Order: rotate → straighten → flip → perspective → crop cut.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Text(
            text = "Optics (manual only)",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        Text(
            text = "Manual only — no profile database.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        ProSlider(
            label = "Vignette correction",
            value = optics.vignetteCorr,
            onValueChange = onVignette,
            valueRange = -100f..100f,
            displayValue = "${optics.vignetteCorr.roundToInt()}",
            onReset = { onVignette(0f) }
        )
        ProSlider(
            label = "CA shift (px)",
            value = optics.caShift,
            onValueChange = onCa,
            valueRange = -10f..10f,
            displayValue = "${(optics.caShift * 10).roundToInt() / 10f}",
            onReset = { onCa(0f) }
        )
        ProSlider(
            label = "Distortion",
            value = optics.distortion,
            onValueChange = onDistortion,
            valueRange = -100f..100f,
            displayValue = "${optics.distortion.roundToInt()}",
            onReset = { onDistortion(0f) }
        )
        Text(
            text = "Inverse-vignette gain from corners, radial red/blue shift, " +
                "inverse radial remap. All early-out at 0.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onResetOptics,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset optics") }
            TextButton(
                onClick = onReset,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset crop") }
        }
    }
}

private fun trimNum(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}
