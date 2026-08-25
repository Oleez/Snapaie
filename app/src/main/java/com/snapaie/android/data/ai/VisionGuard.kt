package com.snapaie.android.data.ai

import android.content.Context

/**
 * Remembers whether reading images has ever killed this app on this device.
 *
 * A failure inside the model's native code is not an exception — it is a SIGSEGV, and the
 * process is gone before any handler runs. So it cannot be caught, only *noticed after the
 * fact*: a flag is written immediately before an image is handed over and cleared as soon
 * as the call returns. If the flag is still set at the next launch, the only thing that
 * could have prevented it being cleared is the process dying mid-call.
 *
 * Two strikes rather than one, because a single crash can have other causes — a
 * simultaneous low-memory kill, the user force-stopping the app. Twice is a pattern, and
 * from then on this device uses the text path only. Better a slower snap than an app that
 * dies every time someone photographs a page.
 */
class VisionGuard(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when images may still be sent to the model on this device. */
    val isVisionAllowed: Boolean get() = prefs.getInt(KEY_STRIKES, 0) < MAX_STRIKES

    /**
     * Call once at startup, before any inference. Converts "we never cleared the flag" into
     * a recorded strike.
     */
    fun recordStartup() {
        if (!prefs.getBoolean(KEY_IN_FLIGHT, false)) return
        val strikes = prefs.getInt(KEY_STRIKES, 0) + 1
        prefs.edit()
            .putBoolean(KEY_IN_FLIGHT, false)
            .putInt(KEY_STRIKES, strikes)
            .apply()
    }

    fun beginVisionCall() {
        // commit(), not apply(), and lint's advice to the contrary is wrong here: the whole
        // point is that this flag reaches disk *before* the native call that may kill the
        // process. An asynchronous write loses the race and the guard never fires.
        prefs.edit().putBoolean(KEY_IN_FLIGHT, true).commit()
    }

    fun endVisionCall() {
        prefs.edit().putBoolean(KEY_IN_FLIGHT, false).apply()
    }

    /**
     * Switches images off for good on this device.
     *
     * For failures the runtime actually reports — a missing image encoder — rather than
     * the silent kind. There is nothing to retry, so it goes straight past the strikes.
     */
    fun disableVision() {
        prefs.edit().putInt(KEY_STRIKES, MAX_STRIKES).putBoolean(KEY_IN_FLIGHT, false).apply()
    }

    /** Lets someone who has fixed their situation try again. */
    fun reset() {
        prefs.edit().putInt(KEY_STRIKES, 0).putBoolean(KEY_IN_FLIGHT, false).apply()
    }

    private companion object {
        const val PREFS = "snapaie_vision_guard"
        const val KEY_IN_FLIGHT = "vision_in_flight"
        const val KEY_STRIKES = "vision_strikes"
        const val MAX_STRIKES = 2
    }
}
