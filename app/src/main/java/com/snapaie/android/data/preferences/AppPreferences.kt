package com.snapaie.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore by preferencesDataStore(name = "snapaie_prefs")

/** App-wide user settings surfaced in Settings / AE Tweaks. */
data class UserSettings(
    val explainStyle: String = "Auto",
    val outputLanguage: String = "en",
    val themeMode: String = "SnapDark", // SnapDark | Light | Aurora
    val textScale: Float = 1.0f,
    val bubblesMode: String = "on", // on | slower | faster | off
    val ttsEnabled: Boolean = true,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val userName: String = "",
    val aeName: String = "AE",
    val userGender: String = "neutral",
    val customInstructions: String = "",
    val chatPersona: String = "auto",
    val chatAppearance: String = "Classic",
    val gemmaLicenseAccepted: Boolean = false,
)

/** Persisted Library filter state (see LibraryScreen). */
data class LibraryFilters(
    val range: String = "All",
    val sort: String = "Newest",
)

/** Forge Recall gamification state (ported keys from the extension's lockIn* storage). */
data class RecallPrefs(
    val xpTotal: Int = 0,
    val streakDays: Int = 0,
    val lastPlayDay: String = "",
    val streakFreezes: Int = 0,
    val streakDaysForFreezeGrant: Int = 0,
    val questDate: String = "",
    val questReviewsDone: Int = 0,
    val questBonusAwarded: Boolean = false,
    val sessionCount: Int = 0,
    val survivalBestSec: Int = 0,
)

class AppPreferencesRepository(private val context: Context) {

    private val onboardingDone = booleanPreferencesKey("onboarding_done")
    private val cachedIsPro = booleanPreferencesKey("cached_is_pro")

    private val explainStyle = stringPreferencesKey("explain_style")
    private val outputLanguage = stringPreferencesKey("output_language")
    private val themeMode = stringPreferencesKey("theme_mode")
    private val textScale = floatPreferencesKey("text_scale")
    private val bubblesMode = stringPreferencesKey("bubbles_mode")
    private val ttsEnabled = booleanPreferencesKey("tts_enabled")
    private val ttsRate = floatPreferencesKey("tts_rate")
    private val ttsPitch = floatPreferencesKey("tts_pitch")
    private val userName = stringPreferencesKey("user_name")
    private val aeName = stringPreferencesKey("ae_name")
    private val userGender = stringPreferencesKey("user_gender")
    private val customInstructions = stringPreferencesKey("custom_instructions")
    private val chatPersona = stringPreferencesKey("chat_persona")
    private val chatAppearance = stringPreferencesKey("chat_appearance")
    private val gemmaLicenseAccepted = booleanPreferencesKey("gemma_license_accepted")

    private val notificationsJsonKey = stringPreferencesKey("notifications_json")
    private val libraryRange = stringPreferencesKey("library_range")
    private val librarySort = stringPreferencesKey("library_sort")

    private val recallXpTotal = intPreferencesKey("recall_xp_total")
    private val recallStreakDays = intPreferencesKey("recall_streak_days")
    private val recallLastPlayDay = stringPreferencesKey("recall_last_play_day")
    private val recallStreakFreezes = intPreferencesKey("recall_streak_freezes")
    private val recallFreezeGrantDays = intPreferencesKey("recall_freeze_grant_days")
    private val recallQuestDate = stringPreferencesKey("recall_quest_date")
    private val recallQuestReviews = intPreferencesKey("recall_quest_reviews")
    private val recallQuestBonus = booleanPreferencesKey("recall_quest_bonus")
    private val recallSessionCount = intPreferencesKey("recall_session_count")
    private val recallSurvivalBest = intPreferencesKey("recall_survival_best")

    val onboardingCompleted: Flow<Boolean> = context.preferencesDataStore.data.map {
        it[onboardingDone] == true
    }

    val storedProFallback: Flow<Boolean> = context.preferencesDataStore.data.map {
        it[cachedIsPro] == true
    }

    /** Serialized in-app notification centre list (see NotificationCenter). */
    val notificationsJson: Flow<String> = context.preferencesDataStore.data.map {
        it[notificationsJsonKey].orEmpty()
    }

    /** Library filter state, so the chosen range/sort survives an app restart. */
    val libraryFilters: Flow<LibraryFilters> = context.preferencesDataStore.data.map { p ->
        LibraryFilters(
            range = p[libraryRange] ?: "All",
            sort = p[librarySort] ?: "Newest",
        )
    }

