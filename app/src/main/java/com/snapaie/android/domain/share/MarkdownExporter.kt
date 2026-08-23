package com.snapaie.android.domain.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.snapaie.android.data.local.KnowledgeScan
import java.io.File

/**
 * Export surface (Pro): Markdown files compatible with Obsidian and
 * Notion-flavored Markdown for clipboard import ("Notion export" is a local
 * clipboard/file flow — no cloud API, by design).
 */
class MarkdownExporter(private val context: Context) {

    fun scanToMarkdown(scan: KnowledgeScan, includeBranding: Boolean): String = buildString {
        appendLine("---")
        appendLine("title: ${scan.bookTitle}")
        appendLine("date: ${java.time.Instant.ofEpochMilli(scan.createdAtMillis)}")
        appendLine("style: ${scan.mode.label}")
        appendLine("tags: [snapaie, reading]")
        appendLine("---")
        appendLine()
        append(scan.result.toMarkdown(includeBranding))
    }

    /** Writes markdown to cache and returns a share/save chooser intent. */
    fun shareMarkdownFile(markdown: String, fileName: String): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9-_ ]"), "").ifBlank { "snapaie-export" }
        val file = File(dir, "$safe.md")
        file.writeText(markdown)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export markdown")
    }
}
