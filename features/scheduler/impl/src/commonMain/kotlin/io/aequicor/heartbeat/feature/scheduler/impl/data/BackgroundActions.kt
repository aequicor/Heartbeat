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
import io.aequicor.heartbeat.feature.scheduler.api.isAwaited
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Longest wait for a helper agent's first turn. */
private val MAX_HELPER_TIME: Duration = 6.hours

/** Background actions running at once in a profile. */
private const val MAX_RUNNING = 8

/** Background actions running at once for one session. */
private const val MAX_RUNNING_PER_SESSION = 3

private const val KIND_COMMAND = "command"
private const val KIND_AGENT = "agent"
private const val HISTORY_WINDOW = 20

/**
 * Background actions started by agents: a shell command in the project or a helper agent in a new session. Each runs
 * in the profile scope, outliving the turn that started it, and publishes `action.<id>.finished` with its result as
 * the payload. Running actions are journaled, so a restart reports the ones it interrupted. At most [MAX_RUNNING] run
 * at once, [MAX_RUNNING_PER_SESSION] per session. Commands, prompts and results are never logged.
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
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
    private val toggles: FeatureToggles,
    private val clock: Clock,
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) {
    private val log = Log.tag("BackgroundActions")
    private val lock = Mutex()
    private val running = mutableMapOf<ActionId, SessionRef>()
    private val helpers = mutableSetOf<SessionRef>()

    /** Whether command actions can run here. */
    val areCommandsAvailable: Boolean get() = commands.isAvailable && workspaces.isAvailable

    /** Whether [session] is a helper started by an action of this profile; helpers start no helpers. */
    suspend fun isHelper(session: SessionRef): Boolean = lock.withLock { session in helpers }

    /** Starts [command] for [parent] in [workspace]; returns why it was not started, or null. */
    suspend fun startCommand(
        id: ActionId,
        parent: SessionRef,
        workspace: WorkspaceRef,
        command: String,
        timeout: Duration,
    ): String? = reserve(id, parent) ?: run {
        val directory = workspaces.resolve(workspace)
        if (directory == null) {
            release(id)
            "the project is not available"
        } else {
            journal.add(ActionRecord(id, KIND_COMMAND, clock.now()))
            log.i { "action $id: command started, timeout=$timeout" }
            scope.coroutineScope.launch(dispatchers.io) {
                finish(id) { commands.run(directory, command, timeout).payload() }
            }
            null
        }
    }

    /**
     * Starts a helper session through the first host that creates sessions and waits in the background for its
     * first turn to finish; returns why it was not started, or null.
     */
    suspend fun startAgent(id: ActionId, request: SpawnRequest): String? =
        reserve(id, request.parent) ?: spawnAndWatch(id, request)

    private suspend fun spawnAndWatch(id: ActionId, request: SpawnRequest): String? {
        // Turn ends are watched from before the spawn: a quick helper may finish before the spawn returns.
        val finished = Channel<EventKey>(Channel.UNLIMITED)
        val watcher = scope.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { if (it.key.namespace == EventNamespace.Session) finished.trySend(it.key) }
        }
        var spawned: SessionRef? = null
        try {
            spawned = spawnOrNull(id, request)
        } finally {
            if (spawned == null) {
                watcher.cancel()
                withContext(NonCancellable) { release(id) }
            }
        }
        if (spawned == null) return "no chat host could start a helper agent"
        lock.withLock { helpers += spawned }
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
        // While the scheduler is off nothing is delivered: the journal waits for a start with the toggle on.
        if (!toggles.get(SchedulerEnabled)) return
        val interrupted = journal.takeAll()
        if (interrupted.isEmpty()) return
        log.i { "actions interrupted by a restart: ${interrupted.size}" }
        // The bus has no replay and the wake driver may not listen yet: the machine gets the events directly.
        machine.state.first { it is SchedulerState.Ready }
        interrupted.forEach { record ->
            val event = BusEvent(
                EventKeys.actionFinished(record.id),
                EventOrigin.Action(record.id),
                clock.now(),
                "status: interrupted (the app closed before the ${record.kind} finished)",
            )
            if ((machine.state.value as? SchedulerState.Ready)?.isAwaited(event) == true) {
                machine.send(SchedulerIntent.Internal.Observed(event))
            }
        }
    }

    /** Takes a running slot for [id] of [parent]; returns why there is none, or null. */
    private suspend fun reserve(id: ActionId, parent: SessionRef): String? = lock.withLock {
        when {
            running.size >= MAX_RUNNING -> "the profile already runs $MAX_RUNNING background actions"

            running.values.count { it == parent } >= MAX_RUNNING_PER_SESSION ->
                "this session already runs $MAX_RUNNING_PER_SESSION background actions"

            else -> {
                running[id] = parent
                null
            }
        }
    }

    private suspend fun release(id: ActionId) {
        lock.withLock { running.remove(id) }
    }

    /** The helper created by the first host that creates sessions; null when none did. */
    private suspend fun spawnOrNull(id: ActionId, request: SpawnRequest): SessionRef? = try {
        hosts.value.sortedByDescending { it.priority }.firstNotNullOfOrNull { it.spawn(request) }
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
        release(id)
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
