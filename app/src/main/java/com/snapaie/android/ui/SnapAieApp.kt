package com.snapaie.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snapaie.android.AppContainer
import com.snapaie.android.R
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.SnapAieTheme
import com.snapaie.android.core.design.snapScreenBackground
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.model.KnowledgeMode
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.monetization.ConsentAndAds
import com.snapaie.android.monetization.ScanHubBannerAd
import kotlinx.coroutines.flow.first

private object NavRoutes {
    const val Scan = "scan"
    const val Compress = "compress"
    const val Result = "result"
    const val Library = "library"
    const val Upgrade = "upgrade"
    const val Camera = "camera"
    const val LibraryDetail = "library/detail/{scanId}"

    fun libraryDetail(scanId: Long): String = "library/detail/$scanId"
}

private enum class BottomTab(val route: String, val label: String) {
    Scan(NavRoutes.Scan, "Scan"),
    Compress(NavRoutes.Compress, "Compress"),
    Result(NavRoutes.Result, "Clarity"),
    Library(NavRoutes.Library, "Growth"),
}

@Composable
fun SnapAieApp(container: AppContainer) {
    val prefs = container.appPreferencesRepository
    val billing = container.billingBridge

    var onboardingGate by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingGate = prefs.onboardingCompleted.first()
    }

    when (onboardingGate) {
        null ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        false ->
            SnapAieTheme {
                OnboardingFlow(
                    prefs = prefs,
                    onFinished = { onboardingGate = true },
                )
            }
        true ->
            SnapAieTheme {
                val activity = LocalContext.current.findActivity()
                LaunchedEffect(activity) {
                    activity?.let { act ->
                        ConsentAndAds.requestConsentFormThenInitAds(act) { }
                    }
                }

                MainShell(container = container, billing = billing)
            }
    }
}

