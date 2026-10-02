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
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
        return learningPrompt(platform.host, applicable)
    }

    override suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean {
        if (spec.name != LearningTools.REMEMBER) return false
        // A call execution refuses anyway is not put to the user; the trust table still applies on its own.
        if (parseRemember(arguments) is DraftResult.Invalid || projectOf(context.workspace) == Project.Unknown) {
            return false
        }
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
            ) ?: return failure("not saved: the model is unknown, use model_scope=engine")
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
        log.i { "remember kind=${draft.kind} outcome=${outcome?.let { it::class.simpleName } ?: "none"}" }
        return result(outcome, draft, project.ref)
    }

    private fun result(outcome: AgentLearningOutput?, draft: RememberDraft, project: WorkspaceRef?) = when (outcome) {
        is AgentLearningOutput.Learned -> AgentToolResult(
            "Saved the ${draft.kind.label()} \"${draft.title}\" for future sessions of " +
                (if (project == null) "chats without a project" else "this project") + ".",
        )

        is AgentLearningOutput.Rejected -> failure(
            when (outcome.reason) {
                LearnRejection.Duplicate -> "not saved: an instruction with this title already exists here"
                LearnRejection.Limit -> "not saved: the registry is full; the user has to remove old instructions"
                LearnRejection.Unavailable -> "not saved: the registry is not available"
                LearnRejection.NotPersisted -> "not stored: the instruction applies only until the app closes"
            },
        )

        AgentLearningOutput.StorageFailed, null -> failure("not saved: the registry did not answer")
    }

    private suspend fun loadSkill(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val name = (arguments["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val project = projectOf(context.workspace) as? Project.Known ?: return failure("no learned skills here")
        val skills = registry()?.instructions.orEmpty()
            .applicable(project.ref, context.target)
            .filter { it.kind == InstructionKind.Skill }
        val skill = skills.firstOrNull { it.title.equals(name, ignoreCase = true) }
        log.i { "load skill found=${skill != null} available=${skills.size}" }
        return skill?.let { AgentToolResult(it.content) }
            ?: failure("no enabled learned skill named \"$name\"; available: " + skills.joinToString { it.title })
    }

    /** Saves [instruction] through the machine and waits for the outcome it reports for this request. */
    private suspend fun learn(instruction: LearnedInstruction): AgentLearningOutput? = coroutineScope {
        val requestId = Uuid.random().toString()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first {
                (it as? AgentLearningOutput.Learned)?.requestId == requestId ||
                    (it as? AgentLearningOutput.Rejected)?.requestId == requestId
            }
        }
        val sent = machine.send(AgentLearningIntent.Public.Learn(requestId, instruction))
        val result = if (sent == SendResult.Accepted) {
            withTimeoutOrNull(
                OUTCOME_TIMEOUT_MILLIS,
            ) { outcome.await() }
        } else {
            null
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
        worktrees?.tasks?.values?.firstOrNull { it.executionWorkspace == workspace }?.let {
            return Project.Known(it.project)
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

    /** Reviewed content in the language of the other hosted approvals: kind, scope, the agent's rating and text. */
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
        if (draft.reason.isNotEmpty()) append("\nReason: ").append(draft.reason)
        if (draft.description.isNotEmpty()) append("\nWhen to use: ").append(draft.description)
        // The whole text, never a preview: the user approves exactly what will be stored.
        append("\n\n").append(draft.content)
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
                        "kind",
                        "general, model (current engine or model only) or skill",
                        "general",
                        "model",
                        "skill",
                    )
                    stringProperty("title", "Short title; for a skill its unique name")
                    stringProperty("content", "The instruction: imperative and self-contained")
                    stringProperty("description", "For a skill: when to load it. Optional otherwise")
                    enumProperty(
                        "model_scope",
                        "For kind=model: the whole current engine or only the model",
                        "engine",
                        "model",
                    )
                    enumProperty("safety", "Your rating: safe, or review when the user must decide", "safe", "review")
                    stringProperty("reason", "What led to the lesson: the error, correction or preference")
                }
                put(
                    "required",
                    buildJsonArray { listOf("kind", "title", "content", "safety").forEach { add(JsonPrimitive(it)) } },
                )
            },
        )

        val LOAD_SKILL_SPEC = AgentToolSpec(
            LearningTools.LOAD_SKILL,
            "Load the full text of a learned skill listed in the instructions before a task it applies to.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { stringProperty("name", "Skill name as listed") }
                put("required", buildJsonArray { add(JsonPrimitive("name")) })
            },
        )
    }
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
