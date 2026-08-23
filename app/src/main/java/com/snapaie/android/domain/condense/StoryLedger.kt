package com.snapaie.android.domain.condense

import com.snapaie.android.domain.scan.JsonRepair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LedgerCharacter(
    val name: String,
    val note: String = "",
)

/**
 * Running continuity state, carried from one beat to the next.
 *
 * This is what stops a sequential condensation from drifting. The model only ever sees a
 * ~900-word window, so without a ledger it re-introduces characters it has already met,
 * renames them, forgets who is dead, and loses the thread of anything set up three
 * chapters ago. The ledger is small enough to prepend to every prompt and specific enough
 * to keep names spelled the same way from page 1 to page 500.
 */
@Serializable
data class StoryLedger(
    val characters: List<LedgerCharacter> = emptyList(),
    val places: List<String> = emptyList(),
    val openThreads: List<String> = emptyList(),
    /** One line on where the previous beat left off, so the next one continues mid-flow. */
    val lastBeat: String = "",
    val timeline: String = "",
    val pov: String = "",
) {
    val isEmpty: Boolean
        get() = characters.isEmpty() && places.isEmpty() && openThreads.isEmpty() &&
            lastBeat.isBlank() && timeline.isBlank() && pov.isBlank()

    /**
     * The ledger as it appears in a prompt. Deliberately terse — every token here is one
     * the model cannot spend on the passage it is actually condensing.
     */
    fun render(): String = buildString {
        if (pov.isNotBlank()) appendLine("VOICE: $pov")
        if (timeline.isNotBlank()) appendLine("WHEN: $timeline")
        if (characters.isNotEmpty()) {
            appendLine("WHO: " + characters.joinToString("; ") { entry ->
                if (entry.note.isBlank()) entry.name else "${entry.name} (${entry.note})"
            })
        }
        if (places.isNotEmpty()) appendLine("WHERE: " + places.joinToString("; "))
        if (openThreads.isNotEmpty()) {
            appendLine("UNRESOLVED:")
            openThreads.forEach { appendLine("- $it") }
        }
        if (lastBeat.isNotBlank()) appendLine("PREVIOUSLY: $lastBeat")
    }.trim()

    /**
     * Folds a patch in, newest first, then trims back to the caps.
     *
     * Eviction is by least-recently-mentioned rather than by age of first appearance: a
     * character who has not been on the page for two hundred pages is exactly the one the
     * model no longer needs reminding about, and keeping them would crowd out the people
     * in the current scene.
     */
    fun merge(patch: StoryLedger): StoryLedger = StoryLedger(
        characters = mergeCharacters(patch.characters, characters).take(MAX_CHARACTERS),
        places = (patch.places + places).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_PLACES),
        openThreads = (patch.openThreads + openThreads).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_THREADS),
        lastBeat = patch.lastBeat.ifBlank { lastBeat }.take(MAX_FIELD_CHARS),
        timeline = patch.timeline.ifBlank { timeline }.take(MAX_FIELD_CHARS),
        // Voice is established once, by the source, and must not drift mid-book.
        pov = pov.ifBlank { patch.pov }.take(MAX_FIELD_CHARS),
    )

    private fun mergeCharacters(
        incoming: List<LedgerCharacter>,
        existing: List<LedgerCharacter>,
    ): List<LedgerCharacter> {
        val seen = LinkedHashMap<String, LedgerCharacter>()
        (incoming + existing).forEach { entry ->
            val name = entry.name.trim()
            if (name.isBlank()) return@forEach
            val key = name.lowercase()
            val previous = seen[key]
            seen[key] = when {
                previous == null -> LedgerCharacter(name, entry.note.trim().take(MAX_FIELD_CHARS))
                // Keep the newer note but never lose one to a blank update.
                previous.note.isBlank() -> previous.copy(note = entry.note.trim().take(MAX_FIELD_CHARS))
                else -> previous
            }
        }
        return seen.values.toList()
    }

    companion object {
        const val MAX_CHARACTERS = 12
        const val MAX_PLACES = 8
        const val MAX_THREADS = 8
        const val MAX_FIELD_CHARS = 160

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val EMPTY = StoryLedger()

        /**
         * Reads a ledger out of whatever the model produced, reusing the same repair
         * ladder the scan pipeline uses. Returns null rather than throwing: a malformed
         * ledger must never fail a beat, because the prose is the part that matters and
         * the previous ledger is a perfectly good fallback.
         */
        fun parse(raw: String): StoryLedger? {
            if (raw.isBlank()) return null
            return JsonRepair.candidates(raw).firstNotNullOfOrNull { candidate ->
                runCatching { json.decodeFromString<StoryLedger>(candidate) }
                    .getOrNull()
                    ?.takeIf { !it.isEmpty }
            }
        }

        fun encode(ledger: StoryLedger): String = runCatching { json.encodeToString(ledger) }.getOrDefault("")

        fun decode(stored: String): StoryLedger =
            runCatching { json.decodeFromString<StoryLedger>(stored) }.getOrDefault(EMPTY)
    }
}
