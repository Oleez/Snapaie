package com.snapaie.android.entry

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.snapaie.android.MainActivity

/**
 * Door 2 — share sheet. Accepts plain text, PDFs, and images (plus multi-image
 * for Pro batch), stashes the payload, and trampolines into MainActivity's
 * ingest flow where the full pipeline UI can render progress.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = readPayload(intent)
        val next = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            when (payload) {
                is SharePayload.Text -> putExtra(MainActivity.EXTRA_SHARED_TEXT, payload.text)
                is SharePayload.Document -> {
                    putExtra(MainActivity.EXTRA_SHARED_URI, payload.uri.toString())
                    putExtra(MainActivity.EXTRA_SHARED_IS_PDF, payload.isPdf)
                    // Grant the target activity read access to the shared stream.
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                null -> Unit
            }
        }
        startActivity(next)
        finish()
    }

    private fun readPayload(intent: Intent): SharePayload? {
        val type = intent.type.orEmpty()
        return when (intent.action) {
            Intent.ACTION_SEND -> when {
                type.startsWith("text/") -> intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SharePayload.Text(it) }
                else -> intent.streamUri()?.let { SharePayload.Document(it, type == "application/pdf") }
            }
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris().firstOrNull()
                ?.let { SharePayload.Document(it, type == "application/pdf") }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}

private sealed interface SharePayload {
    data class Text(val text: String) : SharePayload
    data class Document(val uri: Uri, val isPdf: Boolean) : SharePayload
}
