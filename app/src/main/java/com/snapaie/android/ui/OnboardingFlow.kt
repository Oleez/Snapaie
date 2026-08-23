package com.snapaie.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.AppContainer
import com.snapaie.android.R
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.XpBar
import com.snapaie.android.core.design.snapScreenBackground
import com.snapaie.android.data.ai.ModelUiState
import com.snapaie.android.ui.scan.formatBytes
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
    val demo: Boolean = false,
    val model: Boolean = false,
)

@Composable
fun OnboardingFlow(
    container: AppContainer,
    onFinished: () -> Unit,
) {
    val prefs = container.appPreferencesRepository
    var step by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Resolve what there is to download before the user reaches the last page, so the
    // size on the button is real rather than a placeholder.
    LaunchedEffect(Unit) { container.modelRepository.checkForUpdateIfDue() }

    val pages = listOf(
        OnboardingPage(
            stringResource(R.string.onboarding_headline_snap),
            stringResource(R.string.onboarding_detail_snap),
            demo = true,
        ),
        OnboardingPage(
            stringResource(R.string.onboarding_headline_privacy),
            stringResource(R.string.onboarding_detail_privacy),
        ),
        OnboardingPage(
            stringResource(R.string.onboarding_headline_model),
            stringResource(R.string.onboarding_detail_model),
            model = true,
        ),
    )

    val finish: () -> Unit = {
        scope.launch {
            prefs.setOnboardingCompleted()
            onFinished()
        }
        Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .snapScreenBackground()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val page = pages[step.coerceIn(0, pages.lastIndex)]
        LiquidGlassSurface(contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.Start) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${step + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                }
                Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Value demo first (PDF rule): show the payoff before asking for a 2 GB download.
                if (page.demo) SampleResultPreview()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .size(width = if (index == step) 28.dp else 8.dp, height = 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == step) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (page.model) {
                    ModelOnboardingStep(container = container, onDone = finish)
                } else {
                    Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_next))
                    }
                    TextButton(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Skip") }
                }
            }
        }
    }
}

/**
 * The one screen that asks for the model.
 *
 * Before this existed the download lived only on a card partway down the Snap hub, so a
 * user could run the whole app never knowing there was an AI to turn on — every scan
 * quietly degraded to a heuristic draft instead. The download still starts only on an
 * explicit tap, and onboarding never blocks on it: the transfer continues in the
 * background while the user gets on with reading.
 */
@Composable
private fun ModelOnboardingStep(
    container: AppContainer,
    onDone: () -> Unit,
) {
    val modelState: ModelUiState by container.modelRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Default to Wi-Fi: two gigabytes over cellular is a bill, not a preference.
    var wifiOnly by remember { mutableStateOf(true) }

    val spec = modelState.downloadableSpec
    val download = modelState.download

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            modelState.isModelInstalled -> {
                Text(
                    "Offline AI is ready — ${modelState.installed?.modelId.orEmpty()} is on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            download.status.isActive -> {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Downloading — ${download.percent}%. You can carry on; it keeps going in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            spec != null -> {
                Text(
                    "${spec.modelId} · ${formatBytes(spec.expectedBytes)} · Apache-2.0. " +
                        "Downloaded once, then every page you read is processed on this phone — " +
                        "no account, no upload, works in airplane mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
            }

            else -> {
                Text(
                    "No model is reachable right now. You can turn offline AI on later from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        modelState.ramWarning?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (spec != null && !modelState.isModelInstalled && !download.status.isActive) {
            Button(
                onClick = {
                    // Same consent the Snap hub card collects, recorded before the transfer.
                    scope.launch { container.appPreferencesRepository.setGemmaLicenseAccepted() }
                    container.modelRepository.startDownload(wifiOnly)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download ${formatBytes(spec.expectedBytes)}")
            }
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started))
        }
        if (spec != null && !modelState.isModelInstalled && !download.status.isActive) {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

/** A canned before/after so the first screen proves the payoff without any model. */
@Composable
private fun SampleResultPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DesignTokens.ForgeHero)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("A page of dense text →", color = DesignTokens.ForgeText.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text(
            "\"Compound interest rewards time in the market far more than timing of the market, because returns accrue on prior returns…\"",
            color = DesignTokens.ForgeText.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Core idea", color = DesignTokens.Mint, style = MaterialTheme.typography.labelMedium)
        Text(
            "Staying invested beats trying to pick moments — growth builds on previous growth.",
            color = DesignTokens.ForgeText,
            style = MaterialTheme.typography.bodyMedium,
        )
        XpBar(progress = 0.82f)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("82% compressed", color = DesignTokens.DueAmber, style = MaterialTheme.typography.labelMedium)
            Text("✈️ airplane mode", color = DesignTokens.ForgeText.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}
