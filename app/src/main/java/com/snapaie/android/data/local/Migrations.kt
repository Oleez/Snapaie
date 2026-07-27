package com.snapaie.android.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE knowledge_scans ADD COLUMN sourceText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE knowledge_scans ADD COLUMN languageCode TEXT NOT NULL DEFAULT 'en'")
        db.execSQL("ALTER TABLE knowledge_scans ADD COLUMN wordsIn INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE knowledge_scans ADD COLUMN wordsOut INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scanId INTEGER,
                title TEXT NOT NULL,
                persona TEXT NOT NULL,
                appearance TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS practice_topics (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                content TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                sourceScanId INTEGER,
                strengthLevel INTEGER NOT NULL,
                reviewCount INTEGER NOT NULL,
                nextDueAtMillis INTEGER NOT NULL,
                lastReviewedAtMillis INTEGER,
                archived INTEGER NOT NULL,
                hotStreak INTEGER NOT NULL,
                gemsJson TEXT NOT NULL,
                questionBankJson TEXT NOT NULL,
                dedupeKey TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_practice_topics_dedupeKey ON practice_topics(dedupeKey)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                text TEXT NOT NULL,
                reminderAtMillis INTEGER,
                done INTEGER NOT NULL,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
