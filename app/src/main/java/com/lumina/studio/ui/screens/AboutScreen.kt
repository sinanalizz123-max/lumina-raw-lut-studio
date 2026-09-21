package com.lumina.studio.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lumina.studio.BuildConfig
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle

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
                    color = LuminaOnSurface
                )
                Text(
                    "Phase 1 core flow: import, projects, presets and settings backed by Room and DataStore.",
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
                        "metadata is stored locally in Room. No network upload in this phase.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
