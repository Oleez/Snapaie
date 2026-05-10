package com.snapae.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.snapae.android.data.model.BookScanDraft
import com.snapae.android.data.model.KnowledgeMode
import com.snapae.android.data.model.KnowledgeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(entities = [KnowledgeScanEntity::class], version = 1)
abstract class SnapAeDatabase : RoomDatabase() {
    abstract fun knowledgeScanDao(): KnowledgeScanDao
}

@Entity(tableName = "knowledge_scans")
data class KnowledgeScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtMillis: Long,
    val mode: String,
    val bookTitle: String,
    val sourcePreview: String,
    val resultJson: String,
)

data class KnowledgeScan(
    val id: Long,
    val createdAtMillis: Long,
    val mode: KnowledgeMode,
    val bookTitle: String,
    val sourcePreview: String,
    val result: KnowledgeResult,
)

@Dao
interface KnowledgeScanDao {
    @Query("SELECT * FROM knowledge_scans ORDER BY createdAtMillis DESC")
    fun observeScans(): Flow<List<KnowledgeScanEntity>>

    @Insert
    suspend fun insert(entity: KnowledgeScanEntity): Long
}

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun KnowledgeScanEntity.toDomain(): KnowledgeScan = KnowledgeScan(
    id = id,
    createdAtMillis = createdAtMillis,
    mode = runCatching { KnowledgeMode.valueOf(mode) }.getOrDefault(KnowledgeMode.Concise),
    bookTitle = bookTitle,
    sourcePreview = sourcePreview,
    result = runCatching { json.decodeFromString<KnowledgeResult>(resultJson) }
        .getOrDefault(KnowledgeResult()),
)

fun knowledgeScanEntity(
    draft: BookScanDraft,
    result: KnowledgeResult,
): KnowledgeScanEntity = KnowledgeScanEntity(
    createdAtMillis = System.currentTimeMillis(),
    mode = draft.mode.name,
    bookTitle = draft.bookTitle.ifBlank { "Untitled scan" },
    sourcePreview = draft.pageText.take(240),
    resultJson = json.encodeToString(result),
)
