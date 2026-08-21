package com.snapaie.android.data.ai.model

import android.content.Context
import com.snapaie.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of reading the remote manifest. */
sealed interface ManifestResult {
    /** A valid, compatible manifest. [fromCache] when the network copy was unavailable. */
    data class Available(val spec: ModelSpec, val fromCache: Boolean) : ManifestResult

    /** Parsed, but this build cannot use it. The installed model keeps working. */
    data class Incompatible(val reason: ModelIncompatibility, val message: String) : ManifestResult

    /** Network down, 404, malformed JSON, or no URL configured. */
    data class Unavailable(val message: String) : ManifestResult
}

/** What the UI should offer the user about the model. */
sealed interface ModelUpdateStatus {
    /** No manifest URL configured in this build. */
    data object NotConfigured : ModelUpdateStatus

    /** Nothing installed and a model is available to fetch (first-install flow). */
    data class FirstInstallAvailable(val spec: ModelSpec) : ModelUpdateStatus

    /** Installed model matches the manifest. */
    data class UpToDate(val installed: InstalledModel) : ModelUpdateStatus

    /** A newer compatible model exists. */
    data class UpdateAvailable(val spec: ModelSpec, val installed: InstalledModel) : ModelUpdateStatus

    /** Check failed; [installed] (if any) keeps serving inference. */
    data class CheckFailed(val message: String, val installed: InstalledModel?) : ModelUpdateStatus

    /** Manifest is valid but unusable on this build. */
    data class Incompatible(val message: String, val installed: InstalledModel?) : ModelUpdateStatus

    /** Nothing installed and no manifest reachable. */
    data class Unavailable(val message: String) : ModelUpdateStatus
}

/**
 * Fetches and caches the remote `latest.json`.
 *
 * The manifest is the only channel through which model facts reach the app, which is
 * what lets a new model ship without a Play Store release. Every failure mode is
 * non-fatal: the last good manifest is cached on disk, and if that is missing too the
 * caller simply keeps using whatever is already installed.
 */
class ModelManifestRepository(
    context: Context,
    baseClient: OkHttpClient,
    private val registry: ModelRegistry,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val manifestUrl: String = BuildConfig.MODEL_MANIFEST_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(registry.modelsDir, CACHE_FILE)
    private val metaPrefs =
        context.getSharedPreferences("snapaie_model_manifest", Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = manifestUrl.trim().startsWith("https://", ignoreCase = true)

    val lastCheckedAtMillis: Long get() = metaPrefs.getLong(KEY_LAST_CHECKED, 0L)

    /** True when enough time has passed to justify another network check. */
    fun isCheckDue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - lastCheckedAtMillis >= CHECK_INTERVAL_MS

    /**
     * Reads the manifest, preferring the network and falling back to the cached copy.
     * Never throws.
     */
    suspend fun fetch(force: Boolean = false): ManifestResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext ManifestResult.Unavailable("No model manifest URL is configured in this build.")
        }
        if (!force && !isCheckDue()) {
            // Inside the throttle window: serve the cached manifest without a request.
            cachedResult()?.let { return@withContext it }
        }

        val networkJson = runCatching {
            client.newCall(Request.Builder().url(manifestUrl.trim()).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }.getOrElse { error ->
            // Network failure: fall back to the last good manifest so an offline launch
            // still knows what the current model is.
            return@withContext cachedResult()?.let { cached ->
                if (cached is ManifestResult.Available) cached.copy(fromCache = true) else cached
            } ?: ManifestResult.Unavailable(
                error.message?.let { "Could not reach the model manifest ($it)." }
                    ?: "Could not reach the model manifest.",
            )
        }

        metaPrefs.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply()

        val manifest = runCatching { json.decodeFromString<ModelManifest>(networkJson) }.getOrNull()
            ?: return@withContext cachedResult()
                ?: ManifestResult.Unavailable("The model manifest could not be read.")

        when (val validation = ModelManifestValidator.validate(manifest, appVersionCode)) {
            is ModelManifestValidation.Accepted -> {
                runCatching { cacheFile.writeText(networkJson) }
                ManifestResult.Available(validation.spec, fromCache = false)
            }
            is ModelManifestValidation.Rejected ->
                if (validation.reason == ModelIncompatibility.MALFORMED) {
                    ManifestResult.Unavailable(validation.message)
                } else {
                    ManifestResult.Incompatible(validation.reason, validation.message)
                }
        }
    }

    /** Compares the manifest against what is installed to decide what to offer the user. */
    suspend fun checkForUpdate(force: Boolean = false): ModelUpdateStatus {
        val installed = registry.activeRecord()
        if (!isConfigured) return ModelUpdateStatus.NotConfigured

        return when (val result = fetch(force)) {
            is ManifestResult.Incompatible ->
                ModelUpdateStatus.Incompatible(result.message, installed)

            is ManifestResult.Unavailable ->
                if (installed != null) {
                    ModelUpdateStatus.CheckFailed(result.message, installed)
                } else {
                    ModelUpdateStatus.Unavailable(result.message)
                }

            is ManifestResult.Available -> {
                val spec = result.spec
                when {
                    installed == null -> ModelUpdateStatus.FirstInstallAvailable(spec)
                    installed.modelId != spec.modelId ->
                        // A different model line entirely: treat as an update.
                        ModelUpdateStatus.UpdateAvailable(spec, installed)
                    ModelVersions.isNewer(spec.version, installed.version) ->
                        ModelUpdateStatus.UpdateAvailable(spec, installed)
                    else -> ModelUpdateStatus.UpToDate(installed)
                }
            }
        }
    }

    /** The cached manifest, if one was ever stored and is still compatible. */
    fun cachedSpec(): ModelSpec? = (cachedResult() as? ManifestResult.Available)?.spec

    private fun cachedResult(): ManifestResult? {
        if (!cacheFile.isFile) return null
        val manifest = runCatching {
            json.decodeFromString<ModelManifest>(cacheFile.readText())
        }.getOrNull() ?: return null
        return when (val validation = ModelManifestValidator.validate(manifest, appVersionCode)) {
            is ModelManifestValidation.Accepted ->
                ManifestResult.Available(validation.spec, fromCache = true)
            is ModelManifestValidation.Rejected ->
                ManifestResult.Incompatible(validation.reason, validation.message)
        }
    }

    private companion object {
        const val CACHE_FILE = "latest-manifest.json"
        const val KEY_LAST_CHECKED = "last_checked_at"
        val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)
    }
}
