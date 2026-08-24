package com.snapaie.android.domain.scan

import com.snapaie.android.data.model.BookScanDraft

/** Builds the structured-breakdown prompts, kept behind an interface for testing. */
interface ScanPrompts {
    fun buildScanPrompt(draft: BookScanDraft): String
    fun buildRepairPrompt(draft: BookScanDraft, previousOutput: String): String
}
