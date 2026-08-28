package com.snapaie.android.ui.scan

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.model.CefrVocab
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ProducedBy
import com.snapaie.android.data.ai.download.ModelDownloadState
import com.snapaie.android.data.ai.download.ModelDownloadStatus
import com.snapaie.android.data.ai.model.ModelUpdateStatus
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.snapaie.android.core.design.PaperSheet
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.BrandWordmark
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.domain.output.BookContentBuilder
import com.snapaie.android.ui.model.ModelSetupCard
import com.snapaie.android.ui.nav.Routes
import com.snapaie.android.domain.notifications.NotificationKind
import com.snapaie.android.ui.notifications.LocalSnapToast
import com.snapaie.android.ui.notifications.NotificationBell
import kotlinx.coroutines.launch
import com.snapaie.android.core.design.components.ScreenHeader

@Composable
fun ScanHubScreen(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
    unreadNotifications: Int = 0,
    onOpenNotifications: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.extractText(uri)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.ingestPdf(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandWordmark(subtitle = "Shorter. Clearer. All on your phone.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NotificationBell(unreadCount = unreadNotifications, onClick = onOpenNotifications)
                    IconButton(onClick = { navController.navigate(Routes.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }
        }

        item {
            ModelSetupCard(
                modelState = modelState,
                onDownload = viewModel::downloadModel,
                onPause = viewModel::pauseModelDownload,
                onCancel = viewModel::cancelModelDownload,
                onCheckAgain = viewModel::checkForModelUpdate,
                onAcceptLicense = viewModel::acceptGemmaLicense,
            )
        }

        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Explanation style", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExplainStyle.entries.forEach { style ->
                            FilterChip(
                                selected = state.draft.mode == style,
                                onClick = { viewModel.updateStyle(style) },
                                label = { Text(style.label) },
                            )
                        }
                    }
                    Text(
                        state.draft.mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Capture a page", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { navController.navigate(Routes.Camera) }) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Camera")
                        }
                        OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Image")
                        }
                        OutlinedButton(onClick = { pdfPicker.launch("application/pdf") }) {
                            Text("PDF")
                        }
                    }
                    if (state.isOcrRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Text("Reading the page…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.ocrError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    // A page the recogniser could not read. Almost always handwriting, so
                    // this is an offer rather than a failure — and it is deliberately not
                    // styled as an error, because nothing went wrong.
                    if (state.needsCloudRead) {
                        HandwritingNotice()
                    }
                    OutlinedTextField(
                        value = state.draft.bookTitle,
                        onValueChange = viewModel::updateBookTitle,
                        label = { Text("Book / source (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.draft.pageText,
                        onValueChange = viewModel::updatePageText,
                        label = { Text("Page text (or paste anything)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                    )
                    Button(
                        onClick = {
                            navController.navigate(Routes.ScanRun)
                            viewModel.runWorkflow()
                        },
                        enabled = state.draft.pageText.isNotBlank() && !state.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Condense this page")
                    }
                }
            }
        }

        item {
            LiquidGlassSurface(
                modifier = Modifier.animateContentSize(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Writing tools", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Fix, rewrite, humanize, translate — 11 tools, all offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { navController.navigate(Routes.Writing) }) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open writing assistant")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun CompressionRunScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Condensing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Extracted text", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.draft.pageText.take(600) + if (state.draft.pageText.length > 600) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.phases.forEach { phase ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (phase.isComplete) "✅" else "⏳")
                            Column {
                                Text(phase.phase.label, style = MaterialTheme.typography.labelLarge)
                                Text(phase.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.isRunning && state.phases.isEmpty()) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        if (state.streamText.isNotBlank() && state.result == null) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Working…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(state.streamText.takeLast(1200), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            AnimatedVisibility(visible = state.result != null) {
                Button(
                    onClick = {
                        val id = state.lastSavedScanId
                        if (id != null) {
                            navController.navigate(Routes.scanDetail(id)) {
                                popUpTo(Routes.Snap)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open clarity result")
                }
            }
        }
        if (state.isRunning) {
            item {
                OutlinedButton(onClick = {
                    viewModel.cancelRun()
                    navController.popBackStack()
                }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun ScanDetailScreen(viewModel: SnapAieViewModel, navController: NavHostController, scanId: Long) {
    val scan by viewModel.observeScan(scanId).collectAsState(initial = null)
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = LocalSnapToast.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = scan ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var vocabLoading by remember { mutableStateOf(false) }
    var forgeMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = current.bookTitle,
                subtitle = current.mode.label,
                onBack = { navController.popBackStack() },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPill("${current.result.compressionScore}%", "compressed")
                MetricPill("${current.result.estimatedTimeSavedMinutes}m", "saved")
                MetricPill("${current.wordsIn}", "words in")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(
                    onClick = {
                        clipboard.setText(AnnotatedString(current.result.toMarkdown(includeBranding = !isPro)))
                    },
                    label = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                )
                AssistChip(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, current.result.toMarkdown(includeBranding = !isPro))
                        }
                        context.startActivity(Intent.createChooser(send, "Share clarity"))
                    },
                    label = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                )
                AssistChip(
                    onClick = {
                        val tts = viewModel.container.ttsSpeaker
                        tts.languageCode = viewModel.settings.value.outputLanguage
                        tts.speak(
                            listOf(
                                current.result.conciseMeaning,
                                current.result.coreIdea,
                                current.result.simplifiedExplanation,
                            ),
                        )
                    },
                    label = { Text("Narrate") },
                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null) },
                )
                AssistChip(
                    onClick = {
                        scope.launch {
                            val sessionId = viewModel.container.chatEngine.createSession(
                                title = current.bookTitle,
                                persona = com.snapaie.android.domain.chat.Persona.fromId(viewModel.settings.value.chatPersona),
                                scanId = current.id,
                                appearance = viewModel.settings.value.chatAppearance,
                            )
                            navController.navigate(Routes.chat(sessionId))
                        }
                    },
                    label = { Text("Ask AE") },
                )
                AssistChip(
                    onClick = {
                        scope.launch {
                            val saved = viewModel.container.recallEngine.saveTopic(
                                title = current.bookTitle,
                                summary = current.result.coreIdea.take(200),
                                content = current.result.toMarkdown(includeBranding = false),
                                sourceType = "explanation",
                                sourceScanId = current.id,
                                isPro = isPro,
                            )
                            forgeMessage = when {
                                saved != null -> "Added to Forge Recall 🔥"
                                isPro -> "Already in your deck."
                                else -> "Free deck is full (5 topics). Unlock Pro for unlimited."
                            }
                            // Port of the extension's promptUpgradePricing(): a paywall
                            // hit is worth keeping in the notification centre, since the
                            // inline message disappears the moment the screen changes.
                            if (saved == null && !isPro) {
                                viewModel.notificationCenter.push(
                                    message = "Your free Forge deck holds 5 topics. Pro is a one-time " +
                                        "purchase and removes the cap.",
                                    title = "Deck full",
                                    kind = NotificationKind.Promo,
                                    ctaRoute = Routes.Upgrade,
                                    ctaLabel = "See Pro",
                                )
                            }
                        }
                    },
                    label = { Text("🧠 Forge topic") },
                )
                if (isPro) {
                    AssistChip(
                        onClick = {
                            val exporter = viewModel.container.markdownExporter
                            context.startActivity(
                                exporter.shareMarkdownFile(
                                    exporter.scanToMarkdown(current, includeBranding = false),
                                    current.bookTitle,
                                ),
                            )
                            toast.show("Markdown ready to share.")
                        },
                        label = { Text("Export .md") },
                    )
                }
                AssistChip(
                    onClick = {
                        val title = current.bookTitle
                        viewModel.deleteScan(current.id) { undo ->
                            toast.show(
                                message = "Deleted \"$title\"",
                                actionLabel = "Undo",
                                onAction = undo,
                            )
                        }
                        navController.popBackStack()
                    },
                    label = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }
        }

        forgeMessage?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (current.result.isPlainTextOnly) {
            item { ResultSection("Result", listOf(current.result.plainTextFallback)) }
        } else {
            // The retelling leads, because it is the thing someone opened this screen to
            // read. The breakdown below it is reference, not the result.
            if (current.result.condensedProse.isNotBlank()) {
                item {
                    ProsePage(
                        title = current.bookTitle.ifBlank { "Shorter version" },
                        prose = current.result.condensedProse,
                        wordsIn = current.wordsIn,
                        wordsOut = current.wordsOut,
                        producedBy = current.result.producedBy,
                    )
                }
            }
            // The breakdown is a second full generation, so it is offered rather than
            // spent on every snap before the reader gets to the part they came for.
            if (current.result.coreIdea.isBlank() && current.result.conciseMeaning.isBlank()) {
                item {
                    var loading by remember(current.id) { mutableStateOf(false) }
                    LiquidGlassSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Want the detail?", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Core idea, intent, key quotes and vocabulary. Takes another moment.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    loading = true
                                    viewModel.requestBreakdown(current.id) { loading = false }
                                },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (loading) "Breaking it down…" else "Break it down") }
                        }
                    }
                }
            }
            item { ResultSection("Concise meaning", listOf(current.result.conciseMeaning)) }
            item { ResultSection("Core idea", listOf(current.result.coreIdea)) }
            item { ResultSection("Author intent", listOf(current.result.authorIntent)) }
            item { ResultSection("Simplified explanation", listOf(current.result.simplifiedExplanation)) }
            item { ResultSection("Actionable takeaways", current.result.actionableInsights) }
            item { ResultSection("Hidden meaning", listOf(current.result.hiddenMeaning)) }
            if (current.result.importantVocabulary.isNotEmpty()) {
                item {
                    ResultSection(
                        "Smart vocabulary",
                        current.result.importantVocabulary.map { "${it.word} — ${it.meaning}" },
                    )
                }
            }
            if (current.result.fillerDetected.isNotEmpty()) {
                item {
                    ResultSection(
                        "Filler detected",
                        current.result.fillerDetected.map { "${it.type}: ${it.excerpt}" },
                    )
                }
            }
        }

        item {
            CefrVocabSection(
                vocab = current.result.cefrVocabulary,
                loading = vocabLoading,
                onGenerate = {
                    vocabLoading = true
                    viewModel.generateCefrVocab(current.id) { vocabLoading = false }
                },
                onAddToForge = { word, definition ->
                    scope.launch {
                        viewModel.container.recallEngine.saveTopic(
                            title = word,
                            summary = definition.take(200),
                            content = "$word — $definition",
                            sourceType = "vocab",
                            sourceScanId = current.id,
                        )
                    }
                },
            )
        }

        item {
            OutlinedButton(
                onClick = {
                    viewModel.loadDraftFromScan(current)
                    navController.navigate(Routes.ScanRun)
                    viewModel.runWorkflow()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Re-run clarity")
            }
            if (current.sourceText.isBlank()) {
                Text(
                    "Original full text unavailable for this older scan — re-run uses the stored preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun MetricPill(value: String, label: String) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(DesignTokens.RadiusSm))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/**
 * The retelling, set as a page.
 *
 * Everything else on this screen is chrome — chips, cards, controls. This is the one part
 * that exists to be read, so it gets the shape of a printed page instead: paper ground,
 * wide margins, a serif face, indented paragraphs after the first, and a running foot. The
 * point is that it should feel like something written rather than something returned.
 */
@Composable
private fun ProsePage(
    title: String,
    prose: String,
    wordsIn: Int,
    wordsOut: Int,
    producedBy: ProducedBy = ProducedBy.UNKNOWN,
) {
    val paragraphs = BookContentBuilder.paragraphsOf(prose)
    PaperSheet {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            // Says which engine wrote this. A page the model wrote and a page assembled
            // from the author's own sentences look equally finished, so without this a
            // model that never ran is indistinguishable from one that writes like that.
            if (producedBy != ProducedBy.UNKNOWN) {
                Text(
                    producedBy.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(
                        alpha = if (producedBy == ProducedBy.ON_DEVICE) 0.85f else 0.55f,
                    ),
                )
            }
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.14f))

            paragraphs.forEachIndexed { index, paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        lineHeight = 27.sp,
                    ),
                    textAlign = TextAlign.Justify,
                    // First paragraph flush, the rest indented: the convention every
                    // printed book uses, and the cheapest way to read as a page.
                    modifier = Modifier.padding(start = if (index == 0) 0.dp else 14.dp),
                )
            }

            HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.14f))
            Text(
                if (wordsIn > 0 && wordsOut > 0) {
                    "$wordsIn words down to $wordsOut"
                } else {
                    "snapaie"
                },
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
fun ResultSection(title: String, values: List<String>) {
    val meaningful = values.filter { it.isNotBlank() }
    if (meaningful.isEmpty()) return
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            meaningful.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CefrVocabSection(
    vocab: CefrVocab?,
    loading: Boolean,
    onGenerate: () -> Unit,
    onAddToForge: (String, String) -> Unit,
) {
    var selectedLevel by remember { mutableStateOf(0) }
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CEFR vocabulary", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (vocab == null || vocab.isEmpty) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Text("Extracting B2/C1/C2 words…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    OutlinedButton(onClick = onGenerate) { Text("Extract vocabulary") }
                }
            } else {
                val levels = listOf(
                    Triple("B2", DesignTokens.CefrB2, vocab.b2),
                    Triple("C1", DesignTokens.CefrC1, vocab.c1),
                    Triple("C2", DesignTokens.CefrC2, vocab.c2),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    levels.forEachIndexed { index, (label, color, words) ->
                        FilterChip(
                            selected = selectedLevel == index,
                            onClick = { selectedLevel = index },
                            label = { Text("$label (${words.size})") },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.35f),
                            ),
                        )
                    }
                }
                val words = levels[selectedLevel].third
                if (words.isEmpty()) {
                    Text("No words at this level.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                words.forEach { word ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${word.word} · ${word.partOfSpeech}", style = MaterialTheme.typography.labelLarge, color = levels[selectedLevel].second)
                            TextButton(onClick = { onAddToForge(word.word, word.definition) }) { Text("＋ Forge") }
                        }
                        Text(word.definition, style = MaterialTheme.typography.bodySmall)
                        if (word.example.isNotBlank()) {
                            Text(
                                "“${word.example}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%d MB".format(bytes / 1_000_000L)
    else -> "$bytes B"
}

/**
 * Shown when the page is readable-looking but the recogniser could not make sense of it.
 *
 * A text recogniser reads printed shapes. Handwriting is not a harder version of that job,
 * it is a different one, and no amount of retaking the photo will fix it — so telling
 * someone to try again in better light would be advice that cannot work. This says what is
 * actually true and what will actually help.
 */
@Composable
private fun HandwritingNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("This looks handwritten", style = MaterialTheme.typography.titleSmall)
        Text(
            "Offline reading handles printed pages. Handwritten ones need Cloud Read, " +
                "which is coming soon.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "You can still type or paste the text below and shorten it now.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
