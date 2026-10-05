package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Arguments
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Kinds
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Approval title of a command action; the command follows in full. */
private const val COMMAND_APPROVAL = "Фоновая команда в проекте"

/** Approval title of a helper agent; its task follows in full. */
private const val AGENT_APPROVAL = "Агент-помощник в новом чате"

private const val MAX_COMMAND = 4_000
private const val MAX_PROMPT = 8_000
private const val MAX_TITLE = 60
private const val DEFAULT_TIMEOUT_SECONDS = 1_800L
private const val MAX_TIMEOUT_SECONDS = 21_600L

/**
 * `scheduler_start_action`: a background command in the project (Desktop) or a helper agent in a new chat, whose
 * result is published as `action.<id>.finished`. With a wake note the caller sleeps until then; the wake is scheduled
 * before the action starts, so a quick action cannot finish unobserved. Every start needs the user's decision; a
 * helper agent cannot start helpers. Commands and prompts are shown in the approval, never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SchedulerActionTools(
    private val actions: BackgroundActions,
    private val scheduler: WakeScheduler,
    private val machine: SchedulerMachine,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : AgentToolContribution {
    private val log = Log.tag("SchedulerActionTools")

    override val isDetachedSupported: Boolean get() = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) listOf(START_ACTION_SPEC) else emptyList()

    /**
     * Every start is put to the user, whatever the trust level: the work outlives the turn and the user's attention,
     * and a command could stop the host itself.
     */
    override suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean = spec.name == SchedulerTools.START_ACTION

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (arguments.text(Arguments.KIND)) {
        Kinds.COMMAND -> AgentToolApproval(spec.name, COMMAND_APPROVAL, arguments.text(Arguments.COMMAND))
        Kinds.AGENT -> AgentToolApproval(spec.name, AGENT_APPROVAL, arguments.text(Arguments.PROMPT))
        else -> AgentToolApproval(spec.name, spec.description)
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (name != SchedulerTools.START_ACTION) return failure("unknown tool")
        if (!isEnabled()) return failure("background actions are turned off")
        if (arguments.text(Arguments.KIND) == Kinds.AGENT && actions.isHelper(context.session)) {
            return failure("a helper agent cannot start helpers of its own")
        }
        val start = when (val parsed = parse(context, arguments)) {
            is ParsedAction.Invalid -> return failure(parsed.message)
            is ParsedAction.Valid -> parsed.start
        }
        val id = start.id
        val wake = arguments.text(Arguments.WAKE_NOTE)?.let { note ->
            if (note.length > SchedulerLimits.MAX_NOTE) return failure("the wake note is too long")
            val request = WakeRequest(
                newWakeId(),
                context.session,
                context.workspace,
                WakeCondition(setOf(EventKeys.actionFinished(id)), clock.now() + SchedulerLimits.EVENT_WAIT),
                note,
                WakeOrigin.Agent(context.turn),
                context.target,
            )
            val outcome = scheduler.schedule(request, clock.now())
            if (outcome != ScheduleOutcome.Scheduled) return failure("not started: ${outcome.failureMessage()}")
            request.id
        }
        val refusal = when (start) {
            is ActionStart.Command ->
                actions.startCommand(id, context.session, start.workspace, start.command, start.timeout)

            is ActionStart.Agent -> actions.startAgent(id, start.request)
        }
        log.i {
            "start action $id kind=${start::class.simpleName.orEmpty()} wake=${wake != null} refused=${refusal != null}"
        }
        if (refusal != null) {
            wake?.let { machine.send(SchedulerIntent.Public.Cancel(it, context.session)) }
            return failure("not started: $refusal")
        }
        return AgentToolResult(started(id, wake))
    }

    private suspend fun isEnabled(): Boolean = toggles.get(SchedulerEnabled) && toggles.get(SchedulerActions)

    private fun parse(context: AgentToolContext, arguments: JsonObject): ParsedAction =
        when (arguments.text(Arguments.KIND)) {
            Kinds.COMMAND -> parseCommand(context, arguments)
            Kinds.AGENT -> parseAgent(context, arguments)
            else -> ParsedAction.Invalid("${Arguments.KIND} must be ${Kinds.COMMAND} or ${Kinds.AGENT}")
        }

    private fun parseCommand(context: AgentToolContext, arguments: JsonObject): ParsedAction {
        val command = arguments.text(Arguments.COMMAND).orEmpty()
        val workspace = context.workspace
        return when {
            command.isEmpty() -> ParsedAction.Invalid("the command is empty")

            command.length > MAX_COMMAND -> ParsedAction.Invalid("the command exceeds $MAX_COMMAND characters")

            workspace == null -> ParsedAction.Invalid("command actions need a project")

            !actions.areCommandsAvailable -> ParsedAction.Invalid("command actions run only on Desktop")

            else -> {
                val seconds = (arguments.whole(Arguments.TIMEOUT_SECONDS) ?: DEFAULT_TIMEOUT_SECONDS)
                    .coerceIn(1, MAX_TIMEOUT_SECONDS)
                ParsedAction.Valid(ActionStart.Command(newActionId(), workspace, command, seconds.seconds))
            }
        }
    }

    private fun parseAgent(context: AgentToolContext, arguments: JsonObject): ParsedAction {
        val prompt = arguments.text(Arguments.PROMPT).orEmpty()
        val target = context.target
        return when {
            prompt.isEmpty() -> ParsedAction.Invalid("the task is empty")

            prompt.length > MAX_PROMPT -> ParsedAction.Invalid("the task exceeds $MAX_PROMPT characters")

            target == null -> ParsedAction.Invalid("the engine of this session is unknown")

            else -> {
                val id = newActionId()
                val title = (arguments.text(Arguments.TITLE) ?: prompt.lineSequence().first()).take(MAX_TITLE)
                val request = SpawnRequest(context.session, context.workspace, target, title, helperPrompt(id, prompt))
                ParsedAction.Valid(ActionStart.Agent(id, request))
            }
        }
    }

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private companion object {
        val START_ACTION_SPEC = AgentToolSpec(
            SchedulerTools.START_ACTION,
            "Start a background shell command in the project or a helper agent in a new chat. Its result is " +
                "published as action.<id>.finished; with ${Arguments.WAKE_NOTE} this session sleeps until then and " +
                "you must end your turn.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    enumProperty(Arguments.KIND, "What to start", Kinds.COMMAND, Kinds.AGENT)
                    stringProperty(Arguments.COMMAND, "For a command: the command line, run in the project root")
                    integerProperty(
                        Arguments.TIMEOUT_SECONDS,
                        "For a command: time limit, default $DEFAULT_TIMEOUT_SECONDS, at most $MAX_TIMEOUT_SECONDS",
                    )
                    stringProperty(Arguments.PROMPT, "For an agent: the self-contained task of the helper")
                    stringProperty(Arguments.TITLE, "For an agent: title of its chat")
                    stringProperty(Arguments.WAKE_NOTE, "Sleep until the action finishes; what to do then")
                }
                required(Arguments.KIND)
            },
            action = AgentToolAction.Command,
        )
    }
}

private fun started(id: ActionId, wake: WakeId?): String = buildString {
    append("Started action $id; its result is published as ${EventKeys.actionFinished(id)}.")
    if (wake != null) append(" Wake $wake is pending: end your turn now, you resume when the action finishes.")
}

/** The helper's first prompt: the task, plus a directive about how its result is used. */
private fun helperPrompt(id: ActionId, task: String) = WakePrompt(
    RequestId("action_${id.value}"),
    task,
    "You are a helper agent started by another session (action $id). Do the task, then end with a final message " +
        "that summarises the result: it is handed back to the requesting session.",
)

private sealed interface ActionStart {
    val id: ActionId

    data class Command(
        override val id: ActionId,
        val workspace: WorkspaceRef,
        val command: String,
        val timeout: Duration,
    ) : ActionStart {
        override fun toString(): String = "Command(id=$id, timeout=$timeout)"
    }

    data class Agent(override val id: ActionId, val request: SpawnRequest) : ActionStart
}

private sealed interface ParsedAction {
    data class Valid(val start: ActionStart) : ParsedAction
    data class Invalid(val message: String) : ParsedAction
}
