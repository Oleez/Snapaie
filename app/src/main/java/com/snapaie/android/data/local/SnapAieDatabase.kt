package com.snapaie.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.KnowledgeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(
    entities = [
        KnowledgeScanEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        PracticeTopicEntity::class,
        NoteEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SnapAieDatabase : RoomDatabase() {
    abstract fun knowledgeScanDao(): KnowledgeScanDao
    abstract fun chatDao(): ChatDao
    abstract fun recallDao(): RecallDao
    abstract fun noteDao(): NoteDao
}

// region Scans

@Entity(tableName = "knowledge_scans")
data class KnowledgeScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtMillis: Long,
    val mode: String,
    val bookTitle: String,
    val sourcePreview: String,
    val resultJson: String,
    val sourceText: String = "",
    val languageCode: String = "en",
    val wordsIn: Int = 0,
    val wordsOut: Int = 0,
)

data class KnowledgeScan(
    val id: Long,
    val createdAtMillis: Long,
    val mode: ExplainStyle,
    val bookTitle: String,
    val sourcePreview: String,
    val sourceText: String,
    val result: KnowledgeResult,
    val wordsIn: Int,
    val wordsOut: Int,
)

@Dao
interface KnowledgeScanDao {
    @Query("SELECT * FROM knowledge_scans ORDER BY createdAtMillis DESC")
    fun observeScans(): Flow<List<KnowledgeScanEntity>>

    @Query("SELECT * FROM knowledge_scans WHERE id = :id")
    fun observeScan(id: Long): Flow<KnowledgeScanEntity?>

    @Query("SELECT * FROM knowledge_scans WHERE id = :id")
    suspend fun getScan(id: Long): KnowledgeScanEntity?

    @Insert
    suspend fun insert(entity: KnowledgeScanEntity): Long

    @Update
    suspend fun update(entity: KnowledgeScanEntity)

    @Query("DELETE FROM knowledge_scans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM knowledge_scans")
    suspend fun deleteAll()
}

// endregion

// region Chat

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val scanId: Long? = null,
    val title: String,
    val persona: String,
    val appearance: String = "Classic",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val role: String, // "user" | "ai"
    val content: String,
    val createdAtMillis: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAtMillis DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertSession(entity: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(entity: ChatSessionEntity)

    @Insert
    suspend fun insertMessage(entity: ChatMessageEntity): Long

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
}

// endregion

// region Forge Recall

@Entity(
    tableName = "practice_topics",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class PracticeTopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val summary: String,
    val content: String,
    val sourceType: String, // "explanation" | "vocab" | "saved"
    val sourceScanId: Long? = null,
    val strengthLevel: Int = 50,
    val reviewCount: Int = 0,
    val nextDueAtMillis: Long,
    val lastReviewedAtMillis: Long? = null,
    val archived: Boolean = false,
    val hotStreak: Boolean = false,
    val gemsJson: String = "[]",
    val questionBankJson: String = "",
    val dedupeKey: String,
    val createdAtMillis: Long,
)

@Dao
interface RecallDao {
    @Query("SELECT * FROM practice_topics WHERE archived = 0 ORDER BY nextDueAtMillis ASC")
    fun observeTopics(): Flow<List<PracticeTopicEntity>>

    @Query("SELECT * FROM practice_topics WHERE archived = 0 ORDER BY nextDueAtMillis ASC")
    suspend fun getTopics(): List<PracticeTopicEntity>

    @Query("SELECT * FROM practice_topics WHERE id = :id")
    suspend fun getTopic(id: Long): PracticeTopicEntity?

    @Query("SELECT COUNT(*) FROM practice_topics WHERE archived = 0")
    suspend fun countTopics(): Int

    @Insert
    suspend fun insert(entity: PracticeTopicEntity): Long

    @Update
    suspend fun update(entity: PracticeTopicEntity)

    @Query("DELETE FROM practice_topics WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM practice_topics")
    suspend fun deleteAll()
}

// endregion

// region Notes

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val reminderAtMillis: Long? = null,
    val done: Boolean = false,
    val createdAtMillis: Long,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAtMillis DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(entity: NoteEntity): Long

    @Update
    suspend fun update(entity: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

// endregion

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun KnowledgeScanEntity.toDomain(): KnowledgeScan = KnowledgeScan(
    id = id,
    createdAtMillis = createdAtMillis,
    mode = ExplainStyle.fromStored(mode),
    bookTitle = bookTitle,
    sourcePreview = sourcePreview,
    sourceText = sourceText,
    result = runCatching { json.decodeFromString<KnowledgeResult>(resultJson) }
        .getOrDefault(KnowledgeResult()),
    wordsIn = wordsIn,
    wordsOut = wordsOut,
)

fun knowledgeScanEntity(
    draft: BookScanDraft,
    result: KnowledgeResult,
    wordsIn: Int,
    wordsOut: Int,
    languageCode: String = "en",
): KnowledgeScanEntity = KnowledgeScanEntity(
    createdAtMillis = System.currentTimeMillis(),
    mode = draft.mode.name,
    bookTitle = draft.bookTitle.ifBlank { "Untitled scan" },
    sourcePreview = draft.pageText.take(240),
    sourceText = draft.pageText,
    resultJson = json.encodeToString(result),
    languageCode = languageCode,
    wordsIn = wordsIn,
    wordsOut = wordsOut,
)

fun encodeResultJson(result: KnowledgeResult): String = json.encodeToString(result)
