package com.snapaie.android.entry

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.snapaie.android.MainActivity
import com.snapaie.android.data.model.BookSourceKind

/**
 * Doors 2 and 3 — the share sheet and "Open with".
 *
 * Reads whatever arrived, works out whether it is a book or a page, and trampolines into
 * MainActivity where the pipeline UI can render. It stays headless and transparent so
 * sharing from another app never flashes a screen the user did not ask for.
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
                    putExtra(MainActivity.EXTRA_SHARED_URI, payload.document.uri.toString())
                    putExtra(MainActivity.EXTRA_SHARED_KIND, payload.document.kind.name)
                    putExtra(MainActivity.EXTRA_SHARED_NAME, payload.document.displayName)
                    putExtra(MainActivity.EXTRA_SHARED_SIZE, payload.document.sizeBytes)
                    putExtra(MainActivity.EXTRA_SHARED_IS_BOOK, payload.document.looksLikeBook)
                    putExtra(MainActivity.EXTRA_SHARED_IS_PDF, payload.document.kind == BookSourceKind.PDF)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                is SharePayload.Pages -> {
                    putExtra(
                        MainActivity.EXTRA_SHARED_PAGE_URIS,
                        ArrayList(payload.uris.map { it.toString() }),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                null -> Unit
            }
        }
        startActivity(next)
        finish()
    }

    private fun readPayload(intent: Intent): SharePayload? = when (intent.action) {
        Intent.ACTION_SEND -> {
            val type = intent.type.orEmpty()
            if (type.startsWith("text/") && intent.streamUri() == null) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SharePayload.Text(it) }
            } else {
                intent.streamUri()?.let { document(it, type) }
            }
        }

        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = intent.streamUris()
            when {
                uris.isEmpty() -> null
                // Several images are a stack of pages, in the order they were shared.
                uris.size > 1 && intent.type.orEmpty().startsWith("image/") -> SharePayload.Pages(uris)
                else -> document(uris.first(), intent.type.orEmpty())
            }
        }

        // "Open with" hands the file over on the intent's own data URI.
        Intent.ACTION_VIEW -> intent.data?.let { document(it, intent.type.orEmpty()) }

        else -> null
    }

    private fun document(uri: Uri, type: String): SharePayload? =
        IncomingDocument.of(this, uri, type)?.let { SharePayload.Document(it) }

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
    data class Document(val document: IncomingDocument) : SharePayload
    data class Pages(val uris: List<Uri>) : SharePayload
}
