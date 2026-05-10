package com.snapaie.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.snapaie.android.AppContainer
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.SnapAieTheme
import com.snapaie.android.core.design.snapScreenBackground
import com.snapaie.android.data.model.KnowledgeMode
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelTier

private enum class Route(val value: String, val label: String) {
    Scan("scan", "Scan"),
    Compress("compress", "Compress"),
    Result("result", "Clarity"),
    Library("library", "Growth"),
}

@Composable
fun SnapAieApp(container: AppContainer) {
    SnapAieTheme {
        val viewModel: SnapAieViewModel = viewModel(factory = SnapAieViewModelFactory(container))
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .snapScreenBackground(),
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                ) {
                    Route.entries.forEach { route ->
                        NavigationBarItem(
                            selected = backStack?.destination?.hierarchy?.any { it.route == route.value } == true,
                            onClick = { navController.navigate(route.value) { launchSingleTop = true } },
                            icon = {
                                Icon(
                                    imageVector = when (route) {
                                        Route.Scan -> Icons.Default.ImageSearch
                                        Route.Compress -> Icons.Default.PlayArrow
                                        Route.Result -> Icons.Default.AutoAwesome
                                        Route.Library -> Icons.Default.Insights
                                    },
                                    contentDescription = route.label,
                                )
                            },
                            label = { Text(route.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Route.Scan.value,
                modifier = Modifier.padding(padding),
            ) {
                composable(Route.Scan.value) {
                    ScanHub(viewModel) { navController.navigate(Route.Compress.value) }
                }
                composable(Route.Compress.value) {
                    CompressionRun(viewModel) { navController.navigate(Route.Result.value) }
                }
                composable(Route.Result.value) {
                    ClarityScreen(viewModel.uiState.value.result)
                }
                composable(Route.Library.value) {
                    GrowthScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun ScanHub(viewModel: SnapAieViewModel, onRun: () -> Unit) {
    val state = viewModel.uiState.value
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.extractText(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Header("snapaie", "Cut the fluff. Keep the knowledge.")
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
                    Button(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ImageSearch, contentDescription = null)
                        Text(if (state.isOcrRunning) "Reading page..." else "Snap or import book page")
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
private fun CompressionRun(viewModel: SnapAieViewModel, onResult: () -> Unit) {
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
                        Button(onClick = onResult, modifier = Modifier.fillMaxWidth()) {
                            Text("Open clarity result")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClarityScreen(result: KnowledgeResult?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("${result.compressionScore}%", "compressed")
                        Metric("${result.estimatedTimeSavedMinutes}m", "saved")
                        IconButton(onClick = { clipboard.setText(AnnotatedString(result.toMarkdown())) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(Intent.EXTRA_TEXT, result.toMarkdown())
                                }
                                context.startActivity(Intent.createChooser(intent, "Share clarity result"))
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                }
            }
            item { ResultSection("Concise meaning", listOf(result.conciseMeaning)) }
            item { ResultSection("Core idea", listOf(result.coreIdea)) }
            item { ResultSection("Author intent", listOf(result.authorIntent)) }
            item { ResultSection("Simplified explanation", listOf(result.simplifiedExplanation)) }
            item { ResultSection("Actionable takeaways", result.actionableInsights) }
            item { ResultSection("Hidden meaning", listOf(result.hiddenMeaning)) }
            item { ResultSection("Smart vocabulary", result.importantVocabulary.map { "${it.word}: ${it.meaning} Simpler: ${it.simplerVersion}" }) }
            item { ResultSection("Filler detected", result.fillerDetected.map { "${it.type}: ${it.excerpt} (${it.reason})" }) }
        }
    }
}

@Composable
private fun GrowthScreen(viewModel: SnapAieViewModel) {
    val scans by viewModel.library.collectAsStateWithLifecycle()
    val stats by viewModel.readerStats.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Growth", "Become smarter faster.") }
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
            LiquidGlassSurface(modifier = Modifier.animateContentSize()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scan.bookTitle, style = MaterialTheme.typography.titleMedium)
                    Text(scan.mode.label, color = MaterialTheme.colorScheme.secondary)
                    Text("${scan.result.compressionScore}% compressed")
                    Text(scan.result.coreIdea)
                }
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
