package com.snapae.android.domain

import android.content.Context
import com.snapae.android.data.ai.LocalInferenceEngine
import com.snapae.android.data.model.InputDraft
import com.snapae.android.data.model.LaunchPackage
import com.snapae.android.data.model.ModelTier
import com.snapae.android.data.model.PhaseUpdate
import com.snapae.android.data.model.WorkflowPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowEngine(
    private val context: Context,
    private val inferenceEngine: LocalInferenceEngine,
) {
    private val parser = StructuredOutputParser()

    fun run(draft: InputDraft, tier: ModelTier): Flow<WorkflowEvent> = flow {
        val prompt = PromptLibrary(context).buildPrompt(draft)
        emit(WorkflowEvent.Phase(PhaseUpdate(WorkflowPhase.Intake, "Reading source and extracting usable claims.")))
        delay(250)
        emit(WorkflowEvent.Phase(PhaseUpdate(WorkflowPhase.Audience, "Finding the clearest audience and buying moment.")))
        delay(250)

        inferenceEngine.stream(prompt, tier).collect { token ->
            if (token.isNotBlank()) emit(WorkflowEvent.Token(token))
        }

        WorkflowPhase.entries.drop(2).forEach { phase ->
            emit(WorkflowEvent.Phase(PhaseUpdate(phase, "Completed ${phase.label.lowercase()}.", true)))
            delay(120)
        }

        val generated = LocalPackageDrafting.generate(draft)
        emit(WorkflowEvent.Package(parser.parseOrRepair(generated)))
    }

    fun cancel() {
        inferenceEngine.cancel()
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Package(val launchPackage: LaunchPackage) : WorkflowEvent
}

class PromptLibrary(private val context: Context) {
    fun buildPrompt(draft: InputDraft): String {
        val guardrails = readAsset("prompts/guardrails.md")
        val orchestrator = readAsset("prompts/orchestrator.md")
        return """
            $guardrails

            $orchestrator

            Asset type: ${draft.assetType.label}
            Working title: ${draft.title}
            Context: ${draft.context}
            Source:
            ${draft.content}
        """.trimIndent()
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}

class StructuredOutputParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseOrRepair(raw: String): LaunchPackage {
        val trimmed = raw.substringAfter("```json", raw).substringBeforeLast("```").trim()
        return runCatching { json.decodeFromString<LaunchPackage>(trimmed) }
            .getOrElse { LocalPackageDrafting.emptyWithError("Local JSON repair needed: ${it.message}") }
    }
}

private object LocalPackageDrafting {
    private val json = Json { encodeDefaults = true }

    fun generate(draft: InputDraft): String {
        val subject = draft.title.ifBlank {
            draft.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(54) ?: draft.assetType.label
        }
        val cleanSubject = subject.replace(Regex("\\s+"), " ").trim()
        val audience = "People already close to this problem who need a specific, believable reason to try $cleanSubject."
        return json.encodeToString(
            LaunchPackage(
                audience = audience,
                strongestHookAngle = "Lead with the practical change the audience can verify, then show the mechanism without inflated claims.",
                titles = List(10) { index -> titleFor(index, cleanSubject) },
                hooks = listOf(
                    "Here is the part of $cleanSubject that actually changes the workflow.",
                    "Most launches skip the proof. This one starts with it.",
                    "If you are trying to move faster without sounding generic, start here.",
                    "The useful angle is not hype. It is the repeatable step.",
                    "Before you post another update, turn it into a system people can use.",
                ),
                captions = listOf(
                    "$cleanSubject is easier to explain when the promise is specific and the proof is visible.",
                    "The launch angle: show the workflow, name the tradeoff, and give people a clear next step.",
                    "Turn the update into a package: hook, proof, CTA, follow-up, and tracking.",
                    "No fake urgency. Just the clearest reason this matters now.",
                    "Use the strongest claim you can support, then let the details do the work.",
                ),
                hashtags = listOf("#buildinpublic", "#productmarketing", "#contentstrategy", "#launch", "#founder"),
                thumbnailText = listOf("The useful angle", "Launch without hype", "Proof first", "Turn updates into assets"),
                pinnedComment = "Want the template behind this? Comment with the part you are launching and I will share the structure.",
                cta = "Try the workflow on one update today and track which hook earns the clearest reply.",
                repurposingBlocks = listOf(
                    "Short video: lead with the before/after workflow, then show one concrete example.",
                    "LinkedIn: write a proof-led post with one screenshot, one lesson, and one question.",
                    "X thread: split the package into hook, mechanism, example, CTA, and follow-up.",
                    "Newsletter: expand the strongest hook into a short teardown and reusable checklist.",
                ),
                scheduleSuggestion = "Post the primary asset on the highest-attention weekday slot, then publish two supporting cuts within 48 hours and review replies before the final follow-up.",
                engagementPrompts = listOf(
                    "What would make this more useful in your workflow?",
                    "Which title feels most specific without overpromising?",
                    "What proof would you want before trying this?",
                ),
                analyticsTemplateMarkdown = "| Asset | Hook | Platform | Posted | Views | Saves | Replies | CTA clicks | Next action |\n|---|---|---|---|---:|---:|---:|---:|---|",
                nextIdeas = listOf(
                    "A teardown of the strongest launch hook and why it worked.",
                    "A behind-the-scenes workflow showing how the package was made.",
                    "A comparison post: hype-heavy launch vs proof-led launch.",
                ),
            ),
        )
    }

    fun emptyWithError(message: String): LaunchPackage = LaunchPackage(strongestHookAngle = message)

    private fun titleFor(index: Int, subject: String): String = when (index) {
        0 -> "How $subject turns one update into a launch package"
        1 -> "The proof-first way to launch $subject"
        2 -> "Stop posting $subject as a one-off update"
        3 -> "A practical launch system for $subject"
        4 -> "What to say when $subject is useful but hard to explain"
        5 -> "The $subject angle that does not need fake hype"
        6 -> "From rough note to launch plan: $subject"
        7 -> "Make $subject easier to understand, share, and track"
        8 -> "The content package hidden inside $subject"
        else -> "Launch $subject with clearer hooks and better follow-up"
    }
}
