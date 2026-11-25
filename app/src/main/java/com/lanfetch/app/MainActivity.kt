package com.lanfetch.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.lanfetch.app.ui.DownloadsScreen
import com.lanfetch.app.ui.DownloadsViewModel
import com.lanfetch.app.ui.LanFetchTheme
import com.lanfetch.app.ui.LibraryScreen
import com.lanfetch.app.ui.LibraryViewModel
import com.lanfetch.app.ui.SettingsScreen
import com.lanfetch.app.ui.SettingsViewModel
import com.lanfetch.app.ui.SourcesScreen
import com.lanfetch.app.ui.SourcesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LanFetchTheme {
                LanFetchRoot()
            }
        }
    }
}

private object Routes {
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
}

@Composable
private fun LanFetchRoot() {
    val libraryVm: LibraryViewModel = viewModel()
    val downloadsVm: DownloadsViewModel = viewModel()
    val sourcesVm: SourcesViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.LIBRARY
    val activeCount by downloadsVm.activeCount.collectAsState()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        val app = context.applicationContext as LanFetchApp
        app.container.syncManager.messages.collect { snackbar.showSnackbar(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavItem(
                    selected = route == Routes.LIBRARY,
                    label = "Library",
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Library") },
                    onClick = { nav.navigateTo(Routes.LIBRARY) },
                )
                NavigationBarItem(
                    selected = route == Routes.DOWNLOADS,
                    onClick = { nav.navigateTo(Routes.DOWNLOADS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeCount > 0) Badge { Text("$activeCount") }
                            }
                        ) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = "Downloads")
                        }
                    },
                    label = { Text("Downloads") },
                )
                NavItem(
                    selected = route == Routes.SOURCES,
                    label = "Servers",
                    icon = { Icon(painterResource(R.drawable.ic_storage), contentDescription = "Servers") },
                    onClick = { nav.navigateTo(Routes.SOURCES) },
                )
                NavItem(
                    selected = route == Routes.SETTINGS,
                    label = "Settings",
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    onClick = { nav.navigateTo(Routes.SETTINGS) },
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LIBRARY) { LibraryScreen(libraryVm) }
            composable(Routes.DOWNLOADS) { DownloadsScreen(downloadsVm) }
            composable(Routes.SOURCES) { SourcesScreen(sourcesVm) }
            composable(Routes.SETTINGS) { SettingsScreen(settingsVm) }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label) },
    )
}

/** Keeps each tab's scroll/filter state when switching tabs. */
private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
