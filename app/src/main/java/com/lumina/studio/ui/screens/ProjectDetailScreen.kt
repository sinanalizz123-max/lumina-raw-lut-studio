package com.lumina.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.components.LoadingShimmer
import com.lumina.studio.core.design.components.LoadingShimmerStyle
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.core.util.timeAgo
import com.lumina.studio.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ProjectDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _editCount = MutableStateFlow(0)
    val editCount: StateFlow<Int> = _editCount.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _photoMissing = MutableStateFlow(false)
    val photoMissing: StateFlow<Boolean> = _photoMissing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    fun load(projectId: String) {
        viewModelScope.launch {
            _loading.value = true
            val loaded = database.projectDao().getById(projectId)
            var resolved = loaded
            if (loaded != null && (loaded.width <= 0 || loaded.height <= 0)) {
                val path = loaded.photoUri
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists()) {
                        val bounds = ImageFiles.decodeBounds(file)
                        if (bounds.width > 0 && bounds.height > 0) {
                            resolved = loaded.copy(width = bounds.width, height = bounds.height)
                            database.projectDao().upsert(resolved)
                        }
                    }
                }
            }
            _project.value = resolved
            val photoPath = resolved?.photoUri
            _photoMissing.value = resolved != null &&
                (photoPath.isNullOrBlank() || !File(photoPath).isFile)
            _editCount.value = database.editHistoryDao().countForProject(projectId)
            _loading.value = false
        }
    }

    fun toggleFavorite() {
        val current = _project.value ?: return
        viewModelScope.launch {
            val updated = current.copy(
                isFavorite = !current.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
            database.projectDao().upsert(updated)
            _project.value = updated
        }
    }

    fun relocatePhoto(uri: Uri) {
        val current = _project.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dest = ProjectStore.copyUriToOriginal(getApplication(), current.id, uri, null)
            if (dest == null) {
                _notice.value = "Could not read that file"
                return@launch
            }
            val bounds = ImageFiles.decodeBounds(dest)
            val updated = current.copy(
                photoUri = dest.absolutePath,
                updatedAt = System.currentTimeMillis(),
                width = bounds.width.takeIf { it > 0 } ?: current.width,
                height = bounds.height.takeIf { it > 0 } ?: current.height
            )
            database.projectDao().upsert(updated)
            _project.value = updated
            _photoMissing.value = false
        }
    }

    fun removeProject() {
        val current = _project.value ?: return
        _project.value = null
        _photoMissing.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            database.projectDao().deleteById(current.id)
            runCatching { database.editHistoryDao().clearForProject(current.id) }
            runCatching { ProjectStore.deleteProjectFiles(app, current.id) }
            runCatching { ProjectStore.deleteOwnedFile(app, current.photoUri) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    navController: NavController,
    projectId: String,
    detailViewModel: ProjectDetailViewModel = viewModel(
        key = "detail_$projectId",
        factory = ProjectDetailFactory(LocalContext.current.applicationContext as Application, projectId)
    )
) {
    val project by detailViewModel.project.collectAsState()
    val editCount by detailViewModel.editCount.collectAsState()
    val loading by detailViewModel.loading.collectAsState()
    val photoMissing by detailViewModel.photoMissing.collectAsState()
    val notice by detailViewModel.notice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val locateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) detailViewModel.relocatePhoto(uri)
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice!!)
            detailViewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Project detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { detailViewModel.toggleFavorite() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            if (project?.isFavorite == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Favorite"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                LoadingShimmer(style = LoadingShimmerStyle.Card)
                return@Column
            }
            val current = project
            if (current == null) {
                EmptyState(
                    title = "Project not found",
                    message = "It may have been deleted.",
                    actionLabel = "Back",
                    onAction = { navController.popBackStack() },
                    illustration = EmptyStateIllustration.Folder
                )
                return@Column
            }
            val model: Any? = current.photoUri?.let { path ->
                val file = File(path)
                if (file.exists()) file else path
            }
            if (photoMissing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Photo unavailable",
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Text(
                            "The original file for this project is missing. " +
                                "Locate it again or remove the project.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { locateLauncher.launch(arrayOf("image/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Locate file")
                            }
                            OutlinedButton(
                                onClick = {
                                    detailViewModel.removeProject()
                                    navController.popBackStack()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Remove project")
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    AsyncImage(
                        model = model,
                        contentDescription = current.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Text(
                current.name,
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = LuminaSurfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Details",
                        style = LuminaSectionHeaderTextStyle,
                        color = LuminaOnSurface
                    )
                    Text(
                        "Type: ${current.fileType.ifBlank { "IMAGE" }}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        "Resolution: ${ImageFiles.resolutionLabel(current.width, current.height)}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        "Modified: ${timeAgo(current.updatedAt)}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        "Preset: ${current.presetName ?: "No preset"}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        "Edits: $editCount",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        if (current.isFavorite) "Favorite: yes" else "Favorite: no",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                }
            }
            if (!photoMissing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { navController.navigate(Routes.editor(current.id)) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Open in editor")
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                navController.currentBackStackEntry?.savedStateHandle?.set(
                                    "history_project_id", current.id
                                )
                            } catch (_: Exception) {
                            }
                            navController.navigate(Routes.HISTORY)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("View history")
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private class ProjectDetailFactory(
    private val application: Application,
    private val projectId: String
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        val vm = ProjectDetailViewModel(application)
        vm.load(projectId)
        return vm as T
    }
}
