package com.snapaie.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snapaie.android.AppContainer
import com.snapaie.android.IngestRequest
import com.snapaie.android.core.design.SnapAieTheme
import com.snapaie.android.core.design.ThemeMode
import com.snapaie.android.core.design.components.AmbientBubbles
import com.snapaie.android.core.design.snapScreenBackground
import com.snapaie.android.ui.chat.ChatScreen
import com.snapaie.android.ui.library.LibraryScreen
import com.snapaie.android.ui.nav.Routes
import com.snapaie.android.ui.notifications.LocalSnapToast
import com.snapaie.android.ui.notifications.NotificationCenterSheet
import com.snapaie.android.ui.notifications.rememberSnapToastController
import com.snapaie.android.ui.progress.ProgressScreen
import com.snapaie.android.ui.progress.ReaderReportScreen
import com.snapaie.android.ui.recall.FeynmanScreen
import com.snapaie.android.ui.recall.RapidFireScreen
import com.snapaie.android.ui.recall.RecallHubScreen
import com.snapaie.android.ui.recall.SurvivalScreen
import com.snapaie.android.ui.recall.VaultScreen
import com.snapaie.android.ui.scan.CompressionRunScreen
import com.snapaie.android.ui.scan.ScanDetailScreen
import com.snapaie.android.ui.scan.ScanHubScreen
import com.snapaie.android.ui.settings.SettingsScreen
import com.snapaie.android.ui.upgrade.UpgradeScreen
import com.snapaie.android.ui.writing.WritingScreen
import kotlinx.coroutines.flow.first

private enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    Snap(Routes.Snap, "Snap", Icons.Filled.CameraAlt),
    Recall(Routes.Recall, "Recall", Icons.Filled.Psychology),
    Library(Routes.Library, "Library", Icons.Filled.AutoStories),
    Progress(Routes.Progress, "Progress", Icons.Filled.Insights),
}

@Composable
fun SnapAieApp(
    container: AppContainer,
    startRoute: String? = null,
    ingest: IngestRequest.Content? = null,
    onIngestConsumed: () -> Unit = {},
) {
    val prefs = container.appPreferencesRepository

    var onboardingGate by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingGate = prefs.onboardingCompleted.first()
    }

    val settings by prefs.userSettings.collectAsStateWithLifecycle(
        initialValue = com.snapaie.android.data.preferences.UserSettings(),
    )
    val themeMode = ThemeMode.fromStored(settings.themeMode)

    SnapAieTheme(mode = themeMode, textScale = settings.textScale) {
        when (onboardingGate) {
            null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            false ->
                OnboardingFlow(prefs = prefs, onFinished = { onboardingGate = true })
            true ->
                MainShell(
                    container = container,
                    startRoute = startRoute,
                    bubblesMode = settings.bubblesMode,
                    ingest = ingest,
                    onIngestConsumed = onIngestConsumed,
                )
        }
    }
}

