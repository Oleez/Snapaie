package com.snapaie.android.ui.nav

object Routes {
    const val Books = "books"
    const val Snap = "snap"
    const val Recall = "recall"
    const val Library = "library"
    const val Progress = "progress"

    const val BookDetail = "book/{bookId}"
    const val BookReader = "book/{bookId}/read"
    const val BookExport = "book/{bookId}/export"

    const val ScanRun = "scanRun"
    const val Camera = "camera"
    const val ScanDetail = "scanDetail/{scanId}"
    const val Chat = "chat/{sessionId}"
    const val Writing = "writing"
    const val RecallRapid = "recall/rapid/{topicId}"
    const val RecallSurvival = "recall/survival/{topicId}"
    const val RecallFeynman = "recall/feynman/{topicId}"
    const val RecallVault = "recall/vault"
    const val ReaderReport = "readerReport"
    const val Upgrade = "upgrade"
    const val Settings = "settings"

    fun bookDetail(bookId: Long) = "book/$bookId"
    fun bookReader(bookId: Long) = "book/$bookId/read"
    fun bookExport(bookId: Long) = "book/$bookId/export"

    fun scanDetail(scanId: Long) = "scanDetail/$scanId"
    fun chat(sessionId: Long) = "chat/$sessionId"
    fun recallRapid(topicId: Long) = "recall/rapid/$topicId"
    fun recallSurvival(topicId: Long) = "recall/survival/$topicId"
    fun recallFeynman(topicId: Long) = "recall/feynman/$topicId"
}
