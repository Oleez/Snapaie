package com.snapaie.android.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.LiquidGlassSurface
import com.snapaie.android.core.design.ThemeMode
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.ai.model.ModelUpdateStatus
import com.snapaie.android.domain.chat.Languages
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.chat.ChatAppearance
import com.snapaie.android.ui.nav.Routes
import kotlinx.coroutines.launch
import com.snapaie.android.core.design.components.ScreenHeader

@Composable
fun SettingsScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val prefs = viewModel.container.appPreferencesRepository
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }
    var languageQuery by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader("Settings", onBack = { navController.popBackStack() })
        }

        item {
            SettingsCard("Explanation style") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExplainStyle.entries.forEach { style ->
                        FilterChip(
                            selected = settings.explainStyle == style.name,
                            onClick = { viewModel.updateStyle(style) },
                            label = { Text(style.label) },
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("Output language") {
                OutlinedTextField(
                    value = languageQuery,
                    onValueChange = { languageQuery = it },
                    label = { Text("Search languages") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                val matches = Languages.map.entries
                    .filter { languageQuery.isBlank() || it.value.contains(languageQuery, ignoreCase = true) }
                    .take(if (languageQuery.isBlank()) 8 else 12)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matches.forEach { (code, name) ->
                        FilterChip(
                            selected = settings.outputLanguage == code,
                            onClick = { scope.launch { prefs.setOutputLanguage(code) } },
                            label = { Text(name) },
                        )
                    }
                }
                Text(
                    "Current: ${Languages.nameFor(settings.outputLanguage)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode.name,
                            onClick = { scope.launch { prefs.setThemeMode(mode.name) } },
                            label = { Text(mode.name) },
                        )
                    }
                }
                Text("Text size", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.textScale,
                    onValueChange = { scope.launch { prefs.setTextScale(it) } },
                    valueRange = 0.85f..1.3f,
                    steps = 3,
                )
                Text("Ambient bubbles", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("on", "slower", "faster", "off").forEach { mode ->
                        FilterChip(
                            selected = settings.bubblesMode == mode,
                            onClick = { scope.launch { prefs.setBubblesMode(mode) } },
                            label = { Text(mode) },
                        )
                    }
                }
                Text("Chat style", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChatAppearance.entries.forEach { appearance ->
                        val unlocked = isPro || appearance.freeTier
                        FilterChip(
                            selected = settings.chatAppearance == appearance.name,
                            onClick = {
                                if (unlocked) {
                                    scope.launch { prefs.setChatAppearance(appearance.name) }
                                } else {
                                    navController.navigate(Routes.Upgrade)
                                }
                            },
                            label = { Text(appearance.label + if (!unlocked) " 🔒" else "") },
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("Narration") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Read results aloud", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.ttsEnabled,
                        onCheckedChange = { scope.launch { prefs.setTtsEnabled(it) } },
                    )
                }
                Text("Speed", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.ttsRate,
                    onValueChange = {
                        viewModel.container.ttsSpeaker.rate = it
                        scope.launch { prefs.setTtsRate(it) }
                    },
                    valueRange = 0.6f..1.8f,
                )
                OutlinedButton(onClick = { viewModel.container.ttsSpeaker.stop() }) { Text("Stop narration") }
            }
        }

        item {
            SettingsCard("AE Tweaks") {
                OutlinedTextField(
                    value = settings.userName,
                    onValueChange = { scope.launch { prefs.setUserName(it) } },
                    label = { Text("Your name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = settings.aeName,
                    onValueChange = { scope.launch { prefs.setAeName(it) } },
                    label = { Text("Assistant name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("Your pronouns / gender for tailored advice", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("neutral", "female", "male", "other").forEach { gender ->
                        FilterChip(
                            selected = settings.userGender == gender,
                            onClick = { scope.launch { prefs.setUserGender(gender) } },
                            label = { Text(gender) },
                        )
                    }
                }
                if (isPro) {
                    OutlinedTextField(
                        value = settings.customInstructions,
                        onValueChange = { scope.launch { prefs.setCustomInstructions(it.take(2000)) } },
                        label = { Text("Custom instructions (Pro)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                } else {
                    OutlinedButton(onClick = { navController.navigate(Routes.Upgrade) }) {
                        Text("Custom instructions 🔒 Pro")
                    }
                }
            }
        }

        item {
            SettingsCard("Offline AI") {
                val installed = modelState.installed
                Text(
                    if (installed != null) {
                        "Ready. Everything runs on this phone, with no internet."
                    } else {
                        "Not downloaded yet. You get instant basic results until then."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val status = modelState.updateStatus) {
                    is ModelUpdateStatus.UpdateAvailable -> Text(
                        "An update is ready. You can download it from the Books tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is ModelUpdateStatus.CheckFailed -> Text(
                        "Last update check failed: ${status.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is ModelUpdateStatus.Incompatible -> Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ModelUpdateStatus.NotConfigured -> Text(
                        "Offline AI isn't available in this version yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Unit
                }
                modelState.ramWarning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "The download happens once and stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.checkForModelUpdate() },
                        enabled = !modelState.isCheckingManifest,
                    ) {
                        Text(if (modelState.isCheckingManifest) "Checking…" else "Check for updates")
                    }
                    OutlinedButton(onClick = { viewModel.deleteModelWeights() }) { Text("Remove download") }
                }
            }
        }

        item {
            SettingsCard("Privacy") {
                Text(
                    "snapaie never sends your pages anywhere. Reading, writing, history and narration all happen on this device — it works with airplane mode on and costs zero data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The one-time download you approve is the only thing this app ever sends or fetches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard("Reset") {
                OutlinedButton(onClick = { showReset = true }) { Text("Factory reset") }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Factory reset?") },
            text = { Text("Deletes every scan, chat, note, Forge topic, and setting on this device. The download is removed too. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    viewModel.factoryReset()
                    navController.popBackStack()
                }) { Text("Erase everything") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    LiquidGlassSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
