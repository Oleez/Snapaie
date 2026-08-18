package com.snapaie.android.domain.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.snapaie.android.data.local.ChatMessageEntity
import com.snapaie.android.data.local.ChatSessionEntity
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.NoteEntity
import com.snapaie.android.domain.chat.Persona
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One chat session plus its messages, loaded before export. */
data class ChatTranscript(
    val session: ChatSessionEntity,
    val messages: List<ChatMessageEntity>,
)

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    Markdown("Markdown (.md)", "md", "text/markdown"),
    Json("JSON (.json)", "json", "application/json"),
}

/**
 * Bulk export for the Library, ported from the extension's history export
 * ("export current session" / "export stored history" across the Explanations,
 * Answers and Chat subtabs).
 *
 * Two differences from the extension:
 *  - it exports whatever the Library filter currently shows, so a search for
 *    one book exports only that book;
 *  - JSON is offered next to Markdown, because the whole point on-device is
 *    that the user can take their data out without a server round trip.
 *
 * [MarkdownExporter] still owns single-scan export; this class is the
 * many-rows case and covers chats and notes too.
 */
class LibraryExporter(private val context: Context) {

    private val json = Json { prettyPrint = true }

    // region Scans

    fun scansToMarkdown(scans: List<KnowledgeScan>, includeBranding: Boolean): String = buildString {
        appendLine("# snapaie library export")
        appendLine()
        appendLine("_${scans.size} scan${plural(scans.size)} · exported ${humanTimestamp(System.currentTimeMillis())}_")
        appendLine()
        if (scans.isEmpty()) {
            appendLine("No scans matched the current filter.")
            return@buildString
        }
        scans.forEach { scan ->
            appendLine("---")
            appendLine()
            appendLine("## ${scan.bookTitle}")
            appendLine()
            appendLine("- Date: ${humanTimestamp(scan.createdAtMillis)}")
            appendLine("- Style: ${scan.mode.label}")
            appendLine("- Compression: ${scan.result.compressionScore}%")
            appendLine("- Time saved: ${scan.result.estimatedTimeSavedMinutes} min")
            appendLine()
            appendLine(scan.result.toMarkdown(includeBranding = false))
            appendLine()
        }
        if (includeBranding) {
            appendLine("---")
            appendLine()
            appendLine("_Compressed on-device with snapaie._")
        }
    }

    fun scansToJson(scans: List<KnowledgeScan>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            scans.forEach { scan ->
                add(
                    buildJsonObject {
                        put("id", scan.id)
                        put("title", scan.bookTitle)
                        put("createdAt", humanTimestamp(scan.createdAtMillis))
                        put("createdAtMillis", scan.createdAtMillis)
                        put("style", scan.mode.label)
                        put("compressionScore", scan.result.compressionScore)
                        put("estimatedTimeSavedMinutes", scan.result.estimatedTimeSavedMinutes)
                        put("wordsIn", scan.wordsIn)
                        put("wordsOut", scan.wordsOut)
                        put("conciseMeaning", scan.result.conciseMeaning)
                        put("coreIdea", scan.result.coreIdea)
                        put("authorIntent", scan.result.authorIntent)
                        put("simplifiedExplanation", scan.result.simplifiedExplanation)
                        put("hiddenMeaning", scan.result.hiddenMeaning)
                        put(
                            "actionableInsights",
                            buildJsonArray { scan.result.actionableInsights.forEach { add(it) } },
                        )
                        put(
                            "keyQuotesToKeep",
                            buildJsonArray { scan.result.keyQuotesToKeep.forEach { add(it) } },
                        )
                    },
                )
            }
        },
    )

    // endregion

    // region Chats

    fun chatsToMarkdown(chats: List<ChatTranscript>): String = buildString {
        appendLine("# snapaie chat export")
        appendLine()
        appendLine("_${chats.size} chat${plural(chats.size)} · exported ${humanTimestamp(System.currentTimeMillis())}_")
        appendLine()
        if (chats.isEmpty()) {
            appendLine("No chats matched the current filter.")
            return@buildString
        }
        chats.forEach { transcript ->
            appendLine("---")
            appendLine()
            appendLine("## ${transcript.session.title}")
            appendLine()
            appendLine("- Lens: ${Persona.fromId(transcript.session.persona).label}")
            appendLine("- Started: ${humanTimestamp(transcript.session.createdAtMillis)}")
            appendLine()
            transcript.messages.forEach { message ->
                val speaker = if (message.role == "user") "You" else "AE"
                appendLine("**$speaker:** ${message.content}")
                appendLine()
            }
        }
    }

    fun chatsToJson(chats: List<ChatTranscript>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            chats.forEach { transcript ->
                add(
                    buildJsonObject {
                        put("id", transcript.session.id)
                        put("title", transcript.session.title)
                        put("persona", Persona.fromId(transcript.session.persona).label)
                        put("createdAt", humanTimestamp(transcript.session.createdAtMillis))
                        put(
                            "messages",
                            buildJsonArray {
                                transcript.messages.forEach { message ->
                                    add(
                                        buildJsonObject {
                                            put("role", message.role)
                                            put("content", message.content)
                                            put("createdAt", humanTimestamp(message.createdAtMillis))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        },
    )

    // endregion

    // region Notes

    fun notesToMarkdown(notes: List<NoteEntity>): String = buildString {
        appendLine("# snapaie notes export")
        appendLine()
        appendLine("_${notes.size} note${plural(notes.size)} · exported ${humanTimestamp(System.currentTimeMillis())}_")
        appendLine()
        if (notes.isEmpty()) {
            appendLine("No notes matched the current filter.")
            return@buildString
        }
        notes.forEach { note ->
            appendLine("- [${if (note.done) "x" else " "}] ${note.text}  ")
            appendLine("  _${humanTimestamp(note.createdAtMillis)}_")
        }
    }

    fun notesToJson(notes: List<NoteEntity>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            notes.forEach { note ->
                add(
                    buildJsonObject {
                        put("id", note.id)
                        put("text", note.text)
                        put("done", note.done)
                        put("createdAt", humanTimestamp(note.createdAtMillis))
                    },
                )
            }
        },
    )

    // endregion

    /** Writes [content] into the shared cache dir and returns a chooser intent. */
    fun shareFile(content: String, fileName: String, format: ExportFormat): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "snapaie-export" }
        val file = File(dir, "$safe.${format.extension}")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, safe)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export ${format.label}")
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private fun humanTimestamp(millis: Long): String =
        TIMESTAMP.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
