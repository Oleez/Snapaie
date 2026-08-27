package com.snapaie.android.domain.condense

import com.snapaie.android.data.cloud.CloudCondenseApi
import com.snapaie.android.data.cloud.CloudPassage
import com.snapaie.android.data.cloud.CloudResult

/**
 * Condenses a book in the cloud, falling back to the phone when it cannot.
 *
 * This is the answer to "why does a book take hours". It is not that the offline model is
 * badly used — it is that a five-hundred-page book is around two hundred thousand tokens
 * that something has to *read*, and on a handset that is hours no matter how the work is
 * arranged. Moving it to a machine that reads at a different order of magnitude is the only
 * change that turns hours into minutes.
 *
 * Passages are gathered rather than sent one at a time. The pipeline hands them over
 * singly because a phone can only hold one at a time, so this buffers until it has a
 * batch's worth and spends one request on the lot — 150 round trips become 15.
 *
 * Every failure falls through to [local]. Out of credit, no signal, backend down: the book
 * still finishes, on the phone, slower and rougher. A paid feature being unavailable should
 * cost quality, never the book.
 */
class CloudPassageCondenser(
    private val cloud: CloudCondenseApi,
    private val local: PassageCondenser,
    /**
     * The source text of passages still to come, so a request can carry more than one.
     *
     * The pipeline hands passages over one at a time and wants each answer immediately,
     * so there is nothing to buffer — by the time a second passage arrives the first has
     * already been answered. Batching therefore needs to look forward, not backward, and
     * only the caller knows what is coming.
     */
    private var lookahead: suspend (limit: Int) -> List<String> = { emptyList() },
    private val onCreditExhausted: () -> Unit = {},
    private val batchSize: Int = DEFAULT_BATCH,
) : PassageCondenser {

    /** Answers keyed by the source text they came from, not by position. */
    private val ready = mutableMapOf<String, String>()

    /**
     * True once credit ran out. Latched deliberately: having been told no, asking again
     * for every remaining passage of a long book would be hundreds of pointless round
     * trips, each one slower than the local path it is delaying.
     */
    @Volatile
    private var exhausted = false

    override fun isReady(): Boolean = cloud.isConfigured || local.isReady()

    override fun setLookahead(upcoming: suspend (limit: Int) -> List<String>) {
        lookahead = upcoming
    }

    override suspend fun condense(
        sourceText: String,
        ledger: StoryLedger,
        previousTail: String,
        budgetWords: Int,
        onToken: (String) -> Unit,
    ): CondensedBeat {
        ready.remove(sourceText)?.let { return finished(it, ledger) }

        if (exhausted || !cloud.isConfigured) {
            return local.condense(sourceText, ledger, previousTail, budgetWords, onToken)
        }

        fetch(sourceText, ledger, budgetWords)

        ready.remove(sourceText)?.let { return finished(it, ledger) }

        // The batch did not cover this passage — it was skipped, or the call failed.
        // Doing it here costs one passage rather than abandoning the book.
        return local.condense(sourceText, ledger, previousTail, budgetWords, onToken)
    }

    private fun finished(prose: String, ledger: StoryLedger) = CondensedBeat(
        prose = prose,
        ledger = ledger,
        words = Abridger.countWords(prose),
        attempts = 1,
        usedFallback = false,
    )

    /** Sends this passage together with the next few, and files what comes back. */
    private suspend fun fetch(current: String, ledger: StoryLedger, budgetWords: Int) {
        val texts = buildList {
            add(current)
            // Distinct, because a book can repeat a passage and an answer keyed by text
            // would otherwise be claimed twice.
            addAll(lookahead(batchSize - 1).filter { it != current }.distinct())
        }

        val passages = texts.mapIndexed { index, text ->
            CloudPassage(index, text, budgetWords)
        }
        // Charged in source pages. A page is about 300 words, rounded up so we never
        // under-declare what we are asking the backend to read.
        val pages = texts.sumOf {
            (Abridger.countWords(it) + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE
        }.coerceAtLeast(1)

        when (val result = cloud.condenseBatch(passages, ledger.render(), "", pages)) {
            is CloudResult.Ok ->
                result.value.forEach { answer ->
                    texts.getOrNull(answer.id)?.let { ready[it] = answer.prose }
                }
            CloudResult.OutOfCredit -> {
                exhausted = true
                onCreditExhausted()
            }
            is CloudResult.Failed -> Unit // The caller condenses locally instead.
        }
    }

    private companion object {
        /**
         * Passages per request. Large enough that a book is tens of calls rather than
         * hundreds; small enough that one failed request costs a chapter, not the book.
         */
        const val DEFAULT_BATCH = 10
        const val WORDS_PER_PAGE = 300
    }
}
