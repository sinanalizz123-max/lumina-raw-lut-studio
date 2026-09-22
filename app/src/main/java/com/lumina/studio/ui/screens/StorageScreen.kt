package com.lumina.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(navController: NavController, settingsViewModel: SettingsViewModel = viewModel()) {
    val cacheSize by settingsViewModel.cacheSize.collectAsState()
    val originalsSize by settingsViewModel.originalsSize.collectAsState()
    val cacheMessage by settingsViewModel.cacheMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { settingsViewModel.refreshCacheSize() }
    LaunchedEffect(cacheMessage) {
        if (cacheMessage != null) {
            snackbarHostState.showSnackbar(cacheMessage!!)
            settingsViewModel.consumeCacheMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage and cache") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "Cache") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cache size: $cacheSize", modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { settingsViewModel.clearCache() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Clear cache")
                    }
                }
                Text(
                    "Frees temporary files. Originals are kept.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
            SettingsSection(title = "Originals") {
                Text("Originals: $originalsSize")
                Text(
                    "Originals are kept when clearing the cache.",
                    style = LuminaCaptionTextStyle,
                    color = LuminaMuted
                )
            }
        }
    }
}
