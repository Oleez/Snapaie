package com.snapaie.android.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.data.ai.ModelUiState
import com.snapaie.android.data.ai.download.ModelDownloadState
import com.snapaie.android.data.ai.download.ModelDownloadStatus
import com.snapaie.android.data.ai.model.ModelUpdateStatus
import com.snapaie.android.ui.scan.formatBytes

/**
 * The one place the offline model can be turned on.
 *
 * It used to live only inside the Snap hub, which stopped working the moment Books became
 * the start destination: anyone who had already finished onboarding landed on a tab with
 * no model UI on it at all and no reason to go looking for one. It is stateless now so
 * every screen that needs it can show the same card.
 */
@Composable
fun ModelSetupCard(
    modelState: ModelUiState,
    licenseAccepted: Boolean,
    onDownload: (Boolean) -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onCheckAgain: () -> Unit,
    onAcceptLicense: () -> Unit,
) {
    val download = modelState.download
    val spec = modelState.downloadableSpec
    var showLicense by remember { mutableStateOf(false) }
    // Defaults to Wi-Fi because two gigabytes over cellular is a bill, not a preference.
    var wifiOnly by remember { mutableStateOf(true) }

    // Nothing to show once a model is installed and no newer one is offered.
    if (modelState.isModelInstalled && !modelState.hasUpdateAvailable && !download.status.isActive) return

    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (modelState.hasUpdateAvailable) "A newer offline model is available" else "Enable full on-device AI",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    spec != null && modelState.hasUpdateAvailable ->
                        "${spec.modelId} ${spec.version} (${formatBytes(spec.expectedBytes)}). Your current model keeps working until the new one is verified."
                    spec != null ->
                        "Download ${spec.modelId} ${spec.version} once (${formatBytes(spec.expectedBytes)}) and every scan runs entirely on this phone — airplane mode included. Until then you get instant offline drafts."
                    else -> modelStatusMessage(modelState.updateStatus)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            spec?.releaseNotes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            modelState.ramWarning?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (spec != null && !download.status.isActive && !modelState.isModelInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
            }

            when {
                // Held by WorkManager until its constraints are met. Rendering this as a
                // spinner made a blocked transfer look identical to a hung one, with no
                // hint that the fix was to join Wi-Fi or turn the toggle off.
                download.isWaitingForNetwork -> {
                    Text(
                        if (download.wifiOnly) {
                            "Waiting for Wi-Fi. It starts on its own once you are connected."
                        } else {
                            "Queued. Starting shortly."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (download.wifiOnly) {
                            Button(onClick = {
                                onCancel()
                                wifiOnly = false
                                onDownload(false)
                            }) { Text("Use mobile data") }
                        }
                        TextButton(onClick = { onCancel() }) { Text("Cancel") }
                    }
                }

                download.status.isActive -> {
                    LinearProgressIndicator(
                        progress = { download.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        downloadProgressLabel(download),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Keep downloading in the background — you can leave the app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPause() }) { Text("Pause") }
                        TextButton(onClick = { onCancel() }) { Text("Cancel") }
                    }
                }

                download.isResumable -> {
                    LinearProgressIndicator(
                        progress = { download.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    download.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${formatBytes(download.downloadedBytes)} of ${formatBytes(download.totalBytes)} saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(wifiOnly) }) { Text("Resume download") }
                        TextButton(onClick = { onCancel() }) { Text("Discard") }
                    }
                }

                spec == null -> {
                    OutlinedButton(onClick = { onCheckAgain() }) { Text("Check again") }
                }

                !licenseAccepted && !showLicense -> {
                    Button(onClick = { showLicense = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download model")
                    }
                }

                !licenseAccepted -> {
                    Text(
                        "This model is provided under its publisher's licence terms, shown at the download source. By downloading you agree to those terms. The model runs only on your device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onAcceptLicense()
                            onDownload(wifiOnly)
                        }) { Text("Agree & download") }
                        TextButton(onClick = { showLicense = false }) { Text("Not now") }
                    }
                }

                else -> {
                    Button(onClick = { onDownload(wifiOnly) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (modelState.hasUpdateAvailable) "Download update" else "Download model")
                    }
                }
            }

            if (download.status == ModelDownloadStatus.FAILED && !download.isResumable) {
                download.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun modelStatusMessage(status: ModelUpdateStatus): String = when (status) {
    is ModelUpdateStatus.NotConfigured ->
        "Offline AI is not configured in this build yet. Scans use instant offline drafts."
    is ModelUpdateStatus.CheckFailed ->
        "${status.message} Your installed model keeps working."
    is ModelUpdateStatus.Unavailable -> status.message
    is ModelUpdateStatus.Incompatible -> status.message
    is ModelUpdateStatus.UpToDate ->
        "You have the current model (${status.installed.modelId} ${status.installed.version})."
    else -> "Checking for the current model…"
}

private fun downloadProgressLabel(download: ModelDownloadState): String = when (download.status) {
    ModelDownloadStatus.CHECKING -> "Checking…"
    ModelDownloadStatus.PREPARING -> "Preparing…"
    ModelDownloadStatus.VERIFYING -> "Verifying integrity…"
    ModelDownloadStatus.INSTALLING -> "Installing…"
    else -> buildString {
        append("${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}")
        append(" · ${download.percent}%")
        if (download.bytesPerSecond > 0L) append(" · ${formatBytes(download.bytesPerSecond)}/s")
    }
}

