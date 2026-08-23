package com.snapaie.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.snapaie.android.data.model.BookSourceKind
import com.snapaie.android.ui.SnapAieApp
import com.snapaie.android.ui.nav.Routes

class MainActivity : ComponentActivity() {

    private var pendingIntentState by mutableStateOf<IngestRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntentState = parseIngest(intent)
        setContent {
            val request = pendingIntentState
            SnapAieApp(
                container = (application as SnapAieApplication).container,
                startRoute = request?.let {
                    when (it) {
                        is IngestRequest.OpenScan -> Routes.scanDetail(it.scanId)
                        else -> Routes.Snap
                    }
                },
                ingest = request as? IngestRequest.Content,
                onIngestConsumed = { pendingIntentState = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentState = parseIngest(intent)
    }

    private fun parseIngest(intent: Intent?): IngestRequest? {
        if (intent == null) return null
        intent.getLongExtra(EXTRA_SCAN_ID, -1L).takeIf { it > 0 }?.let {
            return IngestRequest.OpenScan(it)
        }
        intent.getStringExtra(EXTRA_SHARED_TEXT)?.takeIf { it.isNotBlank() }?.let {
            return IngestRequest.Content(text = it)
        }
        intent.getStringExtra(EXTRA_SHARED_URI)?.let { uriString ->
            val kind = BookSourceKind.fromStored(intent.getStringExtra(EXTRA_SHARED_KIND))
            return IngestRequest.Content(
                uri = Uri.parse(uriString),
                isPdf = intent.getBooleanExtra(EXTRA_SHARED_IS_PDF, false),
                kind = kind,
                displayName = intent.getStringExtra(EXTRA_SHARED_NAME).orEmpty(),
                sizeBytes = intent.getLongExtra(EXTRA_SHARED_SIZE, 0L),
                looksLikeBook = intent.getBooleanExtra(EXTRA_SHARED_IS_BOOK, false),
            )
        }
        intent.getStringArrayListExtra(EXTRA_SHARED_PAGE_URIS)
            ?.takeIf { it.isNotEmpty() }
            ?.let { uris ->
                return IngestRequest.Content(pageUris = uris.map(Uri::parse))
            }
        return null
    }

    companion object {
        const val EXTRA_SCAN_ID = "snapaie.extra.scanId"
        const val EXTRA_SHARED_TEXT = "snapaie.extra.sharedText"
        const val EXTRA_SHARED_URI = "snapaie.extra.sharedUri"
        const val EXTRA_SHARED_IS_PDF = "snapaie.extra.sharedIsPdf"
        const val EXTRA_SHARED_KIND = "snapaie.extra.sharedKind"
        const val EXTRA_SHARED_NAME = "snapaie.extra.sharedName"
        const val EXTRA_SHARED_SIZE = "snapaie.extra.sharedSize"
        const val EXTRA_SHARED_IS_BOOK = "snapaie.extra.sharedIsBook"
        const val EXTRA_SHARED_PAGE_URIS = "snapaie.extra.sharedPageUris"
    }
}

sealed interface IngestRequest {
    data class OpenScan(val scanId: Long) : IngestRequest
    data class Content(
        val text: String? = null,
        val uri: Uri? = null,
        val isPdf: Boolean = false,
        val kind: BookSourceKind = BookSourceKind.TEXT,
        val displayName: String = "",
        val sizeBytes: Long = 0L,
        /**
         * Big enough that the user probably meant the whole-book flow. Only a hint — the
         * routing sheet still asks, because the two paths differ by hours.
         */
        val looksLikeBook: Boolean = false,
        /** Several images shared at once: a stack of pages in the order they arrived. */
        val pageUris: List<Uri> = emptyList(),
    ) : IngestRequest
}
