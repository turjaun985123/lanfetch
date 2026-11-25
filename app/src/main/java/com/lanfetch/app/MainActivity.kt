package com.lanfetch.app

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lanfetch.app.ui.brand.TurjaunLoadingExperience
import com.lanfetch.app.ui.brand.rememberReducedMotion
import com.lanfetch.app.ui.components.TurjaunDivider
import com.lanfetch.app.ui.downloads.DownloadsScreen
import com.lanfetch.app.ui.downloads.DownloadsViewModel
import com.lanfetch.app.ui.library.LibraryScreen
import com.lanfetch.app.ui.library.LibraryViewModel
import com.lanfetch.app.ui.servers.SourcesScreen
import com.lanfetch.app.ui.servers.SourcesViewModel
import com.lanfetch.app.ui.settings.SettingsScreen
import com.lanfetch.app.ui.settings.SettingsViewModel
import com.lanfetch.app.ui.theme.TurjaunMotion
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            TurjaunTheme {
                TurjaunAppRoot()
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
private fun TurjaunAppRoot() {
    val libraryVm: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as LanFetchApp }
    val activeCount by remember(app) {
        app.container.downloadCoordinator.activeCount()
    }.collectAsState(initial = 0)

    val categories by libraryVm.categories.collectAsState()
    val items by libraryVm.items.collectAsState()
    val isLoadingInitial by libraryVm.isLoadingInitial.collectAsState()
    val errorMessage by libraryVm.errorMessage.collectAsState()

    val reducedMotion = rememberReducedMotion()
    var openingFinished by remember { mutableStateOf(false) }
    val openingReady = !isLoadingInitial ||
        categories.isNotEmpty() ||
        items.isNotEmpty() ||
        !errorMessage.isNullOrBlank()
    val currentOpeningReady = rememberUpdatedState(openingReady)

    LaunchedEffect(openingFinished) {
        if (!openingFinished) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            snapshotFlow { currentOpeningReady.value }.first { it }
            val minDurationMs = if (reducedMotion) 0L else TurjaunMotion.OpeningMinimumMs.toLong()
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            if (elapsed < minDurationMs) {
                delay(minDurationMs - elapsed)
            }
            openingFinished = true
        }
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.LIBRARY
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(app) {
        app.container.messages.collect { snackbar.showSnackbar(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(openingFinished) {
        if (!openingFinished) return@LaunchedEffect
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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = TurjaunPalette.Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    TurjaunDivider()
                    NavigationBar(
                        containerColor = TurjaunPalette.Surface,
                        tonalElevation = 0.dp,
                    ) {
                        NavTab(
                            selected = route == Routes.LIBRARY,
                            label = "Library",
                            onClick = { nav.navigateTo(Routes.LIBRARY) },
                            icon = { Icon(Icons.Filled.Home, contentDescription = "Library") },
                        )
                        NavTab(
                            selected = route == Routes.DOWNLOADS,
                            label = "Downloads",
                            onClick = { nav.navigateTo(Routes.DOWNLOADS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (activeCount > 0) {
                                            Badge(
                                                containerColor = TurjaunPalette.Accent,
                                                contentColor = TurjaunPalette.OnAccent,
                                            ) {
                                                Text("$activeCount")
                                            }
                                        }
                                    },
                                ) {
                                    Icon(painterResource(R.drawable.ic_download), contentDescription = "Downloads")
                                }
                            },
                        )
                        NavTab(
                            selected = route == Routes.SOURCES,
                            label = "Servers",
                            onClick = { nav.navigateTo(Routes.SOURCES) },
                            icon = { Icon(painterResource(R.drawable.ic_storage), contentDescription = "Servers") },
                        )
                        NavTab(
                            selected = route == Routes.SETTINGS,
                            label = "Settings",
                            onClick = { nav.navigateTo(Routes.SETTINGS) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(tween(TurjaunMotion.FastMs)) },
                exitTransition = { fadeOut(tween(TurjaunMotion.FastMs)) },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.LIBRARY) { LibraryScreen(libraryVm) }
                composable(Routes.DOWNLOADS) {
                    val downloadsVm: DownloadsViewModel = viewModel()
                    DownloadsScreen(downloadsVm)
                }
                composable(Routes.SOURCES) {
                    val sourcesVm: SourcesViewModel = viewModel()
                    SourcesScreen(sourcesVm)
                }
                composable(Routes.SETTINGS) {
                    val settingsVm: SettingsViewModel = viewModel()
                    SettingsScreen(settingsVm)
                }
            }
        }

        // Animated TURJAUN wordmark shows strictly during app opening, then leaves composition.
        AnimatedVisibility(
            visible = !openingFinished,
            enter = EnterTransition.None,
            exit = fadeOut(tween(durationMillis = TurjaunMotion.StandardMs, easing = TurjaunMotion.EaseStandard)),
        ) {
            TurjaunLoadingExperience()
        }
    }
}

@Composable
private fun RowScope.NavTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = TurjaunPalette.Accent,
            selectedTextColor = TurjaunPalette.InkPrimary,
            indicatorColor = TurjaunPalette.AccentContainer,
            unselectedIconColor = TurjaunPalette.InkMuted,
            unselectedTextColor = TurjaunPalette.InkMuted,
        ),
    )
}

private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
