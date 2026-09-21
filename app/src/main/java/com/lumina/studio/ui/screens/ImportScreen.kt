package com.lumina.studio.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.design.components.ErrorState
import com.lumina.studio.core.design.components.LoadingShimmer
import com.lumina.studio.core.design.components.LoadingShimmerStyle
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.core.util.IncomingImages
import com.lumina.studio.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(navController: NavController, importViewModel: ImportViewModel = viewModel()) {
    val uiState by importViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) importViewModel.importUri(uri)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) importViewModel.importUri(uri)
    }
    fun launchGallery() {
        // Android Photo Picker is the primary path (no permission needed).
        // GetContent stays as the fallback where the system picker is unavailable.
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
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importViewModel.importUri(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            if (bitmap != null) importViewModel.importBitmap(bitmap)
            else importViewModel.reportError("Camera returned no image")
        }
    }

    LaunchedEffect(uiState.lastProjectId) {
        val id = uiState.lastProjectId
        if (id != null) {
            navController.navigate(Routes.editor(id)) {
                popUpTo(Routes.IMPORT) { inclusive = true }
            }
            importViewModel.clearNavigation()
        }
    }

    // Shared/view intents parked by MainActivity: import the single URI here.
    // SEND_MULTIPLE imports the first image and notes that batch lands later.
    LaunchedEffect(Unit) {
        IncomingImages.pending.collect { shared ->
            if (shared == null) return@collect
            val consumed = IncomingImages.consume() ?: return@collect
            importViewModel.importUri(consumed.primary)
            if (consumed.total > 1) {
                runCatching { snackbarHostState.showSnackbar("Only the first image was imported — batch import lands later") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            // Phase 4B RAW loading state: shimmer + progress while EXIF read and
            // decode run (ImportViewModel sets isImporting around that IO work).
            if (uiState.isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Reading photo details…",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                LoadingShimmer(style = LoadingShimmerStyle.List)
            }
            if (uiState.rawDetected || uiState.previewOnly) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    Text(
                        text = if (uiState.rawDetected) "RAW image detected — opens as embedded preview"
                        else uiState.previewNote ?: "Preview only — editing is limited for this format",
                        modifier = Modifier.padding(16.dp),
                        style = LuminaSectionHeaderTextStyle,
                        color = LuminaOnSurface
                    )
                }
            }
            uiState.lastExif?.let { exif ->
                if (!exif.isEmpty) {
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
                                text = "Photo details",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                            exif.cameraModel?.let {
                                Text(
                                    "Camera: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.lensModel?.let {
                                Text(
                                    "Lens: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.iso?.let {
                                Text(
                                    "ISO: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.shutter?.let {
                                Text(
                                    "Shutter: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.aperture?.let {
                                Text(
                                    "Aperture: f$it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.focalLength?.let {
                                Text(
                                    "Focal length: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            if (exif.hasGps) {
                                Text(
                                    "Location: present (stripped from exports unless enabled)",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            exif.bitsPerSample?.let {
                                Text(
                                    "Bits per sample: $it",
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                        }
                    }
                }
            }

            ImportSourceTile(
                icon = Icons.Filled.PhotoCamera,
                title = "Camera",
                subtitle = "Capture a new photo",
                onClick = {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    runCatching { cameraLauncher.launch(intent) }
                }
            )
            ImportSourceTile(
                icon = Icons.Filled.PhotoLibrary,
                title = "Gallery",
                subtitle = "Pick from your photos",
                onClick = { launchGallery() }
            )
            ImportSourceTile(
                icon = Icons.Filled.FolderOpen,
                title = "Files",
                subtitle = "Browse documents and downloads",
                onClick = {
                    filesLauncher.launch(
                        arrayOf(
                            "image/*",
                            "image/x-adobe-dng",
                            "image/x-canon-cr2",
                            "image/heic",
                            "image/heif",
                            "image/avif",
                            "image/bmp",
                            "image/gif",
                            "*/*"
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ImageFiles.FORMAT_FOOTER,
                style = LuminaCaptionTextStyle,
                color = LuminaMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            if (uiState.errorMessage != null) {
                ErrorState(
                    title = "Cannot import file",
                    message = uiState.errorMessage!!,
                    actionLabel = "Dismiss",
                    onAction = { importViewModel.clearError() }
                )
            }
            Button(
                onClick = { navController.navigate(Routes.editor()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Continue to editor")
            }
        }
    }
}

@Composable
private fun ImportSourceTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuminaSurfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = title, tint = LuminaAmber)
            Column {
                Text(
                    title,
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface
                )
                Text(
                    subtitle,
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
        }
    }
}
