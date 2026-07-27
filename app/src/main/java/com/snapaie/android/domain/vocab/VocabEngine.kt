package com.snapaie.android.domain.vocab

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.model.CefrVocab
import com.snapaie.android.data.model.CefrWord
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.domain.chat.Languages
import com.snapaie.android.domain.scan.JsonRepair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VocabEngine(private val sessionManager: ModelSessionManager) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** CEFR extraction ported verbatim from the extension (popup.js:29345). */
    suspend fun extract(text: String, languageCode: String, tier: ModelTier): CefrVocab? {
        val langName = Languages.nameFor(languageCode)
        val prompt = """
You are an expert in CEFR vocabulary levels. Assign words from the text to B2, C1, or C2.

CRITICAL – difficulty order (do not swap):
- **B2 = LOWEST difficulty** of the three: easier, more common words. Upper-intermediate.
- **C1 = MIDDLE**: harder than B2, easier than C2. Advanced.
- **C2 = HIGHEST difficulty**: hardest, rarest, most formal/literary. Proficiency.

So: words in B2 must be EASIER than words in C1; words in C1 must be EASIER than words in C2. Never put a C2-type (rare, formal, literary) word in B2. Never put a B2-type (common, everyday) word in C2.

**B2 (easiest of the three):** Common words an upper-intermediate learner knows or learns first: frequent academic/workplace vocabulary, everyday abstract terms, common idioms. If a word is rare, formal, or literary, it does NOT belong in B2.

**C1 (middle):** Less common than B2; precise or idiomatic; professional/academic; typical of educated use. Harder than B2 but not the rarest.

**C2 (hardest of the three):** Rare or low-frequency; highly formal, literary, or technical; subtle distinctions; words educated natives might look up. Only the most difficult words go here.

Rules:
- Each word in exactly ONE level. No duplication.
- Only words that appear in the text (or clear derivatives).
- When unsure between two levels, assign the EASIER level: prefer B2 over C1, prefer C1 over C2.
- If the text has no very hard words, leave C2 empty. If no advanced words, leave C1 and C2 empty. Do not force hard words into B2 or easy words into C2.
- For each word: word, partOfSpeech, definition in $langName, example sentence in $langName.

Return ONLY a JSON object with this exact structure (no markdown, no extra text):
{
  "B2": [
    {"word": "word1", "partOfSpeech": "noun", "definition": "definition in $langName", "example": "example sentence in $langName"}
  ],
  "C1": [
    {"word": "word2", "partOfSpeech": "adjective", "definition": "definition in $langName", "example": "example sentence in $langName"}
  ],
  "C2": [
    {"word": "word3", "partOfSpeech": "verb", "definition": "definition in $langName", "example": "example sentence in $langName"}
  ]
}

Text to analyze:
${text.take(2000)}
        """.trimIndent()

        val raw = sessionManager.generate(prompt, tier)
        for (candidate in JsonRepair.candidates(raw)) {
            val parsed = runCatching { json.decodeFromString<CefrPayload>(candidate) }.getOrNull()
            if (parsed != null && (parsed.B2.isNotEmpty() || parsed.C1.isNotEmpty() || parsed.C2.isNotEmpty())) {
                return CefrVocab(b2 = parsed.B2, c1 = parsed.C1, c2 = parsed.C2)
            }
        }
        return null
    }

    @Serializable
    private data class CefrPayload(
        val B2: List<CefrWord> = emptyList(),
        val C1: List<CefrWord> = emptyList(),
        val C2: List<CefrWord> = emptyList(),
    )
}
