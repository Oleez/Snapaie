package com.snapaie.android.data.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One backend-specific build of the same model version.
 *
 * The GPU and CPU artifacts of a model differ in size (the GPU build of Gemma 4 E2B is
 * ~575 MB smaller), so offering the right one is worth a device probe. Optional: a
 * manifest with no [ModelManifest.variants] still works via the top-level fields.
 */
@Serializable
data class ModelVariant(
    val backend: String = ModelBackend.CPU.wireName,
    val filename: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
)

/**
 * The remote `latest.json` describing the current model artifact.
 *
 * This is the only place model facts live. Nothing about the artifact — URL, size,
 * hash, filename, version — is compiled into the app, so a new model can ship without
 * a Play Store release. A copy shipped in `assets/model/default-manifest.json` is used
 * only when no remote manifest is configured or reachable.
 *
 * Unknown fields are ignored so the manifest can gain fields without breaking old apps.
 */
@Serializable
data class ModelManifest(
    val modelId: String = "",
    val version: String = "",
    val filename: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    /** Runtime the artifact targets, e.g. the LiteRT-LM family. */
    val runtime: String = DEFAULT_RUNTIME,
    /** Runtime contract revision; the app declares the range it can load. */
    val runtimeVersion: Int = 1,
    /** Lowest app versionCode allowed to use this artifact. */
    val minAppVersion: Int = 0,
    /** Optional human note shown on the update card. */
    @SerialName("releaseNotes") val releaseNotes: String = "",
    /**
     * Optional per-backend builds. When present the app picks the one matching this
     * device; when absent (or when no entry matches) the top-level fields are used.
     */
    val variants: List<ModelVariant> = emptyList(),
) {
    companion object {
        const val DEFAULT_RUNTIME = "litert-lm"
    }
}

/** Why a manifest cannot be used on this device/build. */
enum class ModelIncompatibility {
    NONE,
    MALFORMED,
    APP_TOO_OLD,
    UNSUPPORTED_RUNTIME,
}

/**
 * A validated manifest entry, ready to download. Produced only after the manifest has
 * been checked for completeness and compatibility.
 */
data class ModelSpec(
    val modelId: String,
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val runtime: String,
    val runtimeVersion: Int,
    val releaseNotes: String = "",
    /** Backend this artifact was selected for. */
    val backend: ModelBackend = ModelBackend.CPU,
) {
    val expectedSha256: String get() = sha256.trim().lowercase()

    /**
     * Stable key used for directories and registry lookups. Deliberately excludes the
     * backend: only one variant is ever installed per device, and keeping the key stable
     * means a device that switches backend replaces rather than duplicates 2 GB.
     */
    val key: String get() = "$modelId@$version"

    /**
     * What a person is shown this artifact called.
     *
     * Deliberately not the model id and version. Nobody downloading this is choosing
     * between checkpoints, and a notification reading "Downloading gemma-4-e2b-it 1.0.0"
     * tells them nothing they can act on. The real identifiers stay on [modelId] and
     * [version] for the registry, the directory layout and update comparisons.
     */
    val displayName: String get() = "Offline AI"

    companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

/** Validates a raw manifest against this build and turns it into a [ModelSpec]. */
object ModelManifestValidator {

    /** Runtime families this build can actually load. */
    private val supportedRuntimes = setOf(ModelManifest.DEFAULT_RUNTIME)

    /** Inclusive range of runtime contract revisions this build understands. */
    private const val MIN_RUNTIME_VERSION = 1
    private const val MAX_RUNTIME_VERSION = 1

    fun validate(
        manifest: ModelManifest,
        appVersionCode: Int,
        preferredBackend: ModelBackend = DeviceBackends.preferred(),
    ): ModelManifestValidation {
        // Resolve the backend variant first, so the completeness checks below run against
        // the artifact we would actually download.
        val chosen = chooseVariant(manifest, preferredBackend)

        val missing = buildList {
            if (manifest.modelId.isBlank()) add("modelId")
            if (manifest.version.isBlank()) add("version")
            if (chosen.filename.isBlank()) add("filename")
            if (!chosen.downloadUrl.startsWith("https://", ignoreCase = true)) add("downloadUrl (https)")
            if (chosen.sizeBytes <= 0L) add("sizeBytes")
            if (!isValidSha256(chosen.sha256)) add("sha256")
        }
        if (missing.isNotEmpty()) {
            return ModelManifestValidation.Rejected(
                ModelIncompatibility.MALFORMED,
                "Manifest is incomplete. Missing or invalid: ${missing.joinToString(", ")}.",
            )
        }
        if (appVersionCode < manifest.minAppVersion) {
            return ModelManifestValidation.Rejected(
                ModelIncompatibility.APP_TOO_OLD,
                "This model needs a newer version of the app. Update Snapaie to continue.",
            )
        }
        if (manifest.runtime.lowercase() !in supportedRuntimes ||
            manifest.runtimeVersion !in MIN_RUNTIME_VERSION..MAX_RUNTIME_VERSION
        ) {
            return ModelManifestValidation.Rejected(
                ModelIncompatibility.UNSUPPORTED_RUNTIME,
                "This model targets a runtime this app build cannot load.",
            )
        }
        return ModelManifestValidation.Accepted(
            ModelSpec(
                modelId = manifest.modelId.trim(),
                version = manifest.version.trim(),
                fileName = chosen.filename.trim(),
                downloadUrl = chosen.downloadUrl.trim(),
                expectedBytes = chosen.sizeBytes,
                sha256 = chosen.sha256.trim(),
                runtime = manifest.runtime.trim(),
                runtimeVersion = manifest.runtimeVersion,
                releaseNotes = manifest.releaseNotes.trim(),
                backend = ModelBackend.fromWire(chosen.backend),
            ),
        )
    }

    /**
     * The variant for [preferredBackend], else any other listed variant, else the
     * top-level fields. Falling back to *some* variant matters: a GPU-only manifest must
     * still be installable on a device whose OpenCL probe came back negative, because the
     * probe is a heuristic and the engine retries on CPU anyway.
     */
    private fun chooseVariant(manifest: ModelManifest, preferredBackend: ModelBackend): ModelVariant {
        val topLevel = ModelVariant(
            backend = preferredBackend.wireName,
            filename = manifest.filename,
            downloadUrl = manifest.downloadUrl,
            sizeBytes = manifest.sizeBytes,
            sha256 = manifest.sha256,
        )
        if (manifest.variants.isEmpty()) return topLevel
        return manifest.variants.firstOrNull {
            ModelBackend.fromWire(it.backend) == preferredBackend
        } ?: manifest.variants.first()
    }

    fun isValidSha256(value: String): Boolean {
        val hash = value.trim().lowercase()
        return hash.length == ModelSpec.SHA256_HEX_LENGTH &&
            hash.all { it.isDigit() || it in 'a'..'f' }
    }
}

sealed interface ModelManifestValidation {
    data class Accepted(val spec: ModelSpec) : ModelManifestValidation
    data class Rejected(val reason: ModelIncompatibility, val message: String) : ModelManifestValidation
}

/**
 * Dotted version comparison (1.10.0 sorts above 1.9.0). Non-numeric segments fall back
 * to a case-insensitive string comparison so odd version strings still order stably
 * instead of throwing.
 */
object ModelVersions {

    fun compare(left: String, right: String): Int {
        val a = left.trim().split('.', '-')
        val b = right.trim().split('.', '-')
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrNull(i).orEmpty()
            val y = b.getOrNull(i).orEmpty()
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val result = when {
                xn != null && yn != null -> xn.compareTo(yn)
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (result != 0) return result
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
