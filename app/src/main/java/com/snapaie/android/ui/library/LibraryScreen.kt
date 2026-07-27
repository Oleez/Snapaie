package com.snapaie.android.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.data.local.NoteEntity
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.nav.Routes
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Explanations", "Chats", "Notes").forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
                }
            }
        }
        when (tab) {
            0 -> explanationItems(viewModel, navController)
            1 -> chatItems(viewModel, navController)
            else -> noteItems(viewModel)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.explanationItems(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
) {
    item {
        val scans by viewModel.library.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scans.isEmpty()) {
                Text("No scans yet — snap a page from the Snap tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            scans.forEach { scan ->
                LiquidGlassSurface(
                    modifier = Modifier
                        .animateContentSize()
                        .clickable { navController.navigate(Routes.scanDetail(scan.id)) },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(scan.bookTitle, style = MaterialTheme.typography.titleMedium)
                        Text(scan.mode.label, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                        Text("${scan.result.compressionScore}% compressed · ${scan.result.estimatedTimeSavedMinutes}m saved", style = MaterialTheme.typography.bodySmall)
                        Text(scan.result.coreIdea, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.chatItems(
    viewModel: SnapAieViewModel,
    navController: NavHostController,
) {
    item {
        val sessions by viewModel.container.database.chatDao().observeSessions()
            .collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                scope.launch {
                    val id = viewModel.container.chatEngine.createSession(
                        title = "New chat",
                        persona = com.snapaie.android.domain.chat.Persona.fromId(viewModel.settings.value.chatPersona),
                    )
                    navController.navigate(Routes.chat(id))
                }
            }) { Text("＋ New chat") }
            if (sessions.isEmpty()) {
                Text("No chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sessions.forEach { session ->
                LiquidGlassSurface(
                    modifier = Modifier.clickable { navController.navigate(Routes.chat(session.id)) },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                com.snapaie.android.domain.chat.Persona.fromId(session.persona).label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch { viewModel.container.database.chatDao().deleteSession(session.id) }
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.noteItems(viewModel: SnapAieViewModel) {
    item {
        val notes by viewModel.container.database.noteDao().observeNotes()
            .collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var draft by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Save note") }
                }
            }
            notes.forEach { note ->
                LiquidGlassSurface {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch { viewModel.container.database.noteDao().deleteById(note.id) }
                        }) { Text("✕") }
                    }
                }
            }
        }
    }
}
