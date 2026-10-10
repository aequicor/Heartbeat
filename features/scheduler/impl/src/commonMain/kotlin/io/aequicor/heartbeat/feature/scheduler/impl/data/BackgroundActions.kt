package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityLimits
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityRejection
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

private const val KIND_COMMAND = "command"
private const val KIND_AGENT = "agent"

/**
 * Background actions started by agents: a shell command in the project or a helper agent in a new session. Each runs
 * in the profile scope, outliving the turn that started it, and publishes `action.<id>.finished` with its result as
 * the payload. Running actions are journaled; helper results require terminal proof even after timeout or restart.
 * Admission shares
 * [BackgroundCapacityLimits.PROFILE] slots with workflow helpers, retaining
 * [BackgroundCapacityLimits.SCHEDULED_PER_SESSION] scheduled actions per session. Text is never logged.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class BackgroundActions(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    private val commands: CommandRunner,
    private val workspaces: LocalWorkspaces,
    private val clock: Clock,
    private val capacity: ProfileBackgroundCapacity,
    private val results: ActionResults,
    private val helperActions: ScheduledHelperActions,
) {
    private val log = Log.tag("BackgroundActions")
    private val lock = Mutex()

    /** Replays durable results when enabled and retires them only after their waits settle or are cancelled. */
    suspend fun start() {
        helperActions.recover()
        results.start()
    }

    /** Whether command actions can run here. */
    val areCommandsAvailable: Boolean get() = commands.isAvailable && workspaces.isAvailable

    /** Whether [session] is a helper started by an action of this profile; helpers start no helpers. */
    suspend fun isHelper(session: SessionRef): Boolean = helperActions.isHelper(session)

    /** Starts [command] for [caller] in [workspace]; returns why it was not started, or null. */
    suspend fun startCommand(
        id: ActionId,
        caller: BackgroundActionCaller,
        workspace: WorkspaceRef,
        command: String,
        timeout: Duration,
    ): String? = reserve(id, caller.session) ?: run {
        var isStarted = false
        try {
            val directory = workspaces.resolve(workspace) ?: return@run "the project is not available"
            val record = ActionRecord(id, KIND_COMMAND, clock.now(), initiator = caller.initiator())
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
     * Starts a helper through a host with request-correlated supervision and awaits its exact attempt in background;
     * returns why it was not started, or null. Once journaled and handed to the profile, startup
     * belongs to that profile too: cancelling the tool's wait cannot abandon an accepted helper.
     */
    suspend fun startAgent(id: ActionId, request: SpawnRequest, initiator: RequestInitiator? = null): String? = reserve(
        id,
        request.parent,
    ) ?: run {
        var isHandedOff = false
        try {
            val record = ActionRecord(
                id,
                KIND_AGENT,
                clock.now(),
                parent = request.parent,
                request = request.prompt.request,
                initiator = initiator,
            )
            results.begin(record)
            val startup = scope.coroutineScope.async(
                start = CoroutineStart.LAZY,
            ) { helperActions.start(record, request) }
            startup.start()
            isHandedOff = true
            startup.await()
        } finally {
            if (!isHandedOff) withContext(NonCancellable) { rollback(id) }
        }
    }

    /** Replays completed results and reports scheduler operations interrupted by a restart. */
    suspend fun recover() {
        helperActions.recover()
        results.recover()
    }

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
}

private fun CommandOutcome.payload(): String =
    "status: ${exitCode?.let { "exited with code $it" } ?: "timed out"}\noutput:\n$output"
