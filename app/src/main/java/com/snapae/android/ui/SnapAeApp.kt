package com.snapae.android.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.snapae.android.AppContainer
import com.snapae.android.core.design.LiquidGlassSurface
import com.snapae.android.core.design.SnapAeTheme
import com.snapae.android.core.design.snapScreenBackground
import com.snapae.android.data.model.AssetType
import com.snapae.android.data.model.LaunchPackage
import com.snapae.android.data.model.ModelTier

private enum class Route(val value: String, val label: String) {
    Input("input", "Input"),
    Run("run", "Run"),
    Package("package", "Package"),
    Library("library", "Library"),
}

@Composable
fun SnapAeApp(container: AppContainer) {
    SnapAeTheme {
        val viewModel: SnapAeViewModel = viewModel(factory = SnapAeViewModelFactory(container))
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .snapScreenBackground(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                                        Route.Input -> Icons.Default.Home
                                        Route.Run -> Icons.Default.PlayArrow
                                        Route.Package -> Icons.Default.AutoAwesome
                                        Route.Library -> Icons.Default.Folder
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
                startDestination = Route.Input.value,
                modifier = Modifier.padding(padding),
            ) {
                composable(Route.Input.value) {
                    InputHub(viewModel) { navController.navigate(Route.Run.value) }
                }
                composable(Route.Run.value) {
                    OrchestratorRun(viewModel) { navController.navigate(Route.Package.value) }
                }
                composable(Route.Package.value) {
                    LaunchPackageScreen(viewModel.uiState.value.launchPackage)
                }
                composable(Route.Library.value) {
                    LibraryScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputHub(viewModel: SnapAeViewModel, onRun: () -> Unit) {
    val state = viewModel.uiState.value
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Header("SnapAE", "Local launch packages from rough source material.")
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Model setup", style = MaterialTheme.typography.titleMedium)
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
                        Text(if (modelState.isReady) "Model ready" else "Download model")
                    }
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = state.draft.assetType.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Asset type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            AssetType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.label) },
                                    onClick = {
                                        viewModel.updateAssetType(type)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.draft.title,
                        onValueChange = viewModel::updateTitle,
                        label = { Text("Working title") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.draft.content,
                        onValueChange = viewModel::updateContent,
                        label = { Text("Transcript, notes, pitch, or update") },
                        minLines = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.draft.context,
                        onValueChange = viewModel::updateContext,
                        label = { Text("Optional audience, platform, or launch context") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.runWorkflow()
                            onRun()
                        },
                        enabled = state.draft.content.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Text("Generate launch package")
                    }
                }
            }
        }
    }
}

@Composable
private fun OrchestratorRun(viewModel: SnapAeViewModel, onPackage: () -> Unit) {
    val state = viewModel.uiState.value
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Orchestrator", "Ten local phases, one package.") }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::runWorkflow, enabled = !state.isRunning) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Run")
                        }
                        TextButton(onClick = viewModel::cancelRun, enabled = state.isRunning) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Cancel")
                        }
                    }
                    state.phases.forEach { phase ->
                        Text(
                            text = "${phase.phase.label}: ${phase.text}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AnimatedVisibility(state.streamText.isNotBlank()) {
                        Text(state.streamText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(state.launchPackage != null) {
                        Button(onClick = onPackage, modifier = Modifier.fillMaxWidth()) {
                            Text("Open launch package")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchPackageScreen(launchPackage: LaunchPackage?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Launch Package", "Copy, share, or export the finished run.") }
        if (launchPackage == null) {
            item { LiquidGlassSurface { Text("Generate a package from the Run tab first.") } }
        } else {
            item {
                LiquidGlassSurface {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(launchPackage.toMarkdown())) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Markdown")
                        }
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(Intent.EXTRA_TEXT, launchPackage.toMarkdown())
                                }
                                context.startActivity(Intent.createChooser(intent, "Share launch package"))
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                }
            }
            item { PackageSection("Audience", listOf(launchPackage.audience)) }
            item { PackageSection("Strongest hook angle", listOf(launchPackage.strongestHookAngle)) }
            item { PackageSection("Titles", launchPackage.titles) }
            item { PackageSection("Hooks", launchPackage.hooks) }
            item { PackageSection("Captions", launchPackage.captions) }
            item { PackageSection("Hashtags", launchPackage.hashtags) }
            item { PackageSection("Repurposing", launchPackage.repurposingBlocks) }
            item { PackageSection("Schedule", listOf(launchPackage.scheduleSuggestion)) }
            item { PackageSection("Analytics", listOf(launchPackage.analyticsTemplateMarkdown)) }
            item { PackageSection("Next ideas", launchPackage.nextIdeas) }
        }
    }
}

@Composable
private fun LibraryScreen(viewModel: SnapAeViewModel) {
    val runs by viewModel.library.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header("Library", "Offline history saved with Room.") }
        items(runs) { run ->
            LiquidGlassSurface(modifier = Modifier.animateContentSize()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(run.title, style = MaterialTheme.typography.titleMedium)
                    Text(run.assetType.label, color = MaterialTheme.colorScheme.secondary)
                    Text(run.launchPackage.strongestHookAngle)
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
private fun PackageSection(title: String, values: List<String>) {
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            values.filter { it.isNotBlank() }.forEach { value ->
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
