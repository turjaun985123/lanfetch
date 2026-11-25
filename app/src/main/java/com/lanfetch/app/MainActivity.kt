package com.lanfetch.app

import android.graphics.Color
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.downloads.DownloadsScreen
import com.lanfetch.app.ui.downloads.DownloadsViewModel
import com.lanfetch.app.ui.library.LibraryScreen
import com.lanfetch.app.ui.library.LibraryViewModel
import com.lanfetch.app.ui.servers.SourcesScreen
import com.lanfetch.app.ui.servers.SourcesViewModel
import com.lanfetch.app.ui.settings.SettingsScreen
import com.lanfetch.app.ui.settings.SettingsViewModel
import com.lanfetch.app.ui.theme.ThemeMode
import com.lanfetch.app.data.AppSettings
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesTheme
import androidx.compose.runtime.SideEffect

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            LanesAppRoot()
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
private fun LanesAppRoot() {
    val libraryVm: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as LanFetchApp }
    val appSettings by app.container.settingsStore.flow.collectAsState(initial = AppSettings())
    val themeMode = runCatching { ThemeMode.valueOf(appSettings.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
    SideEffect { applyLanguage(appSettings.language) }

    LanesTheme(mode = themeMode) {
        if (!appSettings.onboardingDone) {
            // First run: live network check before the tabs (redesign spec).
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            com.lanfetch.app.ui.onboarding.FirstRunCheck(
                container = app.container,
                onDone = {
                    scope.launch { app.container.settingsStore.setOnboardingDone() }
                },
            )
        } else {
            LanesAppScaffold(libraryVm, app)
        }
    }
}

@Composable
private fun LanesAppScaffold(libraryVm: LibraryViewModel, app: LanFetchApp) {
    val activeCount by remember(app) {
        app.container.downloadCoordinator.activeCount()
    }.collectAsState(initial = 0)

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.LIBRARY
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(app) {
        app.container.messages.collect { snackbar.showSnackbar(it) }
    }
    val bottomBar: (@Composable () -> Unit)? = if (route == Routes.SETTINGS) null else {
        {
            Column {
                LanesDivider()
                NavigationBar(
                    containerColor = LanesPalette.Surface,
                    tonalElevation = 0.dp,
                ) {
                    NavTab(
                        selected = route == Routes.LIBRARY,
                        label = "Browse",
                        onClick = { nav.navigateTo(Routes.LIBRARY) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Browse") },
                    )
                    NavTab(
                        selected = route == Routes.DOWNLOADS,
                        label = "Transfers",
                        onClick = { nav.navigateTo(Routes.DOWNLOADS) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (activeCount > 0) {
                                        Badge(
                                            containerColor = LanesPalette.Accent,
                                            contentColor = LanesPalette.OnAccent,
                                        ) { Text("$activeCount") }
                                    }
                                },
                            ) {
                                Icon(painterResource(R.drawable.ic_download), contentDescription = "Transfers")
                            }
                        },
                    )
                    NavTab(
                        selected = route == Routes.SOURCES,
                        label = "Servers",
                        onClick = { nav.navigateTo(Routes.SOURCES) },
                        icon = { Icon(painterResource(R.drawable.ic_storage), contentDescription = "Servers") },
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = LanesPalette.Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { bottomBar?.invoke() },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.padding(padding),
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.LIBRARY) { LibraryScreen(libraryVm, onSettings = { nav.navigateTo(Routes.SETTINGS) }) }
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

    }
}

private fun applyLanguage(language: String) {
    val locales = when (language) {
        "bn" -> androidx.core.os.LocaleListCompat.forLanguageTags("bn")
        "en" -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
        else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
    }
    if (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales() != locales) {
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
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
            selectedIconColor = LanesPalette.Accent,
            selectedTextColor = LanesPalette.InkPrimary,
            indicatorColor = LanesPalette.AccentContainer,
            unselectedIconColor = LanesPalette.InkMuted,
            unselectedTextColor = LanesPalette.InkMuted,
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
