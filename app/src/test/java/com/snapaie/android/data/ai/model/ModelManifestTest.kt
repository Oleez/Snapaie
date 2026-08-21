package com.snapaie.android.data.ai.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVersionsTest {

    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertTrue(ModelVersions.isNewer("1.10.0", "1.9.0"))
        assertFalse(ModelVersions.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(ModelVersions.isNewer("1.0.0", "1.0.0"))
        assertEquals(0, ModelVersions.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `missing segments are treated as lower`() {
        assertTrue(ModelVersions.isNewer("1.0.1", "1.0"))
        assertFalse(ModelVersions.isNewer("1.0", "1.0.1"))
    }

    @Test
    fun `patch and minor bumps are detected`() {
        assertTrue(ModelVersions.isNewer("1.1.0", "1.0.9"))
        assertTrue(ModelVersions.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `non numeric segments fall back to string ordering without throwing`() {
        ModelVersions.compare("1.0.0-beta", "1.0.0-alpha")
        assertTrue(ModelVersions.isNewer("1.0.0-beta", "1.0.0-alpha"))
    }
}

class ModelManifestValidatorTest {

    private val appVersion = 5

    private fun manifest(
        modelId: String = "example-model",
        version: String = "1.0.0",
        filename: String = "example.litertlm",
        url: String = "https://example.com/example.litertlm",
        size: Long = 1_234L,
        sha: String = "a".repeat(64),
        runtime: String = "litert-lm",
        runtimeVersion: Int = 1,
        minAppVersion: Int = 0,
    ) = ModelManifest(
        modelId = modelId,
        version = version,
        filename = filename,
        downloadUrl = url,
        sizeBytes = size,
        sha256 = sha,
        runtime = runtime,
        runtimeVersion = runtimeVersion,
        minAppVersion = minAppVersion,
    )

    @Test
    fun `complete manifest is accepted`() {
        val result = ModelManifestValidator.validate(manifest(), appVersion)
        assertTrue(result is ModelManifestValidation.Accepted)
        val spec = (result as ModelManifestValidation.Accepted).spec
        assertEquals("example-model", spec.modelId)
        assertEquals("example-model@1.0.0", spec.key)
    }

    @Test
    fun `placeholder url and hash are rejected as malformed`() {
        val result = ModelManifestValidator.validate(
            manifest(url = "PLACEHOLDER", sha = "PLACEHOLDER", size = 0L),
            appVersion,
        )
        assertTrue(result is ModelManifestValidation.Rejected)
        assertEquals(ModelIncompatibility.MALFORMED, (result as ModelManifestValidation.Rejected).reason)
    }

    @Test
    fun `non https url is rejected`() {
        val result = ModelManifestValidator.validate(
            manifest(url = "http://example.com/model.litertlm"),
            appVersion,
        )
        assertTrue(result is ModelManifestValidation.Rejected)
    }

    @Test
    fun `short hash is rejected`() {
        val result = ModelManifestValidator.validate(manifest(sha = "abc123"), appVersion)
        assertTrue(result is ModelManifestValidation.Rejected)
    }

    @Test
    fun `non hex hash is rejected`() {
        val result = ModelManifestValidator.validate(manifest(sha = "z".repeat(64)), appVersion)
        assertTrue(result is ModelManifestValidation.Rejected)
    }

    @Test
    fun `manifest requiring a newer app is rejected with a clear reason`() {
        val result = ModelManifestValidator.validate(manifest(minAppVersion = 99), appVersion)
        assertEquals(
            ModelIncompatibility.APP_TOO_OLD,
            (result as ModelManifestValidation.Rejected).reason,
        )
    }

    @Test
    fun `unknown runtime is rejected`() {
        val result = ModelManifestValidator.validate(manifest(runtime = "some-future-runtime"), appVersion)
        assertEquals(
            ModelIncompatibility.UNSUPPORTED_RUNTIME,
            (result as ModelManifestValidation.Rejected).reason,
        )
    }

    @Test
    fun `future runtime revision is rejected`() {
        val result = ModelManifestValidator.validate(manifest(runtimeVersion = 99), appVersion)
        assertEquals(
            ModelIncompatibility.UNSUPPORTED_RUNTIME,
            (result as ModelManifestValidation.Rejected).reason,
        )
    }

    @Test
    fun `manifest json parses and ignores unknown fields`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val parsed = json.decodeFromString<ModelManifest>(
            """
            {
              "modelId": "example-model",
              "version": "1.2.0",
              "filename": "example.litertlm",
              "downloadUrl": "https://example.com/example.litertlm",
              "sizeBytes": 4096,
              "sha256": "${"b".repeat(64)}",
              "minAppVersion": 1,
              "somethingWeAddLater": true
            }
            """.trimIndent(),
        )
        assertEquals("1.2.0", parsed.version)
        assertEquals(4096L, parsed.sizeBytes)
        // Runtime defaults let older manifests stay valid.
        assertEquals(ModelManifest.DEFAULT_RUNTIME, parsed.runtime)
        assertTrue(ModelManifestValidator.validate(parsed, appVersion) is ModelManifestValidation.Accepted)
    }

    @Test
    fun `hash validation accepts a well formed lowercase digest`() {
        assertTrue(ModelManifestValidator.isValidSha256("0123456789abcdef".repeat(4)))
        assertFalse(ModelManifestValidator.isValidSha256(""))
    }
}
