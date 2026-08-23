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

/** Where a manifest came from, in descending order of authority. */
enum class ManifestSource {
    /** Freshly fetched from the configured remote URL. */
    NETWORK,

    /** The last good remote manifest, replayed from disk. */
    CACHE,

    /** The copy compiled into the APK at `assets/model/default-manifest.json`. */
    BUNDLED,
}

/** Outcome of reading the manifest. */
sealed interface ManifestResult {
    /** A valid, compatible manifest. */
    data class Available(val spec: ModelSpec, val source: ManifestSource) : ManifestResult {
        val fromCache: Boolean get() = source != ManifestSource.NETWORK
    }

    /** Parsed, but this build cannot use it. The installed model keeps working. */
    data class Incompatible(val reason: ModelIncompatibility, val message: String) : ManifestResult

    /** Network down, 404, malformed JSON, and no usable fallback. */
    data class Unavailable(val message: String) : ManifestResult
}

/** What the UI should offer the user about the model. */
sealed interface ModelUpdateStatus {
    /** No manifest URL configured and no usable bundled default. */
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
 * Resolves the model manifest from, in order: the configured remote URL, the last good
 * remote copy cached on disk, and finally the manifest compiled into the APK.
 *
 * The remote manifest is still the channel that lets a new model ship without a Play
 * Store release, and it always wins when reachable. The bundled default exists so that
 * a build with no hosting configured is not silently AI-less: before it existed, an empty
 * `snapaie.model.manifest.url` meant every scan fell back to a heuristic draft with no
 * way for the user to fix it.
 *
 * Every failure mode is non-fatal — the caller keeps using whatever is already installed.
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

    private val assets = context.applicationContext.assets
    private val cacheFile = File(registry.modelsDir, CACHE_FILE)
    private val metaPrefs =
        context.getSharedPreferences("snapaie_model_manifest", Context.MODE_PRIVATE)

    /** True when this build points at a hosted manifest. */
    val hasRemoteManifest: Boolean
        get() = manifestUrl.trim().startsWith("https://", ignoreCase = true)

    /** True when the app knows about any model at all — remote, cached, or bundled. */
    val isConfigured: Boolean
        get() = hasRemoteManifest || bundledResult() is ManifestResult.Available

    val lastCheckedAtMillis: Long get() = metaPrefs.getLong(KEY_LAST_CHECKED, 0L)

    /** True when enough time has passed to justify another network check. */
    fun isCheckDue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - lastCheckedAtMillis >= CHECK_INTERVAL_MS

    /**
     * Reads the manifest, preferring the network and degrading through the disk cache to
     * the bundled default. Never throws.
     */
    suspend fun fetch(force: Boolean = false): ManifestResult = withContext(Dispatchers.IO) {
        if (!hasRemoteManifest) {
            return@withContext cachedResult() ?: bundledResult() ?: ManifestResult.Unavailable(
                "No model manifest is configured in this build.",
            )
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
            // Network failure: fall back to the last good manifest, then to the bundled
            // one, so an offline first launch still knows what it can download later.
            return@withContext cachedResult() ?: bundledResult() ?: ManifestResult.Unavailable(
                error.message?.let { "Could not reach the model manifest ($it)." }
                    ?: "Could not reach the model manifest.",
            )
        }

        metaPrefs.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply()

        val manifest = runCatching { json.decodeFromString<ModelManifest>(networkJson) }.getOrNull()
            ?: return@withContext cachedResult() ?: bundledResult()
                ?: ManifestResult.Unavailable("The model manifest could not be read.")

        when (val validation = ModelManifestValidator.validate(manifest, appVersionCode)) {
            is ModelManifestValidation.Accepted -> {
                runCatching { cacheFile.writeText(networkJson) }
                ManifestResult.Available(validation.spec, ManifestSource.NETWORK)
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

        return when (val result = fetch(force)) {
            is ManifestResult.Incompatible ->
                ModelUpdateStatus.Incompatible(result.message, installed)

            is ManifestResult.Unavailable ->
                when {
                    installed != null -> ModelUpdateStatus.CheckFailed(result.message, installed)
                    !isConfigured -> ModelUpdateStatus.NotConfigured
                    else -> ModelUpdateStatus.Unavailable(result.message)
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

    /** The best manifest available without touching the network. */
    fun cachedSpec(): ModelSpec? =
        ((cachedResult() ?: bundledResult()) as? ManifestResult.Available)?.spec

    private fun cachedResult(): ManifestResult? {
        if (!cacheFile.isFile) return null
        return parse(runCatching { cacheFile.readText() }.getOrNull(), ManifestSource.CACHE)
    }

    private fun bundledResult(): ManifestResult? = parse(
        runCatching { assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }.getOrNull(),
        ManifestSource.BUNDLED,
    )

    private fun parse(raw: String?, source: ManifestSource): ManifestResult? {
        val manifest = runCatching {
            json.decodeFromString<ModelManifest>(raw ?: return null)
        }.getOrNull() ?: return null
        return when (val validation = ModelManifestValidator.validate(manifest, appVersionCode)) {
            is ModelManifestValidation.Accepted ->
                ManifestResult.Available(validation.spec, source)
            is ModelManifestValidation.Rejected ->
                ManifestResult.Incompatible(validation.reason, validation.message)
        }
    }

    private companion object {
        const val CACHE_FILE = "latest-manifest.json"
        const val BUNDLED_ASSET = "model/default-manifest.json"
        const val KEY_LAST_CHECKED = "last_checked_at"
        val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)
    }
}
