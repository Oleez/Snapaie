package com.snapaie.android.ui.nav

object Routes {
    const val Snap = "snap"
    const val Recall = "recall"
    const val Library = "library"
    const val Progress = "progress"

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

    fun scanDetail(scanId: Long) = "scanDetail/$scanId"
    fun chat(sessionId: Long) = "chat/$sessionId"
    fun recallRapid(topicId: Long) = "recall/rapid/$topicId"
    fun recallSurvival(topicId: Long) = "recall/survival/$topicId"
    fun recallFeynman(topicId: Long) = "recall/feynman/$topicId"
}