    val userSettings: Flow<UserSettings> = context.preferencesDataStore.data.map { p ->
        UserSettings(
            explainStyle = p[explainStyle] ?: "Auto",
            outputLanguage = p[outputLanguage] ?: "en",
            themeMode = p[themeMode] ?: "SnapDark",
            textScale = p[textScale] ?: 1.0f,
            bubblesMode = p[bubblesMode] ?: "on",
            ttsEnabled = p[ttsEnabled] != false,
            ttsRate = p[ttsRate] ?: 1.0f,
            ttsPitch = p[ttsPitch] ?: 1.0f,
            userName = p[userName].orEmpty(),
            aeName = p[aeName] ?: "AE",
            userGender = p[userGender] ?: "neutral",
            customInstructions = p[customInstructions].orEmpty(),
            chatPersona = p[chatPersona] ?: "auto",
            chatAppearance = p[chatAppearance] ?: "Classic",
            gemmaLicenseAccepted = p[gemmaLicenseAccepted] == true,
        )
    }

    val recallPrefs: Flow<RecallPrefs> = context.preferencesDataStore.data.map { p ->
        RecallPrefs(
            xpTotal = p[recallXpTotal] ?: 0,
            streakDays = p[recallStreakDays] ?: 0,
            lastPlayDay = p[recallLastPlayDay].orEmpty(),
            streakFreezes = p[recallStreakFreezes] ?: 0,
            streakDaysForFreezeGrant = p[recallFreezeGrantDays] ?: 0,
            questDate = p[recallQuestDate].orEmpty(),
            questReviewsDone = p[recallQuestReviews] ?: 0,
            questBonusAwarded = p[recallQuestBonus] == true,
            sessionCount = p[recallSessionCount] ?: 0,
            survivalBestSec = p[recallSurvivalBest] ?: 0,
        )
    }

    suspend fun setOnboardingCompleted() = edit { it[onboardingDone] = true }
    suspend fun setCachedIsPro(isPro: Boolean) = edit { it[cachedIsPro] = isPro }

    suspend fun setExplainStyle(value: String) = edit { it[explainStyle] = value }
    suspend fun setOutputLanguage(value: String) = edit { it[outputLanguage] = value }
    suspend fun setThemeMode(value: String) = edit { it[themeMode] = value }
    suspend fun setTextScale(value: Float) = edit { it[textScale] = value }
    suspend fun setBubblesMode(value: String) = edit { it[bubblesMode] = value }
    suspend fun setTtsEnabled(value: Boolean) = edit { it[ttsEnabled] = value }
    suspend fun setTtsRate(value: Float) = edit { it[ttsRate] = value }
    suspend fun setTtsPitch(value: Float) = edit { it[ttsPitch] = value }
    suspend fun setUserName(value: String) = edit { it[userName] = value }
    suspend fun setAeName(value: String) = edit { it[aeName] = value }
    suspend fun setUserGender(value: String) = edit { it[userGender] = value }
    suspend fun setCustomInstructions(value: String) = edit { it[customInstructions] = value }
    suspend fun setChatPersona(value: String) = edit { it[chatPersona] = value }
    suspend fun setChatAppearance(value: String) = edit { it[chatAppearance] = value }
    suspend fun setGemmaLicenseAccepted() = edit { it[gemmaLicenseAccepted] = true }
    suspend fun setNotificationsJson(value: String) = edit { it[notificationsJsonKey] = value }
    suspend fun setLibraryRange(value: String) = edit { it[libraryRange] = value }
    suspend fun setLibrarySort(value: String) = edit { it[librarySort] = value }

    suspend fun updateRecall(transform: (RecallPrefs) -> RecallPrefs) {
        context.preferencesDataStore.edit { p ->
            val current = RecallPrefs(
                xpTotal = p[recallXpTotal] ?: 0,
                streakDays = p[recallStreakDays] ?: 0,
                lastPlayDay = p[recallLastPlayDay].orEmpty(),
                streakFreezes = p[recallStreakFreezes] ?: 0,
                streakDaysForFreezeGrant = p[recallFreezeGrantDays] ?: 0,
                questDate = p[recallQuestDate].orEmpty(),
                questReviewsDone = p[recallQuestReviews] ?: 0,
                questBonusAwarded = p[recallQuestBonus] == true,
                sessionCount = p[recallSessionCount] ?: 0,
                survivalBestSec = p[recallSurvivalBest] ?: 0,
            )
            val next = transform(current)
            p[recallXpTotal] = next.xpTotal
            p[recallStreakDays] = next.streakDays
            p[recallLastPlayDay] = next.lastPlayDay
            p[recallStreakFreezes] = next.streakFreezes
            p[recallFreezeGrantDays] = next.streakDaysForFreezeGrant
            p[recallQuestDate] = next.questDate
            p[recallQuestReviews] = next.questReviewsDone
            p[recallQuestBonus] = next.questBonusAwarded
            p[recallSessionCount] = next.sessionCount
            p[recallSurvivalBest] = next.survivalBestSec
        }
    }

    /** Factory reset: clears every preference (DB and weights are cleared by callers). */
    suspend fun clearAll() {
        context.preferencesDataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit): Preferences =
        context.preferencesDataStore.edit { block(it) }
}
