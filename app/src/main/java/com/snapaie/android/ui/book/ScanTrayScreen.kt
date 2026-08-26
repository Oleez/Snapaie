package com.snapaie.android.ui.book

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.ScreenHeader
import com.snapaie.android.data.model.BookSourceKind
import com.snapaie.android.data.scan.DocumentScanner
import com.snapaie.android.domain.scan.ScanFilter
import com.snapaie.android.ui.findActivity
import com.snapaie.android.ui.notifications.LocalSnapToast
import kotlinx.coroutines.launch

/**
 * Photograph a whole book, page after page, then turn the stack into something.
 *
 * The stack is the point. Scanning one page and getting one result back is what the app
 * already did; this keeps pages together in order so they can leave as a single PDF, or go
 * into the condense pipeline as one book.
 */
@Composable
fun ScanTrayScreen(
    viewModel: ScanTrayViewModel,
    bookViewModel: BookViewModel,
    onBack: () -> Unit,
    onCondense: () -> Unit,
    onFallbackCamera: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast = LocalSnapToast.current
    val scope = rememberCoroutineScope()
    val scanner = remember { DocumentScanner(context) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?.pages.orEmpty().map { it.imageUri }
        if (pages.isNotEmpty()) viewModel.addPages(pages)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> viewModel.addPages(uris) }

    LaunchedEffect(state.message) {
        state.message?.let {
            toast.show(it)
            viewModel.consumeMessage()
        }
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
                title = "Scan pages",
                subtitle = if (state.pages.isEmpty()) {
                    "Photograph a book page by page."
                } else {
                    "${state.pages.size} page(s), in order"
                },
                onBack = onBack,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity == null || !scanner.isAvailable()) {
                            // No Play Services: the plain camera screen still captures,
                            // just without edge detection.
                            onFallbackCamera()
                            return@Button
                        }
                        scope.launch {
                            runCatching { scanner.intentSender(activity) }
                                .onSuccess { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                                .onFailure { onFallbackCamera() }
                        }
                    },
                ) { Text("Add pages") }
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) { Text("From gallery") }
                if (state.pages.isNotEmpty()) {
                    TextButton(onClick = viewModel::clear) { Text("Clear") }
                }
            }
        }

        if (state.pages.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Look", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ScanFilter.entries.toList()) { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.applyFilter(filter) },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    Text(
                        state.filter.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.isBusy) {
            item { CircularProgressIndicator() }
        }

        itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
            TrayPageRow(
                index = index,
                page = page,
                canMoveUp = index > 0,
                canMoveDown = index < state.pages.lastIndex,
                onUp = { viewModel.move(index, index - 1) },
                onDown = { viewModel.move(index, index + 1) },
                onRemove = { viewModel.remove(page.id) },
            )
        }

        if (state.pages.isNotEmpty()) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Turn these into", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.compile(fitToPage = false) }) { Text("A PDF") }
                            OutlinedButton(onClick = { viewModel.compile(fitToPage = true) }) {
                                Text("A4 PDF")
                            }
                        }
                        state.compiledPdf?.let { file ->
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    // Straight into the condense pipeline. It reads the
                                    // pages back with OCR, exactly as a shared scan would.
                                    bookViewModel.importDocument(
                                        Uri.fromFile(file),
                                        BookSourceKind.SCAN,
                                        file.name,
                                    )
                                    onCondense()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Condense this book") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayPageRow(
    index: Int,
    page: TrayPage,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    LiquidGlassSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrayThumbnail(page.displayPath)
            Column(Modifier.weight(1f)) {
                Text("Page ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(
                    page.filter.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove page")
            }
        }
    }
}

/**
 * Thumbnails are decoded down rather than at full size: a tray of sixty 12-megapixel
 * captures would exhaust the heap long before the list finished scrolling.
 */
@Composable
private fun TrayThumbnail(path: String) {
    val bitmap = remember(path) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 160)
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
    if (bitmap == null) {
        Column(Modifier.size(width = 48.dp, height = 64.dp)) {}
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(48.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(6.dp)),
    )
}
