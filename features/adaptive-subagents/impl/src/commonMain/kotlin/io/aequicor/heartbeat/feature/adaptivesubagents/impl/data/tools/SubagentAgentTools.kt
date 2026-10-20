package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.tools

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsSessionTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools.Arguments
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.runs.SubagentStarts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentResult
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRun
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRunStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.brief
import io.aequicor.heartbeat.feature.aiengine.facade.api.reviewModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentRequest
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStart
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStatus
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentWake
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * `subagent_spawn` / `subagent_receive`: subagents of ordinary chats. A spawn chooses the model configuration with
 * [AdaptiveModels] and starts a scheduler background agent in a helper chat, authorized by the shared profile setting;
 * the scheduler owns its work, recovery and result, retained for this feature until the parent reads it. Starting
 * follows the host's trust policy in interactive mode and needs an shared profile authorization in automatic mode.
 * Tasks and results are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SubagentAgentTools(
    // Lazy: these reach the engine runtime; declaring tools must not build it.
    private val adaptive: Lazy<AdaptiveModels>,
    private val agents: Lazy<BackgroundAgents>,
    private val runs: SubagentRunStore,
    private val starts: SubagentStarts,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : AgentToolContribution {
    override val group: String = "subagents"
    override val title: String = "Субагенты"
    override val catalog: List<ToolCatalogEntry> get() = listOf(SpawnSpec, ReceiveSpec).toolCatalog()
    override val isDetachedSupported: Boolean get() = true

    private val log = Log.tag("SubagentTools")

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) listOf(SpawnSpec, ReceiveSpec) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (isEnabled()) INSTRUCTIONS else ""

    override suspend fun approval(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) =
        if (spec.name == SubagentTools.SPAWN) {
            subagentApproval(context, APPROVAL_TITLE, arguments)
        } else {
            AgentToolApproval(spec.name, spec.description)
        }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!isEnabled()) return failure("subagents are turned off")
        return when (name) {
            SubagentTools.SPAWN -> spawn(context, arguments)
            SubagentTools.RECEIVE -> receive(context, arguments.flag(Arguments.WAIT))
            else -> failure("unknown tool")
        }
    }

    private suspend fun spawn(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val isReviewed = context.authorization == subagentApproval(context, APPROVAL_TITLE, arguments)
        if (!isReviewed) return failure("the request changed after authorization; request it again")
        val request = arguments.spawnRequest().getOrElse { return failure(it.message.orEmpty()) }
        val selection = SelectionContext(SubagentHost.StudioHelper, context.target, context.workspace)
        val choice = adaptive.value.select(request, selection)
        val (target, effort) = when (choice) {
            is ModelChoice.Selected -> choice.target to choice.reasoningEffort
            is ModelChoice.Inherit -> (context.target ?: return failure("the model of this session is unknown")) to null
        }
        val id = ActionId("sa" + Uuid.random().toHexString().take(ID_LENGTH))
        val title = (arguments.text(Arguments.TITLE) ?: request.task.lineSequence().first()).take(MAX_TITLE)
            .ifBlank { DEFAULT_TITLE }
        val model = (choice as? ModelChoice.Selected)?.title ?: target.model.value
        val run = SubagentRun(id.value, context.session, title, model, effort, clock.now())
        val started = starts.start(
            run,
            BackgroundAgentRequest(
                id,
                SubagentTools.OWNER,
                context.session,
                context.workspace,
                target,
                title,
                request.task,
                directive(id),
                HelperModelOptions(effort, context.reviewModel),
                context.request?.let { RequestInitiator(context.session, it) },
            ),
        )
        if (started is BackgroundAgentStart.Refused) return failure("not started: ${started.reason}")
        log.i { "subagent $id started on $target effort=${effort ?: "default"} source=${choice.sourceName()}" }
        return AgentToolResult(started(id, title, model, effort, (choice as? ModelChoice.Selected)?.rationale))
    }

    private suspend fun receive(context: AgentToolContext, wait: Boolean): AgentToolResult = starts.read {
        val own = runs.list().filter { it.parent == context.session }
        val statuses = own.associateWith { starts.status(it) }
        val finished = statuses.mapNotNull { (run, status) ->
            (status as? BackgroundAgentStatus.Finished)?.let { run to it }
        }.toMap()
        val pending = statuses.filterValues { it == BackgroundAgentStatus.Running }.keys
        val batch = resultBatch(finished, pending, ReceiveSpec.resultChars - WAKE_SUFFIX_BUDGET)
        if (batch.delivered.isNotEmpty()) {
            runs.saveResults(
                batch.delivered.associate { run ->
                    val result = finished.getValue(run)
                    run.id to SubagentResult(result.isCompleted, result.text)
                },
            )
            releaseResults(batch.delivered)
        }
        val sleep = if (wait && finished.isEmpty() && pending.isNotEmpty()) sleep(context, pending) else null
        log.i {
            "subagent results read: finished=${batch.delivered.size} pending=${pending.size} wait=${sleep != null}"
        }
        if (sleep != null && sleep.isError) sleep else AgentToolResult(batch.text + sleep?.text.orEmpty())
    }

    /** The response is already built and backed up; cleanup errors may repeat it, but must not discard it. */
    private suspend fun releaseResults(delivered: Set<SubagentRun>) {
        try {
            delivered.forEach { agents.value.acknowledge(ActionId(it.id), SubagentTools.OWNER) }
            forgetResults(delivered.map { it.id }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(IllegalStateException(e::class.simpleName.orEmpty())) {
                "subagent result cleanup failed; returning the prepared batch"
            }
        }
    }

    /** A closing storage owner can cancel removal even after writing it; the prepared response must still return. */
    private suspend fun forgetResults(ids: Set<String>) = withContext(NonCancellable) {
        supervisorScope {
            val cleanup = async { runs.remove(ids) }
            cleanup.join()
            if (cleanup.isCancelled) {
                log.w { "subagent local result cleanup did not complete; returning the prepared batch" }
            }
        }
    }

    private suspend fun sleep(context: AgentToolContext, pending: Set<SubagentRun>): AgentToolResult {
        if (!toggles.get(SchedulerEnabled)) {
            return failure("cannot sleep while the scheduler is off; read results later")
        }
        val request = WakeRequest(
            WakeId("w" + Uuid.random().toHexString().take(ID_LENGTH)),
            context.session,
            context.workspace,
            WakeCondition(
                pending.take(SchedulerLimits.MAX_EVENTS).map { EventKeys.actionFinished(ActionId(it.id)) }.toSet(),
                clock.now() + SchedulerLimits.EVENT_WAIT,
            ),
            WAKE_NOTE,
            WakeOrigin.Agent(context.turn),
            context.target,
            initiator = context.request?.let { RequestInitiator(context.session, it) },
        )
        return when (val result = agents.value.schedule(request)) {
            BackgroundAgentWake.Scheduled ->
                AgentToolResult(
                    "\nWake ${request.id} is pending: end your turn now; you resume when a subagent finishes.",
                )

            is BackgroundAgentWake.Refused -> failure("cannot sleep now (${brief(result.reason)}); read results later")
        }
    }

    private suspend fun isEnabled(): Boolean = toggles.get(AdaptiveSubagentsEnabled) &&
        toggles.get(AdaptiveSubagentsSessionTools) && toggles.get(SchedulerEnabled) && toggles.get(SchedulerActions)

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)
}

