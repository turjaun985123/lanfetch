package com.lanfetch.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lanfetch.app.download.DlState
import com.lanfetch.app.ui.DownloadsScreen
import com.lanfetch.app.ui.LanFetchTheme
import com.lanfetch.app.ui.LibraryScreen
import com.lanfetch.app.ui.MainViewModel
import com.lanfetch.app.ui.SettingsScreen
import com.lanfetch.app.ui.SourcesScreen

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

@Composable
private fun LanFetchRoot(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "library"
    val downloads by vm.downloads.collectAsState()
    val activeCount = downloads.count { it.state == DlState.RUNNING || it.state == DlState.QUEUED }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "library",
                    onClick = { nav.navigate("library") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Library") },
                    label = { Text("Library") },
                )
                NavigationBarItem(
                    selected = route == "downloads",
                    onClick = { nav.navigate("downloads") { launchSingleTop = true } },
                    icon = {
                        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                            Icon(Icons.Filled.Download, contentDescription = "Downloads")
                        }
                    },
                    label = { Text("Downloads") },
                )
                NavigationBarItem(
                    selected = route == "sources",
                    onClick = { nav.navigate("sources") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Storage, contentDescription = "Servers") },
                    label = { Text("Servers") },
                )
                NavigationBarItem(
                    selected = route == "settings",
                    onClick = { nav.navigate("settings") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                )
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "library", modifier = androidx.compose.ui.Modifier.padding(padding)) {
            composable("library") { LibraryScreen(vm) }
            composable("downloads") { DownloadsScreen(vm) }
            composable("sources") { SourcesScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
