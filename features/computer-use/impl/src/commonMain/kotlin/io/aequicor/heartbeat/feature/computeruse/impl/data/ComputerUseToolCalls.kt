package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

// Correlated machine calls of the hosted computer tools: one operation, one reply, cleanup of the own session only.

/** How long a tool waits for a correlated reply before cancelling its own session. */
internal const val OUTPUT_TIMEOUT_MILLIS = 30_000L

/** Subscribes before sending and matches the unique operation ID, including rejected responses. */
internal suspend fun awaitOutput(
    machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
    intent: ComputerUseIntent.Public,
    timeoutMillis: Long = OUTPUT_TIMEOUT_MILLIS,
): ComputerUseOutput? = coroutineScope {
    val session = (machine.state.value as? ComputerUseState.Capturing)?.session
    val id = Uuid.random().toString()
    var owner: CaptureOwner? = null
    val correlated = when (intent) {
        is ComputerUseIntent.Public.Capture -> intent.copy(requestId = id).also { owner = it.expectedOwner }

        is ComputerUseIntent.Public.Crop -> intent.copy(requestId = id).also { owner = it.expectedOwner }

        is ComputerUseIntent.Public.Input -> intent.copy(requestId = id).also { owner = it.expectedOwner }

        is ComputerUseIntent.Public.Record -> intent.copy(requestId = id).also { owner = it.expectedOwner }

        ComputerUseIntent.Public.Start, ComputerUseIntent.Public.Retry,
        ComputerUseIntent.Public.RefreshTargets, ComputerUseIntent.Public.EndCapture,
        ComputerUseIntent.Public.Revoke, is ComputerUseIntent.Public.ArmInput,
        is ComputerUseIntent.Public.BeginCapture, is ComputerUseIntent.Public.CancelSession,
        is ComputerUseIntent.Public.SwitchMode, is ComputerUseIntent.Public.OwnerReleased,
        is ComputerUseIntent.Public.StopAgent, is ComputerUseIntent.Public.SetRoi,
        -> error("Only operation intents have correlated replies")
    }
    val answered = async(start = CoroutineStart.UNDISPATCHED) {
        machine.outputs.first { it.requestId() == id }
    }
    // A stopped or replaced session never answers; its closure is the answer.
    val closed = async(start = CoroutineStart.UNDISPATCHED) {
        val ended = machine.state.first { (it as? ComputerUseState.Capturing)?.session != session }
        ComputerUseOutput.SessionClosed(
            session ?: CaptureSessionId(""),
            (ended as? ComputerUseState.Failed)?.takeIf { it.session == session }?.reason,
        )
    }
    var isCompleted = false
    try {
        if (machine.send(correlated) != SendResult.Accepted) {
            isCompleted = true
            return@coroutineScope ComputerUseOutput.Rejected(ignoredReason(machine.state.value, intent), id)
        }
        val result = withTimeoutOrNull(timeoutMillis) {
            select {
                answered.onAwait { it }
                closed.onAwait { it }
            }
        }
        isCompleted = result != null
        result
    } finally {
        answered.cancel()
        closed.cancel()
        if (!isCompleted && session != null) {
            // Cancel state effects as well as the waiter, without touching any replacement session.
            withContext(NonCancellable) { machine.send(ComputerUseIntent.Public.CancelSession(session, owner)) }
        }
    }
}

private fun ignoredReason(state: ComputerUseState, intent: ComputerUseIntent.Public): ComputerUseFailure =
    if (intent is ComputerUseIntent.Public.Input && state is ComputerUseState.Capturing) {
        if (!state.isInputArmed) ComputerUseFailure.NotArmed else ComputerUseFailure.ModeNotAllowed
    } else {
        ComputerUseFailure.Unavailable
    }

private fun ComputerUseOutput.requestId(): String? = when (this) {
    is ComputerUseOutput.FrameReady -> requestId

    is ComputerUseOutput.Rejected -> requestId

    is ComputerUseOutput.InputApplied -> requestId

    is ComputerUseOutput.ClipReady -> requestId

    is ComputerUseOutput.CaptureChanged, is ComputerUseOutput.PermissionRequired,
    is ComputerUseOutput.SessionClosed, ComputerUseOutput.Revoked,
    -> null
}
