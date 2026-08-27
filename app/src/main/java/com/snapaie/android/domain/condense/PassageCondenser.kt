package com.snapaie.android.domain.condense

/**
 * Whatever turns one passage of a book into a shorter one.
 *
 * Exists so the book pipeline does not care whether the work happened on the phone or in
 * the cloud. [CondensePipeline] walks beats, records them, and resumes after a killed
 * process; none of that changes with where the condensing runs, and it should not have to
 * know.
 */
interface PassageCondenser {

    suspend fun condense(
        sourceText: String,
        ledger: StoryLedger,
        previousTail: String,
        budgetWords: Int,
        onToken: (String) -> Unit = {},
    ): CondensedBeat

    /** True when this condenser can run right now — a model installed, or a reachable backend. */
    fun isReady(): Boolean

    /**
     * Tells this condenser what is coming, so it can do more than one at a time.
     *
     * The pipeline hands passages over singly and wants each answer before it moves on, so
     * there is nothing to buffer. A condenser that batches has to look forward, and only
     * the pipeline knows what is next. Ignored by anything that works one at a time.
     */
    fun setLookahead(upcoming: suspend (limit: Int) -> List<String>) = Unit
}
