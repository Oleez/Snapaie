package com.snapaie.android.domain.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.snapaie.android.data.model.BookExportFormat
import java.io.File

/**
 * Hands a finished book to the rest of the phone.
 *
 * Two routes, because they answer different intentions: [shareIntent] sends the file
 * somewhere (Gmail, Drive, a chat), while [saveIntent] opens the system file picker so the
 * user chooses where it lands on their own storage. Offering only the share sheet would
 * make "keep this" harder than it needs to be.
 */
object BookSharing {

    fun shareIntent(context: Context, file: File, format: BookExportFormat, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share \"$title\"") }
    }

    /** The "Save to Files" half: the caller writes our bytes into the URI it comes back with. */
    fun saveIntent(fileName: String, format: BookExportFormat): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

    /** Copies an export into the destination the user picked in the file picker. */
    fun copyTo(context: Context, source: File, destination: android.net.Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } != null
    }.getOrDefault(false)

    /** Picker filter for importing. octet-stream is included because plenty of providers
     *  report EPUBs that way and would otherwise be greyed out. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/octet-stream",
    )
}
