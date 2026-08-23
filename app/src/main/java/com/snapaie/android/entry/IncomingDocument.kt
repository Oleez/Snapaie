package com.snapaie.android.entry

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.snapaie.android.data.model.BookSourceKind

/** What arrived, and enough about it to decide where to send the user. */
data class IncomingDocument(
    val uri: Uri,
    val kind: BookSourceKind,
    val displayName: String,
    val sizeBytes: Long,
) {
    /**
     * Whether this is big enough to be a book rather than a page.
     *
     * The two flows are genuinely different — one is a few seconds, the other can run all
     * night — so guessing wrong is expensive in both directions. Size is a crude signal but
     * a reliable one: nobody shares a four-megabyte PDF expecting a single-page summary,
     * and nobody shares a 40 KB one expecting an overnight job. The user can still override
     * on the routing sheet.
     */
    val looksLikeBook: Boolean
        get() = kind == BookSourceKind.EPUB || (kind == BookSourceKind.PDF && sizeBytes >= BOOK_SIZE_HINT)

    companion object {
        private const val BOOK_SIZE_HINT = 400L * 1024L

        private val EPUB_MIMES = setOf("application/epub+zip", "application/x-epub+zip")

        fun of(context: Context, uri: Uri, declaredMime: String?): IncomingDocument? {
            val resolved = declaredMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: context.contentResolver.getType(uri)
            val (name, size) = query(context, uri)

            val kind = when {
                resolved in EPUB_MIMES -> BookSourceKind.EPUB
                resolved == "application/pdf" -> BookSourceKind.PDF
                // Providers that report octet-stream leave only the file name to go on.
                name.endsWith(".epub", ignoreCase = true) -> BookSourceKind.EPUB
                name.endsWith(".pdf", ignoreCase = true) -> BookSourceKind.PDF
                resolved?.startsWith("image/") == true -> BookSourceKind.SCAN
                resolved?.startsWith("text/") == true -> BookSourceKind.TEXT
                else -> return null
            }
            return IncomingDocument(uri, kind, name.ifBlank { "Document" }, size)
        }

        private fun query(context: Context, uri: Uri): Pair<String, Long> {
            val fromPath = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            return runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use fromPath to 0L
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else fromPath
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    name to size
                }
            }.getOrNull() ?: (fromPath to 0L)
        }
    }
}
