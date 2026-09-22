package com.lumina.studio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lumina.studio.BuildConfig
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.util.DebugDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: BuildConfig.VERSION_NAME
        }.getOrNull() ?: BuildConfig.VERSION_NAME
    }
    var versionTaps by remember { mutableIntStateOf(0) }
    val debugVisible = versionTaps >= 7
    val backendName by DebugDiagnostics.backendName.collectAsState()
    val decoderName by DebugDiagnostics.decoderName.collectAsState()
    val lastRenderMs by DebugDiagnostics.lastRenderMs.collectAsState()
    val imageDims by DebugDiagnostics.imageDims.collectAsState()
    val paramsRevision by DebugDiagnostics.paramsRevision.collectAsState()
    val zoomTileInfo by DebugDiagnostics.zoomTileInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "Lumina RAW & LUT Studio") {
                Text(
                    "Version $versionName",
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface,
                    modifier = Modifier.clickable { versionTaps += 1 }
                )
                Text(
                    "Import, projects, presets and settings stored on this device.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            SettingsSection(title = "Licenses") {
                Text(
                    "Built with AndroidX (Room, DataStore, Compose, Navigation), Coil for image loading, " +
                        "and ExifInterface for metadata. See their respective open-source licenses.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            SettingsSection(title = "Privacy") {
                Text(
                    "Photos stay on your device. Imports are copied to the app cache and project " +
                        "metadata is stored locally in Room. No network upload.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (debugVisible) {
                SettingsSection(title = "Debug") {
                    Text("Backend: $backendName", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text(
                        "Last render: ${if (lastRenderMs < 0) "—" else "$lastRenderMs ms"}",
                        style = LuminaCaptionTextStyle, color = LuminaMuted
                    )
                    Text("Memory: ${DebugDiagnostics.memorySummary()}", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("Decoder: $decoderName", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("Color space: ${DebugDiagnostics.COLOR_SPACE}", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("Image: $imageDims", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("Params revision: $paramsRevision", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("Zoom tile: $zoomTileInfo", style = LuminaCaptionTextStyle, color = LuminaMuted)
                    Text("GPU: ${DebugDiagnostics.GPU_INFO}", style = LuminaCaptionTextStyle, color = LuminaMuted)
                }
            }
        }
    }
}
