package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioDefaults
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Executes the demo workspace effects while [io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime]
 * is off. With the toggle on the studio uses [EngineStudioEffects].
 * A run records the prompt, streams the agent reply into the repository and
 * finishes when the agent completes, fails or receives a stop request for its session. A stop is sticky: it is
 * kept until its run ends, so a request arriving before the stream starts is not lost. Leaving the studio
 * cancels runs; their replies are closed so no transcript stays "streaming".
 */
class AiStudioEffects(
    private val repository: StudioRepository,
    private val agent: StudioAgent,
    private val availability: StudioAvailability,
    private val clock: Clock,
) : EffectHandler<AiStudioEffect, AiStudioIntent> {
    private val log = Log.tag("AiStudioEffects")
    private val activeRuns = MutableStateFlow(emptyMap<String, Instant>())
    private val stopRequests = MutableStateFlow(emptySet<String>())

    override suspend fun handle(effect: AiStudioEffect, machine: EffectScope<AiStudioIntent>) {
        when (effect) {
            is AiStudioEffect.Usage -> Unit

            is AiStudioEffect.Configuration -> configure(effect)

            AiStudioEffect.ObserveRuntime -> activeRuns.collect {
                machine.send(
                    AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(running = it.keys, runStartedAt = it)),
                )
            }

            // The scripted agent never asks for permissions; a stray answer is a caller error, not a decision.
            is AiStudioEffect.RespondPermission -> log.w { "Permission answer ignored: the demo agent asks none" }

            AiStudioEffect.ObserveModels -> repository.observeModels().collect { models ->
                machine.send(AiStudioIntent.Internal.ModelsChanged(models.map { it.id }))
            }

            AiStudioEffect.ObserveProjects -> machine.send(AiStudioIntent.Internal.ProjectAvailabilityChanged(false))

            is AiStudioEffect.ChooseProject -> error("Local folder selection is unavailable in the demo workspace")

            AiStudioEffect.Load -> machine.send(
                AiStudioIntent.Internal.Loaded(
                    isEnabled = availability.isEnabled(),
                    defaults = StudioDefaults(repository.defaultProjectId(), DefaultRunSettings),
                ),
            )

            AiStudioEffect.ObserveAvailability -> availability.observe().distinctUntilChanged().collect {
                machine.send(AiStudioIntent.Internal.AvailabilityChanged(it))
            }

            is AiStudioEffect.CreateSession -> {
                val session = repository.createSession(
                    effect.projectId,
                    titleOf(effect.prompt),
                    isWorktree = false,
                    isOrganism = effect.isOrganism,
                )
                machine.send(
                    AiStudioIntent.Internal.SessionCreated(
                        effect.paneId,
                        session.id,
                        effect.prompt,
                        effect.settings,
                        effect.requestId,
                        effect.attachments,
                        effect.submissionId,
                    ),
                )
            }

            is AiStudioEffect.Run -> runEffect(effect, machine)

            is AiStudioEffect.Cancel -> cancel(effect.sessionId)

            is AiStudioEffect.Apply -> repository.edit(effect.sessionId, effect.edit)
        }
    }

    private suspend fun runEffect(effect: AiStudioEffect.Run, machine: EffectScope<AiStudioIntent>) {
        require(effect.attachments.isEmpty()) { "The demo agent does not accept attachments" }
        if (effect.submissionId.isNotEmpty()) {
            machine.send(
                AiStudioIntent.Internal.RunAccepted(effect.paneId, effect.submissionId, effect.sessionId),
            )
        }
        machine.send(AiStudioIntent.Internal.RunFinished(effect.sessionId, run(effect)))
    }

    private fun configure(effect: AiStudioEffect.Configuration) {
        when (effect) {
            is AiStudioEffect.SaveSettings -> log.d { "Demo preferences remain in memory" }
            is AiStudioEffect.ChangeSessionSetting -> error("Live configuration requires an engine session")
        }
    }

    private fun cancel(sessionId: String) {
        val isActive = sessionId in activeRuns.value
        if (isActive) stopRequests.update { it + sessionId }
        log.i { "stop requested active=$isActive" }
    }

    /** Registers the run before its first suspension, so a stop sent right after the submit finds it. */
    private suspend fun run(effect: AiStudioEffect.Run): RunOutcome {
        val startedAt = clock.now()
        val started = activeRuns.updateAndGet { it + (effect.sessionId to startedAt) }
        log.d { "active runs: ${started.size - 1} -> ${started.size}" }
        try {
            return execute(effect, startedAt)
        } finally {
            val remaining = activeRuns.updateAndGet { it - effect.sessionId }
            log.d { "active runs: ${remaining.size + 1} -> ${remaining.size}" }
            stopRequests.update { it - effect.sessionId }
        }
    }

    private suspend fun execute(effect: AiStudioEffect.Run, startedAt: Instant): RunOutcome {
        var reply: StudioMessage.Reply? = null
        val outcome = try {
            val history = repository.observeMessages(effect.sessionId).first()
            val workspace = repository.observeWorkspace().first()
            val project = workspace.project(workspace.session(effect.sessionId)?.projectId)
            val prompt = StudioMessage.Prompt(repository.newMessageId(), startedAt, effect.prompt)
            repository.append(effect.sessionId, prompt)
            var streamed = StudioMessage.Reply(repository.newMessageId(), startedAt, isStreaming = true)
            reply = streamed
            repository.append(effect.sessionId, streamed)
            val request = AgentRequest(effect.prompt, history, effect.settings, project)
            merge(
                stopRequests.filter { effect.sessionId in it }.map { RunOutcome.Stopped },
                flow {
                    agent.run(request).collect { event ->
                        streamed = streamed.apply(event)
                        reply = streamed
                        repository.replace(effect.sessionId, streamed)
                        if (event is AgentEvent.BranchCreated) repository.setBranch(effect.sessionId, event.name)
                    }
                    emit(RunOutcome.Completed)
                },
            ).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "agent run failed" }
            RunOutcome.Failed
        } finally {
            withContext(NonCancellable) {
                reply?.let { repository.replace(effect.sessionId, it.closed()) }
            }
        }
        when (outcome) {
            RunOutcome.Completed -> Unit

            RunOutcome.Stopped -> repository.append(
                effect.sessionId,
                StudioMessage.Stopped(repository.newMessageId(), clock.now(), clock.now() - startedAt),
            )

            RunOutcome.Failed -> repository.append(
                effect.sessionId,
                StudioMessage.Failed(repository.newMessageId(), clock.now()),
            )
        }
        log.i { "run ended outcome=$outcome tools=${reply?.tools?.size ?: 0} length=${reply?.text?.length ?: 0}" }
        return outcome
    }
}