@Composable
private fun MainShell(container: AppContainer, billing: com.snapaie.android.billing.BillingBridge) {
    val viewModel: SnapAieViewModel = viewModel(factory = SnapAieViewModelFactory(container))
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route.orEmpty()

    val isPro by billing.isPro.collectAsStateWithLifecycle(initialValue = false)
    val activity = LocalContext.current.findActivity()
    val consentOk = activity?.let { ConsentAndAds.canRequestAds(it) } == true
    val showBanner = !isPro && consentOk

    val hideBottomBar =
        currentRoute == NavRoutes.Upgrade ||
            currentRoute == NavRoutes.Camera ||
            currentRoute.startsWith("library/detail/")

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .snapScreenBackground(),
        containerColor = Color.Transparent,
        bottomBar = {
            if (!hideBottomBar) {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                ) {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected =
                                backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        BottomTab.Scan -> Icons.Default.ImageSearch
                                        BottomTab.Compress -> Icons.Default.PlayArrow
                                        BottomTab.Result -> Icons.Default.AutoAwesome
                                        BottomTab.Library -> Icons.Default.Insights
                                    },
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Scan,
            modifier = Modifier.padding(padding),
        ) {
            composable(NavRoutes.Scan) {
                ScanHub(
                    viewModel = viewModel,
                    navController = navController,
                    isPro = isPro,
                    showBannerAd = showBanner,
                    onRun = { navController.navigate(NavRoutes.Compress) },
                )
            }
            composable(NavRoutes.Compress) {
                CompressionRun(viewModel = viewModel, onOpenResult = { navController.navigate(NavRoutes.Result) })
            }
            composable(NavRoutes.Result) {
                ClarityScreen(result = viewModel.uiState.value.result, includeMarketingFooter = !isPro)
            }
            composable(NavRoutes.Library) {
                GrowthScreen(
                    viewModel = viewModel,
                    navController = navController,
                    onUpgradeClick = { navController.navigate(NavRoutes.Upgrade) },
                    showBannerAd = showBanner,
                )
            }
            composable(
                NavRoutes.LibraryDetail,
                arguments = listOf(navArgument("scanId") { type = NavType.LongType }),
            ) { entry ->
                val scanId = entry.arguments?.getLong("scanId") ?: return@composable
                ScanDetailScreen(scanId = scanId, viewModel = viewModel, navController = navController, includeMarketingFooter = !isPro)
            }
            composable(NavRoutes.Upgrade) {
                UpgradeScreen(container = container, onClose = { navController.navigateUp() })
            }
            composable(NavRoutes.Camera) {
                CameraScanRoute(
                    onImageCaptured = { uri ->
                        viewModel.extractText(uri)
                        navController.popBackStack()
                    },
                    onDismiss = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun UpgradeScreen(container: AppContainer, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("snapaie Pro", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.marketing_disclosure), style = MaterialTheme.typography.bodyMedium)
                    Bullet("No banner ads • calmer scanning experience")
                    Bullet("Funds faster improvements and experimentation")
                    Bullet("Restore anytime with your Google Play account")
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Flexible monthly (~\$4.99 USD)", style = MaterialTheme.typography.titleMedium)
                    Button(
                        enabled = activity != null,
                        onClick = { activity?.let { container.billingBridge.launchMonthlyPurchase(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start monthly subscription")
                    }
                    Text("Own forever (~\$89.99 USD)", style = MaterialTheme.typography.titleMedium)
                    Button(
                        enabled = activity != null,
                        onClick = { activity?.let { container.billingBridge.launchLifetimePurchase(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Buy lifetime unlock")
                    }
                    OutlinedButton(
                        onClick = { container.billingBridge.restorePurchases() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Restore purchases")
                    }
                    Text(stringResource(R.string.privacy_policy_url), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScanDetailScreen(
    scanId: Long,
    viewModel: SnapAieViewModel,
    navController: NavHostController,
    includeMarketingFooter: Boolean,
) {
    val scanNullable by viewModel.observeScan(scanId).collectAsStateWithLifecycle(initialValue = null)

    if (scanNullable == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val scan = scanNullable!!

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(scan.bookTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { navController.popBackStack() }) { Text("Close") }
            }
        }
        item {
            LiquidGlassSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = {
                        viewModel.loadDraftFromScan(scan)
                        navController.navigate(NavRoutes.Compress) { launchSingleTop = true }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Re-run clarity")
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteScan(scanId)
                            navController.popBackStack()
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("Delete")
                    }
                }
            }
        }
        scan.result.let { result ->
            item {
                LiquidGlassSurface {
                    MetricRow(result = result, includeMarketingFooter = includeMarketingFooter)
                }
            }
            item { ResultSection("Concise meaning", listOf(result.conciseMeaning)) }
            item { ResultSection("Core idea", listOf(result.coreIdea)) }
            item { ResultSection("Author intent", listOf(result.authorIntent)) }
            item { ResultSection("Simplified explanation", listOf(result.simplifiedExplanation)) }
            item { ResultSection("Actionable takeaways", result.actionableInsights) }
            item { ResultSection("Hidden meaning", listOf(result.hiddenMeaning)) }
            item {
                ResultSection(
                    title = "Smart vocabulary",
                    values = result.importantVocabulary.map { "${it.word}: ${it.meaning} Simpler: ${it.simplerVersion}" },
                )
            }
            item {
                ResultSection(
                    title = "Filler detected",
                    values = result.fillerDetected.map { "${it.type}: ${it.excerpt} (${it.reason})" },
                )
            }
        }
    }
}

@Composable
private fun MetricRow(result: KnowledgeResult, includeMarketingFooter: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Metric("${result.compressionScore}%", "compressed")
        Metric("${result.estimatedTimeSavedMinutes}m", "saved")
        IconButton(onClick = { clipboard.setText(AnnotatedString(result.toMarkdown(includeBranding = includeMarketingFooter))) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
        }
        IconButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, result.toMarkdown(includeBranding = includeMarketingFooter))
                }
                context.startActivity(Intent.createChooser(intent, "Share clarity result"))
            },
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
    }
}

@Composable
private fun ScanHub(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
    isPro: Boolean,
    showBannerAd: Boolean,
    onRun: () -> Unit,
) {
    val state = viewModel.uiState.value
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.extractText(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Header("snapaie", if (isPro) "Reading mode: Pro unlocked." else "Free • upgrade for banner-free focus.")
                }
                if (!isPro) {
                    TextButton(onClick = { navController.navigate(NavRoutes.Upgrade) }) { Text("Pro") }
                }
            }
        }
        item { ScanHero(isActive = state.isOcrRunning || state.isRunning) }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Local model", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelTier.entries.forEach { tier ->
                            FilterChip(
                                selected = modelState.selectedTier == tier,
                                onClick = { viewModel.selectTier(tier) },
                                label = { Text(tier.displayName) },
                            )
                        }
                    }
                    modelState.warning?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    LinearProgressIndicator(
                        progress = { modelState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::downloadModel,
                        enabled = !modelState.isDownloading && !modelState.isReady,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(if (modelState.isReady) "Model ready" else "Download Gemma")
                    }
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("AI mode", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        KnowledgeMode.entries.take(3).forEach { mode ->
                            FilterChip(
                                selected = state.draft.mode == mode,
                                onClick = { viewModel.updateMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        KnowledgeMode.entries.drop(3).forEach { mode ->
                            FilterChip(
                                selected = state.draft.mode == mode,
                                onClick = { viewModel.updateMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(state.draft.mode.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ImageSearch, contentDescription = null)
                            Text(if (state.isOcrRunning) "Reading page..." else "Import photo")
                        }
                        OutlinedButton(onClick = { navController.navigate(NavRoutes.Camera) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            Text("Camera")
                        }
                    }
                    state.ocrError?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    OutlinedTextField(
                        value = state.draft.bookTitle,
                        onValueChange = viewModel::updateBookTitle,
                        label = { Text("Book or source") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.draft.pageText,
                        onValueChange = viewModel::updatePageText,
                        label = { Text("OCR text or pasted page") },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.draft.context,
                        onValueChange = viewModel::updateContext,
                        label = { Text("Optional chapter, goal, or exam context") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.runWorkflow()
                            onRun()
                        },
                        enabled = state.draft.pageText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Text("Compress into clarity")
                    }
                }
            }
        }
        if (showBannerAd) {
            item {
                ScanHubBannerAd(isVisible = true)
            }
        }
    }
}

@Composable
private fun CompressionRun(viewModel: SnapAieViewModel, onOpenResult: () -> Unit) {
    val state = viewModel.uiState.value
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Compression", "Information is being transformed into clarity.") }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::runWorkflow, enabled = !state.isRunning && state.draft.pageText.isNotBlank()) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Run")
                        }
                        TextButton(onClick = viewModel::cancelRun, enabled = state.isRunning) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Cancel")
                        }
                    }
                    state.phases.forEach { phase ->
                        Text("${phase.phase.label}: ${phase.text}", style = MaterialTheme.typography.bodyMedium)
                    }
                    AnimatedVisibility(state.streamText.isNotBlank()) {
                        Text(state.streamText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(state.result != null) {
                        Button(onClick = onOpenResult, modifier = Modifier.fillMaxWidth()) {
                            Text("Open clarity result")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClarityScreen(result: KnowledgeResult?, includeMarketingFooter: Boolean) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Clarity", "Sharp, compressed, high signal.") }
        if (result == null) {
            item { LiquidGlassSurface { Text("Compress a page first.") } }
        } else {
            item {
                LiquidGlassSurface {
                    MetricRow(result = result, includeMarketingFooter = includeMarketingFooter)
                }
            }
            item { ResultSection("Concise meaning", listOf(result.conciseMeaning)) }
            item { ResultSection("Core idea", listOf(result.coreIdea)) }
            item { ResultSection("Author intent", listOf(result.authorIntent)) }
            item { ResultSection("Simplified explanation", listOf(result.simplifiedExplanation)) }
            item { ResultSection("Actionable takeaways", result.actionableInsights) }
            item { ResultSection("Hidden meaning", listOf(result.hiddenMeaning)) }
            item {
                ResultSection(
                    "Smart vocabulary",
                    result.importantVocabulary.map { "${it.word}: ${it.meaning} Simpler: ${it.simplerVersion}" },
                )
            }
            item {
                ResultSection(
                    "Filler detected",
                    result.fillerDetected.map { "${it.type}: ${it.excerpt} (${it.reason})" },
                )
            }
        }
    }
}

@Composable
private fun GrowthScreen(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
    onUpgradeClick: () -> Unit,
    showBannerAd: Boolean,
) {
    val scans by viewModel.library.collectAsStateWithLifecycle()
    val stats by viewModel.readerStats.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Header("Growth", "Become smarter faster.")
                }
                TextButton(onClick = onUpgradeClick) { Text("Upgrade") }
            }
        }
        item {
            LiquidGlassSurface {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Metric(stats.streakDays.toString(), "streak")
                    Metric(stats.pagesProcessed.toString(), "pages")
                    Metric("${stats.minutesSaved}m", "saved")
                    Metric("${stats.averageCompression}%", "avg")
                }
            }
        }
        items(scans) { scan ->
            LiquidGlassSurface(
                modifier = Modifier
                    .animateContentSize()
                    .clickable { navController.navigate(NavRoutes.libraryDetail(scan.id)) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scan.bookTitle, style = MaterialTheme.typography.titleMedium)
                    Text(scan.mode.label, color = MaterialTheme.colorScheme.secondary)
                    Text("${scan.result.compressionScore}% compressed")
                    Text(scan.result.coreIdea)
                }
            }
        }
        if (showBannerAd) {
            item { ScanHubBannerAd(isVisible = true) }
        }
    }
}

@Composable
private fun ScanHero(isActive: Boolean) {
    LiquidGlassSurface {
        Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF97E7D3).copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.3f),
                        radius = size.width * 0.55f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF0C36A).copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(size.width * 0.8f, size.height * 0.8f),
                        radius = size.width * 0.5f,
                    ),
                )
                val y = if (isActive) size.height * 0.42f else size.height * 0.58f
                drawLine(
                    color = Color(0xFF97E7D3).copy(alpha = 0.85f),
                    start = Offset(20f, y),
                    end = Offset(size.width - 20f, y),
                    strokeWidth = 5f,
                )
            }
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(30.dp))
                Text("Snap your books. Understand instantly.", style = MaterialTheme.typography.headlineSmall)
                Text("Filler becomes signal. Pages become insight.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.08f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultSection(title: String, values: List<String>) {
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            values.filter { it.isNotBlank() }.ifEmpty { listOf("No signal found.") }.forEach { value ->
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
