package com.snapaie.android.data.ai.model

import java.io.File

/** Compute backend an artifact targets. */
enum class ModelBackend(val wireName: String) {
    GPU("gpu"),
    CPU("cpu"),
    ;

    companion object {
        /** Tolerant parse: anything we do not recognise is treated as CPU (always safe). */
        fun fromWire(value: String?): ModelBackend =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: CPU
    }
}

/**
 * Decides which backend this device should download weights for.
 *
 * LiteRT-LM's GPU backend needs an OpenCL driver, which is a vendor library rather than
 * part of the platform — `AndroidManifest.xml` already declares it as an optional
 * `<uses-native-library>`. There is no supported API to ask "is OpenCL usable?", and
 * actually dlopen-ing it from Kotlin is unreliable, so we probe the handful of paths
 * every vendor ships it at.
 *
 * A wrong guess is not fatal in either direction: the CPU artifact loads on a GPU device,
 * and [com.snapaie.android.data.ai.ModelSessionManager] retries on CPU when a GPU load
 * fails. This only decides which download we *offer*, which matters because the GPU
 * artifact is ~575 MB smaller.
 */
object DeviceBackends {

    private val OPENCL_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
        "/system/vendor/lib64/egl/libGLES_mali.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib/libOpenCL.so",
    )

    /** True when an OpenCL driver appears to be present. */
    fun supportsGpu(): Boolean = OPENCL_PATHS.any { path ->
        runCatching { File(path).exists() }.getOrDefault(false)
    }

    fun preferred(): ModelBackend = if (supportsGpu()) ModelBackend.GPU else ModelBackend.CPU
}
