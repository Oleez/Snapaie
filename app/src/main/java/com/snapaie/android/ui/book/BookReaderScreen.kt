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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.snapaie.android.domain.output.BookLayoutEngine
import com.snapaie.android.domain.output.PageSpec
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

    // Pages, not a scroll of text. The finished book is a laid-out artefact with real page
    // breaks, and until now the only way to see it as one was to export a file and open it
    // somewhere else — which is a strange thing to ask of someone who is already in the app
    // that made it. Same layout engine as the writer, so what is on screen is what exports.
    var asPages by remember { mutableStateOf(true) }
    val measurer = rememberTextMeasurer()
    val pageSpec = PageSpec.SIX_BY_NINE
    val textWidth = rememberComposeTextWidth(measurer)
    val layout = remember(beats, state.chapters, textWidth) {
        if (beats.isEmpty()) {
            null
        } else {
            BookLayoutEngine(pageSpec, textWidth).layout(
                BookContentBuilder.build(
                    chapters = state.chapters,
                    beatsByChapter = beats.groupBy { it.chapterId },
                    assetsByBeat = emptyMap(),
                    includeImages = false,
                ),
            )
        }
    }

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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = asPages,
                    onClick = { asPages = true },
                    label = { Text("Pages") },
                )
                FilterChip(
                    selected = !asPages,
                    onClick = { asPages = false },
                    label = { Text("Text") },
                )
            }
        }

        if (asPages && layout != null) {
            itemsIndexed(layout.pages, key = { index, _ -> "page-$index" }) { index, page ->
                Column {
                    BookPage(page = page, spec = pageSpec, measurer = measurer)
                    Text(
                        "${index + 1} of ${layout.pages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
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
                label = { Text("Rough patch — kept from the original") },
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
