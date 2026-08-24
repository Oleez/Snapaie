package com.snapaie.android.domain.scan

import android.content.Context
import com.snapaie.android.data.model.BookScanDraft

class PromptLibrary(private val context: Context) : PromptSource, ScanPrompts {

    override fun buildScanPrompt(draft: BookScanDraft): String {
        val guardrails = readAsset("prompts/guardrails.md")
        val orchestrator = readAsset("prompts/orchestrator.md")
        return buildString {
            appendLine(guardrails)
            appendLine()
            appendLine(orchestrator)
            appendLine()
            appendLine("Style: ${draft.mode.label}")
            appendLine("Style behavior: ${draft.mode.description}")
            if (draft.bookTitle.isNotBlank()) appendLine("Book/source: ${draft.bookTitle}")
            if (draft.context.isNotBlank()) appendLine("User context: ${draft.context}")
            appendLine("OCR/page text:")
            append(draft.pageText.take(MAX_SOURCE_CHARS))
        }
    }

    override fun buildRepairPrompt(draft: BookScanDraft, previousOutput: String): String = buildString {
        appendLine(readAsset("prompts/repair_retry.md"))
        appendLine()
        appendLine("Source text:")
        appendLine(draft.pageText.take(REPAIR_SOURCE_CHARS))
        appendLine()
        appendLine("Your previous unparseable answer (for reference, fix its format):")
        append(previousOutput.take(REPAIR_PREVIOUS_CHARS))
    }

    override fun read(path: String): String = readAsset(path)

    fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        // Budgets tuned for a 2-4B on-device model.
        const val MAX_SOURCE_CHARS = 6_000
        const val REPAIR_SOURCE_CHARS = 3_000
        const val REPAIR_PREVIOUS_CHARS = 2_000
    }
}
