package com.snapae.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.snapae.android.data.model.AssetType
import com.snapae.android.data.model.LaunchPackage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(entities = [LaunchRunEntity::class], version = 1)
abstract class SnapAeDatabase : RoomDatabase() {
    abstract fun launchRunDao(): LaunchRunDao
}

@Entity(tableName = "launch_runs")
data class LaunchRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtMillis: Long,
    val assetType: String,
    val title: String,
    val sourcePreview: String,
    val packageJson: String,
)

data class LaunchRun(
    val id: Long,
    val createdAtMillis: Long,
    val assetType: AssetType,
    val title: String,
    val sourcePreview: String,
    val launchPackage: LaunchPackage,
)

@Dao
interface LaunchRunDao {
    @Query("SELECT * FROM launch_runs ORDER BY createdAtMillis DESC")
    fun observeRuns(): Flow<List<LaunchRunEntity>>

    @Insert
    suspend fun insert(entity: LaunchRunEntity): Long
}

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun LaunchRunEntity.toDomain(): LaunchRun = LaunchRun(
    id = id,
    createdAtMillis = createdAtMillis,
    assetType = runCatching { AssetType.valueOf(assetType) }.getOrDefault(AssetType.Transcript),
    title = title,
    sourcePreview = sourcePreview,
    launchPackage = runCatching { json.decodeFromString<LaunchPackage>(packageJson) }
        .getOrDefault(LaunchPackage()),
)

fun launchRunEntity(
    assetType: AssetType,
    title: String,
    source: String,
    launchPackage: LaunchPackage,
): LaunchRunEntity = LaunchRunEntity(
    createdAtMillis = System.currentTimeMillis(),
    assetType = assetType.name,
    title = title.ifBlank { assetType.label },
    sourcePreview = source.take(240),
    packageJson = json.encodeToString(launchPackage),
)
