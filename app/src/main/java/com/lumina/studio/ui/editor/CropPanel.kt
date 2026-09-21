package com.lumina.studio.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    modifier: Modifier = Modifier
) {
    val crop = params.crop
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onReset,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset crop") }
        }
    }
}
