package com.snapaie.android.domain.scan

import com.snapaie.android.data.ai.ModelSessionManager

/**
 * How much source text can be sent without overflowing the model's context window.
 *
 * The app used to answer this with a magic number. [ModelSessionManager.MAX_CONTEXT_TOKENS]
 * was 2,048 while the pipeline sent 9,000 characters of source, and nothing connected the
 * two, so nobody noticed that a full-size passage needed about half again as much room as
 * the engine had been built with.
 *
 * What made it costly is that overflow is silent. The engine fails or truncates, the reply
 * is rejected as unusable, and the caller falls back to its local heuristic — so the app
 * kept producing output while the model was never actually the thing producing it. Deriving
 * the cap here means the window and the source size can no longer disagree.
 */
object PromptBudget {

    /**
     * Characters per token for English prose.
     *
     * Deliberately pessimistic. The usual rule of thumb is about four, but names, rare
     * words and punctuation all tokenise worse than average, and the cost of guessing low
     * is a wasted round trip while the cost of guessing high is the silent failure above.
     */
    const val CHARS_PER_TOKEN = 3.6

    /** Headroom for the chat template, role markers and the model's own preamble. */
    const val OVERHEAD_TOKENS = 128

    fun estimateTokens(text: String): Int = Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    /**
     * The largest source excerpt that still leaves room for [templateText] and a reply of
     * [outputTokens]. Never negative: a template that alone fills the window yields zero,
     * and the caller falls back rather than sending a prompt that cannot fit.
     */
    fun maxSourceChars(
        templateText: String,
        outputTokens: Int = ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS,
        contextTokens: Int = ModelSessionManager.MAX_CONTEXT_TOKENS,
    ): Int {
        val spare = contextTokens - outputTokens - OVERHEAD_TOKENS - estimateTokens(templateText)
        return (spare * CHARS_PER_TOKEN).toInt().coerceAtLeast(0)
    }

    /** True when a fully-built prompt plus its reply fit the window. */
    fun fits(
        prompt: String,
        outputTokens: Int = ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS,
        contextTokens: Int = ModelSessionManager.MAX_CONTEXT_TOKENS,
    ): Boolean = estimateTokens(prompt) + outputTokens + OVERHEAD_TOKENS <= contextTokens
}
