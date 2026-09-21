package com.lumina.studio.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaBackground
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle

enum class EmptyStateIllustration {
    None,
    Photo,
    Palette,
    Folder,
    Sliders
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    illustration: EmptyStateIllustration = EmptyStateIllustration.None
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (illustration != EmptyStateIllustration.None) {
            EmptyIllustration(type = illustration)
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, color = LuminaMuted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LuminaAmber,
                    contentColor = LuminaBackground
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyIllustration(
    type: EmptyStateIllustration,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(96.dp)) {
        val strokePx = 2.5.dp.toPx()
        val style = Stroke(width = strokePx)
        val frame = 12.dp.toPx()
        when (type) {
            EmptyStateIllustration.Photo -> {
                drawRoundRect(
                    color = LuminaMuted,
                    topLeft = Offset(frame, 18.dp.toPx()),
                    size = Size(size.width - frame * 2f, size.height - 36.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = style
                )
                drawCircle(
                    color = LuminaAmber,
                    radius = 5.dp.toPx(),
                    center = Offset(size.width * 0.68f, size.height * 0.37f)
                )
                val peaks = Path().apply {
                    moveTo(frame, size.height - 18.dp.toPx())
                    lineTo(size.width * 0.42f, size.height * 0.52f)
                    lineTo(size.width * 0.58f, size.height * 0.65f)
                    lineTo(size.width * 0.72f, size.height * 0.5f)
                    lineTo(size.width - frame, size.height - 18.dp.toPx())
                }
                drawPath(path = peaks, color = LuminaMuted, style = style)
            }
            EmptyStateIllustration.Palette -> {
                drawCircle(
                    color = LuminaMuted,
                    radius = size.minDimension * 0.36f,
                    center = center,
                    style = style
                )
                drawCircle(
                    color = LuminaAmber,
                    radius = 4.dp.toPx(),
                    center = center + Offset(-14.dp.toPx(), -8.dp.toPx())
                )
                drawCircle(
                    color = LuminaMuted,
                    radius = 3.dp.toPx(),
                    center = center + Offset(10.dp.toPx(), -12.dp.toPx())
                )
                drawCircle(
                    color = LuminaMuted,
                    radius = 3.dp.toPx(),
                    center = center + Offset(14.dp.toPx(), 10.dp.toPx())
                )
                drawCircle(
                    color = LuminaMuted,
                    radius = 3.dp.toPx(),
                    center = center + Offset(-8.dp.toPx(), 14.dp.toPx())
                )
            }
            EmptyStateIllustration.Folder -> {
                val tab = Path().apply {
                    moveTo(16.dp.toPx(), 32.dp.toPx())
                    lineTo(16.dp.toPx(), 24.dp.toPx())
                    lineTo(36.dp.toPx(), 24.dp.toPx())
                    lineTo(42.dp.toPx(), 32.dp.toPx())
                }
                drawPath(path = tab, color = LuminaMuted, style = style)
                drawRoundRect(
                    color = LuminaMuted,
                    topLeft = Offset(16.dp.toPx(), 32.dp.toPx()),
                    size = Size(size.width - 32.dp.toPx(), size.height - 56.dp.toPx()),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = style
                )
                drawCircle(
                    color = LuminaAmber,
                    radius = 3.dp.toPx(),
                    center = Offset(size.width / 2f, size.height - 24.dp.toPx() + 12.dp.toPx())
                )
            }
            EmptyStateIllustration.Sliders -> {
                val knobXs = listOf(0.35f, 0.62f, 0.45f)
                for (i in 0..2) {
                    val y = size.height * (0.3f + 0.2f * i)
                    drawLine(
                        color = LuminaMuted,
                        start = Offset(14.dp.toPx(), y),
                        end = Offset(size.width - 14.dp.toPx(), y),
                        strokeWidth = strokePx
                    )
                    val knobCenter = Offset(size.width * knobXs[i], y)
                    drawCircle(
                        color = LuminaBackground,
                        radius = 7.dp.toPx(),
                        center = knobCenter
                    )
                    drawCircle(
                        color = if (i == 1) LuminaAmber else LuminaMuted,
                        radius = 7.dp.toPx(),
                        center = knobCenter,
                        style = style
                    )
                    if (i == 1) {
                        drawCircle(
                            color = LuminaAmber,
                            radius = 2.5.dp.toPx(),
                            center = knobCenter
                        )
                    }
                }
            }
            EmptyStateIllustration.None -> Unit
        }
    }
}
