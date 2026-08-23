package com.snapaie.android.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.ScreenHeader
import com.snapaie.android.data.local.BookBeatEntity
import com.snapaie.android.data.model.BeatStatus
import com.snapaie.android.domain.output.BookContentBuilder
import kotlinx.coroutines.launch

/**
 * Reads the condensed book, chapter by chapter, while the rest is still being written.
 *
 * Two things here are not just decoration. Passages that fell back to extractive text are
 * marked, because the user deserves to know which paragraphs are rough rather than
 * wondering why the prose dipped. And any passage can be opened against its source, which
 * is the only way to actually check the claim this whole app makes — that nothing was
 * skipped.
 */
@Composable
fun BookReaderScreen(
    bookId: Long,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.detail(bookId).collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var comparing by remember { mutableStateOf<Long?>(null) }
    var sourceText by remember { mutableStateOf("") }

    val chapterTitles = state.chapters.associate { it.id to it.title }
    val beats = state.readableBeats

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = state.book?.title.orEmpty(),
                subtitle = if (state.progress.isComplete) {
                    "${state.progress.outputWords} words"
                } else {
                    "${state.progress.done} of ${state.progress.total} passages so far"
                },
                onBack = onBack,
            )
        }

        var lastChapter = -1L
        beats.forEach { beat ->
            if (beat.chapterId != lastChapter) {
                lastChapter = beat.chapterId
                item(key = "chapter-${beat.chapterId}") {
                    Text(
                        chapterTitles[beat.chapterId].orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                }
            }

            item(key = "beat-${beat.id}") {
                BeatBlock(
                    beat = beat,
                    isComparing = comparing == beat.id,
                    sourceText = sourceText,
                    onCompare = {
                        if (comparing == beat.id) {
                            comparing = null
                        } else {
                            comparing = beat.id
                            scope.launch { sourceText = viewModel.sourceFor(beat) }
                        }
                    },
                )
            }
        }

        if (!state.progress.isComplete) {
            item {
                Text(
                    "Still condensing the rest — chapters finish in order, so this grows from the top.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun BeatBlock(
    beat: BookBeatEntity,
    isComparing: Boolean,
    sourceText: String,
    onCompare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (BeatStatus.fromStored(beat.status) == BeatStatus.FALLBACK) {
            AssistChip(
                onClick = onCompare,
                label = { Text("Rough passage — taken from the source") },
            )
        }

        BookContentBuilder.paragraphsOf(beat.outputText).forEach { paragraph ->
            Text(
                paragraph,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCompare) {
                Text(if (isComparing) "Hide the original" else "Compare with the original")
            }
            Text(
                "source p.${beat.srcPageFrom}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        if (isComparing) {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Original passage · ${beat.srcWords} words → ${beat.outputWords}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sourceText.ifBlank { "Loading…" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
