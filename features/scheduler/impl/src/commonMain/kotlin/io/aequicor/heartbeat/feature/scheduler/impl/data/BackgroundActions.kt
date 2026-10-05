package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Longest wait for a helper agent's first turn. */
private val MAX_HELPER_TIME: Duration = 6.hours

private const val KIND_COMMAND = "command"
private const val KIND_AGENT = "agent"
private const val HISTORY_WINDOW = 20

/**
 * Background actions started by agents: a shell command in the project or a helper agent in a new session. Each runs
 * in the profile scope, outliving the turn that started it, and publishes `action.<id>.finished` with its result as
 * the payload. Running actions are journaled, so a restart reports the ones it interrupted. Commands, prompts and
 * results are never logged.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class BackgroundActions(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    private val bus: SchedulerBus,
    private val machine: SchedulerMachine,
    private val journal: ActionJournal,
    private val commands: CommandRunner,
    private val workspaces: LocalWorkspaces,
    private val hosts: Set<ScheduledSessionHost>,
    private val toggles: FeatureToggles,
    private val clock: Clock,
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) {
    private val log = Log.tag("BackgroundActions")

    /** Whether command actions can run here. */
    val areCommandsAvailable: Boolean get() = commands.isAvailable && workspaces.isAvailable

    /** Starts [command] in [workspace]; returns why it was not started, or null. */
    suspend fun startCommand(id: ActionId, workspace: WorkspaceRef, command: String, timeout: Duration): String? {
        val directory = workspaces.resolve(workspace) ?: return "the project is not available"
        journal.add(ActionRecord(id, KIND_COMMAND, clock.now()))
        log.i { "action $id: command started, timeout=$timeout" }
        scope.coroutineScope.launch(dispatchers.io) {
            finish(id) { commands.run(directory, command, timeout).payload() }
        }
        return null
    }

    /**
     * Starts a helper session through the first host that creates sessions and waits in the background for its
     * first turn to finish; returns why it was not started, or null.
     */
    suspend fun startAgent(id: ActionId, request: SpawnRequest): String? {
        // Turn ends are watched from before the spawn: a quick helper may finish before the spawn returns.
        val finished = Channel<EventKey>(Channel.UNLIMITED)
        val watcher = scope.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { if (it.key.namespace == EventNamespace.Session) finished.trySend(it.key) }
        }
        var spawned: SessionRef? = null
        try {
            spawned = spawnOrNull(id, request)
        } finally {
            if (spawned == null) watcher.cancel()
        }
        if (spawned == null) return "no chat host could start a helper agent"
        journal.add(ActionRecord(id, KIND_AGENT, clock.now()))
        log.i { "action $id: helper agent started on ${spawned.engine.value}" }
        scope.coroutineScope.launch {
            try {
                finish(id) { helperResult(spawned, finished) }
            } finally {
                watcher.cancel()
            }
        }
        return null
    }

    /** Reports actions a previous run of the profile started and never finished. */
    suspend fun recover() {
        val interrupted = journal.takeAll()
        if (interrupted.isEmpty()) return
        log.i { "actions interrupted by a restart: ${interrupted.size}" }
        if (!toggles.get(SchedulerEnabled)) return
        // The bus has no replay and the wake driver may not listen yet: the machine gets the events directly.
        machine.state.first { it is SchedulerState.Ready }
        interrupted.forEach { record ->
            val event = BusEvent(
                EventKeys.actionFinished(record.id),
                EventOrigin.Action(record.id),
                clock.now(),
                "status: interrupted (the app closed before the ${record.kind} finished)",
            )
            val ready = machine.state.value as? SchedulerState.Ready
            if (ready?.wakes.orEmpty().any { it.id !in ready?.delivering.orEmpty() && it.matches(event) }) {
                machine.send(SchedulerIntent.Internal.Observed(event))
            }
        }
    }

    /** The helper created by the first host that creates sessions; null when none did. */
    private suspend fun spawnOrNull(id: ActionId, request: SpawnRequest): SessionRef? = try {
        hosts.sortedByDescending { it.priority }.firstNotNullOfOrNull { it.spawn(request) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "action $id: helper could not be started" }
        null
    }

    private suspend fun finish(id: ActionId, result: suspend () -> String) {
        val payload = try {
            result()
        } catch (e: CancellationException) {
            // The profile closed: the journal keeps the action, and the next start reports it as interrupted.
            throw e
        } catch (e: Exception) {
            log.w(e) { "action $id failed" }
            "status: failed (${e::class.simpleName.orEmpty()})"
        }
        bus.publish(EventKeys.actionFinished(id), EventOrigin.Action(id), payload.take(SchedulerLimits.MAX_PAYLOAD))
        journal.remove(id)
        log.i { "action $id finished" }
    }

    private suspend fun helperResult(helper: SessionRef, finished: ReceiveChannel<EventKey>): String {
        val key = EventKeys.turnFinished(helper)
        val isDone = withTimeoutOrNull(MAX_HELPER_TIME) {
            while (finished.receive() != key) continue
            true
        } == true
        if (!isDone) return "status: timed out after $MAX_HELPER_TIME"
        return "status: finished\nhelper session key: $key\nfinal message:\n${finalMessage(helper)}"
    }

    private suspend fun finalMessage(helper: SessionRef): String = try {
        val history = facade.sessions.get(helper).features.resolve(SessionHistory).orThrow()
        history.page(HistoryPageRequest(limit = HISTORY_WINDOW)).items
            .filterIsInstance<SessionItem.Message>()
            .lastOrNull { it.role == MessageRole.Assistant }
            ?.parts
            ?.filterIsInstance<ContentPart.Text>()
            ?.joinToString("\n") { it.text }
            ?: "(the helper wrote no text)"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "helper history is unavailable" }
        "(unavailable: open the helper's chat)"
    }
}

private fun CommandOutcome.payload(): String =
    "status: ${exitCode?.let { "exited with code $it" } ?: "timed out"}\noutput:\n$output"
