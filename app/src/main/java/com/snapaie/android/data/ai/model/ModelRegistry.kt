package com.snapaie.android.data.ai.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One model artifact that passed SHA-256 verification and was installed. */
@Serializable
data class InstalledModel(
    val modelId: String,
    val version: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val runtime: String,
    val runtimeVersion: Int,
    val verifiedAtMillis: Long,
    /** True once the engine has actually loaded this artifact at least once. */
    val loadVerified: Boolean = false,
    /** Backend this artifact was downloaded for, per the manifest variant. */
    val backend: String = ModelBackend.CPU.wireName,
    /**
     * Backend the engine actually managed to load it with. Null until a load succeeds.
     * Set when [backend] turned out to be wrong (the OpenCL probe is a heuristic), so the
     * next load starts with the one that works instead of retrying the failure.
     */
    val loadedBackend: String? = null,
)

@Serializable
data class ModelRegistrySnapshot(
    val activeKey: String? = null,
    val installed: List<InstalledModel> = emptyList(),
)

/**
 * Tracks which model artifacts are installed, which one is active, and which have been
 * proven loadable.
 *
 * Artifacts live at `models/<modelId>/<version>/<filename>`, so a new version can be
 * downloaded while the current one keeps serving inference. The registry file records
 * the active pointer, which is what makes a safe swap (and rollback) possible:
 *
 *  - download + verify -> [markInstalled] (installed, not yet active)
 *  - engine loads it   -> [promoteToActive] (active, older versions pruned)
 *  - engine fails      -> [rejectInstall] (artifact deleted, previous active untouched)
 */