private fun started(id: ActionId, title: String, model: String, effort: String?, rationale: String?) = buildString {
    append("Started subagent ").append(id).append(" \"").append(brief(title)).append("\" on ").append(model)
    effort?.let { append(", effort ").append(it) }
    append('.')
    rationale?.let { append(" Why: ").append(it).append('.') }
    append(" Continue your work; read its result with ${SubagentTools.RECEIVE}, or call it with ")
    append("${Arguments.WAIT}=true; end your turn only after it confirms a wake.")
}

private fun directive(id: ActionId) =
    "You are a subagent started by another session (subagent $id). Do the task completely on your own; you cannot " +
        "start subagents. End with a final message of at most 6000 characters that summarises the result, files you " +
        "changed and anything left undone: it is handed back to the requesting session."

private fun ModelChoice.sourceName(): String = when (this) {
    is ModelChoice.Selected -> source.name
    is ModelChoice.Inherit -> "inherit:${reason.name}"
}

private const val ID_LENGTH = 20
private const val WAKE_SUFFIX_BUDGET = 512
private const val DEFAULT_TITLE = "Subagent"
private const val APPROVAL_TITLE = "Субагент в новом чате"
private const val WAKE_NOTE = "Subagent results are ready: call subagent_receive to read them."
private val INSTRUCTIONS = """
Subagents: delegate a self-contained subtask that can run in parallel with ${SubagentTools.SPAWN}. The host picks
the subagent's model, connection and reasoning effort from your priority (quality, balanced, speed, cost),
complexity (low, medium, high), required inputs and task tags, so cheap or fast models take simple work and strong
ones take hard work. Give the full task: the subagent sees nothing else and cannot start subagents. Read results
with ${SubagentTools.RECEIVE}; call again when more finished results remain unread. With wait=true and none
finished, end your turn only if the result confirms a wake; on refusal keep working and read results later.
In organic AI cells use organism_divide instead.
""".trimIndent()
