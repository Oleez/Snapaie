package com.snapaie.android.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.data.local.ChatSessionEntity
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.NoteEntity
import com.snapaie.android.data.preferences.LibraryFilters
import com.snapaie.android.domain.chat.Persona
import com.snapaie.android.domain.library.LibraryFilter
import com.snapaie.android.domain.library.LibraryHistory
import com.snapaie.android.domain.library.LibraryRange
import com.snapaie.android.domain.library.LibrarySort
import com.snapaie.android.domain.library.LibraryTab
import com.snapaie.android.domain.share.ChatTranscript
import com.snapaie.android.domain.share.ExportFormat
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.nav.Routes
import com.snapaie.android.ui.notifications.LocalSnapToast
import com.snapaie.android.ui.notifications.NotificationBell
import com.snapaie.android.ui.notifications.relativeTime
import kotlinx.coroutines.launch

/**
 * Library with search, time/sort filters, live result counts and bulk export.
 *
 * Ported from the AI Explainer extension's History panel: the subtab row was
 * already here, but the extension also had a search box, a date filter and an
 * "export session" action, and none of those existed on Android. Everything the
 * user is looking at is what gets exported, so a search for one book exports
 * only that book.
 */
@Composable
fun LibraryScreen(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
    unreadNotifications: Int = 0,
    onOpenNotifications: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalSnapToast.current
    val keyboard = LocalSoftwareKeyboardController.current
    val preferences = viewModel.container.appPreferencesRepository

    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val scans by viewModel.library.collectAsStateWithLifecycle()
    val sessions by viewModel.container.database.chatDao().observeSessions()
        .collectAsState(initial = emptyList())
    val notes by viewModel.container.database.noteDao().observeNotes()
        .collectAsState(initial = emptyList())
    val storedFilters by preferences.libraryFilters.collectAsState(initial = LibraryFilters())

    var tab by remember { mutableStateOf(LibraryTab.Explanations) }
    var query by remember { mutableStateOf("") }
    var exportMenuOpen by remember { mutableStateOf(false) }

    val range = LibraryRange.fromStored(storedFilters.range)
    val sort = LibrarySort.fromStored(storedFilters.sort)

    val visibleScans = remember(scans, query, range, sort) {
        LibraryFilter.scans(scans, query, range, sort)
    }
    val visibleSessions = remember(sessions, query, range, sort) {
        LibraryFilter.chats(sessions, query, range, sort)
    }
    val visibleNotes = remember(notes, query, range, sort) {
        LibraryFilter.notes(notes, query, range, sort)
    }

    val totalForTab = when (tab) {
        LibraryTab.Explanations -> scans.size
        LibraryTab.Chats -> sessions.size
        LibraryTab.Notes -> notes.size
    }
    val visibleForTab = when (tab) {
        LibraryTab.Explanations -> visibleScans.size
        LibraryTab.Chats -> visibleSessions.size
        LibraryTab.Notes -> visibleNotes.size
    }

    fun export(format: ExportFormat) {
        if (!isPro) {
            toast.show(
                message = "Bulk export is part of Pro (one-time purchase).",
                actionLabel = "See Pro",
                onAction = { navController.navigate(Routes.Upgrade) },
            )
            return
        }
        scope.launch {
            val exporter = viewModel.container.libraryExporter
            val chatDao = viewModel.container.database.chatDao()
            val (content, name) = when (tab) {
                LibraryTab.Explanations -> {
                    val body = when (format) {
                        ExportFormat.Markdown -> exporter.scansToMarkdown(visibleScans, includeBranding = !isPro)
                        ExportFormat.Json -> exporter.scansToJson(visibleScans)
                    }
                    body to "snapaie-scans"
                }
                LibraryTab.Chats -> {
                    val transcripts = visibleSessions.map { session ->
                        ChatTranscript(session, chatDao.getMessages(session.id))
                    }
                    val body = when (format) {
                        ExportFormat.Markdown -> exporter.chatsToMarkdown(transcripts)
                        ExportFormat.Json -> exporter.chatsToJson(transcripts)
                    }
                    body to "snapaie-chats"
                }
                LibraryTab.Notes -> {
                    val body = when (format) {
                        ExportFormat.Markdown -> exporter.notesToMarkdown(visibleNotes)
                        ExportFormat.Json -> exporter.notesToJson(visibleNotes)
                    }
                    body to "snapaie-notes"
                }
            }
            context.startActivity(exporter.shareFile(content, name, format))
            toast.show("Exported $visibleForTab ${tab.label.lowercase()} as ${format.extension.uppercase()}.")
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                NotificationBell(unreadCount = unreadNotifications, onClick = onOpenNotifications)
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(DesignTokens.RadiusLg),
                placeholder = { Text("Search titles, ideas, quotes…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                // Results are live, so the action just dismisses the keyboard
                // instead of leaving it covering half the list.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryTab.entries.forEach { entry ->
                    val count = when (entry) {
                        LibraryTab.Explanations -> scans.size
                        LibraryTab.Chats -> sessions.size
                        LibraryTab.Notes -> notes.size
                    }
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(if (count > 0) "${entry.label} $count" else entry.label) },
                    )
                }
            }
        }

        item {
            // Range and sort chips look identical, so each group carries its own
            // caption rather than running together in one anonymous strip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupLabel("Show")
                LibraryRange.entries.forEach { entry ->
                    FilterChip(
                        selected = range == entry,
                        onClick = { scope.launch { preferences.setLibraryRange(entry.name) } },
                        label = { Text(entry.label) },
                    )
                }
                Spacer(Modifier.width(2.dp))
                GroupLabel("Sort")
                LibrarySort.entries.forEach { entry ->
                    // "Best compression" only orders scans, so hide it elsewhere.
                    if (entry == LibrarySort.BestCompression && tab != LibraryTab.Explanations) {
                        return@forEach
                    }
                    FilterChip(
                        selected = sort == entry,
                        onClick = { scope.launch { preferences.setLibrarySort(entry.name) } },
                        label = { Text(entry.label) },
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (visibleForTab == totalForTab) {
                        "$totalForTab ${tab.label.lowercase()}"
                    } else {
                        "$visibleForTab of $totalForTab ${tab.label.lowercase()}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedButton(
                        onClick = { exportMenuOpen = true },
                        enabled = visibleForTab > 0,
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPro) "Export" else "Export (Pro)")
                    }
                    DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.label) },
                                onClick = {
                                    exportMenuOpen = false
                                    export(format)
                                },
                            )
                        }
                    }
                }
            }
        }

        when (tab) {
            LibraryTab.Explanations -> {
                if (visibleScans.isEmpty()) {
                    item { EmptyState(total = scans.size, query = query, kind = "scans", hint = "Snap a page from the Snap tab.") }
                }
                // Dated sections rather than one flat list: people come back to their
                // history asking "what did I read on Tuesday", not "what is newest".
                LibraryHistory.byDay(visibleScans).forEach { day ->
                    item(key = "day-${day.startOfDayMillis}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                day.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (day.scans.size == 1) "1 page" else "${day.scans.size} pages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(day.scans, key = { it.id }) { scan ->
                        ScanCard(scan = scan, onOpen = { navController.navigate(Routes.scanDetail(scan.id)) })
                    }
                }
            }

            LibraryTab.Chats -> {
                item {
                    Button(onClick = {
                        scope.launch {
                            val id = viewModel.container.chatEngine.createSession(
                                title = "New chat",
                                persona = Persona.fromId(viewModel.settings.value.chatPersona),
                            )
                            navController.navigate(Routes.chat(id))
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("New chat")
                    }
                }
                if (visibleSessions.isEmpty()) {
                    item { EmptyState(total = sessions.size, query = query, kind = "chats", hint = "Start one from any scan with “Ask AE”.") }
                }
                items(visibleSessions, key = { it.id }) { session ->
                    ChatCard(
                        session = session,
                        onOpen = { navController.navigate(Routes.chat(session.id)) },
                        onDelete = {
                            scope.launch {
                                viewModel.container.database.chatDao().deleteSession(session.id)
                                toast.show("Chat deleted.")
                            }
                        },
                    )
                }
            }

            LibraryTab.Notes -> {
                item { NoteComposer(viewModel) }
                if (visibleNotes.isEmpty()) {
                    item { EmptyState(total = notes.size, query = query, kind = "notes", hint = "Capture a thought above.") }
                }
                items(visibleNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onDelete = {
                            scope.launch {
                                val dao = viewModel.container.database.noteDao()
                                dao.deleteById(note.id)
                                toast.show(
                                    message = "Note deleted.",
                                    actionLabel = "Undo",
                                    // Re-inserted with its original id, so the row comes back unchanged.
                                    onAction = { scope.launch { dao.insert(note) } },
                                )
                            }
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ScanCard(scan: KnowledgeScan, onOpen: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    LiquidGlassSurface(
        modifier = Modifier
            .animateContentSize()
            .clickable(onClick = onOpen),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    scan.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    relativeTime(scan.createdAtMillis, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                scan.mode.label,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "${scan.result.compressionScore}% compressed · ${scan.result.estimatedTimeSavedMinutes}m saved",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                scan.result.coreIdea,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatCard(session: ChatSessionEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    LiquidGlassSurface(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    Persona.fromId(session.persona).label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onDelete: () -> Unit) {
    LiquidGlassSurface {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    relativeTime(note.createdAtMillis, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteComposer(viewModel: SnapAieViewModel) {
    val scope = rememberCoroutineScope()
    val toast = LocalSnapToast.current
    var draft by remember { mutableStateOf("") }

    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Note to self 💬", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Capture a thought…") },
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    scope.launch {
                        viewModel.container.database.noteDao().insert(
                            NoteEntity(text = text, createdAtMillis = System.currentTimeMillis()),
                        )
                        toast.show("Note saved.")
                    }
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save note") }
        }
    }
}

/** Small caption that names a chip group in the filter strip. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 2.dp),
    )
}

/** Distinguishes "nothing here yet" from "your filter hid everything". */
@Composable
private fun EmptyState(total: Int, query: String, kind: String, hint: String) {
    val headline: String
    val body: String
    when {
        total == 0 -> {
            headline = "No $kind yet"
            body = hint
        }
        query.isNotBlank() -> {
            headline = "Nothing matches “$query”"
            body = "Try a shorter search, or widen the time filter."
        }
        else -> {
            headline = "Nothing in this time range"
            body = "Switch to All time to see everything."
        }
    }
    LiquidGlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (total == 0) Icons.Filled.AutoStories else Icons.Filled.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(headline, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
