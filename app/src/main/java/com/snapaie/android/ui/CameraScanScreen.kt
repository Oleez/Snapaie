package com.snapaie.android.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import kotlin.coroutines.resumeWith
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScanRoute(onImageCaptured: (Uri) -> Unit, onDismiss: () -> Unit) {
    val permissions = rememberMultiplePermissionsState(listOf(Manifest.permission.CAMERA))

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (!permissions.allPermissionsGranted) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Camera access lets you snap physical book pages for instant clarity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
                        Text("Allow camera")
                    }
                    Button(onClick = onDismiss) { Text("Back") }
                }
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                LiveCamera(onCapture = onImageCaptured)
            }
        }
    }
}

@Composable
private fun LiveCamera(onCapture: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(previewView, lifecycleOwner) {
        val cameraProvider = suspendGetCameraProvider(context)
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder().build()
        imageCapture = capture
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            )
        }
        Button(
            onClick = {
                val capture = imageCapture ?: return@Button
                val file = File(context.cacheDir, "snapaie_page_${System.currentTimeMillis()}.jpg")
                val output = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    output,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            onCapture(uri)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            failure = exception.message ?: "Capture failed"
                        }
                    },
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Text("Capture page", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private suspend fun suspendGetCameraProvider(context: Context): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resumeWith(Result.success(future.get()))
            } catch (t: Throwable) {
                cont.resumeWith(Result.failure(t))
            }
        }, ContextCompat.getMainExecutor(context))
    }
