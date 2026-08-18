package com.snapaie.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.snapaie.android.R
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.components.XpBar
import com.snapaie.android.core.design.snapScreenBackground
import kotlinx.coroutines.launch

private data class OnboardingPage(val title: String, val body: String, val demo: Boolean = false)

@Composable
fun OnboardingFlow(
    prefs: com.snapaie.android.data.preferences.AppPreferencesRepository,
    onFinished: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

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
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .snapScreenBackground()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val page = pages[step.coerceIn(0, pages.lastIndex)]
        LiquidGlassSurface(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
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

                // Value demo first (PDF rule): show the payoff before asking for a 3 GB download.
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
                Button(
                    onClick = {
                        if (step < pages.lastIndex) {
                            step++
                        } else {
                            scope.launch {
                                prefs.setOnboardingCompleted()
                                onFinished()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (step < pages.lastIndex) {
                            stringResource(R.string.onboarding_next)
                        } else {
                            stringResource(R.string.onboarding_get_started)
                        },
                    )
                }
                if (step < pages.lastIndex) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                prefs.setOnboardingCompleted()
                                onFinished()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Skip") }
                }
            }
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
