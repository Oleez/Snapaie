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
            return IngestRequest.Content(
                uri = Uri.parse(uriString),
                isPdf = intent.getBooleanExtra(EXTRA_SHARED_IS_PDF, false),
            )
        }
        return null
    }

    companion object {
        const val EXTRA_SCAN_ID = "snapaie.extra.scanId"
        const val EXTRA_SHARED_TEXT = "snapaie.extra.sharedText"
        const val EXTRA_SHARED_URI = "snapaie.extra.sharedUri"
        const val EXTRA_SHARED_IS_PDF = "snapaie.extra.sharedIsPdf"
    }
}

sealed interface IngestRequest {
    data class OpenScan(val scanId: Long) : IngestRequest
    data class Content(
        val text: String? = null,
        val uri: Uri? = null,
        val isPdf: Boolean = false,
    ) : IngestRequest
}
