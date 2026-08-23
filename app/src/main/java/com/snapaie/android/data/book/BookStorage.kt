package com.snapaie.android.data.book

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Where a book's bytes live.
 *
 * Everything is copied into app storage at import time rather than read from the incoming
 * content URI. A condense run lasts hours and survives reboots; the temporary read grant
 * that arrived with a share intent does not, and neither does the file if the user deletes
 * it from Downloads halfway through.
 */
class BookStorage(private val context: Context) {

    fun bookDir(bookId: Long): File =
        File(File(context.filesDir, ROOT), bookId.toString()).also { it.mkdirs() }

    fun imageDir(bookId: Long): File = File(bookDir(bookId), "images").also { it.mkdirs() }

    fun exportDir(bookId: Long): File = File(bookDir(bookId), "exports").also { it.mkdirs() }

    fun sourceFile(bookId: Long, extension: String): File =
        File(bookDir(bookId), "source.${extension.ifBlank { "bin" }}")

    /** The flattened text every later stage indexes into with character offsets. */
    fun textFile(bookId: Long): File = File(bookDir(bookId), "text.txt")

    fun readText(bookId: Long): String =
        textFile(bookId).let { if (it.isFile) it.readText() else "" }

    fun writeText(bookId: Long, text: String) {
        textFile(bookId).writeText(text)
    }

    /**
     * Source text for a ladder pass above the first, which is the previous pass's output.
     * Kept as its own file so pass 1 stays readable and exportable while pass 2 runs.
     */
    fun passTextFile(bookId: Long, pass: Int): File = File(bookDir(bookId), "text-pass$pass.txt")

    /** Copies the incoming document in. Returns null when the URI cannot be opened. */
    fun copyIn(bookId: Long, uri: Uri, extension: String): File? {
        val target = sourceFile(bookId, extension)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

    fun delete(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    fun deleteAll() {
        File(context.filesDir, ROOT).deleteRecursively()
    }

    private companion object {
        const val ROOT = "books"
    }
}
