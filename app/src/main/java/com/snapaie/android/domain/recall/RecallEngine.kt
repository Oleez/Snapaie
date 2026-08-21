package com.snapaie.android.domain.recall

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.local.PracticeTopicEntity
import com.snapaie.android.data.local.RecallDao
import com.snapaie.android.data.preferences.AppPreferencesRepository
import com.snapaie.android.data.preferences.RecallPrefs
import com.snapaie.android.domain.scan.JsonRepair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class RapidCard(
    val statement: String,
    val isTrue: Boolean = true,
    val explanation: String = "",
)

@Serializable
data class Flashcard(val front: String, val back: String)

@Serializable
data class FeynmanScore(
    val score: Int = 0,
    val missingPoints: List<String> = emptyList(),
    val incorrectPoints: List<String> = emptyList(),
)

class RecallEngine(
    private val sessionManager: ModelSessionManager,
    private val recallDao: RecallDao,
    private val prefs: AppPreferencesRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // region Topic management

    /**
     * Saves a topic to the deck. Returns null when the row already exists or the
     * free-tier cap is reached (Pro unlocks unlimited topics).
     */
    suspend fun saveTopic(
        title: String,
        summary: String,
        content: String,
        sourceType: String,
        sourceScanId: Long? = null,
        isPro: Boolean = true,
    ): Long? {
        if (!isPro && recallDao.countTopics() >= FREE_TOPIC_LIMIT) return null
        val dedupe = "${sourceType}:${title.trim().lowercase().take(120)}"
        val now = System.currentTimeMillis()
        return runCatching {
            recallDao.insert(
                PracticeTopicEntity(
                    title = title.take(120).ifBlank { "Untitled topic" },
                    summary = summary.take(300),
                    content = content.take(8000),
                    sourceType = sourceType,
                    sourceScanId = sourceScanId,
                    nextDueAtMillis = SpacedRepetitionScheduler.nextDueAt(0, 50, now),
                    dedupeKey = dedupe,
                    createdAtMillis = now,
                ),
            )
        }.getOrNull() // unique dedupeKey violation -> already saved
    }

    /** Applies a finished review session to the topic: strength, schedule, hot streak. */
    suspend fun recordReview(topicId: Long, correct: Int, total: Int) {
        val topic = recallDao.getTopic(topicId) ?: return
        val strength = (topic.strengthLevel + SpacedRepetitionScheduler.strengthDelta(correct, total))
            .coerceIn(0, 100)
        val reviews = topic.reviewCount + 1
        recallDao.update(
            topic.copy(
                strengthLevel = strength,
                reviewCount = reviews,
                lastReviewedAtMillis = System.currentTimeMillis(),
                nextDueAtMillis = SpacedRepetitionScheduler.nextDueAt(reviews, strength),
                hotStreak = correct == total && total >= 3,
            ),
        )
    }

    // endregion

    // region Gamification state

    /** Registers a played session: XP gain, daily streak/freeze logic, quest progress, loot. */
    suspend fun registerSession(xpGain: Int, reviewedDueTopic: Boolean) {
        val today = LocalDate.now().toString()
        prefs.updateRecall { current ->
            var next = current.copy(xpTotal = current.xpTotal + xpGain.coerceAtLeast(0))
            next = applyStreak(next, today)
            if (next.questDate != today) {
                next = next.copy(questDate = today, questReviewsDone = 0, questBonusAwarded = false)
            }
            if (reviewedDueTopic) {
                next = next.copy(questReviewsDone = next.questReviewsDone + 1)
            }
            if (!next.questBonusAwarded && next.questReviewsDone >= XpLedger.DAILY_QUEST_TARGET) {
                next = next.copy(
                    questBonusAwarded = true,
                    xpTotal = next.xpTotal + XpLedger.DAILY_QUEST_BONUS_XP,
                )
            }
            next.copy(sessionCount = next.sessionCount + 1)
        }
    }

    suspend fun recordSurvivalBest(elapsedSec: Int) {
        prefs.updateRecall { current ->
            if (elapsedSec > current.survivalBestSec) current.copy(survivalBestSec = elapsedSec) else current
        }
    }

    private fun applyStreak(state: RecallPrefs, today: String): RecallPrefs {
        if (state.lastPlayDay == today) return state
        val yesterday = LocalDate.now().minusDays(1).toString()
        var streak = state.streakDays
        var freezes = state.streakFreezes
        streak = when {
            state.lastPlayDay.isEmpty() -> 1
            state.lastPlayDay == yesterday -> streak + 1
            // Missed exactly one day and a freeze is banked: spend it, keep the streak.
            state.lastPlayDay == LocalDate.now().minusDays(2).toString() && freezes > 0 -> {
                freezes -= 1
                streak + 1
            }
            else -> 1
        }
        var grantDays = state.streakDaysForFreezeGrant + 1
        if (grantDays >= XpLedger.STREAK_DAYS_PER_FREEZE) {
            grantDays = 0
            freezes = (freezes + 1).coerceAtMost(XpLedger.MAX_STREAK_FREEZES)
        }
        return state.copy(
            lastPlayDay = today,
            streakDays = streak,
            streakFreezes = freezes,
            streakDaysForFreezeGrant = grantDays,
        )
    }

    // endregion

    // region Question generation (prompts ported verbatim from server.js:2596-2672)

    suspend fun generateRapidCards(
        topic: PracticeTopicEntity,
        count: Int = 10,
        cloze: Boolean = false,
        priorTopic: PracticeTopicEntity? = null,
    ): List<RapidCard> {
        val formatPrompt = if (cloze) {
            """
Return ONLY valid JSON in this exact shape:
{
  "questions": [
    { "statement": "Sentence with ____ blank for a missing key term", "isTrue": true, "explanation": "the missing term" }
  ]
}

Generate $count cloze-style True/False statements: the statement should contain a blank (____) representing a missing concept from the source; isTrue is whether a follow-up claim about the blank is true (you may encode as: statement is the full sentence with blank, and explanation names the correct term). Base everything strictly on the source. Mix difficulty. Output ONLY valid JSON.
            """.trimIndent()
        } else {
            buildString {
                appendLine("Return ONLY valid JSON in this exact shape:")
                appendLine("{")
                appendLine("  \"questions\": [")
                appendLine("    { \"statement\": \"short declarative sentence\", \"isTrue\": true, \"explanation\": \"under 15 words\" }")
                appendLine("  ]")
                appendLine("}")
                appendLine()
                append("Generate $count short True/False statements based strictly on the PRIMARY source content below")
                appendLine(if (priorTopic != null) ", and on the PRIOR topic block when testing connections, contrasts, or recall across both topics." else "")
                appendLine()
                appendLine("Rules:")
                appendLine("- Mix correct and incorrect statements")
                appendLine("- Incorrect ones should be subtly wrong (not obvious)")
                appendLine("- Each explanation must reference the relevant source (primary or prior when both exist).")
                appendLine("- Keep explanations under 15 words")
                append("- Output ONLY valid JSON")
                if (priorTopic != null) {
                    appendLine()
                    append("- About half of the statements should test the PRIMARY topic only; about half should test the PRIOR topic, or require distinguishing / relating the two (no facts from outside the two blocks).")
                }
            }
        }
        val prompt = buildString {
            appendLine("You create practice material for a learning product.")
            appendLine("Primary topic title: ${topic.title}")
            appendLine("Difficulty: medium")
            appendLine(formatPrompt)
            appendLine(
                if (priorTopic != null) {
                    "Two source blocks are provided: PRIMARY (newest) and PRIOR (saved earlier). Use both exactly as written—no outside knowledge."
                } else {
                    "Base everything only on the source material below."
                },
            )
            appendLine("Do not add markdown fences, commentary, or extra prose.")
            appendLine()
            appendLine("PRIMARY source material:")
            appendLine(topic.content.take(4000))
            if (priorTopic != null) {
                appendLine()
                appendLine("PRIOR topic title: ${priorTopic.title}")
                appendLine("PRIOR source material:")
                append(priorTopic.content.take(3000))
            }
        }
        val raw = sessionManager.generate(prompt)
        for (candidate in JsonRepair.candidates(raw, arrayWrapKey = "questions")) {
            val parsed = runCatching { json.decodeFromString<QuestionsPayload>(candidate) }.getOrNull()
            if (parsed != null && parsed.questions.isNotEmpty()) {
                return parsed.questions.filter { it.statement.isNotBlank() }
            }
        }
        return emptyList()
    }

    suspend fun generateFlashcards(topic: PracticeTopicEntity, count: Int = 8): List<Flashcard> {
        val prompt = buildString {
            appendLine("You create practice material for a learning product.")
            appendLine("Primary topic title: ${topic.title}")
            appendLine("Return JSON only in this exact shape:")
            appendLine("{")
            appendLine("  \"flashcards\": [")
            appendLine("    { \"front\": \"Question or cue\", \"back\": \"Answer or explanation\" }")
            appendLine("  ]")
            appendLine("}")
            appendLine()
            appendLine("Create $count concise flashcards. Make each front short and each back clear enough for quick review.")
            appendLine("Base everything only on the source material below.")
            appendLine("Do not add markdown fences, commentary, or extra prose.")
            appendLine()
            appendLine("PRIMARY source material:")
            append(topic.content.take(4000))
        }
        val raw = sessionManager.generate(prompt)
        for (candidate in JsonRepair.candidates(raw, arrayWrapKey = "flashcards")) {
            val parsed = runCatching { json.decodeFromString<FlashcardsPayload>(candidate) }.getOrNull()
            if (parsed != null && parsed.flashcards.isNotEmpty()) return parsed.flashcards
        }
        return emptyList()
    }

    /** Feynman evaluation, prompt ported verbatim from server.js:2789-2806. */
    suspend fun evaluateFeynman(
        topic: PracticeTopicEntity,
        userAnswer: String,
    ): FeynmanScore {
        val prompt = buildString {
            appendLine("You are evaluating a user's explanation of a concept (Feynman-style).")
            appendLine("PRIMARY TOPIC the learner was asked to explain (use this to focus scoring): \"${topic.title}\"")
            appendLine("Compare the user's answer with the SOURCE CONTENT.")
            appendLine("The user was shown that topic/title in the UI; judge whether their explanation covers the same ideas as the source material.")
            appendLine("Scoring: weight factual alignment with the source, completeness, and clarity.")
            appendLine("Treat correct paraphrases, synonyms, and equivalent phrasing as largely correct — do not penalize different wording when the meaning matches.")
            appendLine("If the user is mostly right but imprecise, score in the high 70s–90s and use missingPoints for minor gaps; reserve low scores for clear contradictions or major omissions.")
            appendLine("incorrectPoints should list only substantive errors or misconceptions, not stylistic differences.")
            appendLine("Output ONLY valid JSON (no markdown):")
            appendLine("{")
            appendLine("  \"score\": <number 0-100>,")
            appendLine("  \"missingPoints\": [\"...\"],")
            appendLine("  \"incorrectPoints\": [\"...\"]")
            appendLine("}")
            appendLine("Rules: Be strict on factual errors, generous on phrasing. Keep arrays short. Base everything ONLY on SOURCE CONTENT.")
            appendLine()
            appendLine("SOURCE CONTENT:")
            appendLine(topic.content.take(6000))
            appendLine()
            appendLine("USER ANSWER:")
            append(userAnswer.take(4000))
        }
        val raw = sessionManager.generate(prompt)
        for (candidate in JsonRepair.candidates(raw)) {
            val parsed = runCatching { json.decodeFromString<FeynmanScore>(candidate) }.getOrNull()
            if (parsed != null) return parsed.copy(score = parsed.score.coerceIn(0, 100))
        }
        return FeynmanScore(score = 0, missingPoints = listOf("Could not evaluate — the model output was unreadable. Try again."))
    }

    // endregion

    @Serializable
    private data class QuestionsPayload(val questions: List<RapidCard> = emptyList())

    @Serializable
    private data class FlashcardsPayload(val flashcards: List<Flashcard> = emptyList())

    companion object {
        const val FREE_TOPIC_LIMIT = 5
    }
}
