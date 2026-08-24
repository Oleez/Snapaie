package com.snapaie.android.data.ai

import kotlinx.coroutines.flow.Flow

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
}
