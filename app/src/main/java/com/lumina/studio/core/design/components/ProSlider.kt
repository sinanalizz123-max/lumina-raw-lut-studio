package com.lumina.studio.core.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaMotion
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaValueTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    displayValue: String = value.toInt().toString(),
    onReset: (() -> Unit)? = null,
    fineActive: Boolean = false,
    onFineChange: ((Boolean) -> Unit)? = null,
    onValueChangeFinished: () -> Unit = {},
    showBubble: Boolean = true,
    showTicks: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val bubbleVisible = showBubble && (dragged || pressed)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onReset, onFineChange) {
                    detectTapGestures(
                        onDoubleTap = { onReset?.invoke() },
                        onLongPress = {
                            if (onFineChange != null) onFineChange(!fineActive)
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Text(
                text = if (fineActive) "$displayValue •fine" else displayValue,
                style = LuminaValueTextStyle,
                color = if (fineActive) LuminaAmber else LuminaMuted
            )
        }
        AnimatedVisibility(
            visible = bubbleVisible,
            enter = fadeIn(animationSpec = LuminaMotion.shortTween()) +
                expandVertically(animationSpec = LuminaMotion.shortTween()),
            exit = fadeOut(animationSpec = LuminaMotion.shortTween()) +
                shrinkVertically(animationSpec = LuminaMotion.shortTween())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = LuminaSurfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = displayValue,
                        style = LuminaValueTextStyle,
                        color = LuminaOnSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            interactionSource = interactionSource,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(LuminaSurfaceContainerHigh)
                        .border(1.5.dp, LuminaAmber, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(LuminaAmber)
                    )
                }
            },
            track = { sliderState ->
                val fraction = sliderState.coercedValueAsFraction
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(LuminaSurfaceContainerHigh)
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        Box(
                            modifier = Modifier
                                .weight(fraction.coerceAtLeast(0.001f))
                                .fillMaxHeight()
                                .background(LuminaAmber)
                        )
                        if (fraction < 1f) Spacer(modifier = Modifier.weight(1f - fraction))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$label: $displayValue" }
        )
        if (showTicks) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(LuminaMuted.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}
