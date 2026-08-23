package com.snapaie.android.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.ScreenHeader
import com.snapaie.android.data.model.CondenseJobState
import com.snapaie.android.data.model.CondenseTargetKind
import com.snapaie.android.domain.condense.CondenseTarget

/**
 * One book: choose a length, watch it run, read what is finished.
 *
 * Deliberately one screen rather than three. A condense job is not a wizard the user
 * completes and leaves — they come back to it repeatedly over hours, and every time the
 * question is the same: how far has it got, and can I read any of it yet.
 */
@Composable
fun BookDetailScreen(
    bookId: Long,
    viewModel: BookViewModel,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    onExport: (Long) -> Unit,
) {
    val state by viewModel.detail(bookId).collectAsStateWithLifecycle()
    val book = state.book

    var targetKind by remember { mutableStateOf(CondenseTargetKind.PERCENT) }
    var percent by remember { mutableIntStateOf(30) }
    var pages by remember { mutableStateOf("150") }
    var chargingOnly by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = book?.title.orEmpty().ifBlank { "Book" },
                subtitle = book?.let { "${it.sourcePageCount} pages · ${it.sourceWordCount} words" },
                onBack = onBack,
            )
        }

        if (state.progress.total > 0) {
            item { ProgressCard(state, viewModel, bookId, onRead, onExport) }
        }

        if (!state.progress.isComplete) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How short?", style = MaterialTheme.typography.titleMedium)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "30%", 10 to "10%").forEach { (value, label) ->
                                FilterChip(
                                    selected = targetKind == CondenseTargetKind.PERCENT && percent == value,
                                    onClick = {
                                        targetKind = CondenseTargetKind.PERCENT
                                        percent = value
                                    },
                                    label = { Text(label) },
                                )
                            }
                            FilterChip(
                                selected = targetKind == CondenseTargetKind.PAGES,
                                onClick = { targetKind = CondenseTargetKind.PAGES },
                                label = { Text("Pages") },
                            )
                        }

                        if (targetKind == CondenseTargetKind.PAGES) {
                            OutlinedTextField(
                                value = pages,
                                onValueChange = { pages = it.filter(Char::isDigit).take(4) },
                                label = { Text("Target pages") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        book?.let { EstimateLine(it.sourceWordCount, targetKind, percent, pages) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.fillMaxWidth(0.75f)) {
                                Text("Only while charging", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Recommended. This runs for hours and works the GPU hard.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = chargingOnly, onCheckedChange = { chargingOnly = it })
                        }

                        if (!state.isRunning) {
                            Button(
                                onClick = {
                                    viewModel.startCondense(
                                        bookId = bookId,
                                        targetKind = targetKind,
                                        targetValue = if (targetKind == CondenseTargetKind.PAGES) {
                                            pages.toIntOrNull() ?: 150
                                        } else {
                                            percent
                                        },
                                        chargingOnly = chargingOnly,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.progress.done > 0) "Resume" else "Condense this book")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The honest estimate.
 *
 * A 500-page book is several hours of on-device inference, and hiding that behind a
 * spinner would be the single worst thing this screen could do — the user needs to decide
 * to leave the phone plugged in overnight *before* they start, not discover it at 2am.
 */
@Composable
private fun EstimateLine(
    sourceWords: Int,
    targetKind: CondenseTargetKind,
    percent: Int,
    pages: String,
) {
    val targetWords = when (targetKind) {
        CondenseTargetKind.PAGES -> CondenseTarget.wordsForPages(pages.toIntOrNull() ?: 150)
        CondenseTargetKind.PERCENT -> CondenseTarget.wordsForPercent(sourceWords, percent)
    }
    val outputPages = (targetWords / CondenseTarget.WORDS_PER_PAGE).coerceAtLeast(1)
    val ladder = CondenseTarget.needsLadder(targetWords, sourceWords)
    // Roughly words-out per hour on a mid-range phone; deliberately pessimistic, because
    // an estimate that runs short is worse than one that runs long.
    val hours = ((if (ladder) targetWords + sourceWords * 0.3f else targetWords.toFloat()) /
        WORDS_PER_HOUR).coerceAtLeast(0.2f)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "About $outputPages pages out of ${sourceWords / CondenseTarget.WORDS_PER_PAGE} in.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            buildString {
                append("Roughly ")
                append(if (hours < 1f) "under an hour" else "${hours.toInt()}–${hours.toInt() + 2} hours")
                append(" on this device. It runs in the background; you can close the app.")
                if (ladder) {
                    append(" At this length it goes via a 30% edition first, which stays readable on its own.")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressCard(
    state: BookDetailState,
    viewModel: BookViewModel,
    bookId: Long,
    onRead: (Long) -> Unit,
    onExport: (Long) -> Unit,
) {
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    state.progress.isComplete -> "Finished"
                    state.jobState == CondenseJobState.PAUSED -> "Paused"
                    state.jobState == CondenseJobState.FAILED -> "Stopped"
                    state.isRunning -> "Condensing"
                    else -> "Part-way through"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            LinearProgressIndicator(
                progress = { state.progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${state.progress.done} of ${state.progress.total} passages · " +
                    "about ${state.progress.outputWords / CondenseTarget.WORDS_PER_PAGE + 1} pages written",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.job?.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (state.fallbackCount > 0) {
                Text(
                    "${state.fallbackCount} passage(s) were written from the source text rather than " +
                        "retold, so nothing is missing but the prose there is rougher. They are marked " +
                        "in the reader.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.readableBeats.isNotEmpty()) {
                    // Readable while it runs: chapters finish in order, so the beginning of
                    // the book is done long before the end.
                    Button(onClick = { onRead(bookId) }) {
                        Text(if (state.progress.isComplete) "Read" else "Read what is done")
                    }
                }
                if (state.progress.isComplete) {
                    OutlinedButton(onClick = { onExport(bookId) }) { Text("Export") }
                }
                when {
                    state.isRunning -> TextButton(onClick = { viewModel.pauseCondense(bookId) }) {
                        Text("Pause")
                    }
                    state.jobState == CondenseJobState.PAUSED ->
                        TextButton(onClick = { viewModel.resumeCondense(bookId) }) { Text("Resume") }
                    else -> Unit
                }
            }
        }
    }
}

private const val WORDS_PER_HOUR = 14_000f
