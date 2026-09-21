package com.lumina.studio.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.EditHistory
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaBackground
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.core.util.FormatCapabilities
import com.lumina.studio.core.util.CapabilityStatus
import com.lumina.studio.core.util.timeAgo
import com.lumina.studio.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

val HomePresetPacks = listOf(
    "Cinematic", "Film", "Portrait", "Moody", "Travel", "Nature", "B&W", "Vintage"
)

@Composable
fun LuminaLogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(32.dp)) {
        val r = size.minDimension / 2f
        drawCircle(color = LuminaAmber, style = Stroke(width = 4f))
        val dotR = r * 0.28f
        drawCircle(color = LuminaAmber)
        drawCircle(color = Color(0xFF101012), radius = dotR)
        for (i in 0 until 6) {
            val angle = (i * 60f - 90f) * Math.PI / 180.0
            val start = (r * 0.45f).toFloat()
            val end = (r * 0.85f).toFloat()
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawLine(
                color = LuminaAmber,
                start = androidx.compose.ui.geometry.Offset(
                    (cx + start * Math.cos(angle)).toFloat(),
                    (cy + start * Math.sin(angle)).toFloat()
                ),
                end = androidx.compose.ui.geometry.Offset(
                    (cx + end * Math.cos(angle)).toFloat(),
                    (cy + end * Math.sin(angle)).toFloat()
                ),
                strokeWidth = 3f
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, homeViewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val recent by homeViewModel.recentProjects.collectAsState()

    fun importPickedUri(uri: android.net.Uri) {
        scope.launch {
            val projectId = withContext(Dispatchers.IO) {
                runCatching {
                    val appContext = context.applicationContext
                    val db = DatabaseProvider.get(appContext)
                    val displayName = ImageFiles.displayNameOf(context, uri)
                        ?: "photo"
                    val mime = ImageFiles.mimeOf(context, uri)
                    var ext = ImageFiles.extensionOf(displayName)
                    if (ext.isEmpty() && mime != null) {
                        ext = when (mime.lowercase(Locale.US)) {
                            "image/jpeg" -> "jpg"
                            "image/png" -> "png"
                            "image/webp" -> "webp"
                            "image/tiff" -> "tiff"
                            "image/x-tiff" -> "tiff"
                            "image/heic" -> "heic"
                            "image/heif" -> "heif"
                            "image/avif" -> "avif"
                            "image/bmp" -> "bmp"
                            "image/gif" -> "gif"
                            "image/x-adobe-dng" -> "dng"
                            else -> ""
                        }
                    }
                    if (FormatCapabilities.statusOf(ext, mime) == CapabilityStatus.UNSUPPORTED) return@runCatching null
                    val projectId = UUID.randomUUID().toString()
                    val file = ProjectStore.copyUriToOriginal(appContext, projectId, uri, displayName)
                        ?: return@runCatching null
                    val bounds = ImageFiles.decodeBounds(file)
                    val now = System.currentTimeMillis()
                    val project = Project(
                        id = projectId,
                        name = displayName.ifBlank { file.name },
                        photoUri = file.absolutePath,
                        createdAt = now,
                        updatedAt = now,
                        fileType = ImageFiles.typeBadge(ext, mime),
                        mimeType = mime,
                        width = bounds.width,
                        height = bounds.height
                    )
                    db.projectDao().upsert(project)
                    runCatching {
                        db.editHistoryDao().insert(
                            EditHistory(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                toolName = "import",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    project.id
                }.getOrNull()
            }
            if (projectId != null) {
                navController.navigate(Routes.editor(projectId))
            } else {
                scope.launch { snackbarHostState.showSnackbar("Unsupported format") }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importPickedUri(uri)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importPickedUri(uri)
    }

    fun launchGallery() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            runCatching {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure {
                runCatching { galleryLauncher.launch("image/*") }
            }
        } else {
            runCatching { galleryLauncher.launch("image/*") }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val bitmap = result.data?.extras?.get("data") as? Bitmap
        if (bitmap == null) {
            scope.launch { snackbarHostState.showSnackbar("Camera returned no image") }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val projectId = withContext(Dispatchers.IO) {
                runCatching {
                    val appContext = context.applicationContext
                    val db = DatabaseProvider.get(appContext)
                    val newId = UUID.randomUUID().toString()
                    val file = ProjectStore.saveBitmapToOriginal(appContext, newId, bitmap)
                        ?: return@runCatching null
                    val bounds = ImageFiles.decodeBounds(file)
                    val now = System.currentTimeMillis()
                    val project = Project(
                        id = newId,
                        name = file.name,
                        photoUri = file.absolutePath,
                        createdAt = now,
                        updatedAt = now,
                        fileType = "JPEG",
                        mimeType = "image/jpeg",
                        width = bounds.width,
                        height = bounds.height
                    )
                    db.projectDao().upsert(project)
                    runCatching {
                        db.editHistoryDao().insert(
                            EditHistory(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                toolName = "import",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    project.id
                }.getOrNull()
            }
            if (projectId != null) navController.navigate(Routes.editor(projectId))
            else scope.launch { snackbarHostState.showSnackbar("Could not save camera photo") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LuminaLogoMark()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lumina")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { navController.navigate(Routes.IMPORT) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = LuminaAmber,
                    contentColor = LuminaBackground
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    "New Edit",
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaBackground
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { launchGallery() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Open Photo")
                }
                IconButton(
                    onClick = {
                        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        runCatching { cameraLauncher.launch(intent) }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera")
                }
            }

            Text(
                "Recent Projects",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            if (recent.isEmpty()) {
                EmptyState(
                    title = "No projects yet",
                    message = "Import a photo to start your first edit.",
                    actionLabel = "New Edit",
                    onAction = { navController.navigate(Routes.IMPORT) },
                    illustration = EmptyStateIllustration.Photo
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(recent, key = { it.id }) { project ->
                        RecentProjectCard(
                            project = project,
                            onClick = { navController.navigate(Routes.editor(project.id)) }
                        )
                    }
                }
            }

            Text(
                "Preset Library",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomePresetPacks.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { pack ->
                            val packShape = RoundedCornerShape(16.dp)
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("$pack coming in Phase 2")
                                        }
                                    },
                                shape = packShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = LuminaSurfaceContainerLow
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        pack,
                                        style = LuminaSectionHeaderTextStyle,
                                        color = LuminaOnSurface
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable {
                                scope.launch { snackbarHostState.showSnackbar("Import .CUBE coming in Phase 2") }
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerLow
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Import .CUBE",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                        }
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable {
                                scope.launch { snackbarHostState.showSnackbar("Import Pack coming in Phase 2") }
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerLow
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Import Pack",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RecentProjectCard(project: Project, onClick: () -> Unit) {
    val cardShape = RoundedCornerShape(16.dp)
    Card(
        modifier = Modifier
            .width(160.dp)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = LuminaSurfaceContainerLow
        )
    ) {
        Column {
            val model: Any? = project.photoUri?.let { path ->
                val file = File(path)
                if (file.exists()) file else path
            }
            AsyncImage(
                model = model,
                contentDescription = project.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    project.name,
                    maxLines = 1,
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LuminaSurfaceContainerHigh,
                        contentColor = LuminaOnSurface,
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = project.fileType.ifBlank { "IMAGE" },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = LuminaCaptionTextStyle,
                            color = LuminaOnSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(
                                color = if (!project.presetName.isNullOrBlank()) LuminaAmber else LuminaMuted
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeAgo(project.updatedAt),
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
        }
    }
}
