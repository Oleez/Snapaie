package com.snapae.android.data

interface AuthGateway {
    suspend fun currentUserId(): String?
}

interface SyncRepository {
    suspend fun syncNow()
}

interface AnalyticsSink {
    suspend fun track(event: String, properties: Map<String, String> = emptyMap())
}

class NoOpAuthGateway : AuthGateway {
    override suspend fun currentUserId(): String? = null
}

class NoOpSyncRepository : SyncRepository {
    override suspend fun syncNow() = Unit
}

class NoOpAnalyticsSink : AnalyticsSink {
    override suspend fun track(event: String, properties: Map<String, String>) = Unit
}
