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

/**
 * Adds the book condensation tables. Purely additive — nothing existing is touched, so a
 * user mid-way through the old page-scan flow keeps every scan, chat, topic and note.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS books (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL DEFAULT '',
                sourceKind TEXT NOT NULL,
                sourcePath TEXT NOT NULL DEFAULT '',
                sourcePageCount INTEGER NOT NULL DEFAULT 0,
                sourceWordCount INTEGER NOT NULL DEFAULT 0,
                sourceCharCount INTEGER NOT NULL DEFAULT 0,
                coverPath TEXT,
                importState TEXT NOT NULL,
                importError TEXT,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_chapters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                orderIndex INTEGER NOT NULL,
                title TEXT NOT NULL,
                srcStartChar INTEGER NOT NULL,
                srcEndChar INTEGER NOT NULL,
                srcPageFrom INTEGER NOT NULL DEFAULT 0,
                outputStartPage INTEGER,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_chapters_bookId ON book_chapters(bookId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_beats (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                chapterId INTEGER NOT NULL,
                pass INTEGER NOT NULL DEFAULT 1,
                orderIndex INTEGER NOT NULL,
                srcStartChar INTEGER NOT NULL,
                srcEndChar INTEGER NOT NULL,
                srcWords INTEGER NOT NULL,
                srcPageFrom INTEGER NOT NULL DEFAULT 0,
                srcPageTo INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                targetWords INTEGER NOT NULL DEFAULT 0,
                outputText TEXT NOT NULL DEFAULT '',
                outputWords INTEGER NOT NULL DEFAULT 0,
                ledgerJson TEXT NOT NULL DEFAULT '',
                errorMessage TEXT,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_beats_bookId ON book_beats(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_beats_chapterId ON book_beats(chapterId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_book_beats_bookId_pass_orderIndex " +
                "ON book_beats(bookId, pass, orderIndex)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                path TEXT NOT NULL,
                anchorBeatId INTEGER,
                srcPage INTEGER NOT NULL DEFAULT 0,
                srcChar INTEGER NOT NULL DEFAULT 0,
                widthPx INTEGER NOT NULL DEFAULT 0,
                heightPx INTEGER NOT NULL DEFAULT 0,
                captionText TEXT NOT NULL DEFAULT '',
                orderInBeat INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_assets_bookId ON book_assets(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_assets_anchorBeatId ON book_assets(anchorBeatId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS condense_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                targetKind TEXT NOT NULL,
                targetValue INTEGER NOT NULL,
                pass INTEGER NOT NULL DEFAULT 1,
                state TEXT NOT NULL,
                targetWords INTEGER NOT NULL DEFAULT 0,
                producedWords INTEGER NOT NULL DEFAULT 0,
                chargingOnly INTEGER NOT NULL DEFAULT 1,
                startedAtMillis INTEGER NOT NULL DEFAULT 0,
                finishedAtMillis INTEGER,
                errorMessage TEXT,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_condense_jobs_bookId ON condense_jobs(bookId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_exports (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                format TEXT NOT NULL,
                path TEXT NOT NULL,
                pageCount INTEGER NOT NULL DEFAULT 0,
                createdAtMillis INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_exports_bookId ON book_exports(bookId)")
    }
}
