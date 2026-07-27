package com.snapaie.android.domain.chat

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.local.ChatDao
import com.snapaie.android.data.local.ChatMessageEntity
import com.snapaie.android.data.local.ChatSessionEntity
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.data.preferences.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ChatEngine(
    private val sessionManager: ModelSessionManager,
    private val chatDao: ChatDao,
) {

    suspend fun createSession(
        title: String,
        persona: Persona,
        scanId: Long? = null,
        appearance: String = "Classic",
    ): Long {
        val now = System.currentTimeMillis()
        return chatDao.insertSession(
            ChatSessionEntity(
                scanId = scanId,
                title = title.take(80).ifBlank { "New chat" },
                persona = persona.id,
                appearance = appearance,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }

    /**
     * Sends [message] in [sessionId]: persists the user turn, streams the model reply
     * (emitting partial text), then persists the AI turn.
     */
    fun send(
        sessionId: Long,
        message: String,
        settings: UserSettings,
        tier: ModelTier,
        isPro: Boolean,
        originalText: String,
        originalExplanation: String,
    ): Flow<String> = flow {
        val session = chatDao.getSession(sessionId) ?: error("Chat session missing")
        val now = System.currentTimeMillis()
        chatDao.insertMessage(
            ChatMessageEntity(sessionId = sessionId, role = "user", content = message, createdAtMillis = now),
        )

        val history = chatDao.getMessages(sessionId)
            .dropLast(1)
            .map { ChatTurn(role = it.role, content = it.content) }
        val available = Persona.entries.filter { isPro || it.freeTier }
        val persona = Persona.fromId(session.persona).let { if (it in available) it else Persona.Auto }

        val prompt = ChatPromptBuilder.build(
            ChatPromptInput(
                message = message,
                originalText = originalText,
                originalExplanation = originalExplanation,
                recentTurns = history,
                aiName = settings.aeName.ifBlank { "AE" },
                userName = settings.userName.ifBlank { "You" },
                userGender = settings.userGender,
                persona = persona,
                availablePersonas = available,
                languageCode = settings.outputLanguage,
                customInstructions = if (isPro) settings.customInstructions else "",
            ),
        )

        val reply = StringBuilder()
        sessionManager.stream(prompt, tier).collect { token ->
            reply.append(token)
            emit(reply.toString())
        }

        val cleaned = reply.toString().trim()
        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "ai",
                content = cleaned.ifBlank { "…the model returned nothing. Try again." },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        chatDao.updateSession(session.copy(updatedAtMillis = System.currentTimeMillis()))
    }
}

/** Parses `[[WORK: title]] … [[/WORK]]` deliverable boxes out of an AI reply for card rendering. */
object WorkBoxParser {
    private val pattern = Regex("\\[\\[WORK:\\s*(.*?)]]\\s*([\\s\\S]*?)\\[\\[/WORK]]")

    data class Segment(val isWork: Boolean, val title: String, val text: String)

    fun parse(content: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var index = 0
        for (match in pattern.findAll(content)) {
            val before = content.substring(index, match.range.first).trim()
            if (before.isNotEmpty()) segments += Segment(false, "", before)
            segments += Segment(true, match.groupValues[1].trim(), match.groupValues[2].trim())
            index = match.range.last + 1
        }
        val tail = content.substring(index).trim()
        if (tail.isNotEmpty()) segments += Segment(false, "", tail)
        return segments.ifEmpty { listOf(Segment(false, "", content)) }
    }
}
