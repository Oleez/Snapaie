package com.snapaie.android.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrProcessor(private val context: Context) {

    // One shared recognizer instead of a leaked instance per call.
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractText(uri: Uri): String =
        recognize(InputImage.fromFilePath(context, uri))

    suspend fun extractText(bitmap: android.graphics.Bitmap): String =
        recognize(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text -> continuation.resume(text.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    fun close() {
        recognizer.close()
    }
}
