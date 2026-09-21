package com.lumina.studio.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lumina.studio.ui.screens.AboutScreen
import com.lumina.studio.ui.screens.AppearanceScreen
import com.lumina.studio.ui.screens.EditorScreen
import com.lumina.studio.ui.screens.ExportScreen
import com.lumina.studio.ui.screens.HistoryScreen
import com.lumina.studio.ui.screens.HomeScreen
import com.lumina.studio.ui.screens.ImportScreen
import com.lumina.studio.ui.screens.PackDetailScreen
import com.lumina.studio.ui.screens.PresetDetailScreen
import com.lumina.studio.ui.screens.PresetsLibraryScreen
import com.lumina.studio.ui.screens.ProjectDetailScreen
import com.lumina.studio.ui.screens.ProjectsScreen
import com.lumina.studio.ui.screens.SettingsScreen
import com.lumina.studio.ui.screens.StorageScreen
import com.lumina.studio.core.util.IncomingImages
import kotlinx.coroutines.flow.filterNotNull

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.PROJECTS, "Projects", Icons.Filled.PhotoLibrary),
    Tab(Routes.PRESETS, "Presets", Icons.Filled.Style),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBottomBar = TABS.any { it.route == currentDestination?.route }

    // Shared/view intents (VIEW/SEND/SEND_MULTIPLE image/*) route into Import.
    // Routes unchanged: MainActivity (singleTop) parks the URI in
    // IncomingImages.pending; we navigate to IMPORT and let ImportScreen consume it.
    LaunchedEffect(navController) {
        IncomingImages.pending.filterNotNull().collect {
            runCatching {
                navController.navigate(Routes.IMPORT) {
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(navController) }
            composable(Routes.PROJECTS) { ProjectsScreen(navController) }
            composable(
                Routes.PROJECT_DETAIL,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                ProjectDetailScreen(
                    navController = navController,
                    projectId = entry.arguments?.getString("projectId") ?: "unknown"
                )
            }
            composable(Routes.PRESETS) { PresetsLibraryScreen(navController) }
            composable(
                Routes.PACK_DETAIL,
                arguments = listOf(navArgument("packId") { type = NavType.StringType })
            ) { entry ->
                PackDetailScreen(
                    navController = navController,
                    packId = entry.arguments?.getString("packId") ?: "unknown"
                )
            }
            composable(
                Routes.PRESET_DETAIL,
                arguments = listOf(navArgument("presetId") { type = NavType.StringType })
            ) { entry ->
                PresetDetailScreen(
                    navController = navController,
                    presetId = entry.arguments?.getString("presetId") ?: "unknown"
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.APPEARANCE) { AppearanceScreen(navController) }
            composable(Routes.STORAGE) { StorageScreen(navController) }
            composable(Routes.ABOUT) { AboutScreen(navController) }
            composable(Routes.IMPORT) { ImportScreen(navController) }
            composable(
                route = "editor?projectId={projectId}",
                arguments = listOf(
                    navArgument("projectId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId")?.takeIf { it.isNotEmpty() }
                EditorScreen(navController = navController, projectId = projectId)
            }
            composable(Routes.EXPORT) {
                val projectId = remember(backStack) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.get<String>("export_project_id")
                }
                ExportScreen(navController = navController, projectId = projectId)
            }
            composable(Routes.HISTORY) { HistoryScreen(navController) }
        }
    }
}
