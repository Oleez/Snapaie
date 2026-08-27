package com.snapaie.android.data.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Who a free user is, without asking them to sign in.
 *
 * Cloud pages cost real money, so something has to count them, and counting them on the
 * phone is not counting them at all — a number in app storage is a spend limit the spender
 * can edit. The count lives on the server; this is the name it counts against.
 *
 * [Settings.Secure.ANDROID_ID] rather than a generated UUID, for one reason: a UUID kept in
 * app storage dies when the app is uninstalled, so anyone wanting another free allowance
 * only has to reinstall. ANDROID_ID survives that. It changes on a factory reset, and it is
 * already scoped per app-signing-key and per user profile, so two users on the same handset
 * get separate allowances while one user reinstalling gets the same one.
 *
 * It is hashed before it leaves the device. The raw value is a device identifier and there
 * is no reason for a server to hold one — the server needs to tell accounts apart, not to
 * know which handset it is talking to. Salting with the package name means the same phone
 * talking to a different app of ours would look like a different account.
 *
 * This is not unforgeable, and nothing that avoids a login is. A rooted phone can return
 * whatever it likes here. The actual protection against a bad night is the server-side
 * daily ceiling: worst case is bounded spend, not unbounded. Real accounts can come later,
 * and the shape of this does not have to change when they do.
 */
object DeviceIdentity {

    private const val SALT = "com.snapaie.android"

    @Volatile
    private var cached: String? = null

    @SuppressLint("HardwareIds")
    fun of(context: Context): String {
        cached?.let { return it }
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        // Absent on some devices, and famously the same broken value on a batch of old
        // ones. Either way a stable-but-shared id would pool every affected phone into one
        // allowance, so fall back to something random and per-install instead: worse for
        // us, but it fails towards giving people their own quota rather than none.
        val seed = if (raw.isNullOrBlank() || raw == BROKEN_ANDROID_ID) {
            "fallback:" + java.util.UUID.randomUUID()
        } else {
            raw
        }

        return hash(SALT + ":" + seed).also { cached = it }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Shipped identically on a large run of 2013-era devices; useless as an identifier. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"
}
