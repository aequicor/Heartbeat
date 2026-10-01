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
     * Releases only this owner's active capture and awaits all its already-ending capture sessions within one
     * shared deadline. An unconfirmed closure is logged and forgotten, so a lost acknowledgement neither fails the
     * finished turn nor keeps its waiters alive. A cancelled caller keeps the waiters for a retry.
     */
    suspend fun finishTurn(owner: CaptureOwner.Agent): Unit = guard.withLock {
        val cleanups = ownerCaptures.getOrPut(owner) { mutableListOf() }
        val machine = machines.find(ComputerUseMachineKey)
        val state = machine?.state?.value as? ComputerUseState.Capturing
        if (state?.owner == owner) {
            if (cleanups.none { it.session == state.session }) cleanups += pendingCleanup(machine, state.session)
            val result = machine.send(ComputerUseIntent.Public.OwnerReleased(owner))
            log.i { "agent turn released computer capture result=$result" }
        }
        val isConfirmed = withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { cleanups.forEach { it.closed.await() } } != null
        if (!isConfirmed) {
            val pending = cleanups.filterNot { it.closed.isCompleted }
            log.w { "agent turn capture cleanup was not confirmed in time sessions=${pending.size}" }
            pending.forEach { it.closed.cancel() }
        }
        ownerCaptures.remove(owner)
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
