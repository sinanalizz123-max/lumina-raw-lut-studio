package com.lumina.studio.ui.editor

import android.graphics.Bitmap
import android.graphics.Color.HSVToColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private fun makeGradeWheel(sizePx: Int = 168): ImageBitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val half = sizePx / 2f
    val hsv = FloatArray(3)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            val dx = (x + 0.5f - half) / half
            val dy = (y + 0.5f - half) / half
            val r = sqrt(dx * dx + dy * dy)
            if (r > 1f) {
                bmp.setPixel(x, y, 0)
            } else {
                val hue = ((atan2(dy, dx) * 180.0 / PI).toFloat() + 360f) % 360f
                hsv[0] = hue
                hsv[1] = r.coerceIn(0f, 1f)
                hsv[2] = 1f
                bmp.setPixel(x, y, HSVToColor(hsv))
            }
        }
    }
    return bmp.asImageBitmap()
}

private fun formatSigned(value: Float): String =
    (if (value > 0) "+" else "") + value.roundToInt().toString()

@Composable
fun GradePanel(
    params: EditParams,
    selected: GradeZone = GradeZone.GLOBAL,
    onSelectZone: (GradeZone) -> Unit = {},
    onBeginDrag: () -> Unit = {},
    onLiveZone: (GradeZone, GradeAdjust) -> Unit = { _, _ -> },
    onSetZone: (GradeZone, GradeAdjust) -> Unit = { _, _ -> },
    onResetZone: (GradeZone) -> Unit = {},
    onBlending: (Float) -> Unit = {},
    onBalance: (Float) -> Unit = {},
    onResetAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val adjust = params.getGrade(selected)
    val paramsRef = rememberUpdatedState(params)
    val liveRef = rememberUpdatedState(onLiveZone)
    val setRef = rememberUpdatedState(onSetZone)
    val beginRef = rememberUpdatedState(onBeginDrag)
    val wheel = remember { makeGradeWheel() }
    var hField by remember(selected, adjust) { mutableStateOf(adjust.hue.roundToInt().toString()) }
    var sField by remember(selected, adjust) { mutableStateOf(adjust.sat.roundToInt().toString()) }
    var lField by remember(selected, adjust) { mutableStateOf(adjust.lum.roundToInt().toString()) }

    fun updateFromOffset(pos: Offset, wPx: Float, hPx: Float) {
        if (wPx <= 0f || hPx <= 0f) return
        if (!pos.x.isFinite() || !pos.y.isFinite()) return
        val dx = pos.x - wPx / 2f
        val dy = pos.y - hPx / 2f
        val radius = minOf(wPx, hPx) / 2f
        if (radius <= 0f) return
        val hue = ((atan2(dy, dx) * 180.0 / PI).toFloat() + 360f) % 360f
        val sat = (sqrt(dx * dx + dy * dy) / radius * 100f).coerceIn(0f, 100f)
        val zone = selected
        val current = paramsRef.value.getGrade(zone)
        liveRef.value.invoke(zone, current.copy(hue = hue, sat = sat))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Grade",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset grade") }
        }
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GradeZone.entries.forEach { zone ->
                CategoryChip(
                    label = zone.label,
                    selected = zone == selected,
                    onClick = { onSelectZone(zone) }
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(200.dp)
                    .semantics { contentDescription = "Grade color wheel for ${selected.label}" }
                    .pointerInput(selected) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val wPx = size.width.toFloat()
                            val hPx = size.height.toFloat()
                            beginRef.value.invoke()
                            updateFromOffset(down.position, wPx, hPx)
                            down.consume()
                            var done = false
                            while (!done) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    done = true
                                } else if (!change.pressed) {
                                    done = true
                                } else {
                                    updateFromOffset(change.position, wPx, hPx)
                                    change.consume()
                                }
                                if (event.changes.all { !it.pressed }) done = true
                            }
                            val zone = selected
                            setRef.value.invoke(zone, paramsRef.value.getGrade(zone))
                        }
                    }
            ) {
                drawImage(wheel)
                val radius = minOf(size.width, size.height) / 2f
                val angleRad = adjust.hue * PI.toFloat() / 180f
                val dist = adjust.sat.coerceIn(0f, 100f) / 100f * radius
                val center = Offset(
                    size.width / 2f + cos(angleRad) * dist,
                    size.height / 2f + sin(angleRad) * dist
                )
                drawCircle(Color.Black, radius = 20f, center = center)
                drawCircle(Color.White, radius = 16f, center = center)
            }
        }
        Text(
            text = "${selected.label}: hue ${adjust.hue.roundToInt()}° • sat ${adjust.sat.roundToInt()} • lum ${formatSigned(adjust.lum)}",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hField,
                onValueChange = { v -> hField = v.filter { it.isDigit() }.take(3) },
                label = { Text("Hue 0-360") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sField,
                onValueChange = { v -> sField = v.filter { it.isDigit() }.take(3) },
                label = { Text("Sat 0-100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lField,
                onValueChange = { v -> lField = v.filter { it.isDigit() || it == '-' }.take(4) },
                label = { Text("Lum ±100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    val h = hField.toIntOrNull()?.coerceIn(0, 360) ?: return@TextButton
                    val s = sField.toIntOrNull()?.coerceIn(0, 100) ?: return@TextButton
                    val l = lField.toIntOrNull()?.coerceIn(-100, 100) ?: return@TextButton
                    setRef.value.invoke(
                        selected,
                        GradeAdjust(h.toFloat(), s.toFloat(), l.toFloat())
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Apply values") }
            TextButton(
                onClick = { onResetZone(selected) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset ${selected.label}") }
        }
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        ProSlider(
            label = "Blending",
            value = params.grade.blending,
            onValueChange = onBlending,
            valueRange = 0f..100f,
            displayValue = "${params.grade.blending.roundToInt()}%",
            onReset = { onBlending(50f) }
        )
        ProSlider(
            label = "Balance",
            value = params.grade.balance,
            onValueChange = onBalance,
            valueRange = -100f..100f,
            displayValue = formatSigned(params.grade.balance),
            onReset = { onBalance(0f) }
        )
        Text(
            text = "Wheels tint shadows, midtones and highlights; blending scales every wheel.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
    }
}