class ModelRegistry(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }
    private val registryFile = File(modelsDir, REGISTRY_FILE)

    private val _snapshot = MutableStateFlow(ModelRegistrySnapshot())
    val snapshot: StateFlow<ModelRegistrySnapshot> = _snapshot.asStateFlow()

    init {
        _snapshot.value = readSnapshot()
    }

    // region Paths

    fun modelDir(modelId: String, version: String): File =
        File(File(modelsDir, sanitize(modelId)), sanitize(version))

    fun modelFile(spec: ModelSpec): File =
        File(modelDir(spec.modelId, spec.version), spec.fileName)

    fun modelFile(record: InstalledModel): File =
        File(modelDir(record.modelId, record.version), record.fileName)

    fun partFile(spec: ModelSpec): File =
        File(modelDir(spec.modelId, spec.version), spec.fileName + PART_SUFFIX)

    /** Bytes already fetched for a resumable download. */
    fun partialBytes(spec: ModelSpec): Long =
        partFile(spec).let { if (it.isFile) it.length() else 0L }

    fun ensureDirFor(spec: ModelSpec) {
        modelDir(spec.modelId, spec.version).mkdirs()
    }

    // endregion

    // region Queries

    fun recordFor(modelId: String, version: String): InstalledModel? =
        _snapshot.value.installed.firstOrNull { it.modelId == modelId && it.version == version }

    /**
     * The record the engine should load: the active pointer when it is still valid,
     * otherwise the newest installed artifact that is present on disk.
     */
    fun activeRecord(): InstalledModel? {
        val snap = _snapshot.value
        val active = snap.activeKey
            ?.let { key -> snap.installed.firstOrNull { keyOf(it) == key } }
            ?.takeIf { filePresent(it) }
        if (active != null) return active
        return snap.installed
            .filter { filePresent(it) }
            .maxWithOrNull { a, b -> ModelVersions.compare(a.version, b.version) }
    }

    /** Installed, on disk, and byte-for-byte the size we verified. */
    fun isInstalled(spec: ModelSpec): Boolean {
        val record = recordFor(spec.modelId, spec.version) ?: return false
        return record.sha256.equals(spec.expectedSha256, ignoreCase = true) &&
            record.sizeBytes == spec.expectedBytes &&
            filePresent(record)
    }

    fun isActive(spec: ModelSpec): Boolean {
        val active = activeRecord() ?: return false
        return active.modelId == spec.modelId && active.version == spec.version
    }

    private fun filePresent(record: InstalledModel): Boolean {
        val file = modelFile(record)
        return file.isFile && file.length() == record.sizeBytes
    }

    // endregion

    // region Mutations

    /** Records a verified artifact. It is installed but not yet active. */
    fun markInstalled(spec: ModelSpec) {
        update { snap ->
            val record = InstalledModel(
                modelId = spec.modelId,
                version = spec.version,
                fileName = spec.fileName,
                sha256 = spec.expectedSha256,
                sizeBytes = spec.expectedBytes,
                runtime = spec.runtime,
                runtimeVersion = spec.runtimeVersion,
                verifiedAtMillis = System.currentTimeMillis(),
                loadVerified = false,
                backend = spec.backend.wireName,
            )
            snap.copy(
                installed = snap.installed
                    .filterNot { it.modelId == spec.modelId && it.version == spec.version } + record,
            )
        }
    }

    /**
     * Marks the artifact as successfully loaded, makes it active, and only then removes
     * older versions of the same model.
     */
    fun promoteToActive(spec: ModelSpec) = promoteToActive(spec.modelId, spec.version)

    fun promoteToActive(modelId: String, version: String) {
        update { snap ->
            val promoted = snap.installed.map {
                if (it.modelId == modelId && it.version == version) {
                    it.copy(loadVerified = true)
                } else {
                    it
                }
            }
            snap.copy(activeKey = "$modelId@$version", installed = promoted)
        }
        pruneOtherVersions(modelId, keepVersion = version)
    }

    /**
     * Rollback: the artifact could not be loaded. Delete it and leave the previous
     * active model exactly as it was.
     */
    fun rejectInstall(spec: ModelSpec) = rejectInstall(spec.modelId, spec.version)

    fun rejectInstall(modelId: String, version: String) {
        deleteArtifact(modelId, version)
        update { snap ->
            val remaining = snap.installed
                .filterNot { it.modelId == modelId && it.version == version }
            val activeStillValid = snap.activeKey != null &&
                remaining.any { keyOf(it) == snap.activeKey }
            snap.copy(
                activeKey = if (activeStillValid) snap.activeKey else null,
                installed = remaining,
            )
        }
    }

    /**
     * Records the backend the engine actually loaded this artifact with, so a device
     * whose OpenCL probe guessed wrong does not pay the failed-GPU-load cost on every
     * cold start.
     */
    fun recordLoadedBackend(modelId: String, version: String, backend: ModelBackend) {
        update { snap ->
            snap.copy(
                installed = snap.installed.map {
                    if (it.modelId == modelId && it.version == version) {
                        it.copy(loadedBackend = backend.wireName)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Drops every other version of [modelId] once [keepVersion] is proven good. */
    fun pruneOtherVersions(modelId: String, keepVersion: String) {
        val stale = _snapshot.value.installed
            .filter { it.modelId == modelId && it.version != keepVersion }
        if (stale.isEmpty()) return
        stale.forEach { deleteArtifact(it.modelId, it.version) }
        update { snap ->
            snap.copy(
                installed = snap.installed
                    .filterNot { it.modelId == modelId && it.version != keepVersion },
            )
        }
    }

    fun clearPartial(spec: ModelSpec) {
        partFile(spec).delete()
    }

    /** Wipes every downloaded artifact and the registry (factory reset / free space). */
    fun clearAll() {
        modelsDir.listFiles()?.forEach { it.deleteRecursively() }
        modelsDir.mkdirs()
        _snapshot.value = ModelRegistrySnapshot()
        persist(_snapshot.value)
    }

    private fun deleteArtifact(modelId: String, version: String) {
        modelDir(modelId, version).deleteRecursively()
    }

    private fun update(transform: (ModelRegistrySnapshot) -> ModelRegistrySnapshot) {
        synchronized(this) {
            val next = transform(_snapshot.value)
            _snapshot.value = next
            persist(next)
        }
    }

    // endregion

    fun availableBytes(): Long = runCatching { StatFs(modelsDir.absolutePath).availableBytes }
        .getOrDefault(0L)

    private fun readSnapshot(): ModelRegistrySnapshot {
        if (!registryFile.isFile) return ModelRegistrySnapshot()
        val parsed = runCatching {
            json.decodeFromString<ModelRegistrySnapshot>(registryFile.readText())
        }.getOrNull() ?: return ModelRegistrySnapshot()
        // Drop records whose files vanished (app data cleared, manual deletion).
        val present = parsed.installed.filter { filePresent(it) }
        val activeValid = parsed.activeKey?.takeIf { key -> present.any { keyOf(it) == key } }
        return ModelRegistrySnapshot(activeKey = activeValid, installed = present)
    }

    private fun persist(snapshot: ModelRegistrySnapshot) {
        runCatching {
            modelsDir.mkdirs()
            val temp = File(modelsDir, "$REGISTRY_FILE.tmp")
            temp.writeText(json.encodeToString(snapshot))
            if (!temp.renameTo(registryFile)) {
                registryFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun keyOf(record: InstalledModel): String = "${record.modelId}@${record.version}"

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }

    private companion object {
        const val REGISTRY_FILE = "registry.json"
        const val PART_SUFFIX = ".part"
    }
}
