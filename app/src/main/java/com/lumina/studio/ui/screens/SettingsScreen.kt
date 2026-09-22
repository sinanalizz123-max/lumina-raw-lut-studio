package com.lumina.studio.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.ai.AiResearch
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.navigation.Routes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, settingsViewModel: SettingsViewModel = viewModel()) {
    val gpu by settingsViewModel.gpuAcceleration.collectAsState()
    val rawQuality by settingsViewModel.rawQuality.collectAsState()
    val previewQuality by settingsViewModel.previewQuality.collectAsState()
    val exportFormat by settingsViewModel.exportFormat.collectAsState()
    val exportQuality by settingsViewModel.exportQuality.collectAsState()
    val exportResolution by settingsViewModel.exportResolution.collectAsState()
    val exportColorSpace by settingsViewModel.exportColorSpace.collectAsState()
    val includeMetadata by settingsViewModel.exportIncludeMetadata.collectAsState()
    val includeLocation by settingsViewModel.exportIncludeLocation.collectAsState()
    val aiBackend by settingsViewModel.aiBackend.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
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
            SettingsSection(title = "Editing") {
                SettingsSwitchRow(
                    title = "Preview performance mode",
                    checked = gpu,
                    onCheckedChange = { settingsViewModel.setGpuAcceleration(it) }
                )
                Text(
                    "On: full preview + auto-histogram. Off: 1200px cap, manual histogram.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                SettingsChoiceRow(
                    title = "RAW develop quality",
                    options = listOf("Low", "Medium", "High"),
                    selected = rawQuality,
                    onSelect = { settingsViewModel.setRawQuality(it) }
                )
                Text(
                    "High develops sensor data; otherwise embedded preview.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
                SettingsChoiceRow(
                    title = "Preview quality",
                    options = listOf("Low", "Medium", "High"),
                    selected = previewQuality,
                    onSelect = { settingsViewModel.setPreviewQuality(it) }
                )
                Text(
                    "High 1600 • Medium 1200 • Low 800 px. Cache lives under Storage.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }

            SettingsSection(title = "Selection") {
                Text(
                    "Backend: ${AiResearch.BACKEND_NAME} ($aiBackend)",
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaOnSurface
                )
                Text(
                    "Heuristic on-device — 0 MB, no download. Approximations; refine with Feather.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }

            SettingsSection(title = "Export defaults") {
                SettingsChoiceRow(
                    title = "Format",
                    options = listOf("JPEG", "PNG", "WebP", "TIFF"),
                    selected = exportFormat,
                    onSelect = { settingsViewModel.setExportFormat(it) }
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Quality: $exportQuality",
                        style = LuminaSectionHeaderTextStyle,
                        color = LuminaOnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(70, 80, 90, 100).forEach { q ->
                            CategoryChip(
                                label = "$q",
                                selected = exportQuality == q,
                                onClick = { settingsViewModel.setExportQuality(q) }
                            )
                        }
                    }
                }
                SettingsChoiceRow(
                    title = "Resolution",
                    options = listOf("Original", "Large", "Medium", "Small"),
                    selected = exportResolution,
                    onSelect = { settingsViewModel.setExportResolution(it) }
                )
                SettingsChoiceRow(
                    title = "Color space",
                    options = listOf("sRGB", "Display P3"),
                    selected = exportColorSpace,
                    onSelect = { settingsViewModel.setExportColorSpace(it) }
                )
                SettingsSwitchRow(
                    title = "Include metadata",
                    checked = includeMetadata,
                    onCheckedChange = { settingsViewModel.setExportIncludeMetadata(it) }
                )
                SettingsSwitchRow(
                    title = "Include location metadata on export/share",
                    checked = includeLocation,
                    onCheckedChange = { settingsViewModel.setExportIncludeLocation(it) }
                )
                Text(
                    "Off by default for privacy — GPS tags are stripped from exports when off.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }

            SettingsSection(title = "Presets") {
                OutlinedButton(
                    onClick = { navController.navigate(Routes.PRESETS) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Open preset library")
                }
            }

            SettingsSection(title = "Interface & Storage") {
                Button(
                    onClick = { navController.navigate(Routes.APPEARANCE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Appearance")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Routes.STORAGE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Storage and cache")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Routes.ABOUT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("About")
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
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
                title,
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            HorizontalDivider(color = LuminaSurfaceContainerHigh)
            content()
        }
    }
}

@Composable
fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { option ->
                CategoryChip(
                    label = option,
                    selected = selected == option,
                    onClick = { onSelect(option) }
                )
            }
        }
    }
}
