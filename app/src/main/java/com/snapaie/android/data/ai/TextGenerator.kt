package com.snapaie.android.data.ai

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * What the scan pipeline needs from the model.
 *
 * Narrow on purpose. The condense path is the app's core function and it had no test at
 * all, because exercising it meant standing up a real engine, a real Context and two
 * gigabytes of weights. Every fix to it was therefore guesswork, and several were wrong in
 * ways nothing caught. Behind this interface the whole path can be driven with a fake that
 * fails exactly how the real one has been observed to fail.
 */
interface TextGenerator {

    /** True when a model is installed and loadable. */
    fun isModelInstalled(): Boolean

    /** False once reading images has proven unsupported or fatal on this device. */
    val visionAllowed: Boolean

    fun stream(prompt: String, maxOutputTokens: Int): Flow<String>

    fun streamWithImage(prompt: String, imagePath: String, maxOutputTokens: Int): Flow<String>

    /**
     * Pins the engine in memory for a job that makes more than one call.
     *
     * A snap is now several calls, not one: a passage larger than the context window is
     * walked in runs. Between those runs nothing is generating, so the engine looks idle —
     * and leaving the foreground tears it down. The next run then reloads two gigabytes,
     * which is the difference between a snap that finishes and one that appears not to.
     *
     * Defaulted, because a fake in a test has nothing to pin.
     */
    fun acquireKeepAlive(): Closeable = Closeable {}
}
