package com.snapaie.android.entry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapaie.android.MainActivity
import com.snapaie.android.SnapAieApplication
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.SnapAieTheme
import com.snapaie.android.core.design.ThemeMode
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.local.knowledgeScanEntity
import com.snapaie.android.domain.scan.ScanMetrics
import com.snapaie.android.domain.scan.WorkflowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Door 1 — ACTION_PROCESS_TEXT. The user selects text anywhere in Android and
 * taps "Snap": a transparent activity shows a result sheet over the host app.
 * Zero permissions, zero OCR, clean Unicode input.
 */
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val container = (application as SnapAieApplication).container

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SnapDark) }
            LaunchedEffect(Unit) {
                themeMode = ThemeMode.fromStored(container.appPreferencesRepository.userSettings.first().themeMode)
            }
            SnapAieTheme(mode = themeMode) {
                QuickSnapSheet(
                    selectedText = selected,
                    onDismiss = { finish() },
                    onOpenFull = { scanId ->
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_SCAN_ID, scanId)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickSnapSheet(
    selectedText: String,
    onDismiss: () -> Unit,
    onOpenFull: (Long) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { (context.applicationContext as SnapAieApplication).container }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var style by remember { mutableStateOf(ExplainStyle.Auto) }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var savedScanId by remember { mutableStateOf<Long?>(null) }
    var modelMissing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run() {
        job?.cancel()
        output = ""
        running = true
        savedScanId = null
        val draft = BookScanDraft(mode = style, pageText = selectedText, bookTitle = "Selected text")
        job = scope.launch {
            val settings = container.appPreferencesRepository.userSettings.first()
            val tier = com.snapaie.android.data.model.ModelTier.entries
                .firstOrNull { it.name == settings.selectedModelTier }
                ?: com.snapaie.android.data.model.ModelTier.Gemma3nE2B
            modelMissing = !container.sessionManager.isModelDownloaded(tier)
            container.workflowEngine.run(draft, tier).collect { event ->
                when (event) {
                    is WorkflowEvent.Token -> output += event.value
                    is WorkflowEvent.Result -> {
                        val result = event.result
                        output = listOf(
                            result.conciseMeaning,
                            result.coreIdea,
                            result.simplifiedExplanation,
                            result.plainTextFallback,
                        ).filter { it.isNotBlank() }.joinToString("\n\n")
                        savedScanId = container.database.knowledgeScanDao().insert(
                            knowledgeScanEntity(
                                draft = draft,
                                result = result,
                                wordsIn = ScanMetrics.wordCount(selectedText),
                                wordsOut = ScanMetrics.wordCount(output),
                                languageCode = settings.outputLanguage,
                            ),
                        )
                        running = false
                    }
                    else -> Unit
                }
            }
            running = false
        }
    }

    LaunchedEffect(Unit) { if (selectedText.isNotBlank()) run() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("snapaie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExplainStyle.entries.forEach { entry ->
                            FilterChip(
                                selected = style == entry,
                                onClick = {
                                    style = entry
                                    run()
                                },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                    if (running && output.isBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
                            Text("Thinking on-device…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        output.ifBlank { selectedText.take(300) },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    if (modelMissing) {
                        Text(
                            "Instant offline draft — download Gemma in the app for full AI.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(output)) }, enabled = output.isNotBlank()) {
                            Text("Copy")
                        }
                        Button(
                            onClick = { savedScanId?.let(onOpenFull) },
                            enabled = savedScanId != null,
                        ) {
                            Text("Open in snapaie")
                        }
                    }
                }
            }
        }
    }
}
