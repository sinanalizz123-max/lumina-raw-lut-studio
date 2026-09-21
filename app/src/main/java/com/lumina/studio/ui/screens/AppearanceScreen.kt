package com.lumina.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(navController: NavController, settingsViewModel: SettingsViewModel = viewModel()) {
    val theme by settingsViewModel.theme.collectAsState()
    val haptic by settingsViewModel.hapticEnabled.collectAsState()
    val animations by settingsViewModel.animationsEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
            SettingsSection(title = "Theme") {
                SettingsChoiceRow(
                    title = "App theme (applies immediately)",
                    options = listOf("dark", "dark_plus"),
                    selected = theme,
                    onSelect = { settingsViewModel.setTheme(it) }
                )
            }
            SettingsSection(title = "Feedback") {
                SettingsSwitchRow(
                    title = "Haptic feedback",
                    checked = haptic,
                    onCheckedChange = { settingsViewModel.setHapticEnabled(it) }
                )
                SettingsSwitchRow(
                    title = "Animations",
                    checked = animations,
                    onCheckedChange = { settingsViewModel.setAnimationsEnabled(it) }
                )
            }
        }
    }
}
