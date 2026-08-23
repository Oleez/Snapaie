package com.snapaie.android.data.ai.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend variant selection. The GPU build of a model is materially smaller than the CPU
 * build, so picking the right one is worth doing — but picking *wrongly* must never be
 * fatal, because the OpenCL probe behind [DeviceBackends] is a heuristic.
 */
class ModelVariantSelectionTest {

    private val appVersion = 5
    private val gpuHash = "a".repeat(64)
    private val cpuHash = "b".repeat(64)
    private val topHash = "c".repeat(64)

    private fun manifestWithVariants() = ModelManifest(
        modelId = "gemma-4-e2b-it",
        version = "1.0.0",
        filename = "top.litertlm",
        downloadUrl = "https://example.com/top.litertlm",
        sizeBytes = 3_000L,
        sha256 = topHash,
        minAppVersion = 1,
        variants = listOf(
            ModelVariant("gpu", "gpu.litertlm", "https://example.com/gpu.litertlm", 2_000L, gpuHash),
            ModelVariant("cpu", "cpu.litertlm", "https://example.com/cpu.litertlm", 2_500L, cpuHash),
        ),
    )

    private fun accept(
        manifest: ModelManifest,
        backend: ModelBackend,
    ): ModelSpec {
        val result = ModelManifestValidator.validate(manifest, appVersion, backend)
        assertTrue("expected Accepted but got $result", result is ModelManifestValidation.Accepted)
        return (result as ModelManifestValidation.Accepted).spec
    }

    @Test
    fun `gpu device gets the gpu artifact`() {
        val spec = accept(manifestWithVariants(), ModelBackend.GPU)
        assertEquals("gpu.litertlm", spec.fileName)
        assertEquals(2_000L, spec.expectedBytes)
        assertEquals(gpuHash, spec.expectedSha256)
        assertEquals(ModelBackend.GPU, spec.backend)
    }

    @Test
    fun `cpu device gets the cpu artifact`() {
        val spec = accept(manifestWithVariants(), ModelBackend.CPU)
        assertEquals("cpu.litertlm", spec.fileName)
        assertEquals(2_500L, spec.expectedBytes)
        assertEquals(ModelBackend.CPU, spec.backend)
    }

    @Test
    fun `a manifest without variants still resolves through the top level fields`() {
        val spec = accept(manifestWithVariants().copy(variants = emptyList()), ModelBackend.GPU)
        assertEquals("top.litertlm", spec.fileName)
        assertEquals(3_000L, spec.expectedBytes)
        assertEquals(topHash, spec.expectedSha256)
    }

    @Test
    fun `a gpu only manifest is still installable on a device that probed as cpu`() {
        // The probe is a heuristic and the engine retries on CPU, so refusing to offer
        // any download here would strand the user with no AI at all.
        val gpuOnly = manifestWithVariants().copy(
            variants = manifestWithVariants().variants.filter { it.backend == "gpu" },
        )
        val spec = accept(gpuOnly, ModelBackend.CPU)
        assertEquals("gpu.litertlm", spec.fileName)
    }

    @Test
    fun `variant fields are what get completeness checked not the top level ones`() {
        val brokenVariant = manifestWithVariants().copy(
            variants = listOf(ModelVariant("gpu", "gpu.litertlm", "http://insecure/gpu", 2_000L, gpuHash)),
        )
        val result = ModelManifestValidator.validate(brokenVariant, appVersion, ModelBackend.GPU)
        assertTrue(result is ModelManifestValidation.Rejected)
        assertEquals(
            ModelIncompatibility.MALFORMED,
            (result as ModelManifestValidation.Rejected).reason,
        )
    }

    @Test
    fun `backend wire names round trip and unknown values fall back to cpu`() {
        assertEquals(ModelBackend.GPU, ModelBackend.fromWire("gpu"))
        assertEquals(ModelBackend.GPU, ModelBackend.fromWire("GPU"))
        assertEquals(ModelBackend.CPU, ModelBackend.fromWire("npu"))
        assertEquals(ModelBackend.CPU, ModelBackend.fromWire(null))
    }

    @Test
    fun `a manifest carrying variants is still readable by the tolerant parser`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val parsed = json.decodeFromString<ModelManifest>(
            """
            {
              "modelId": "gemma-4-e2b-it",
              "version": "1.0.0",
              "filename": "top.litertlm",
              "downloadUrl": "https://example.com/top.litertlm",
              "sizeBytes": 3000,
              "sha256": "$topHash",
              "minAppVersion": 1,
              "variants": [
                {
                  "backend": "gpu",
                  "filename": "gpu.litertlm",
                  "downloadUrl": "https://example.com/gpu.litertlm",
                  "sizeBytes": 2000,
                  "sha256": "$gpuHash",
                  "futureField": 1
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, parsed.variants.size)
        assertEquals("gpu.litertlm", parsed.variants.first().filename)
    }
}
