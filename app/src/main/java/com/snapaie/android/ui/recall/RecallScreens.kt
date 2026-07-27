package com.snapaie.android.ui.recall

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.core.design.components.ConfettiOverlay
import com.snapaie.android.core.design.components.StrengthRing
import com.snapaie.android.core.design.components.XpBar
import com.snapaie.android.data.local.PracticeTopicEntity
import com.snapaie.android.data.preferences.RecallPrefs
import com.snapaie.android.domain.recall.FeynmanScore
import com.snapaie.android.domain.recall.RapidCard
import com.snapaie.android.domain.recall.SpacedRepetitionScheduler
import com.snapaie.android.domain.recall.XpLedger
import com.snapaie.android.ui.SnapAieViewModel
import com.snapaie.android.ui.nav.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val forgeCardShape = RoundedCornerShape(16.dp)

@Composable
private fun ForgeSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(forgeCardShape)
            .background(DesignTokens.ForgeHero)
            .border(1.dp, DesignTokens.ForgeBorder, forgeCardShape)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun RecallHubScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val prefs by viewModel.container.appPreferencesRepository.recallPrefs
        .collectAsStateWithLifecycle(initialValue = RecallPrefs())
    val topics by viewModel.container.database.recallDao().observeTopics()
        .collectAsState(initial = emptyList())
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val level = XpLedger.levelFor(prefs.xpTotal)
    val now = System.currentTimeMillis()
    val dueTopics = topics.filter { SpacedRepetitionScheduler.isDueNow(it.nextDueAtMillis, now) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ForgeSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "FORGE RECALL 🔥",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = DesignTokens.ForgeText,
                        )
                        Text(
                            "🧊 ${prefs.streakFreezes}",
                            color = DesignTokens.ForgeText,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Recall Master Lv.$level", color = DesignTokens.DueAmber, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("🔥 ${prefs.streakDays}-day streak", color = DesignTokens.ForgeText, style = MaterialTheme.typography.labelLarge)
                    }
                    XpBar(progress = XpLedger.levelProgress(prefs.xpTotal))
                    Text(
                        "${prefs.xpTotal % XpLedger.XP_PER_LEVEL} / ${XpLedger.XP_PER_LEVEL} XP to Lv.${level + 1}",
                        color = DesignTokens.ForgeText.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val questDone = prefs.questBonusAwarded
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (questDone) Color(0x4010B981) else Color(0x336366F1))
                            .border(1.dp, if (questDone) Color(0x8034D399) else Color(0x59818CF8), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            if (questDone) "✅ Daily quest complete +${XpLedger.DAILY_QUEST_BONUS_XP} XP" else "🎯 Daily quest: review ${XpLedger.DAILY_QUEST_TARGET} due topics (${prefs.questReviewsDone}/${XpLedger.DAILY_QUEST_TARGET}) · +${XpLedger.DAILY_QUEST_BONUS_XP} XP",
                            color = DesignTokens.ForgeText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        if (!isPro && topics.size >= FREE_TOPIC_LIMIT) {
            item {
                ForgeSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Free tier holds $FREE_TOPIC_LIMIT topics", color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleSmall)
                        Text("Unlock unlimited topics, Survival, Feynman, and Interleave with Pro.", color = DesignTokens.ForgeText.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { navController.navigate(Routes.Upgrade) }) { Text("Unlock Pro") }
                    }
                }
            }
        }

        item {
            Text(
                if (dueTopics.isEmpty()) "Knowledge map" else "Knowledge map · ${dueTopics.size} due now",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (topics.isEmpty()) {
            item {
                ForgeSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No topics yet", color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Scan a page, then tap “🧠 Forge topic” on the result to start locking knowledge in.",
                            color = DesignTokens.ForgeText.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((topics.size + 1) / 2 * 150).dp.coerceAtMost(620.dp)),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false,
                ) {
                    items(topics, key = { it.id }) { topic ->
                        TopicTile(
                            topic = topic,
                            isPro = isPro,
                            onRapid = { navController.navigate(Routes.recallRapid(topic.id)) },
                            onSurvival = { navController.navigate(Routes.recallSurvival(topic.id)) },
                            onFeynman = { navController.navigate(Routes.recallFeynman(topic.id)) },
                            onUpgrade = { navController.navigate(Routes.Upgrade) },
                        )
                    }
                }
            }
        }
        item {
            TextButton(onClick = { navController.navigate(Routes.RecallVault) }) { Text("Open vault →") }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

private const val FREE_TOPIC_LIMIT = 5

@Composable
private fun TopicTile(
    topic: PracticeTopicEntity,
    isPro: Boolean,
    onRapid: () -> Unit,
    onSurvival: () -> Unit,
    onFeynman: () -> Unit,
    onUpgrade: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val dueNow = SpacedRepetitionScheduler.isDueNow(topic.nextDueAtMillis, now)
    val dueSoon = SpacedRepetitionScheduler.isDueSoon(topic.nextDueAtMillis, now)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DesignTokens.ForgeMid)
            .border(
                width = if (dueNow) 2.dp else 1.dp,
                color = when {
                    dueNow -> DesignTokens.DueRed
                    dueSoon -> DesignTokens.DueAmber
                    else -> DesignTokens.ForgeBorder
                },
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { expanded = !expanded }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StrengthRing(
            strength = topic.strengthLevel,
            dueNow = dueNow,
            dueSoon = dueSoon,
            modifier = Modifier.size(48.dp),
        )
        Text(
            topic.title,
            style = MaterialTheme.typography.labelLarge,
            color = DesignTokens.ForgeText,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${topic.strengthLevel}%", color = DesignTokens.DueAmber, style = MaterialTheme.typography.labelSmall)
            if (topic.hotStreak) Text("🔥", style = MaterialTheme.typography.labelSmall)
            if (dueNow) Text("DUE", color = DesignTokens.DueRed, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        if (expanded) {
            Button(onClick = onRapid, modifier = Modifier.fillMaxWidth()) { Text("⚡ Rapid") }
            if (isPro) {
                OutlinedButton(onClick = onSurvival, modifier = Modifier.fillMaxWidth()) { Text("❤️‍🔥 Survival") }
                OutlinedButton(onClick = onFeynman, modifier = Modifier.fillMaxWidth()) { Text("🧠 Explain it") }
            } else {
                TextButton(onClick = onUpgrade) { Text("Pro modes 🔒") }
            }
        }
    }
}

// region Rapid Fire

@Composable
fun RapidFireScreen(viewModel: SnapAieViewModel, navController: NavHostController, topicId: Long) {
    val scope = rememberCoroutineScope()
    val engine = viewModel.container.recallEngine
    var topic by remember { mutableStateOf<PracticeTopicEntity?>(null) }
    var cards by remember { mutableStateOf<List<RapidCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var lastExplanation by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var xpAwarded by remember { mutableIntStateOf(0) }

    LaunchedEffect(topicId) {
        val t = viewModel.container.database.recallDao().getTopic(topicId)
        topic = t
        if (t != null) {
            val xp = viewModel.container.appPreferencesRepository.recallPrefs.first().xpTotal
            // Cloze blanks unlock at Lv.3 and then mix in ~15% of the time.
            val useCloze = XpLedger.rollCloze(XpLedger.levelFor(xp))
            cards = engine.generateRapidCards(
                topic = t,
                tier = viewModel.selectedTier,
                cloze = useCloze,
            ).take(10)
        }
        loading = false
    }

    ForgeGameScaffold(title = "⚡ Rapid Fire", onExit = { navController.popBackStack() }) {
        when {
            loading -> LoadingBlock("Forging 10 cards from your topic…")
            cards.isEmpty() -> {
                Text("Could not generate cards — is the model downloaded?", color = DesignTokens.ForgeText)
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
            }
            finished -> {
                val context = LocalContext.current
                ConfettiOverlay(visible = score >= 7)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Session complete", color = DesignTokens.ForgeText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("$score / ${cards.size} · +$xpAwarded XP", color = DesignTokens.DueAmber, style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        val bitmap = viewModel.container.shareCardRenderer.renderForgeCard(
                            headline = "$score/${cards.size} locked in",
                            subtitle = "+$xpAwarded XP · snapaie Forge Recall",
                        )
                        context.startActivity(viewModel.container.shareCardRenderer.shareIntent(bitmap, "Share your win"))
                    }) { Text("📣 Share card") }
                    OutlinedButton(onClick = { navController.popBackStack() }) { Text("Done") }
                }
            }
            else -> {
                val card = cards[index]
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Card ${index + 1} / ${cards.size} · $score correct", color = DesignTokens.ForgeText.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    ForgeSurface {
                        Text(card.statement, color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleMedium)
                    }
                    lastExplanation?.let { (correct, explanation) ->
                        Text(
                            (if (correct) "✅ " else "❌ ") + explanation,
                            color = if (correct) DesignTokens.SuccessGreen else DesignTokens.DueRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassAnswerButton("TRUE", DesignTokens.SuccessGreen, Modifier.weight(1f)) {
                            answerRapid(card, true, onResult = { correct ->
                                if (correct) score++
                                lastExplanation = correct to card.explanation
                                if (index < cards.lastIndex) index++ else {
                                    finished = true
                                    xpAwarded = XpLedger.rapidFireXp(score, completed = true)
                                    scope.launch {
                                        engine.recordReview(topicId, score, cards.size)
                                        engine.registerSession(xpAwarded, reviewedDueTopic = true)
                                    }
                                }
                            })
                        }
                        GlassAnswerButton("FALSE", DesignTokens.DueRed, Modifier.weight(1f)) {
                            answerRapid(card, false, onResult = { correct ->
                                if (correct) score++
                                lastExplanation = correct to card.explanation
                                if (index < cards.lastIndex) index++ else {
                                    finished = true
                                    xpAwarded = XpLedger.rapidFireXp(score, completed = true)
                                    scope.launch {
                                        engine.recordReview(topicId, score, cards.size)
                                        engine.registerSession(xpAwarded, reviewedDueTopic = true)
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

private inline fun answerRapid(card: RapidCard, answer: Boolean, onResult: (Boolean) -> Unit) {
    onResult(answer == card.isTrue)
}

@Composable
private fun GlassAnswerButton(label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.35f), tint.copy(alpha = 0.20f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = DesignTokens.ForgeText, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
    }
}

// endregion

// region Survival

@Composable
fun SurvivalScreen(viewModel: SnapAieViewModel, navController: NavHostController, topicId: Long) {
    val scope = rememberCoroutineScope()
    val engine = viewModel.container.recallEngine
    var cards by remember { mutableStateOf<List<RapidCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var index by remember { mutableIntStateOf(0) }
    var correctStreak by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    var startMillis by remember { mutableStateOf(0L) }
    var finalXp by remember { mutableIntStateOf(0) }
    var elapsedFinal by remember { mutableIntStateOf(0) }

    LaunchedEffect(topicId) {
        val topic = viewModel.container.database.recallDao().getTopic(topicId)
        if (topic != null) {
            cards = engine.generateRapidCards(topic, viewModel.selectedTier, count = 16)
            startMillis = System.currentTimeMillis()
        }
        loading = false
    }

    ForgeGameScaffold(title = "❤️‍🔥 Survival", onExit = { navController.popBackStack() }) {
        when {
            loading -> LoadingBlock("One life. Endless cards. Generating…")
            cards.isEmpty() -> {
                Text("Could not generate cards — is the model downloaded?", color = DesignTokens.ForgeText)
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
            }
            !alive -> {
                val prefs by viewModel.container.appPreferencesRepository.recallPrefs
                    .collectAsStateWithLifecycle(initialValue = RecallPrefs())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💀 Run over", color = DesignTokens.ForgeText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Survived ${elapsedFinal}s · +$finalXp XP", color = DesignTokens.DueAmber, style = MaterialTheme.typography.titleLarge)
                    Text("Personal best: ${maxOf(prefs.survivalBestSec, elapsedFinal)}s", color = DesignTokens.ForgeText.copy(alpha = 0.75f))
                    OutlinedButton(onClick = { navController.popBackStack() }) { Text("Done") }
                }
            }
            else -> {
                val card = cards[index % cards.size]
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Streak $correctStreak · one wrong answer ends the run", color = DesignTokens.ForgeText.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    ForgeSurface {
                        Text(card.statement, color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(true to "TRUE", false to "FALSE").forEach { (answer, label) ->
                            GlassAnswerButton(
                                label,
                                if (answer) DesignTokens.SuccessGreen else DesignTokens.DueRed,
                                Modifier.weight(1f),
                            ) {
                                if (answer == card.isTrue) {
                                    correctStreak++
                                    index++
                                } else {
                                    val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt()
                                    elapsedFinal = elapsed
                                    finalXp = XpLedger.survivalXp(elapsed, correctStreak)
                                    alive = false
                                    scope.launch {
                                        engine.recordSurvivalBest(elapsed)
                                        engine.recordReview(topicId, correctStreak, correctStreak + 1)
                                        engine.registerSession(finalXp, reviewedDueTopic = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Feynman

@Composable
fun FeynmanScreen(viewModel: SnapAieViewModel, navController: NavHostController, topicId: Long) {
    val scope = rememberCoroutineScope()
    val engine = viewModel.container.recallEngine
    var topic by remember { mutableStateOf<PracticeTopicEntity?>(null) }
    var answer by remember { mutableStateOf("") }
    var evaluating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FeynmanScore?>(null) }

    LaunchedEffect(topicId) {
        topic = viewModel.container.database.recallDao().getTopic(topicId)
    }

    ForgeGameScaffold(title = "🧠 Explain It", onExit = { navController.popBackStack() }) {
        val current = topic
        if (current == null) {
            LoadingBlock("Loading topic…")
        } else if (result != null) {
            val r = result!!
            ConfettiOverlay(visible = r.score >= 80)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Score: ${r.score}/100", color = DesignTokens.DueAmber, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                if (r.missingPoints.isNotEmpty()) {
                    Text("Missing:", color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleSmall)
                    r.missingPoints.forEach { Text("• $it", color = DesignTokens.ForgeText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
                }
                if (r.incorrectPoints.isNotEmpty()) {
                    Text("Incorrect:", color = DesignTokens.DueRed, style = MaterialTheme.typography.titleSmall)
                    r.incorrectPoints.forEach { Text("• $it", color = DesignTokens.ForgeText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Done") }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Explain “${current.title}” in your own words, like you're teaching it to a friend.",
                    color = DesignTokens.ForgeText,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text("Your explanation") },
                )
                Button(
                    onClick = {
                        evaluating = true
                        scope.launch {
                            val score = engine.evaluateFeynman(current, answer, viewModel.selectedTier)
                            result = score
                            evaluating = false
                            val correct = if (score.score >= 60) 1 else 0
                            engine.recordReview(topicId, correct, 1)
                            engine.registerSession(XpLedger.feynmanXp(score.score), reviewedDueTopic = true)
                        }
                    },
                    enabled = answer.length > 20 && !evaluating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (evaluating) "Scoring…" else "Score my explanation")
                }
                if (evaluating) LoadingBlock("AE is grading you (on-device)…")
            }
        }
    }
}

// endregion

@Composable
fun VaultScreen(viewModel: SnapAieViewModel, navController: NavHostController) {
    val topics by viewModel.container.database.recallDao().observeTopics()
        .collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Vault", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search topics") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        val filtered = topics.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        items(filtered.size) { i ->
            val topic = filtered[i]
            ForgeSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(topic.title, color = DesignTokens.ForgeText, style = MaterialTheme.typography.titleSmall)
                        Text("${topic.strengthLevel}%", color = DesignTokens.DueAmber, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(topic.summary, color = DesignTokens.ForgeText.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Reviews: ${topic.reviewCount}", color = DesignTokens.ForgeText.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                        if (topic.hotStreak) Text("🔥 hot", style = MaterialTheme.typography.labelSmall, color = DesignTokens.DueAmber)
                    }
                    TextButton(onClick = {
                        scope.launch { viewModel.container.database.recallDao().deleteById(topic.id) }
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun ForgeGameScaffold(title: String, onExit: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DesignTokens.ForgeDeep, DesignTokens.ForgeMid))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = DesignTokens.ForgeText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                TextButton(onClick = onExit) { Text("Exit", color = DesignTokens.ForgeText) }
            }
            content()
        }
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(color = DesignTokens.XpPurple)
        Text(message, color = DesignTokens.ForgeText.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
    }
}
