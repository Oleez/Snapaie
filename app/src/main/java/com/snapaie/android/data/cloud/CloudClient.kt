package com.snapaie.android.data.cloud

import android.content.Context
import com.snapaie.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A passage on its way to be condensed, and what came back. */
@Serializable
data class CloudPassage(val id: Int, val text: String, val targetWords: Int)

@Serializable
data class CloudCondensed(val id: Int, val prose: String)

/** What a cloud call produced, or why it did not. */
sealed interface CloudResult<out T> {
    data class Ok<T>(val value: T, val pagesLeft: Int) : CloudResult<T>

    /** Out of pages. Not an error — the caller falls back and says so. */
    data object OutOfCredit : CloudResult<Nothing>

    /** Anything else: no network, no domain configured, server trouble. */
    data class Failed(val message: String) : CloudResult<Nothing>
}

/**
 * Talks to Cloud Read.
 *
 * Exists because a phone cannot condense a five-hundred-page book in a time anyone will
 * wait for. That is not a shortcoming of the offline model; it is two hundred thousand
 * tokens of reading, and no arrangement of the work changes that arithmetic on a handset.
 *
 * Every failure here is recoverable by design. There is always an offline path — slower to
 * read, weaker prose, but it works — so this returns a result rather than throwing, and the
 * caller decides. A cloud outage should cost quality, never the feature.
 */
class CloudClient(
    private val context: Context,
    baseClient: OkHttpClient,
    private val baseUrl: String = BuildConfig.CLOUD_API_BASE_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // A batch of passages is real work at the far end. Generous, but finite.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** False when this build has no backend configured; the UI hides cloud entirely. */
    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://", ignoreCase = true)

    @Volatile
    private var token: String? = null

    @Serializable
    private data class SessionRequest(val productId: String, val purchaseToken: String)

    @Serializable
    private data class SessionResponse(val token: String, val plan: String, val pagesLeft: Int)

    @Serializable
    private data class BatchRequest(
        val passages: List<CloudPassage>,
        val ledger: String,
        val style: String,
        val pages: Int,
    )

    @Serializable
    private data class BatchResponse(
        val passages: List<CloudCondensed>,
        @SerialName("pagesLeft") val pagesLeft: Int,
    )

    /**
     * Exchanges whatever entitles this user for a short-lived token.
     *
     * The device id stands in for an account. It is checked and counted server-side,
     * because a balance the phone keeps is a balance the phone can edit.
     */
    suspend fun openSession(
        productId: String = FREE_TIER_PRODUCT,
        purchaseToken: String = DeviceIdentity.of(context),
    ): CloudResult<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext CloudResult.Failed("Cloud Read is not set up in this build.")
        val body = json.encodeToString(SessionRequest.serializer(), SessionRequest(productId, purchaseToken))
        when (val response = post("/v1/auth/session", body, authed = false)) {
            is Raw.Ok -> runCatching {
                val parsed = json.decodeFromString(SessionResponse.serializer(), response.body)
                token = parsed.token
                CloudResult.Ok(Unit, parsed.pagesLeft)
            }.getOrElse { CloudResult.Failed("Cloud Read did not answer properly.") }
            is Raw.NoCredit -> CloudResult.OutOfCredit
            is Raw.Error -> CloudResult.Failed(response.message)
        }
    }

    /** Condenses many passages at once. [pages] is what it costs, in source pages. */
    suspend fun condenseBatch(
        passages: List<CloudPassage>,
        ledger: String,
        style: String,
        pages: Int,
    ): CloudResult<List<CloudCondensed>> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext CloudResult.Failed("Cloud Read is not set up in this build.")
        if (token == null) {
            when (val opened = openSession()) {
                is CloudResult.OutOfCredit -> return@withContext CloudResult.OutOfCredit
                is CloudResult.Failed -> return@withContext opened
                else -> Unit
            }
        }
        val body = json.encodeToString(
            BatchRequest.serializer(),
            BatchRequest(passages, ledger, style, pages),
        )
        when (val response = post("/v1/condense/batch", body, authed = true)) {
            is Raw.Ok -> runCatching {
                val parsed = json.decodeFromString(BatchResponse.serializer(), response.body)
                CloudResult.Ok(parsed.passages, parsed.pagesLeft)
            }.getOrElse { CloudResult.Failed("Cloud Read did not answer properly.") }
            is Raw.NoCredit -> CloudResult.OutOfCredit
            is Raw.Error -> CloudResult.Failed(response.message)
        }
    }

    private sealed interface Raw {
        data class Ok(val body: String) : Raw
        data object NoCredit : Raw
        data class Error(val message: String) : Raw
    }

    private fun post(path: String, body: String, authed: Boolean): Raw {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toRequestBody(JSON_MEDIA))
            .apply { if (authed) token?.let { header("Authorization", "Bearer $it") } }
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Raw.Ok(response.body?.string().orEmpty())
                    // 402 is the honest "you have run out", and the only failure the UI
                    // should treat as a decision rather than a fault.
                    response.code == HTTP_PAYMENT_REQUIRED -> Raw.NoCredit
                    response.code == HTTP_UNAUTHORIZED -> {
                        // The token expired mid-book. Drop it so the next call re-opens.
                        token = null
                        Raw.Error("Cloud Read needs to reconnect.")
                    }
                    else -> Raw.Error("Cloud Read is unavailable right now.")
                }
            }
        }.getOrElse { Raw.Error("Could not reach Cloud Read.") }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val HTTP_PAYMENT_REQUIRED = 402
        const val HTTP_UNAUTHORIZED = 401

        /** The allowance everybody gets without paying, granted against the device id. */
        const val FREE_TIER_PRODUCT = "snapaie_free_monthly"
    }
}
