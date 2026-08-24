package com.snapaie.android.domain.scan

/**
 * Where prompt templates come from.
 *
 * Exists so the pipeline can be tested without an Android asset manager — and so a missing
 * template is a value the caller can react to rather than an exception thrown from inside
 * a coroutine.
 */
fun interface PromptSource {
    fun read(path: String): String
}
