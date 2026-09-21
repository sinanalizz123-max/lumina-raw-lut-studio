package com.lumina.studio.core.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow

@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    val outline = if (selected) {
        LuminaAmber.copy(alpha = 0.55f)
    } else {
        LuminaMuted.copy(alpha = 0.35f)
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                color = if (selected) LuminaOnSurface else LuminaMuted
            )
        },
        shape = pillShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = LuminaSurfaceContainerLow,
            labelColor = LuminaMuted,
            selectedContainerColor = LuminaAmber.copy(alpha = 0.16f),
            selectedLabelColor = LuminaOnSurface
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .border(1.dp, outline, pillShape)
    )
}
