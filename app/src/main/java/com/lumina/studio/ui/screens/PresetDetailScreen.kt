package com.lumina.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.design.components.AppBottomSheet
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaBackground
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaScrim
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.ui.presets.PresetThumb
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetDetailScreen(
    navController: NavController,
    presetId: String,
    presetsViewModel: PresetsViewModel = viewModel()
) {
    val presets by presetsViewModel.presets.collectAsState()
    val project by presetsViewModel.currentProject.collectAsState()
    val thumbSource by presetsViewModel.thumbSource.collectAsState()
    val message by presetsViewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message!!)
            presetsViewModel.consumeMessage()
        }
    }

    val preset: Preset? = remember(presets, presetId) {
        presets.firstOrNull { it.id == presetId }
            ?: BuiltInPresets.byId(presetId)?.let { demo ->
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
    }

    val activeParams = project?.toEditParams()
    val isActive = activeParams?.presetId == preset?.id
    val initialIntensity = if (isActive) {
        activeParams?.presetIntensity ?: preset?.defaultIntensity ?: 1f
    } else {
        preset?.defaultIntensity ?: 1f
    }
    var intensity by remember(preset?.id, project?.id, isActive) {
        mutableFloatStateOf(initialIntensity.coerceIn(0f, 1f))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(preset?.name ?: "Preset detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (preset != null) {
                        IconButton(
                            onClick = { presetsViewModel.toggleFavorite(preset) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                if (preset.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (preset.isFavorite) "Unfavorite" else "Favorite"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (preset == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                EmptyState(
                    title = "Preset not found",
                    message = "It may have been deleted.",
                    illustration = EmptyStateIllustration.Palette
                )
            }
            return@Scaffold
        }
        val detailShape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                PresetThumb(
                    preset = preset,
                    source = thumbSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(detailShape)
                        .then(
                            if (isActive) Modifier.border(2.dp, LuminaAmber, detailShape)
                            else Modifier
                        )
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
                color = LuminaOnSurface
            )
            Text(
                text = preset.category,
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            if (project == null) {
                Text(
                    text = "Import a photo to apply this preset.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            } else if (isActive) {
                Text(
                    text = "Applied to current photo",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            ProSlider(
                label = "Intensity",
                value = intensity * 100f,
                onValueChange = { intensity = (it / 100f).coerceIn(0f, 1f) },
                valueRange = 0f..100f,
                displayValue = "${(intensity * 100f).roundToInt()}%"
            )
            if (isActive) {
                AppBottomSheet(
                    onDismiss = {
                        project?.let { presetsViewModel.clearProjectPreset(it.id) }
                        intensity = preset.defaultIntensity.coerceIn(0f, 1f)
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Intensity",
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        ProSlider(
                            label = "Intensity",
                            value = intensity * 100f,
                            onValueChange = { intensity = (it / 100f).coerceIn(0f, 1f) },
                            valueRange = 0f..100f,
                            displayValue = "${(intensity * 100f).roundToInt()}%"
                        )
                        TextButton(
                            onClick = {
                                project?.let { presetsViewModel.clearProjectPreset(it.id) }
                                intensity = preset.defaultIntensity.coerceIn(0f, 1f)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Reset preset") }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val target = project
                        if (target == null) {
                            presetsViewModel.notify("Import a photo first, then apply presets")
                        } else {
                            presetsViewModel.applyToProject(target.id, preset)
                            if (isActive || intensity != preset.defaultIntensity) {
                                presetsViewModel.setProjectIntensity(target.id, intensity)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuminaAmber,
                        contentColor = LuminaBackground
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        "Apply",
                        style = LuminaSectionHeaderTextStyle,
                        color = LuminaBackground
                    )
                }
                OutlinedButton(
                    onClick = {
                        project?.let { presetsViewModel.clearProjectPreset(it.id) }
                        intensity = preset.defaultIntensity.coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Reset")
                }
            }
        }
    }
}
