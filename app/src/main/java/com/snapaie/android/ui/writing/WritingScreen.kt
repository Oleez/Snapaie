package com.snapaie.android.ui.writing

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.domain.writing.HumanizeStrength
import com.snapaie.android.domain.writing.WritingDialect
import com.snapaie.android.domain.writing.WritingRequest
import com.snapaie.android.domain.writing.WritingStyle
import com.snapaie.android.domain.writing.WritingTool
import com.snapaie.android.core.diagnostics.CrashLog
import com.snapaie.android.ui.SnapAieViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.snapaie.android.core.design.components.ScreenHeader

@Composable
fun WritingScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val engine = viewModel.container.writingEngine
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var request by remember { mutableStateOf(WritingRequest()) }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var variant by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(nonce: Int) {
        job?.cancel()
        output = ""
        running = true
        CrashLog.breadcrumb("writing assistant: ${request.tool.id}")
        job = scope.launch {
            runCatching {
                engine.run(
                    request.copy(variantNonce = nonce, targetLanguage = settings.outputLanguage),
                ).collect { token -> output += token }
            }
            output = engine.cleanOutput(output)
            running = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Writing assistant ✍️", onBack = { navController.popBackStack() })
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WritingTool.entries.forEach { tool ->
                    FilterChip(
                        selected = request.tool == tool,
                        onClick = { request = request.copy(tool = tool) },
                        label = { Text("${tool.emoji} ${tool.label}") },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (request.tool) {
                    WritingTool.Rewrite, WritingTool.Paraphrase -> WritingStyle.entries.forEach { style ->
                        FilterChip(
                            selected = request.style == style,
                            onClick = { request = request.copy(style = style) },
                            label = { Text(style.label) },
                        )
                    }
                    WritingTool.Humanize -> HumanizeStrength.entries.forEach { strength ->
                        FilterChip(
                            selected = request.humanizeStrength == strength,
                            onClick = { request = request.copy(humanizeStrength = strength) },
                            label = { Text(strength.label) },
                        )
                    }
                    WritingTool.Tone -> listOf("friendly", "formal", "confident", "empathetic", "direct", "playful").forEach { tone ->
                        FilterChip(
                            selected = request.tone == tone,
                            onClick = { request = request.copy(tone = tone) },
                            label = { Text(tone) },
                        )
                    }
                    else -> WritingDialect.entries.forEach { dialect ->
                        FilterChip(
                            selected = request.dialect == dialect,
                            onClick = { request = request.copy(dialect = dialect) },
                            label = { Text(dialect.label) },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = request.text,
                onValueChange = { request = request.copy(text = it) },
                label = { Text("Your text") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
            )
        }
        item {
            Button(
                onClick = { variant = 0; run(0) },
                enabled = request.text.isNotBlank() && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Working…" else "Transform")
            }
        }
        if (running && output.isBlank()) {
            item { CircularProgressIndicator() }
        }
        if (output.isNotBlank()) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Result", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(output, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(output)) }) { Text("Copy") }
                            OutlinedButton(
                                onClick = { variant += 1; run(variant) },
                                enabled = !running,
                            ) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}
