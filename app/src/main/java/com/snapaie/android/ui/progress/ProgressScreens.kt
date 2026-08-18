package com.snapaie.android.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.data.preferences.RecallPrefs
import com.snapaie.android.domain.recall.XpLedger
import com.snapaie.android.domain.stats.ReaderStatsAggregator
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.nav.Routes
import com.snapaie.android.core.design.components.ScreenHeader

@Composable
fun ProgressScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val stats by viewModel.readerStats.collectAsStateWithLifecycle()
    val scans by viewModel.library.collectAsStateWithLifecycle()
    val recall by viewModel.container.appPreferencesRepository.recallPrefs
        .collectAsStateWithLifecycle(initialValue = RecallPrefs())
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Progress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔥 ${stats.streakDays}-day reading streak", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("${stats.pagesProcessed}", "pages")
                        StatBlock(formatMinutes(stats.minutesSaved), "saved")
                        StatBlock("${stats.averageCompression}%", "compressed")
                        StatBlock("Lv.${XpLedger.levelFor(recall.xpTotal)}", "recall")
                    }
                }
            }
        }
        item {
            Button(onClick = { navController.navigate(Routes.ReaderReport) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open weekly Reader Report →")
            }
        }
        item {
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Words compressed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${stats.wordsIn} words in → ${stats.wordsOut} words out",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Every page stayed on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isPro) {
            item {
                LiquidGlassSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("snapaie Pro", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "One-time unlock: full Forge Recall, exports, batch PDFs, and the larger model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { navController.navigate(Routes.Upgrade) }) { Text("See Pro") }
                    }
                }
            }
        }
        item {
            val weekly = ReaderStatsAggregator.weekly(scans)
            LiquidGlassSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This week", style = MaterialTheme.typography.titleSmall)
                    Text("${weekly.pages} pages · ${formatMinutes(weekly.minutesSaved)} saved · ${weekly.avgCompression}% avg compression")
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun ReaderReportScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val scans by viewModel.library.collectAsStateWithLifecycle()
    val stats by viewModel.readerStats.collectAsStateWithLifecycle()
    val weekly = ReaderStatsAggregator.weekly(scans)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Reader Report", onBack = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DesignTokens.ForgeHero)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("THIS WEEK I SAVED", color = DesignTokens.ForgeText.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            Text(
                formatMinutes(weekly.minutesSaved),
                color = DesignTokens.Mint,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black,
            )
            Text("of reading time", color = DesignTokens.ForgeText.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${weekly.pages} pages · ${weekly.avgCompression}% compressed · ${stats.streakDays}-day streak 🔥",
                color = DesignTokens.ForgeText,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Button(
            onClick = {
                val renderer = viewModel.container.shareCardRenderer
                val bitmap = renderer.renderReaderReportCard(
                    minutesSaved = weekly.minutesSaved,
                    pages = weekly.pages,
                    avgCompression = weekly.avgCompression,
                    streakDays = stats.streakDays,
                )
                context.startActivity(renderer.shareIntent(bitmap, "Share your week"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Share my week")
        }
        Text(
            "The card shows your numbers only — no download links, no ads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}
