package com.snapaie.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.data.local.ChatMessageEntity
import com.snapaie.android.domain.chat.Persona
import com.snapaie.android.domain.chat.WorkBoxParser
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.nav.Routes
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Chat appearance presets ported from the extension (Classic / Aurora / Noir / Slate). */
enum class ChatAppearance(val label: String, val freeTier: Boolean) {
    Classic("Classic", true),
    Aurora("Aurora glass", false),
    Noir("Noir glass", false),
    Slate("Slate mist", false),
    ;

    companion object {
        fun fromStored(value: String): ChatAppearance = entries.firstOrNull { it.name == value } ?: Classic
    }
}

@Composable
fun ChatScreen(viewModel: SnapAieViewModel, navController: NavHostController, sessionId: Long) {
    val container = viewModel.container
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val session by container.database.chatDao().observeSession(sessionId).collectAsState(initial = null)
    val messages by container.database.chatDao().observeMessages(sessionId).collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var streamingReply by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val appearance = ChatAppearance.fromStored(session?.appearance ?: settings.chatAppearance)
    val backgroundBrush = when (appearance) {
        ChatAppearance.Classic -> null
        ChatAppearance.Aurora -> Brush.linearGradient(listOf(Color(0x59FFE4F0), Color(0x61E0E7FF)))
        ChatAppearance.Noir -> Brush.linearGradient(listOf(Color(0xE61E1B4B), Color(0xF20F172A)))
        ChatAppearance.Slate -> Brush.linearGradient(listOf(Color(0x33475569), Color(0x47334155)))
    }

    LaunchedEffect(messages.size, streamingReply) {
        if (messages.isNotEmpty() || streamingReply != null) {
            listState.animateScrollToItem((messages.size + if (streamingReply != null) 1 else 0).coerceAtLeast(1) - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .let { if (backgroundBrush != null) it.background(backgroundBrush) else it }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    session?.title ?: "Chat with ${settings.aeName.ifBlank { "AE" }}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val persona = Persona.fromId(session?.persona ?: settings.chatPersona)
                // Zero content padding keeps the persona line optically aligned
                // with the title above it.
                TextButton(
                    onClick = { showPersonaPicker = !showPersonaPicker },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        "${persona.emoji} ${persona.label} · change",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (showPersonaPicker) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Persona.entries.forEach { persona ->
                    val unlocked = isPro || persona.freeTier
                    FilterChip(
                        selected = session?.persona == persona.id,
                        onClick = {
                            if (unlocked) {
                                scope.launch {
                                    session?.let {
                                        container.database.chatDao().updateSession(it.copy(persona = persona.id))
                                    }
                                    container.appPreferencesRepository.setChatPersona(persona.id)
                                }
                                showPersonaPicker = false
                            } else {
                                navController.navigate(Routes.Upgrade)
                            }
                        },
                        label = { Text("${persona.emoji} ${persona.label}${if (!unlocked) " 🔒" else ""}") },
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages.size) { i ->
                ChatBubble(messages[i], appearance)
            }
            if (streamingReply != null) {
                item {
                    ChatBubble(
                        ChatMessageEntity(sessionId = sessionId, role = "ai", content = streamingReply ?: "…", createdAtMillis = 0L),
                        appearance,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask ${settings.aeName.ifBlank { "AE" }} anything…") },
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    val message = input.trim()
                    if (message.isBlank() || sending) return@IconButton
                    input = ""
                    sending = true
                    streamingReply = ""
                    scope.launch {
                        val scan = session?.scanId?.let { id ->
                            container.database.knowledgeScanDao().observeScan(id).firstOrNull()
                        }
                        runCatching {
                            container.chatEngine.send(
                                sessionId = sessionId,
                                message = message,
                                settings = settings,
                                isPro = isPro,
                                originalText = scan?.sourceText.orEmpty().ifBlank { scan?.sourcePreview.orEmpty() },
                                originalExplanation = scan?.resultJson.orEmpty().take(4000),
                            ).collect { partial -> streamingReply = partial }
                        }
                        streamingReply = null
                        sending = false
                    }
                },
                enabled = !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = DesignTokens.AccentBlue)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageEntity, appearance: ChatAppearance) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    val darkAppearance = appearance == ChatAppearance.Noir
    val aiBubbleColor = when {
        darkAppearance -> Color(0x1AFFFFFF)
        appearance == ChatAppearance.Aurora -> Color(0xE0FFFFFF)
        else -> if (MaterialTheme.colorScheme.background.red < 0.5f) DesignTokens.ChatAiBubbleDark else DesignTokens.ChatAiBubbleLight
    }
    val aiTextColor = when {
        darkAppearance -> Color(0xFFF8FAFC)
        appearance == ChatAppearance.Aurora -> Color(0xFF0F172A)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val shape = if (isUser) {
            RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
        } else {
            RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
        }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (isUser) DesignTokens.ChatUserBubble else aiBubbleColor, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isUser) {
                Text(message.content, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            } else {
                WorkBoxParser.parse(message.content).forEach { segment ->
                    if (segment.isWork) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DesignTokens.HeaderPurple, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    segment.title.ifBlank { "Draft" },
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                TextButton(onClick = { clipboard.setText(AnnotatedString(segment.text)) }) {
                                    Text("Copy", color = Color.White)
                                }
                            }
                            Text(segment.text, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(segment.text, color = aiTextColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
