package com.lumina.studio.core.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LuminaColorScheme = darkColorScheme(
    primary = LuminaAmber,
    onPrimary = LuminaBackground,
    secondary = LuminaAmber,
    onSecondary = LuminaBackground,
    background = LuminaBackground,
    onBackground = LuminaOnSurface,
    surface = LuminaSurface,
    onSurface = LuminaOnSurface,
    surfaceVariant = LuminaSurfaceVariant,
    onSurfaceVariant = LuminaMuted,
    surfaceContainerLowest = LuminaSurfaceContainerLowest,
    surfaceContainerLow = LuminaSurfaceContainerLow,
    surfaceContainer = LuminaSurfaceContainer,
    surfaceContainerHigh = LuminaSurfaceContainerHigh,
    scrim = LuminaScrim,
    error = LuminaError,
    onError = LuminaBackground
)

private val LuminaDarkPlusScheme = darkColorScheme(
    primary = LuminaAmber,
    onPrimary = LuminaBackground,
    secondary = LuminaAmber,
    onSecondary = LuminaBackground,
    background = LuminaBackground,
    onBackground = LuminaOnSurface,
    surface = LuminaSurfaceVariant,
    onSurface = LuminaOnSurface,
    surfaceVariant = LuminaSurface,
    onSurfaceVariant = LuminaMuted,
    surfaceContainerLowest = LuminaSurfaceContainerLow,
    surfaceContainerLow = LuminaSurfaceContainer,
    surfaceContainer = LuminaSurfaceContainerHigh,
    surfaceContainerHigh = LuminaSurfaceContainerHigh,
    scrim = LuminaScrim,
    error = LuminaError,
    onError = LuminaBackground
)

@Composable
fun LuminaTheme(darkPlus: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkPlus) LuminaDarkPlusScheme else LuminaColorScheme,
        typography = LuminaTypography,
        shapes = LuminaShapes,
        content = content
    )
}
