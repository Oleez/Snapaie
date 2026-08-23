package com.snapaie.android.data.scan

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Wraps ML Kit's document scanner: edge detection, auto-capture, per-page retake and
 * reorder, and a compiled PDF, none of which we have to write.
 *
 * It needs Google Play Services, which is a real dent in "works with airplane mode on" —
 * the scanning module is fetched on first use. That is why [isAvailable] exists and why
 * the CameraX screen the app already had is kept as the fallback rather than deleted:
 * capture still works without Play Services, it just loses the edge detection.
 */
class DocumentScanner(private val context: Context) {

    fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /**
     * Prepares a scanning session. The caller launches the returned [IntentSender] and
     * reads pages out of the result with [GmsDocumentScanningResult.fromActivityResultIntent].
     */
    suspend fun intentSender(activity: Activity, pageLimit: Int = DEFAULT_PAGE_LIMIT): IntentSender =
        suspendCoroutine { continuation ->
            val options = GmsDocumentScannerOptions.Builder()
                // FULL gives the editing UI: reorder, retake, delete, rotate.
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(pageLimit.coerceIn(1, MAX_PAGE_LIMIT))
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                )
                .build()

            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    companion object {
        const val DEFAULT_PAGE_LIMIT = 60

        /** ML Kit's own ceiling; asking for more is rejected outright. */
        const val MAX_PAGE_LIMIT = 100
    }
}
