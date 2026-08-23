package com.snapaie.android.data.ai.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest shipped in the APK has to validate on a real device, or the app silently
 * has no AI and no way for the user to fix it. That is exactly the failure this file is
 * here to catch, so it reads the actual asset rather than a fixture.
 */
class BundledManifestTest {

    private val asset = File("src/main/assets/model/default-manifest.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun manifest(): ModelManifest {
        assertTrue("bundled manifest is missing at ${asset.absolutePath}", asset.isFile)
        return json.decodeFromString(asset.readText())
    }

    @Test
    fun `the bundled manifest validates for both backends at the shipping version code`() {
        val manifest = manifest()
        ModelBackend.entries.forEach { backend ->
            val result = ModelManifestValidator.validate(manifest, APP_VERSION_CODE, backend)
            assertTrue(
                "bundled manifest rejected for $backend: $result",
                result is ModelManifestValidation.Accepted,
            )
            val spec = (result as ModelManifestValidation.Accepted).spec
            assertTrue(spec.downloadUrl.startsWith("https://"))
            assertEquals(64, spec.expectedSha256.length)
            assertTrue(spec.expectedBytes > 1_000_000_000L)
        }
    }

    @Test
    fun `minAppVersion cannot exceed the shipping version code`() {
        // A manifest that demands a newer app than the one it ships inside is unusable on
        // every device it reaches.
        assertTrue(
            "minAppVersion ${manifest().minAppVersion} > versionCode $APP_VERSION_CODE",
            manifest().minAppVersion <= APP_VERSION_CODE,
        )
    }

    @Test
    fun `each backend resolves to a different artifact`() {
        val gpu = ModelManifestValidator.validate(manifest(), APP_VERSION_CODE, ModelBackend.GPU)
        val cpu = ModelManifestValidator.validate(manifest(), APP_VERSION_CODE, ModelBackend.CPU)
        val gpuSpec = (gpu as ModelManifestValidation.Accepted).spec
        val cpuSpec = (cpu as ModelManifestValidation.Accepted).spec
        assertEquals(ModelBackend.GPU, gpuSpec.backend)
        assertEquals(ModelBackend.CPU, cpuSpec.backend)
        assertTrue(gpuSpec.fileName != cpuSpec.fileName)
    }

    private companion object {
        /** Must track `versionCode` in app/build.gradle.kts. */
        const val APP_VERSION_CODE = 2
    }
}
