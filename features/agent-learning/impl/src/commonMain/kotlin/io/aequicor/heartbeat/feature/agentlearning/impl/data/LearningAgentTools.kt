package io.aequicor.heartbeat.feature.agentlearning.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningOutput
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnRejection
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.agentlearning.api.ModelScope
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.DraftResult
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningMachine
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.ModelReach
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.RememberDraft
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.applicable
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.isRatedSafe
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.learningPrompt
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.parseRemember
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.singleLine
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The agent's side of self-learning: `remember` saves an instruction for the project of the session (or for chats
 * without a project) and `load_learned_skill` returns a learned skill. Instructions of the session are added to its
 * prompt. Declarations never change while the toggle is on, so native threads that froze their tools stay valid.
 * Instruction texts are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class LearningAgentTools(
    private val machine: LearningMachine,
    private val toggles: FeatureToggles,
    private val machines: MachineRegistry,
    private val workspaces: LocalWorkspaces,
    private val platform: PlatformInfo,
) : AgentToolContribution {
    override val group: String = "learning"
    override val title: String = "Обучение"
    override val catalog: List<ToolCatalogEntry> get() = listOf(REMEMBER_SPEC, LOAD_SKILL_SPEC).toolCatalog()

    private val log = Log.tag("LearningAgentTools")

    override val isDetachedSupported: Boolean get() = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(AgentLearningEnabled)) listOf(REMEMBER_SPEC, LOAD_SKILL_SPEC) else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String {
        if (!toggles.get(AgentLearningEnabled)) return ""
        // remember refuses a workspace that is neither a saved project nor a checkout, so it is not offered there.
        val project = projectOf(scope.workspace) as? Project.Known ?: return ""
        val applicable = registry()?.instructions.orEmpty().applicable(project.ref, scope.target)
        log.i { "learned instructions for the session: ${applicable.size}" }
        return learningPrompt(platform.host, applicable, scope.declared)
    }

    override suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean {
        if (spec.name != LearningTools.REMEMBER) return false
        // Invalid arguments are refused by execution deterministically, so they are not put to the user; the trust
        // table still applies on its own. The project is not consulted: it is resolved again on execution and may
        // become known in between (a worktree getting ready), so an unknown one must not skip the decision.
        if (parseRemember(arguments) is DraftResult.Invalid) return false
        return approvalLevel().requiresDecision(isRatedSafe(arguments))
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        if (spec.name != LearningTools.REMEMBER) return AgentToolApproval(spec.name, spec.description)
        val draft = (parseRemember(arguments) as? DraftResult.Valid)?.draft
        val level = approvalLevel()
        return AgentToolApproval(
            spec.name,
            "Remember instruction \"${draft?.title ?: "untitled"}\"",
            draft?.let { approvalText(it, context) },
            binding = "approval=$level",
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(AgentLearningEnabled)) return failure("self-learning is turned off")
        return when (name) {
            LearningTools.REMEMBER -> remember(context, arguments)
            LearningTools.LOAD_SKILL -> loadSkill(context, arguments)
            else -> failure("unknown tool")
        }
    }

    private suspend fun remember(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val draft = when (val parsed = parseRemember(arguments)) {
            is DraftResult.Invalid -> return failure("not saved: ${parsed.message}")
            is DraftResult.Valid -> parsed.draft
        }
        val project = projectOf(context.workspace) as? Project.Known
            ?: return failure("not saved: this workspace is not a saved project")
        val scope = if (draft.kind == InstructionKind.Model) {
            modelScope(
                context,
                draft.reach,
            ) ?: return failure("not saved: the model is unknown, use ${LearningTools.Arguments.MODEL_SCOPE}=engine")
        } else {
            null
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val instruction = LearnedInstruction(
            InstructionId(Uuid.random().toString()),
            draft.kind,
            project.ref,
            draft.title,
            draft.content,
            draft.description,
            scope,
            createdAtMillis = now,
        )
        val outcome = learn(instruction)
        // The outcome names only its kind and, for a refusal, the reason; never a text of the instruction.
        log.i { "remember kind=${draft.kind} outcome=$outcome" }
        return result(outcome, draft, project.ref)
    }

    private fun result(outcome: LearnOutcome, draft: RememberDraft, project: WorkspaceRef?) = when (outcome) {
        LearnOutcome.Learned -> AgentToolResult(
            "Saved the ${draft.kind.label()} \"${draft.title}\" for future sessions of " +
                (if (project == null) "chats without a project" else "this project") + ".",
        )

        is LearnOutcome.Rejected -> failure(
            when (outcome.reason) {
                LearnRejection.Duplicate -> "not saved: an instruction with this title already exists here"
                LearnRejection.Limit -> "not saved: the registry is full; the user has to remove old instructions"
                LearnRejection.Unavailable -> "not saved: the registry is not available"
                LearnRejection.NotPersisted -> "not saved: the registry could not be written"
                LearnRejection.Invalid -> "not saved: the instruction is empty or longer than allowed"
            },
        )

        LearnOutcome.NotTaken -> failure("not saved: the registry did not take the request")

        LearnOutcome.Unconfirmed -> failure(
            "not confirmed yet: the registry is still saving; if the instruction is missing later, a repeated call " +
                "is safe (a saved one is reported as a duplicate)",
        )
    }

    private suspend fun loadSkill(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val name = (arguments[LearningTools.Arguments.NAME] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            .orEmpty()
        val project = projectOf(context.workspace) as? Project.Known ?: return failure("no learned skills here")
        val skills = registry()?.instructions.orEmpty()
            .applicable(project.ref, context.target)
            .filter { it.kind == InstructionKind.Skill }
        val skill = skills.firstOrNull { it.title.equals(name, ignoreCase = true) }
        log.i { "load skill found=${skill != null} available=${skills.size}" }
        return skill?.let { AgentToolResult(it.content) }
            ?: failure("no enabled learned skill named \"$name\"; available: " + skills.joinToString { it.title })
    }

    /**
     * Saves [instruction] through the machine and waits for the outcome it reports for this request. A request the
     * machine took but did not answer in time may still be written, so it is [LearnOutcome.Unconfirmed], not lost.
     */
    private suspend fun learn(instruction: LearnedInstruction): LearnOutcome = coroutineScope {
        val requestId = Uuid.random().toString()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.mapNotNull { it.outcomeOf(requestId) }.first()
        }
        val sent = machine.send(AgentLearningIntent.Public.Learn(requestId, instruction))
        val result = if (sent == SendResult.Accepted) {
            withTimeoutOrNull(OUTCOME_TIMEOUT_MILLIS) { outcome.await() } ?: LearnOutcome.Unconfirmed
        } else {
            LearnOutcome.NotTaken
        }
        outcome.cancel()
        result
    }

    private suspend fun registry(): AgentLearningState.Ready? = withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
        machine.state.first { it is AgentLearningState.Ready || it is AgentLearningState.Failed }
    } as? AgentLearningState.Ready

    private fun approvalLevel(): LearningApproval =
        (machine.state.value as? AgentLearningState.Ready)?.approval ?: LearningApproval.Ask

    /**
     * The project an instruction belongs to: a worktree checkout maps to its original project, a session without
     * a project to null. A workspace that is neither a saved project nor a known checkout is unknown.
     */
    private suspend fun projectOf(workspace: WorkspaceRef?): Project {
        if (workspace == null) return Project.Known(null)
        val worktrees = machines.find(WorktreeMachineKey)?.state?.value as? WorktreeState.Ready
        worktrees?.sourceProjectOf(workspace)?.let {
            return Project.Known(it)
        }
        val isSaved = workspaces.isAvailable && workspaces.observe().first().any { it.ref == workspace }
        return if (isSaved) Project.Known(workspace) else Project.Unknown
    }

    private fun modelScope(context: AgentToolContext, reach: ModelReach?): ModelScope? {
        val engine = context.target?.engine ?: context.session.engine
        return when (reach) {
            ModelReach.Model -> context.target?.model?.let { ModelScope(engine, it) }
            ModelReach.Engine, null -> ModelScope(engine)
        }
    }

    /**
     * Reviewed content in the language of the other hosted approvals. The window may show only its top, so the first
     * line is the host's (kind, scope and the agent's rating) and no text of the agent stands in front of it. Then,
     * set apart, the text to be stored, whole and never a preview (parseRemember allows no two blank lines in a row
     * there), and one line each: the title, when to use it and the reason.
     */
    private fun approvalText(draft: RememberDraft, context: AgentToolContext): String = buildString {
        append(
            when (draft.kind) {
                InstructionKind.General -> "General instruction"

                InstructionKind.Model -> "Instruction for " + listOfNotNull(
                    (context.target?.engine ?: context.session.engine).value,
                    context.target?.model?.value?.takeIf { draft.reach == ModelReach.Model },
                ).joinToString(" · ")

                InstructionKind.Skill -> "Skill"
            },
        )
        append(if (context.workspace == null) " · chats without a project" else " · this project")
        append(if (draft.isSafe) " · rated safe by the agent" else " · the agent asks you to review it")
        append("\n\n").append(draft.content).append("\n")
        // The title and description are single lines (checked by parseRemember); the reason is folded into one.
        append("\nTitle: ").append(draft.title)
        if (draft.description.isNotEmpty()) append("\nWhen to use: ").append(draft.description)
        if (draft.reason.isNotEmpty()) append("\nReason: ").append(singleLine(draft.reason))
    }

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private sealed interface Project {
        data class Known(val ref: WorkspaceRef?) : Project
        data object Unknown : Project
    }

    private companion object {
        const val OUTCOME_TIMEOUT_MILLIS = 10_000L
        const val LOAD_TIMEOUT_MILLIS = 3_000L

        val REMEMBER_SPEC = AgentToolSpec(
            LearningTools.REMEMBER,
            "Save a lesson for future sessions: an environment workaround, a user correction or preference, or a " +
                "reusable procedure. The user sees every call and may have to approve it. Never include secrets.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    enumProperty(
                        LearningTools.Arguments.KIND,
                        "general, model (current engine or model only) or skill",
                        "general",
                        "model",
                        "skill",
                    )
                    stringProperty(LearningTools.Arguments.TITLE, "Short title; for a skill its unique name")
                    stringProperty(LearningTools.Arguments.CONTENT, "The instruction: imperative and self-contained")
                    stringProperty(
                        LearningTools.Arguments.DESCRIPTION,
                        "For a skill: when to load it. Optional otherwise",
                    )
                    enumProperty(
                        LearningTools.Arguments.MODEL_SCOPE,
                        "For kind=model: the whole current engine or only the model",
                        "engine",
                        "model",
                    )
                    enumProperty(
                        LearningTools.Arguments.SAFETY,
                        "Your rating: safe, or review when the user must decide",
                        "safe",
                        "review",
                    )
                    stringProperty(
                        LearningTools.Arguments.REASON,
                        "What led to the lesson: the error, correction or preference",
                    )
                }
                put(
                    "required",
                    buildJsonArray {
                        with(LearningTools.Arguments) { listOf(KIND, TITLE, CONTENT, SAFETY) }
                            .forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )

        val LOAD_SKILL_SPEC = AgentToolSpec(
            LearningTools.LOAD_SKILL,
            "Load the full text of a learned skill listed in the instructions before a task it applies to.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { stringProperty(LearningTools.Arguments.NAME, "Skill name as listed") }
                put("required", buildJsonArray { add(JsonPrimitive(LearningTools.Arguments.NAME)) })
            },
        )
    }
}

/** What became of a `remember` request. */
private sealed interface LearnOutcome {
    /** The registry stored the instruction. */
    data object Learned : LearnOutcome

    /** The registry refused the instruction for [reason]. */
    data class Rejected(val reason: LearnRejection) : LearnOutcome

    /** The registry did not take the request; nothing was saved. */
    data object NotTaken : LearnOutcome

    /** The registry took the request but did not report the outcome in time; it may still be saved. */
    data object Unconfirmed : LearnOutcome
}

/** The outcome this output reports for [requestId], or null when it belongs to another request. */
private fun AgentLearningOutput.outcomeOf(requestId: String): LearnOutcome? = when (this) {
    is AgentLearningOutput.Learned -> LearnOutcome.Learned.takeIf { this.requestId == requestId }
    is AgentLearningOutput.Rejected -> LearnOutcome.Rejected(reason).takeIf { this.requestId == requestId }
    AgentLearningOutput.StorageFailed -> null
}

private fun InstructionKind.label(): String = when (this) {
    InstructionKind.General -> "general instruction"
    InstructionKind.Model -> "model instruction"
    InstructionKind.Skill -> "skill"
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.enumProperty(
    name: String,
    description: String,
    vararg values: String,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}
