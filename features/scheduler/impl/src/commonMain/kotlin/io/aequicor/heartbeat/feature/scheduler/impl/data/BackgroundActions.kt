package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
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
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityLimits
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityRejection
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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

private const val KIND_COMMAND = "command"
private const val KIND_AGENT = "agent"
private const val HISTORY_WINDOW = 20

/**
 * Background actions started by agents: a shell command in the project or a helper agent in a new session. Each runs
 * in the profile scope, outliving the turn that started it, and publishes `action.<id>.finished` with its result as
 * the payload. Running actions are journaled, so a restart reports the ones it interrupted. Admission shares
 * [BackgroundCapacityLimits.PROFILE] slots with workflow helpers, retaining
 * [BackgroundCapacityLimits.SCHEDULED_PER_SESSION] scheduled actions per session. Text is never logged.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class BackgroundActions(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    private val bus: SchedulerBus,
    private val commands: CommandRunner,
    private val workspaces: LocalWorkspaces,
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
    private val clock: Clock,
    private val capacity: ProfileBackgroundCapacity,
    private val results: ActionResults,
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) {
    private val log = Log.tag("BackgroundActions")
    private val lock = Mutex()
    private val helpers = mutableSetOf<SessionRef>()

    /** Replays durable results when enabled and retires them only after their waits settle or are cancelled. */
    suspend fun start() = results.start()

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
            results.begin(record)
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
            results.begin(record)
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

    /** Replays completed results and reports scheduler operations interrupted by a restart. */
    suspend fun recover() = results.recover()

    /** Takes a running slot for [id] of [parent]; returns why there is none, or null. */
    private suspend fun reserve(id: ActionId, parent: SessionRef): String? {
        start()
        return lock.withLock {
            when (capacity.tryAcquireScheduled(id, parent)) {
                BackgroundCapacityRejection.ProfileLimit ->
                    "the profile already runs ${BackgroundCapacityLimits.PROFILE} background actions"

                BackgroundCapacityRejection.SessionLimit ->
                    "this session already runs ${BackgroundCapacityLimits.SCHEDULED_PER_SESSION} background actions"

                BackgroundCapacityRejection.Duplicate -> "this action is already running"

                null -> null
            }
        }
    }

    private suspend fun release(id: ActionId) {
        results.releaseOwnership(id)
        capacity.release(id)
    }

    /** A failed start must not consume a slot or leave an interruption report for work that never began. */
    private suspend fun rollback(id: ActionId) = withContext(NonCancellable) {
        try {
            results.abandon(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action $id: failed to remove an unstarted action" }
        } finally {
            withContext(NonCancellable) { release(id) }
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
            results.complete(record, payload)
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
