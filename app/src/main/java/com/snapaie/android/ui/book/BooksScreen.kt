package com.snapaie.android.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.pressableScale
import com.snapaie.android.data.local.BookEntity
import com.snapaie.android.data.model.BookImportState
import com.snapaie.android.data.model.BookSourceKind
import com.snapaie.android.data.preferences.UserSettings
import com.snapaie.android.domain.share.BookSharing
import com.snapaie.android.ui.model.ModelSetupCard

/**
 * The shelf. Every imported book, with how far its condensation has got.
 *
 * Progress is shown per book rather than as one global spinner because a run takes hours
 * and the user will come back to this screen many times before it finishes; "chapter 14 of
 * 31" is the only honest answer to why the app is still working.
 */
@Composable
fun BooksScreen(
    viewModel: BookViewModel,
    onOpenBook: (Long) -> Unit,
    onImported: (Long) -> Unit,
    onScanPages: () -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = UserSettings())

    // Resolve what there is to download so the card can name a real size.
    LaunchedEffect(Unit) { viewModel.checkForModel() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val kind = if (name.endsWith(".epub", ignoreCase = true)) BookSourceKind.EPUB else BookSourceKind.PDF
        viewModel.importDocument(uri, kind, name)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Text("Books", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Bring in a PDF or EPUB and get the same story back, shorter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ModelSetupCard(
                modelState = modelState,
                onDownload = viewModel::downloadModel,
                onPause = viewModel::pauseModelDownload,
                onCancel = viewModel::cancelModelDownload,
                onCheckAgain = viewModel::recheckModel,
                onAcceptLicense = viewModel::acceptModelLicense,
            )
        }

        item {
            when (val state = importState) {
                is ImportState.Reading -> LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Reading the file…", style = MaterialTheme.typography.titleSmall)
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { state.page.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Page ${state.page} of ${state.total}" +
                                    if (state.usedOcr) " · reading a scanned page" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }

                is ImportState.Failed -> LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::resetImport) { Text("Dismiss") }
                    }
                }

                is ImportState.Ready -> {
                    LaunchedImport(state.bookId, viewModel, onImported)
                }

                ImportState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(BookSharing.IMPORT_MIME_TYPES) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Add a file")
                    }
                    OutlinedButton(onClick = onScanPages, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Scan pages")
                    }
                }
            }
        }

        if (books.isEmpty() && importState is ImportState.Idle) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add a file, or share a PDF or EPUB to snapaie from any other app. " +
                                "A 500-page novel comes back as 150 pages, or 50, with the story intact. " +
                                "You can also photograph a physical book page by page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(books, key = { it.id }) { book ->
            BookCard(book = book, viewModel = viewModel, onOpen = { onOpenBook(book.id) })
        }
    }
}

@Composable
private fun LaunchedImport(bookId: Long, viewModel: BookViewModel, onImported: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(bookId) {
        viewModel.resetImport()
        onImported(bookId)
    }
}

@Composable
private fun BookCard(book: BookEntity, viewModel: BookViewModel, onOpen: () -> Unit) {
    val detail by viewModel.detail(book.id).collectAsStateWithLifecycle()
    val importState = BookImportState.fromStored(book.importState)

    val interaction = remember { MutableInteractionSource() }
    LiquidGlassSurface(
        modifier = Modifier
            .pressableScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                importState == BookImportState.FAILED -> Text(
                    book.importError ?: "This file could not be read.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )

                importState == BookImportState.IMPORTING -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text("Reading…", style = MaterialTheme.typography.bodySmall)
                }

                detail.progress.total > 0 -> {
                    LinearProgressIndicator(
                        progress = { detail.progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        buildString {
                            append("${detail.progress.done} of ${detail.progress.total} passages")
                            if (detail.progress.outputWords > 0) {
                                append(" · about ${detail.progress.outputWords / 340 + 1} pages so far")
                            }
                            if (detail.progress.isComplete) append(" · done")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Text(
                    "${book.sourcePageCount} pages · ${book.sourceWordCount} words · ready to condense",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
