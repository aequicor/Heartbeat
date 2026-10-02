package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps each turn's capture cleanup awaitable after the machine already returned to Ready or Idle. */
@Inject
@SingleIn(ProfileScope::class)
internal class ComputerUseCaptureLifecycle(
    private val machines: MachineRegistry,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val stoppedTurns: ComputerUseStoppedTurns,
) {
    private val log = Log.tag("ComputerUseCaptureLifecycle")
    private val guard = Mutex()
    private val ownerCaptures = mutableMapOf<CaptureOwner.Agent, MutableList<PendingCleanup>>()

    /** Registers cleanup before opening capture, so immediate closure cannot lose the acknowledgement. */
    suspend fun begin(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        owner: CaptureOwner.Agent,
        session: CaptureSessionId,
        intent: ComputerUseIntent.Public,
        lifetime: Job?,
    ): SendResult = guard.withLock {
        val cleanup = pendingCleanup(machine, session)
        var isRegistered = false
        try {
            val result = machine.send(intent)
            if (result == SendResult.Accepted) {
                ownerCaptures.getOrPut(owner) { mutableListOf() } += cleanup
                isRegistered = true
                lifetime?.invokeOnCompletion {
                    profile.coroutineScope.launch { finishTurn(owner) }
                }
            }
            result
        } finally {
            if (!isRegistered) cleanup.closed.cancel()
        }
    }

    /**
     * The owner's turn ended: releases its active capture, forgets a stop of that turn and awaits all its
     * already-ending capture sessions within one shared deadline. Waiting happens outside the profile-wide guard,
     * so other turns' barriers and captures do not queue behind it. An unconfirmed closure is logged and
     * forgotten; a cancelled caller keeps the waiters for a retry.
     */
    suspend fun finishTurn(owner: CaptureOwner.Agent) {
        val awaited = guard.withLock {
            val cleanups = ownerCaptures.getOrPut(owner) { mutableListOf() }
            val machine = machines.find(ComputerUseMachineKey)
            val state = machine?.state?.value
            val capture = state as? ComputerUseState.Capturing
            if (capture?.owner == owner && cleanups.none { it.session == capture.session }) {
                cleanups += pendingCleanup(machine, capture.session)
            }
            if (capture?.owner == owner || owner in state.stoppedOwners()) {
                val result = machine?.send(ComputerUseIntent.Public.OwnerReleased(owner)) ?: SendResult.Ignored
                log.i { "agent turn released its computer capture and stop result=$result" }
            }
            cleanups.toList()
        }
        val isConfirmed = withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { awaited.forEach { it.closed.await() } } != null
        if (!isConfirmed) {
            val pending = awaited.filterNot { it.closed.isCompleted }
            log.w { "agent turn capture cleanup was not confirmed in time sessions=${pending.size}" }
            pending.forEach { it.closed.cancel() }
        }
        guard.withLock {
            val remaining = ownerCaptures[owner]?.apply { removeAll(awaited) }
            if (remaining.isNullOrEmpty()) ownerCaptures.remove(owner)
        }
        stoppedTurns.forget(owner)
    }

    private fun ComputerUseState?.stoppedOwners(): Set<CaptureOwner.Agent> = when (this) {
        is ComputerUseState.Ready -> stoppedOwners

        is ComputerUseState.Capturing -> stoppedOwners

        ComputerUseState.Idle, ComputerUseState.Checking, is ComputerUseState.Unavailable,
        is ComputerUseState.Failed, null,
        -> emptySet()
    }

    private fun pendingCleanup(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        session: CaptureSessionId,
    ): PendingCleanup = PendingCleanup(
        session,
        profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first { it == ComputerUseOutput.SessionClosed(session) }
            Unit
        },
    )

    private data class PendingCleanup(val session: CaptureSessionId, val closed: Deferred<Unit>)

    private companion object {
        const val CLEANUP_TIMEOUT_MILLIS = 30_000L
    }
}