/** The first line of the first prompt, shortened for the sidebar. */
internal fun titleOf(prompt: String): String {
    val line = prompt.trim().lineSequence().first().trim().ifEmpty { "Attachment" }
    return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
}

/** Folds one agent event into the streamed reply. */
internal fun StudioMessage.Reply.apply(event: AgentEvent): StudioMessage.Reply {
    val ordered = parts.ifEmpty {
        listOfNotNull(text.takeIf(String::isNotEmpty)?.let { StudioReplyPart.Text("$id:text:0", it) }) +
            tools.map { StudioReplyPart.Tool(it) }
    }
    return when (event) {
        is AgentEvent.Text -> {
            val last = ordered.lastOrNull() as? StudioReplyPart.Text
            val updated = if (last == null) {
                ordered + StudioReplyPart.Text("$id:text:${ordered.size}", event.text)
            } else {
                ordered.dropLast(1) + last.copy(text = last.text + event.text)
            }
            copy(text = text + event.text, parts = updated)
        }

        is AgentEvent.ToolStarted -> {
            val tool = StudioToolRun(event.id, event.title)
            copy(tools = tools + tool, parts = ordered + StudioReplyPart.Tool(tool))
        }

        is AgentEvent.ToolOutput -> updateTool(event.id, ordered) { copy(output = output + event.output) }

        is AgentEvent.ToolFinished -> updateTool(event.id, ordered) {
            copy(
                title = event.title,
                status = if (event.isSuccess) ToolRunStatus.Done else ToolRunStatus.Failed,
                diff = event.diff ?: diff,
            )
        }

        is AgentEvent.BranchCreated -> this
    }
}

private fun StudioMessage.Reply.updateTool(
    toolId: String,
    ordered: List<StudioReplyPart>,
    update: StudioToolRun.() -> StudioToolRun,
): StudioMessage.Reply = copy(
    tools = tools.map { if (it.id == toolId) it.update() else it },
    parts = ordered.map {
        if (it is StudioReplyPart.Tool && it.id == toolId) {
            StudioReplyPart.Tool(it.tool.update())
        } else {
            it
        }
    },
)

/** Ends streaming; tool calls interrupted by a stop or failure are marked failed. */
internal fun StudioMessage.Reply.closed(): StudioMessage.Reply {
    val close: StudioToolRun.() -> StudioToolRun = {
        if (status == ToolRunStatus.Running || status == ToolRunStatus.Pending) {
            copy(status = ToolRunStatus.Failed)
        } else {
            this
        }
    }
    return copy(
        isStreaming = false,
        tools = tools.map { it.close() },
        parts = parts.map { if (it is StudioReplyPart.Tool) StudioReplyPart.Tool(it.tool.close()) else it },
    )
}

private const val TITLE_LENGTH = 60
