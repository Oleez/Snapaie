package com.snapaie.android.domain.scan

import android.content.Context
import com.snapaie.android.data.model.BookScanDraft

class PromptLibrary(private val context: Context) : PromptSource, ScanPrompts {

    override fun buildScanPrompt(draft: BookScanDraft): String {
        val guardrails = readAsset("prompts/guardrails.md")
        val orchestrator = readAsset("prompts/orchestrator.md")
        val head = buildString {
            appendLine(guardrails)
            appendLine()
            appendLine(orchestrator)
            appendLine()
            appendLine("Style: ${draft.mode.label}")
            appendLine("Style behavior: ${draft.mode.description}")
            if (draft.bookTitle.isNotBlank()) appendLine("Book/source: ${draft.bookTitle}")
            if (draft.context.isNotBlank()) appendLine("User context: ${draft.context}")
            appendLine("OCR/page text:")
        }
        return head + draft.pageText.take(PromptBudget.maxSourceChars(head).coerceAtLeast(0))
    }

    override fun buildRepairPrompt(draft: BookScanDraft, previousOutput: String): String {
        val head = buildString {
            appendLine(readAsset("prompts/repair_retry.md"))
            appendLine()
            appendLine("Source text:")
        }
        val middle = buildString {
            appendLine()
            appendLine("Your previous unparseable answer (for reference, fix its format):")
        }
        val room = PromptBudget.maxSourceChars(head + middle).coerceAtLeast(0)
        val sourceRoom = room / 2
        return head +
            draft.pageText.take(sourceRoom) +
            middle +
            previousOutput.take(room - sourceRoom)
    }

    override fun read(path: String): String = readAsset(path)

    fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}
