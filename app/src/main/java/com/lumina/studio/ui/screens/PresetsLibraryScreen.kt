package com.lumina.studio.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.design.components.AppBottomSheet
import com.lumina.studio.core.design.components.AppDialog
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.components.ErrorState
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaScrim
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.edit.toEditParams
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.lut.PresetCategory
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.ui.presets.PresetThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsLibraryScreen(
    navController: NavController,
    presetsViewModel: PresetsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val presets by presetsViewModel.presets.collectAsState()
    val project by presetsViewModel.currentProject.collectAsState()
    val query by presetsViewModel.query.collectAsState()
    val category by presetsViewModel.category.collectAsState()
    val importError by presetsViewModel.importError.collectAsState()
    val message by presetsViewModel.message.collectAsState()
    val thumbSource by presetsViewModel.thumbSource.collectAsState()

    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message!!)
            presetsViewModel.consumeMessage()
        }
    }

    LaunchedEffect(importError) {
        if (importError != null) {
            snackbar.showSnackbar(importError!!)
        }
    }

    val cubePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val displayName = withContext(Dispatchers.IO) {
                ImageFiles.displayNameOf(context, uri)
            }
            val text = withContext(Dispatchers.IO) {
                presetsViewModel.readCubeText(uri)
            }
            if (text == null) {
                presetsViewModel.notify("Could not read that .cube file")
            } else {
                presetsViewModel.importCube(displayName, text)
            }
        }
    }

    val presetFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                presetsViewModel.readCubeText(uri)
            }
            if (text == null) {
                presetsViewModel.notify("Could not read that preset file")
            } else {
                presetsViewModel.importShareJson(text)
            }
        }
    }

    var saveDialogOpen by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveCategory by remember { mutableStateOf(PresetCategory.FILM) }

    val visible = presetsViewModel.filtered(presets, query, category)
    val activePresetId = project?.toEditParams()?.presetId
    val activeIntensity = project?.toEditParams()?.presetIntensity ?: 1f
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }

    LaunchedEffect(activePresetId) {
        if (activePresetId != selectedPresetId) sheetVisible = false
        if (activePresetId == null) selectedPresetId = null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Presets") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { presetsViewModel.setQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search presets") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { cubePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Import .CUBE") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (project == null) {
                            presetsViewModel.notify("Import a photo first, then save its settings")
                        } else {
                            saveName = ""
                            saveDialogOpen = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Save current as preset") }
                OutlinedButton(
                    onClick = { presetFilePicker.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Import preset file") }
            }
            Text(
                text = LutRenderer.LARGE_LUT_PREVIEW_NOTE + " Exports always render the full table.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (saveDialogOpen) {
                AlertDialog(
                    onDismissRequest = { saveDialogOpen = false },
                    title = { Text("Save preset") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                label = { Text("Preset name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PresetCategory.entries.forEach { entry ->
                                    CategoryChip(
                                        label = entry.label,
                                        selected = saveCategory == entry,
                                        onClick = { saveCategory = entry }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val target = project
                                if (saveName.isNotBlank() && target != null) {
                                    presetsViewModel.createPresetFromCurrent(
                                        saveName, saveCategory, target.toEditParams()
                                    )
                                    saveDialogOpen = false
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { saveDialogOpen = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                )
            }
            if (importError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    ErrorState(
                        title = "Could not import .CUBE",
                        message = importError!!,
                        actionLabel = "Dismiss",
                        onAction = { presetsViewModel.clearImportError() }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryChip(
                    label = "All",
                    selected = category == null,
                    onClick = { presetsViewModel.setCategory(null) }
                )
                PresetCategory.entries.forEach { entry ->
                    CategoryChip(
                        label = entry.label,
                        selected = category == entry,
                        onClick = { presetsViewModel.setCategory(entry) }
                    )
                }
            }
            if (visible.isEmpty()) {
                // Phase 4B: zero-presets state (no installed presets, or every preset
                // incl. built-ins filtered out) pairs EmptyState with an Import .CUBE CTA.
                if (presets.isEmpty()) {
                    EmptyState(
                        title = "No presets installed",
                        message = "Import a .CUBE file to start.",
                        actionLabel = "Import .CUBE",
                        onAction = { cubePicker.launch(arrayOf("*/*")) },
                        illustration = EmptyStateIllustration.Palette
                    )
                } else {
                    EmptyState(
                        title = "No presets found",
                        message = "Try a different search or category.",
                        illustration = EmptyStateIllustration.Palette
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.id }) { preset ->
                        val selected = preset.id == activePresetId
                        PresetCard(
                            preset = preset,
                            thumbSource = thumbSource,
                            selected = selected,
                            showIntensity = selected && sheetVisible && preset.id == selectedPresetId,
                            intensity = if (selected) activeIntensity else preset.defaultIntensity,
                            onTap = {
                                val target = project
                                if (target == null) {
                                    presetsViewModel.notify("Import a photo first, then apply presets")
                                } else if (preset.id == activePresetId && preset.id == selectedPresetId) {
                                    sheetVisible = !sheetVisible
                                } else if (preset.id == activePresetId) {
                                    selectedPresetId = preset.id
                                    sheetVisible = true
                                } else {
                                    presetsViewModel.applyToProject(target.id, preset)
                                    selectedPresetId = preset.id
                                    sheetVisible = false
                                }
                            },
                            onFavorite = { presetsViewModel.toggleFavorite(preset) },
                            onIntensity = { value ->
                                project?.let { presetsViewModel.setProjectIntensity(it.id, value) }
                            },
                            onDismissSheet = { sheetVisible = false },
                            onReset = {
                                project?.let { presetsViewModel.clearProjectPreset(it.id) }
                                sheetVisible = false
                                selectedPresetId = null
                            },
                            onRename = { name -> presetsViewModel.renamePreset(preset, name) },
                            onDuplicate = { presetsViewModel.duplicatePreset(preset) },
                            onDelete = {
                                presetsViewModel.deletePreset(preset)
                                sheetVisible = false
                                if (selectedPresetId == preset.id) selectedPresetId = null
                            },
                            onShare = {
                                presetsViewModel.buildShareFor(preset) { shared ->
                                    if (shared == null) {
                                        presetsViewModel.notify("Could not share that preset")
                                        return@buildShareFor
                                    }
                                    scope.launch {
                                        try {
                                            val uri = withContext(Dispatchers.IO) {
                                                Exporter.saveJsonToDownloads(
                                                    context, shared.fileName, shared.json
                                                )
                                            }
                                            shared.warning?.let { snackbar.showSnackbar(it) }
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(send, "Share preset")
                                            )
                                        } catch (_: Exception) {
                                            snackbar.showSnackbar("Sharing is not available")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: Preset,
    thumbSource: Bitmap?,
    selected: Boolean,
    showIntensity: Boolean,
    intensity: Float,
    onTap: () -> Unit,
    onFavorite: () -> Unit,
    onIntensity: (Float) -> Unit,
    onDismissSheet: () -> Unit,
    onReset: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var renameOpen by remember(preset.id) { mutableStateOf(false) }
    var renameText by remember(preset.id) { mutableStateOf(preset.name) }
    var deleteConfirm by remember(preset.id) { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(16.dp)
    Card(
        onClick = onTap,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, LuminaAmber, cardShape)
                else Modifier
            ),
        shape = cardShape,
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
                        .clip(cardShape)
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
                        onClick = onFavorite,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.name,
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp),
                    maxLines = 1
                )
                Text(
                    text = preset.category,
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted,
                    maxLines = 1
                )
            }
            if (selected && showIntensity) {
                AppBottomSheet(onDismiss = onDismissSheet) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = preset.name,
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        ProSlider(
                            label = "Intensity",
                            value = intensity * 100f,
                            onValueChange = { onIntensity(it / 100f) },
                            valueRange = 0f..100f,
                            displayValue = "${(intensity * 100f).roundToInt()}%"
                        )
                        TextButton(
                            onClick = onReset,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Reset preset") }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    renameText = preset.name
                                    renameOpen = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Rename") }
                            TextButton(
                                onClick = onDuplicate,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Duplicate") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Share") }
                            TextButton(
                                onClick = { deleteConfirm = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Delete") }
                        }
                    }
                }
            }
            if (renameOpen) {
                AlertDialog(
                    onDismissRequest = { renameOpen = false },
                    title = { Text("Rename preset") },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text("Preset name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (renameText.isNotBlank()) {
                                    onRename(renameText)
                                    renameOpen = false
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Rename") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { renameOpen = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                )
            }
            if (deleteConfirm) {
                AppDialog(
                    title = "Delete preset?",
                    message = "“${preset.name}” will be removed, including its saved LUT file.",
                    confirmLabel = "Delete",
                    onDismiss = { deleteConfirm = false },
                    onConfirm = {
                        deleteConfirm = false
                        onDelete()
                    }
                )
            }
        }
    }
}
