package com.lumina.studio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.design.components.AppBottomSheet
import com.lumina.studio.core.design.components.AppDialog
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.core.util.timeAgo
import com.lumina.studio.core.library.PresetFilter
import com.lumina.studio.core.library.RecencyFilter
import com.lumina.studio.core.library.TypeFilter
import com.lumina.studio.core.merge.MergeKind
import com.lumina.studio.navigation.Routes
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    navController: NavController,
    projectsViewModel: ProjectsViewModel = viewModel(),
    presetsViewModel: PresetsViewModel = viewModel()
) {
    val uiState by projectsViewModel.uiState.collectAsState()
    val notice by projectsViewModel.notice.collectAsState()
    val selectedIds by projectsViewModel.selectedIds.collectAsState()
    val batchState by projectsViewModel.batchState.collectAsState()
    val mergeState by projectsViewModel.mergeState.collectAsState()
    val cullState by projectsViewModel.cullState.collectAsState()
    val presets by presetsViewModel.presets.collectAsState()
    val presetMessage by presetsViewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectMode by remember { mutableStateOf(false) }
    var presetPickerOpen by remember { mutableStateOf(false) }
    var albumPickerIds by remember { mutableStateOf<List<String>?>(null) }
    var albumDetailId by remember { mutableStateOf<String?>(null) }
    var newAlbumOpen by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var newAlbumTarget by remember { mutableStateOf<List<String>?>(null) }
    var cullOpen by remember { mutableStateOf(false) }
    var confirmDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    var confirmDeleteAlbumId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice!!)
            projectsViewModel.consumeNotice()
        }
    }

    LaunchedEffect(presetMessage) {
        if (presetMessage != null) {
            snackbarHostState.showSnackbar(presetMessage!!)
            presetsViewModel.consumeMessage()
        }
    }

    LaunchedEffect(cullOpen) {
        if (cullOpen) projectsViewModel.startCullReview()
    }

    LaunchedEffect(uiState.projects) {
        val alive = uiState.projects.map { it.id }.toSet()
        val stale = selectedIds.filterNot { it in alive }
        if (stale.isNotEmpty()) {
            for (id in stale) projectsViewModel.toggleSelect(id)
        }
        if (uiState.projects.isEmpty()) selectMode = false
    }

    val mergedId = mergeState.doneProjectId
    LaunchedEffect(mergedId) {
        if (mergedId != null) {
            selectMode = false
            projectsViewModel.consumeMergeDone()
            navController.navigate(Routes.projectDetail(mergedId))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Projects") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { projectsViewModel.setQuery(it) },
                label = { Text("Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                singleLine = true
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip(
                    label = "Date",
                    selected = uiState.sort == ProjectSort.DATE,
                    onClick = { projectsViewModel.setSort(ProjectSort.DATE) }
                )
                CategoryChip(
                    label = "Name",
                    selected = uiState.sort == ProjectSort.NAME,
                    onClick = { projectsViewModel.setSort(ProjectSort.NAME) }
                )
                CategoryChip(
                    label = "Type",
                    selected = uiState.sort == ProjectSort.TYPE,
                    onClick = { projectsViewModel.setSort(ProjectSort.TYPE) }
                )
                Spacer(modifier = Modifier.width(4.dp))
                CategoryChip(
                    label = "Favorites",
                    selected = uiState.favoritesOnly,
                    onClick = { projectsViewModel.toggleFavoritesOnly() }
                )
                Spacer(modifier = Modifier.width(4.dp))
                CategoryChip(
                    label = if (selectMode) "Done" else "Select",
                    selected = selectMode,
                    onClick = {
                        selectMode = !selectMode
                        if (!selectMode) projectsViewModel.clearSelection()
                    }
                )
                CategoryChip(
                    label = "Review",
                    selected = cullOpen,
                    onClick = { cullOpen = true }
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    CategoryChip(
                        label = "All types",
                        selected = uiState.typeFilter == TypeFilter.ALL,
                        onClick = { projectsViewModel.setTypeFilter(TypeFilter.ALL) }
                    )
                }
                item {
                    CategoryChip(
                        label = "RAW",
                        selected = uiState.typeFilter == TypeFilter.RAW,
                        onClick = {
                            projectsViewModel.setTypeFilter(
                                if (uiState.typeFilter == TypeFilter.RAW) TypeFilter.ALL
                                else TypeFilter.RAW
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "DNG",
                        selected = uiState.typeFilter == TypeFilter.DNG,
                        onClick = {
                            projectsViewModel.setTypeFilter(
                                if (uiState.typeFilter == TypeFilter.DNG) TypeFilter.ALL
                                else TypeFilter.DNG
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "Edited",
                        selected = uiState.typeFilter == TypeFilter.EDITED,
                        onClick = {
                            projectsViewModel.setTypeFilter(
                                if (uiState.typeFilter == TypeFilter.EDITED) TypeFilter.ALL
                                else TypeFilter.EDITED
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "Other",
                        selected = uiState.typeFilter == TypeFilter.OTHER,
                        onClick = {
                            projectsViewModel.setTypeFilter(
                                if (uiState.typeFilter == TypeFilter.OTHER) TypeFilter.ALL
                                else TypeFilter.OTHER
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "Has preset",
                        selected = uiState.presetFilter == PresetFilter.WITH_PRESET,
                        onClick = {
                            projectsViewModel.setPresetFilter(
                                if (uiState.presetFilter == PresetFilter.WITH_PRESET) PresetFilter.ALL
                                else PresetFilter.WITH_PRESET
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "No preset",
                        selected = uiState.presetFilter == PresetFilter.WITHOUT_PRESET,
                        onClick = {
                            projectsViewModel.setPresetFilter(
                                if (uiState.presetFilter == PresetFilter.WITHOUT_PRESET) PresetFilter.ALL
                                else PresetFilter.WITHOUT_PRESET
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "Recent imports",
                        selected = uiState.recencyFilter == RecencyFilter.RECENT_IMPORT,
                        onClick = {
                            projectsViewModel.setRecencyFilter(
                                if (uiState.recencyFilter == RecencyFilter.RECENT_IMPORT) RecencyFilter.ALL
                                else RecencyFilter.RECENT_IMPORT
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "Recent edits",
                        selected = uiState.recencyFilter == RecencyFilter.RECENT_EDIT,
                        onClick = {
                            projectsViewModel.setRecencyFilter(
                                if (uiState.recencyFilter == RecencyFilter.RECENT_EDIT) RecencyFilter.ALL
                                else RecencyFilter.RECENT_EDIT
                            )
                        }
                    )
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    CategoryChip(
                        label = "All photos",
                        selected = uiState.activeAlbumId == null,
                        onClick = { projectsViewModel.setActiveAlbum(null) }
                    )
                }
                items(uiState.albums, key = { it.id }) { album ->
                    val count = uiState.albumCounts[album.id] ?: 0
                    CategoryChip(
                        label = "${album.name} ($count)",
                        selected = uiState.activeAlbumId == album.id,
                        onClick = {
                            projectsViewModel.setActiveAlbum(
                                if (uiState.activeAlbumId == album.id) null else album.id
                            )
                        }
                    )
                }
                item {
                    CategoryChip(
                        label = "+ New album",
                        selected = false,
                        onClick = {
                            newAlbumTarget = null
                            newAlbumName = ""
                            newAlbumOpen = true
                        }
                    )
                }
            }
            val activeAlbum = uiState.albums.firstOrNull { it.id == uiState.activeAlbumId }
            if (activeAlbum != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Album: ${activeAlbum.name}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { albumDetailId = activeAlbum.id },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Open") }
                    OutlinedButton(
                        onClick = { projectsViewModel.setActiveAlbum(null) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("All") }
                }
            }
            if (selectMode && selectedIds.isNotEmpty()) {
                Text(
                    text = "${selectedIds.size} selected. Location tags follow your Export settings (off by default).",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { presetPickerOpen = true },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Apply preset") }
                    OutlinedButton(
                        onClick = {
                            projectsViewModel.pasteToMany(selectedIds.toList())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Paste") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            projectsViewModel.startHdrMerge(selectedIds.toList())
                        },
                        enabled = selectedIds.size >= 2 && !mergeState.running,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (mergeState.running && mergeState.mode == MergeKind.HDR) "Merging…"
                            else "Merge HDR"
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            projectsViewModel.startPanoramaStitch(selectedIds.toList())
                        },
                        enabled = selectedIds.size >= 2 && !mergeState.running,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (mergeState.running && mergeState.mode == MergeKind.PANORAMA) "Stitching…"
                            else "Stitch panorama"
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            projectsViewModel.startBatchExport(selectedIds.toList())
                        },
                        enabled = !batchState.running,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text(if (batchState.running) "Exporting…" else "Export") }
                    OutlinedButton(
                        onClick = { projectsViewModel.clearSelection() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Clear") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            projectsViewModel.selectAll(uiState.projects.map { it.id })
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Select all") }
                    OutlinedButton(
                        onClick = { albumPickerIds = selectedIds.toList() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Album") }
                    OutlinedButton(
                        onClick = { confirmDeleteIds = selectedIds.toList() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Delete") }
                }
                if (batchState.running) {
                    LinearProgressIndicator(
                        progress = { batchState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { projectsViewModel.cancelBatchExport() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Cancel batch") }
                }
                if (!batchState.running && batchState.summary != null) {
                    Text(
                        text = batchState.summary!!,
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    val failures = batchState.results.filterNot { it.ok }
                    if (failures.isNotEmpty()) {
                        Text(
                            text = failures.take(3).joinToString("\n") {
                                "• ${it.projectId}: ${it.error ?: "failed"}"
                            },
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                }
            }
            if (uiState.projects.isEmpty()) {
                val filtered = uiState.query.isNotBlank() || uiState.favoritesOnly ||
                    uiState.typeFilter != TypeFilter.ALL ||
                    uiState.presetFilter != PresetFilter.ALL ||
                    uiState.recencyFilter != RecencyFilter.ALL ||
                    uiState.activeAlbumId != null
                EmptyState(
                    title = "No projects yet",
                    message = if (filtered)
                        "No projects match your filters."
                    else "Projects you create will appear here.",
                    actionLabel = "Import photo",
                    onAction = { navController.navigate(Routes.IMPORT) },
                    illustration = if (filtered) EmptyStateIllustration.Folder else EmptyStateIllustration.Photo
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.projects, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            editCount = uiState.editCounts[project.id] ?: 0,
                            selected = project.id in selectedIds,
                            showSelect = selectMode,
                            onToggleSelect = { projectsViewModel.toggleSelect(project.id) },
                            onOpen = {
                                if (selectMode) {
                                    projectsViewModel.toggleSelect(project.id)
                                } else {
                                    navController.navigate(Routes.projectDetail(project.id))
                                }
                            },
                            onLongPress = {
                                if (!selectMode) selectMode = true
                                projectsViewModel.toggleSelect(project.id)
                            },
                            onAddToAlbum = { albumPickerIds = listOf(project.id) },
                            onToggleFavorite = { projectsViewModel.toggleFavorite(project) },
                            onDuplicate = { projectsViewModel.duplicate(project) },
                            onDelete = {
                                projectsViewModel.delete(project)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Deleted ${project.name}",
                                        actionLabel = "Undo"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        val restored = projectsViewModel.undoDelete()
                                        if (restored == null) {
                                            snackbarHostState.showSnackbar(
                                                "Original file was already deleted"
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { navController.navigate(Routes.HISTORY) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View history")
                        }
                    }
                }
            }
            if (mergeState.running) {
                AlertDialog(
                    onDismissRequest = { },
                    title = {
                        Text(
                            if (mergeState.mode == MergeKind.PANORAMA) "Stitching panorama…"
                            else "Merging HDR…"
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { mergeState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(mergeState.progress.coerceIn(0f, 1f) * 100).toInt()}% — keep this screen open.",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { projectsViewModel.cancelMerge() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                )
            }
            if (presetPickerOpen) {
                AlertDialog(
                    onDismissRequest = { presetPickerOpen = false },
                    title = { Text("Apply preset to ${selectedIds.size} photo(s)") },
                    text = {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (presets.isEmpty()) {
                                item { Text("No presets installed.") }
                            }
                            items(presets, key = { it.id }) { preset ->
                                TextButton(
                                    onClick = {
                                        projectsViewModel.applyPresetToMany(
                                            selectedIds.toList(), preset.id
                                        )
                                        presetPickerOpen = false
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        "${preset.name} • ${preset.category}",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { presetPickerOpen = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                )
            }
            val pickerIds = albumPickerIds
            if (pickerIds != null) {
                AppBottomSheet(onDismiss = { albumPickerIds = null }) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Add ${pickerIds.size} photo(s) to album",
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        if (uiState.albums.isEmpty()) {
                            Text(
                                "No albums yet — create one below.",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.heightIn(max = 320.dp)
                            ) {
                                items(uiState.albums, key = { it.id }) { album ->
                                    TextButton(
                                        onClick = {
                                            projectsViewModel.addToAlbum(album.id, pickerIds)
                                            albumPickerIds = null
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text(
                                            "${album.name} (${uiState.albumCounts[album.id] ?: 0})",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                newAlbumTarget = pickerIds
                                newAlbumName = ""
                                newAlbumOpen = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text("New album") }
                    }
                }
            }
            if (newAlbumOpen) {
                AlertDialog(
                    onDismissRequest = {
                        newAlbumOpen = false
                        newAlbumTarget = null
                    },
                    title = { Text("New album") },
                    text = {
                        OutlinedTextField(
                            value = newAlbumName,
                            onValueChange = { newAlbumName = it },
                            label = { Text("Album name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val target = newAlbumTarget
                                projectsViewModel.createAlbum(newAlbumName) { album ->
                                    if (album != null && target != null) {
                                        projectsViewModel.addToAlbum(album.id, target)
                                        albumPickerIds = null
                                    }
                                }
                                newAlbumOpen = false
                                newAlbumName = ""
                                newAlbumTarget = null
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Create") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                newAlbumOpen = false
                                newAlbumTarget = null
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                )
            }
            val detailAlbum = uiState.albums.firstOrNull { it.id == albumDetailId }
            if (detailAlbum != null) {
                AppBottomSheet(onDismiss = { albumDetailId = null }) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                detailAlbum.name,
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { albumDetailId = null },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close")
                            }
                        }
                        Text(
                            "Removing photos here keeps them in your library.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        val members = uiState.projects
                        Text(
                            "${members.size} photo(s)",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        if (members.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 400.dp)
                            ) {
                                gridItems(members, key = { it.id }) { project ->
                                    Box {
                                        val model: Any? = project.photoUri?.let { path ->
                                            val file = File(path)
                                            if (file.exists()) file else path
                                        }
                                        AsyncImage(
                                            model = model,
                                            contentDescription = project.name,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(112.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    albumDetailId = null
                                                    navController.navigate(
                                                        Routes.projectDetail(project.id)
                                                    )
                                                },
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = {
                                                projectsViewModel.removeFromAlbum(
                                                    detailAlbum.id, listOf(project.id)
                                                )
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove from album"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteAlbumId = detailAlbum.id },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text("Delete album (keeps photos)") }
                    }
                }
            }
            if (cullOpen) {
                AppBottomSheet(
                    onDismiss = {
                        cullOpen = false
                        projectsViewModel.cancelCullReview()
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Review photos",
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Text(
                            "Heuristic flags only — nothing is deleted automatically. You decide.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        if (cullState.running) {
                            LinearProgressIndicator(
                                progress = {
                                    if (cullState.total <= 0) 0f
                                    else cullState.done.toFloat() / cullState.total.toFloat()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Analyzing ${cullState.done} of ${cullState.total}…",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { projectsViewModel.selectFlaggedCull() },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Select flagged") }
                            OutlinedButton(
                                onClick = { projectsViewModel.clearCullChecked() },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Clear") }
                        }
                        val reviewIds = cullState.names.keys.toList()
                        if (reviewIds.isEmpty() && !cullState.running) {
                            Text(
                                "No photos to review.",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 380.dp)
                            ) {
                                items(reviewIds, key = { it }) { id ->
                                    val flags = cullState.flagsFor(id)
                                    val name = cullState.names[id] ?: "Photo"
                                    val photo = uiState.projects.firstOrNull { it.id == id }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = id in cullState.checked,
                                            onCheckedChange = {
                                                projectsViewModel.toggleCullChecked(id)
                                            },
                                            modifier = Modifier.size(48.dp)
                                        )
                                        val model: Any? = photo?.photoUri?.let { path ->
                                            val file = File(path)
                                            if (file.exists()) file else path
                                        }
                                        if (model != null) {
                                            AsyncImage(
                                                model = model,
                                                contentDescription = name,
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(12.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                name,
                                                style = LuminaSectionHeaderTextStyle,
                                                color = LuminaOnSurface,
                                                maxLines = 1
                                            )
                                            val badges = flags?.badges(
                                                cullState.duplicates[id]?.let { dupId ->
                                                    cullState.names[dupId]?.take(18)
                                                }
                                            ).orEmpty()
                                            if (flags == null && cullState.running) {
                                                Text(
                                                    "Analyzing…",
                                                    style = LuminaCaptionTextStyle,
                                                    color = LuminaMuted
                                                )
                                            } else if (badges.isEmpty()) {
                                                Text(
                                                    "Looks fine",
                                                    style = LuminaCaptionTextStyle,
                                                    color = LuminaMuted
                                                )
                                            } else {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    badges.forEach { badge ->
                                                        CullBadge(badge)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteIds = cullState.checked.toList() },
                            enabled = cullState.checked.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text("Delete selected (${cullState.checked.size})") }
                    }
                }
            }
            val deleteIds = confirmDeleteIds
            if (deleteIds != null) {
                AppDialog(
                    title = "Delete ${deleteIds.size} photo(s)?",
                    message = "This removes the projects and their files. " +
                        "Album membership is dropped too. This cannot be undone.",
                    onDismiss = { confirmDeleteIds = null },
                    onConfirm = {
                        projectsViewModel.deleteMany(deleteIds)
                        projectsViewModel.clearCullChecked()
                        if (!cullOpen) {
                            selectMode = false
                        }
                        confirmDeleteIds = null
                    },
                    confirmLabel = "Delete"
                )
            }
            val deleteAlbumId = confirmDeleteAlbumId
            if (deleteAlbumId != null) {
                AppDialog(
                    title = "Delete album?",
                    message = "Photos stay in your library — only the album is removed.",
                    onDismiss = { confirmDeleteAlbumId = null },
                    onConfirm = {
                        projectsViewModel.deleteAlbum(deleteAlbumId)
                        albumDetailId = null
                        confirmDeleteAlbumId = null
                    },
                    confirmLabel = "Delete"
                )
            }
        }
    }
}

@Composable
private fun CullBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = LuminaSurfaceContainerLow,
        contentColor = LuminaOnSurface
    ) {
        Text(
            text,
            style = LuminaCaptionTextStyle,
            color = LuminaOnSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ProjectRow(
    project: Project,
    editCount: Int,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showSelect: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onAddToAlbum: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuminaSurfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showSelect) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(48.dp)
                )
            }
            val model: Any? = project.photoUri?.let { path ->
                val file = File(path)
                if (file.exists()) file else path
            }
            AsyncImage(
                model = model,
                contentDescription = project.name,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface,
                    maxLines = 1
                )
                Text(
                    text = project.fileType.ifBlank { "IMAGE" } + " • " +
                        ImageFiles.resolutionLabel(project.width, project.height),
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                Text(
                    text = timeAgo(project.updatedAt),
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                Text(
                    text = project.presetName ?: "No preset",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                Text(
                    text = "$editCount edits",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            Column {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (project.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Favorite"
                    )
                }
                IconButton(onClick = onAddToAlbum, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Folder, contentDescription = "Add to album")
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}