@Composable
private fun MainShell(
    container: AppContainer,
    startRoute: String?,
    bubblesMode: String,
    ingest: IngestRequest.Content?,
    onIngestConsumed: () -> Unit,
) {
    val viewModel: SnapAieViewModel = viewModel(factory = SnapAieViewModelFactory(container))
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route.orEmpty()

    LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank() && startRoute != Routes.Snap) navController.navigate(startRoute)
    }

    LaunchedEffect(ingest) {
        if (ingest == null) return@LaunchedEffect
        when {
            ingest.text != null -> viewModel.ingestText(ingest.text, title = "Shared text")
            ingest.uri != null && ingest.isPdf -> viewModel.ingestPdf(ingest.uri)
            ingest.uri != null -> viewModel.extractText(ingest.uri)
        }
        navController.navigate(Routes.Snap) { launchSingleTop = true }
        onIngestConsumed()
    }

    val toastController = rememberSnapToastController()
    val snackbarHostState = remember { SnackbarHostState() }
    var notificationsOpen by remember { mutableStateOf(false) }
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val unreadNotifications by viewModel.unreadNotifications.collectAsStateWithLifecycle()

    // Single collector for the app-wide toast channel, so a snackbar shows above
    // the navigation bar on every route instead of each screen hosting its own.
    LaunchedEffect(toastController) {
        toastController.stream.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                withDismissAction = event.actionLabel == null,
                duration = if (event.actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
        }
    }

    val hideBottomBar = currentRoute == Routes.Upgrade ||
        currentRoute == Routes.Camera ||
        currentRoute.startsWith("scanDetail/") ||
        currentRoute.startsWith("chat/") ||
        currentRoute.startsWith("recall/rapid") ||
        currentRoute.startsWith("recall/survival") ||
        currentRoute.startsWith("recall/feynman")

    CompositionLocalProvider(LocalSnapToast provides toastController) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .snapScreenBackground(),
            containerColor = Color.Transparent,
            // Screens own their top inset via statusBarsPadding(); letting the
            // Scaffold add it too pushed every header down by a second status bar.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets
                .only(WindowInsetsSides.Bottom),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // Slide the bar out on full-screen routes instead of letting it
                // vanish between frames.
                AnimatedVisibility(
                    visible = !hideBottomBar,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                ) {
                    // NavigationBar consumes the gesture inset itself, so no
                    // navigationBarsPadding() here — that stacked a second one.
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        tonalElevation = 0.dp,
                    ) {
                        BottomTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.Snap) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                AmbientBubbles(mode = bubblesMode)
                NavHost(
                    navController = navController,
                    startDestination = Routes.Snap,
                    modifier = Modifier.padding(padding),
                    enterTransition = {
                        fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 14 }
                    },
                    exitTransition = { fadeOut(tween(200)) },
                ) {
                    composable(Routes.Snap) {
                        ScanHubScreen(
                            viewModel = viewModel,
                            navController = navController,
                            unreadNotifications = unreadNotifications,
                            onOpenNotifications = { notificationsOpen = true },
                        )
                    }
                    composable(Routes.ScanRun) {
                        CompressionRunScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(Routes.Camera) {
                        CameraScanRoute(
                            onImageCaptured = { uri ->
                                viewModel.extractText(uri)
                                navController.popBackStack()
                            },
                            onDismiss = { navController.popBackStack() },
                        )
                    }
                    composable(
                        Routes.ScanDetail,
                        arguments = listOf(navArgument("scanId") { type = NavType.LongType }),
                    ) { entry ->
                        ScanDetailScreen(
                            viewModel = viewModel,
                            navController = navController,
                            scanId = entry.arguments?.getLong("scanId") ?: -1L,
                        )
                    }
                    composable(Routes.Recall) {
                        RecallHubScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(
                        Routes.RecallRapid,
                        arguments = listOf(navArgument("topicId") { type = NavType.LongType }),
                    ) { entry ->
                        RapidFireScreen(viewModel, navController, entry.arguments?.getLong("topicId") ?: -1L)
                    }
                    composable(
                        Routes.RecallSurvival,
                        arguments = listOf(navArgument("topicId") { type = NavType.LongType }),
                    ) { entry ->
                        SurvivalScreen(viewModel, navController, entry.arguments?.getLong("topicId") ?: -1L)
                    }
                    composable(
                        Routes.RecallFeynman,
                        arguments = listOf(navArgument("topicId") { type = NavType.LongType }),
                    ) { entry ->
                        FeynmanScreen(viewModel, navController, entry.arguments?.getLong("topicId") ?: -1L)
                    }
                    composable(Routes.RecallVault) {
                        VaultScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(Routes.Library) {
                        LibraryScreen(
                            viewModel = viewModel,
                            navController = navController,
                            unreadNotifications = unreadNotifications,
                            onOpenNotifications = { notificationsOpen = true },
                        )
                    }
                    composable(
                        Routes.Chat,
                        arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
                    ) { entry ->
                        ChatScreen(
                            viewModel = viewModel,
                            navController = navController,
                            sessionId = entry.arguments?.getLong("sessionId") ?: -1L,
                        )
                    }
                    composable(Routes.Writing) {
                        WritingScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(Routes.Progress) {
                        ProgressScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(Routes.ReaderReport) {
                        ReaderReportScreen(viewModel = viewModel, navController = navController)
                    }
                    composable(Routes.Upgrade) {
                        UpgradeScreen(container = viewModel.container, onClose = { navController.popBackStack() })
                    }
                    composable(Routes.Settings) {
                        SettingsScreen(viewModel = viewModel, navController = navController)
                    }
                }
            }
        }

        if (notificationsOpen) {
            NotificationCenterSheet(
                items = notifications,
                onDismissRequest = { notificationsOpen = false },
                onMarkAllRead = { viewModel.notificationCenter.markAllRead() },
                onClearAll = {
                    viewModel.notificationCenter.clearAll()
                    notificationsOpen = false
                },
                onDismissItem = { viewModel.notificationCenter.dismiss(it) },
                onOpenRoute = { route ->
                    notificationsOpen = false
                    navController.navigate(route)
                },
            )
        }
    }
}
