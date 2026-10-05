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
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.combine
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
    private val persistence: SchedulerPersistence,
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
    private val resultsLock = Mutex()
    private val running = mutableMapOf<ActionId, SessionRef>()
    private val helpers = mutableSetOf<SessionRef>()
    private var recovery: Job? = null

    /** Replays durable results when enabled and retires them only after their waits settle or are cancelled. */
    suspend fun start() = lock.withLock {
        if (recovery != null) return@withLock
        recovery = scope.coroutineScope.launch {
            combine(toggles.observe(SchedulerEnabled), machine.state, persistence.revision) { enabled, state, _ ->
                enabled && state is SchedulerState.Ready
            }.collect { ready ->
                if (ready) recoverSafely()
            }
        }
    }

    private suspend fun recoverSafely() {
        try {
            recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action recovery failed" }
        }
    }

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
        var isStarted = false
        try {
            val directory = workspaces.resolve(workspace) ?: return@run "the project is not available"
            val record = ActionRecord(id, KIND_COMMAND, clock.now())
            journal.add(record)
            log.i { "action $id: command started, timeout=$timeout" }
            scope.coroutineScope.launch(dispatchers.io) {
                finish(record) { commands.run(directory, command, timeout).payload() }
            }
            isStarted = true
            null
        } finally {
            if (!isStarted) withContext(NonCancellable) { rollback(id) }
        }
    }

    /**
     * Starts a helper session through the first host that creates sessions and waits in the background for its
     * first turn to finish; returns why it was not started, or null. Once journaled and handed to the profile, startup
     * belongs to that profile too: cancelling the tool's wait cannot abandon an accepted helper.
     */
    suspend fun startAgent(id: ActionId, request: SpawnRequest): String? = reserve(id, request.parent) ?: run {
        var isHandedOff = false
        try {
            val record = ActionRecord(id, KIND_AGENT, clock.now())
            journal.add(record)
            val startup = scope.coroutineScope.async(start = CoroutineStart.LAZY) { spawnAndWatch(record, request) }
            startup.start()
            isHandedOff = true
            startup.await()
        } finally {
            if (!isHandedOff) withContext(NonCancellable) { rollback(id) }
        }
    }

    private suspend fun spawnAndWatch(record: ActionRecord, request: SpawnRequest): String? {
        val id = record.id
        // Turn ends are watched from before the spawn: a quick helper may finish before the spawn returns.
        val finished = Channel<EventKey>(Channel.UNLIMITED)
        val watcher = scope.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { if (it.key.namespace == EventNamespace.Session) finished.trySend(it.key) }
        }
        var isStarted = false
        try {
            val helper = spawnOrNull(id, request) ?: return "no chat host could start a helper agent".also {
                log.w { "action $id: no host accepted the helper" }
            }
            // Once accepted, ownership must reach the profile even if the calling tool is cancelled now.
            withContext(NonCancellable) {
                lock.withLock { helpers += helper }
                scope.coroutineScope.launch {
                    try {
                        finish(record) { helperResult(helper, finished) }
                    } finally {
                        watcher.cancel()
                    }
                }
                isStarted = true
            }
            log.i { "action $id: helper agent started on ${helper.engine.value}" }
            return null
        } finally {
            if (!isStarted) {
                watcher.cancel()
                withContext(NonCancellable) { rollback(id) }
            }
        }
    }

    /** Replays completed results and marks only actions no longer owned by this profile run as interrupted. */
    suspend fun recover() {
        // While disabled, keep both interrupted actions and completed results for the next enable.
        if (!toggles.get(SchedulerEnabled)) return
        machine.state.first { it is SchedulerState.Ready }
        resultsLock.withLock {
            journal.readAll().forEach { stored ->
                if (stored.payload == null && lock.withLock { stored.id in running }) return@forEach
                val record = if (stored.payload != null) {
                    stored
                } else {
                    stored.copy(payload = "status: interrupted (the app closed before the ${stored.kind} finished)")
                        .also { journal.add(it) }
                }
                deliverResult(record)
            }
        }
    }

    private suspend fun deliverResult(record: ActionRecord) {
        val event = BusEvent(
            EventKeys.actionFinished(record.id),
            EventOrigin.Action(record.id),
            clock.now(),
            record.payload,
        )
        val ready = machine.state.value as? SchedulerState.Ready ?: return
        if (ready.wakes.none { it.matches(event) }) {
            // A memory-only settlement must not erase the result of a wake that can return after a crash.
            if (persistence.revision.value >= ready.revision) journal.remove(record.id)
        } else if (ready.isAwaited(event)) {
            // The bus has no replay: recovery must also work before the driver has subscribed.
            machine.send(SchedulerIntent.Internal.Observed(event))
        }
    }

    /** Takes a running slot for [id] of [parent]; returns why there is none, or null. */
    private suspend fun reserve(id: ActionId, parent: SessionRef): String? {
        start()
        return lock.withLock {
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
    }

    private suspend fun release(id: ActionId) {
        lock.withLock { running.remove(id) }
    }

    /** A failed start must not consume a slot or leave an interruption report for work that never began. */
    private suspend fun rollback(id: ActionId) = withContext(NonCancellable) {
        resultsLock.withLock {
            try {
                // A profile-owned startup interrupted by closing the profile must be reported on reopening.
                if (!scope.isClosed) journal.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "action $id: failed to remove an unstarted action" }
            } finally {
                withContext(NonCancellable) { release(id) }
            }
        }
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

    private suspend fun finish(record: ActionRecord, result: suspend () -> String) {
        val id = record.id
        try {
            val payload = try {
                result()
            } catch (e: CancellationException) {
                // The profile closed: the journal keeps the action, and the next start reports it as interrupted.
                throw e
            } catch (e: Exception) {
                log.w(e) { "action $id failed" }
                "status: failed (${e::class.simpleName.orEmpty()})"
            }
            val completed = record.copy(payload = payload.take(SchedulerLimits.MAX_PAYLOAD))
            resultsLock.withLock { journal.add(completed) }
            bus.publish(EventKeys.actionFinished(id), EventOrigin.Action(id), completed.payload)
            recover()
            log.i { "action $id finished" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action $id: result delivery failed, retained for recovery" }
        } finally {
            withContext(NonCancellable) { release(id) }
        }
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
