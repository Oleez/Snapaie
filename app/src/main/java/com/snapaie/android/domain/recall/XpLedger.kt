package com.snapaie.android.domain.recall

import kotlin.random.Random

/**
 * XP / level / loot rules ported from the extension's Forge Recall
 * (popup.js lockIn* constants and session-end math).
 */
object XpLedger {

    const val XP_PER_LEVEL = 500
    const val INTERLEAVE_MIN_LEVEL = 5
    const val CLOZE_MIN_LEVEL = 3
    const val PERFECT_CARD_CHANCE = 0.20
    const val CLOZE_CHANCE = 0.15
    const val PERFECT_CARD_BONUS_XP = 18
    const val DAILY_QUEST_TARGET = 3
    const val DAILY_QUEST_BONUS_XP = 50
    const val MAX_STREAK_FREEZES = 2
    const val STREAK_DAYS_PER_FREEZE = 7

    val lootEmojis = listOf("💎", "⚡", "🔮", "🌟", "🎯", "🦁", "🐉", "👑")

    fun levelFor(xpTotal: Int): Int = (xpTotal / XP_PER_LEVEL) + 1

    fun levelProgress(xpTotal: Int): Float = (xpTotal % XP_PER_LEVEL).toFloat() / XP_PER_LEVEL

    /** Rapid Fire: `28 + score*6` when the 10-card run completes, else `score*3`. */
    fun rapidFireXp(score: Int, completed: Boolean): Int =
        if (completed) 28 + score * 6 else score * 3

    /** Survival: `elapsedSec*2 + streak*10`. */
    fun survivalXp(elapsedSec: Int, correctStreak: Int): Int =
        elapsedSec * 2 + correctStreak * 10

    /** Feynman: score maps to XP at half rate (0-100 -> 0-50). */
    fun feynmanXp(score: Int): Int = score.coerceIn(0, 100) / 2

    fun rollPerfectCard(random: Random = Random.Default): Boolean =
        random.nextDouble() < PERFECT_CARD_CHANCE

    fun rollCloze(level: Int, random: Random = Random.Default): Boolean =
        level >= CLOZE_MIN_LEVEL && random.nextDouble() < CLOZE_CHANCE

    fun rollLoot(random: Random = Random.Default): String =
        lootEmojis[random.nextInt(lootEmojis.size)]
}
