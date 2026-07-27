package com.snapaie.android.ui.upgrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapaie.android.AppContainer
import com.snapaie.android.R
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.ui.findActivity

@Composable
fun UpgradeScreen(container: AppContainer, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val price by container.billingBridge.lifetimePrice.collectAsStateWithLifecycle()
    val isPro by container.billingBridge.isPro.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("snapaie Pro", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "One payment. Yours forever. No subscription, no account, no data leaving your phone.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Bullet("🧠 Full Forge Recall — unlimited topics, Survival, Explain It, Interleave")
                    Bullet("📤 Export to Markdown / Obsidian / Notion-flavored clipboard")
                    Bullet("📚 Batch processing — multi-page PDFs and multi-image imports")
                    Bullet("⚡ Larger Gemma model on capable devices")
                    Bullet("🎭 All 14 chat personas and chat styles")
                    Bullet("✨ Custom instructions, no branding footer on exports")
                }
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isPro) {
                        Text("Pro is active on this device. Thank you 🖤", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            price?.let { "Own forever — $it" } ?: "Own forever",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            enabled = activity != null,
                            onClick = { activity?.let { container.billingBridge.launchLifetimePurchase(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Unlock snapaie Pro")
                        }
                    }
                    OutlinedButton(
                        onClick = { container.billingBridge.restorePurchases() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Restore purchase")
                    }
                    Text(stringResource(R.string.privacy_policy_url), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
