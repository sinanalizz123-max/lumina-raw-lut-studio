package com.lumina.studio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.lumina.studio.core.data.local.Project
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
import com.lumina.studio.navigation.Routes
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(navController: NavController, projectsViewModel: ProjectsViewModel = viewModel()) {
    val uiState by projectsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
            }
            if (uiState.projects.isEmpty()) {
                val filtered = uiState.query.isNotBlank() || uiState.favoritesOnly
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
                            onOpen = { navController.navigate(Routes.projectDetail(project.id)) },
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
                                        projectsViewModel.undoDelete()
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
        }
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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuminaSurfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
