package com.lumina.studio.core.design.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.theme.LuminaMotion
import com.lumina.studio.core.design.theme.LuminaShapes
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow

enum class LoadingShimmerStyle {
    List,
    Card
}

@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    style: LoadingShimmerStyle = LoadingShimmerStyle.List,
    rows: Int = 3
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = LuminaMotion.MediumMillis * 4,
                easing = LuminaMotion.StandardEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerPulse"
    )
    when (style) {
        LoadingShimmerStyle.List -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .graphicsLayer { alpha = pulse },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(rows.coerceAtLeast(1)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(LuminaShapes.medium)
                            .background(LuminaSurfaceContainerLow)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.5f)
                                    .height(14.dp)
                                    .clip(LuminaShapes.extraSmall)
                                    .background(LuminaSurfaceContainerHigh)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(12.dp)
                                    .clip(LuminaShapes.extraSmall)
                                    .background(LuminaSurfaceContainerHigh)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        LoadingShimmerStyle.Card -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .graphicsLayer { alpha = pulse },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(LuminaShapes.medium)
                        .background(LuminaSurfaceContainerLow)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .clip(LuminaShapes.extraSmall)
                        .background(LuminaSurfaceContainerHigh)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(12.dp)
                        .clip(LuminaShapes.extraSmall)
                        .background(LuminaSurfaceContainerHigh)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
