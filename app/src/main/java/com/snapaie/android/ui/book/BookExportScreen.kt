package com.snapaie.android.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.ScreenHeader
import com.snapaie.android.data.model.BookExportFormat
import com.snapaie.android.domain.share.BookSharing

/**
 * Turns the finished book into a file and hands it to the rest of the phone.
 *
 * Both routes are offered because they answer different intentions: sharing sends it
 * somewhere, saving puts it where the user keeps things. Only offering the share sheet
 * would make "keep this" harder than it should be.
 */
@Composable
fun BookExportScreen(
    bookId: Long,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.detail(bookId).collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()

    var format by remember { mutableStateOf(BookExportFormat.PDF) }
    var pageSize by remember { mutableStateOf("6x9") }
    var includeImages by remember { mutableStateOf(true) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val file = export?.file ?: return@rememberLauncherForActivityResult
        if (uri != null) BookSharing.copyTo(context, file, uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Export",
                // Exporting is for taking the book elsewhere, not for reading it. Saying so
                // stops it looking like the only way to see the finished pages.
                subtitle = state.book?.title?.let { "$it · already readable in the app" }
                    ?: "Already readable in the app",
                onBack = onBack,
            )
        }

        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Format", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookExportFormat.entries.forEach { option ->
                            FilterChip(
                                selected = format == option,
                                onClick = { format = option },
                                label = { Text(option.extension.uppercase()) },
                            )
                        }
                    }
                    Text(
                        when (format) {
                            BookExportFormat.PDF ->
                                "Fixed pages, with the contents rebuilt against the new page numbers, " +
                                    "working bookmarks, and every figure kept."
                            BookExportFormat.EPUB ->
                                "Reflows to any reader and follows its font size and night mode."
                            BookExportFormat.MARKDOWN -> "Plain Markdown, for notes apps and Obsidian."
                            BookExportFormat.TEXT -> "Just the words."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (format == BookExportFormat.PDF) {
                        Text("Page size", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("6x9" to "6×9", "A5" to "A5", "A4" to "A4").forEach { (value, label) ->
                                FilterChip(
                                    selected = pageSize == value,
                                    onClick = { pageSize = value },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Include images", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = includeImages, onCheckedChange = { includeImages = it })
                    }

                    Button(
                        onClick = {
                            viewModel.export(bookId, format, state.job?.pass ?: 1, pageSize, includeImages)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create the file") }
                }
            }
        }

        export?.let { result ->
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            buildString {
                                append(result.file.name)
                                if (result.pageCount > 0) append(" · ${result.pageCount} pages")
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    context.startActivity(
                                        BookSharing.shareIntent(
                                            context,
                                            result.file,
                                            result.format,
                                            state.book?.title.orEmpty(),
                                        ),
                                    )
                                },
                            ) { Text("Share") }
                            OutlinedButton(onClick = { saveLauncher.launch(result.file.name) }) {
                                Text("Save to Files")
                            }
                        }
                    }
                }
            }
        }
    }
}
