package com.lumina.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaScrim
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.PresetCategory
import com.lumina.studio.navigation.Routes
import com.lumina.studio.ui.presets.PresetThumb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    navController: NavController,
    packId: String,
    presetsViewModel: PresetsViewModel = viewModel()
) {
    val presets by presetsViewModel.presets.collectAsState()
    val thumbSource by presetsViewModel.thumbSource.collectAsState()

    val category = remember(packId) { packCategoryOf(packId) }
    val packName = category?.label ?: packId.ifBlank { "Pack" }
    val packPresets = remember(presets, category, packId) {
        if (category != null) {
            presets.filter { it.category.equals(category.label, ignoreCase = true) }
        } else {
            presets.filter { it.packId == packId }
        }
    }
    val display: List<Preset> = remember(packPresets, category, presets) {
        if (packPresets.isNotEmpty()) {
            packPresets
        } else if (presets.isEmpty() && category != null) {
            BuiltInPresets.list
                .filter { it.category == category }
                .map { demo ->
                    Preset(
                        id = demo.id,
                        name = demo.name,
                        category = demo.category.label,
                        isFavorite = false,
                        defaultIntensity = demo.defaultIntensity,
                        cubeText = null,
                        createdAt = 0L
                    )
                }
        } else {
            packPresets
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(packName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = packName,
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Text(
                text = if (display.size == 1) "1 preset" else "${display.size} presets",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            if (display.isEmpty()) {
                EmptyState(
                    title = "No presets in this pack",
                    message = "Try another pack or import a .CUBE from the library.",
                    illustration = EmptyStateIllustration.Palette
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(display, key = { it.id }) { preset ->
                        val packCardShape = RoundedCornerShape(16.dp)
                        Card(
                            onClick = { navController.navigate(Routes.presetDetail(preset.id)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = packCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = LuminaSurfaceContainerLow
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    PresetThumb(
                                        preset = preset,
                                        source = thumbSource,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(4f / 3f)
                                            .clip(packCardShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(LuminaScrim),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        IconButton(
                                            onClick = { presetsViewModel.toggleFavorite(preset) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                if (preset.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                                contentDescription = if (preset.isFavorite) "Unfavorite" else "Favorite",
                                                tint = if (preset.isFavorite) LuminaAmber else LuminaOnSurface
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = preset.name,
                                    style = LuminaSectionHeaderTextStyle,
                                    color = LuminaOnSurface,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    text = preset.category,
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun packCategoryOf(packId: String): PresetCategory? {
    val norm = packId.trim()
    if (norm.isBlank()) return null
    if (
        norm.equals("B&W", ignoreCase = true) ||
        norm.equals("B-W", ignoreCase = true) ||
        norm.equals("BW", ignoreCase = true) ||
        norm.equals("Black & White", ignoreCase = true)
    ) {
        return PresetCategory.BW
    }
    PresetCategory.entries.firstOrNull { it.label.equals(norm, ignoreCase = true) }?.let { return it }
    PresetCategory.entries.firstOrNull { it.name.equals(norm, ignoreCase = true) }?.let { return it }
    return null
}
