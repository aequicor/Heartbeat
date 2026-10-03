package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseStatus
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.HostComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.inputWaitLimitMillis
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/** Public host operations are correlated machine commands; status and window enumeration read the host directly. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoutedComputerControl(
    private val coordinator: CaptureCoordinator,
    private val access: ComputerUseAccess,
    private val machines: MachineRegistry,
) : HostComputerControl {
    private val log = Log.tag("RoutedComputerControl")

    override suspend fun status(): ComputerUseStatus {
        val capabilities = access.probe()
        val active = access.active().takeIf { capabilities.isCaptureAvailable }
        return ComputerUseStatus(
            capabilities = capabilities,
            mode = active?.mode,
            isInputArmed = active?.isInputArmed == true && capabilities.isInputAvailable,
            lastPreview = active?.lastPreview,
        )
    }

    override suspend fun windows(): List<WindowTarget> =
        if (access.probe().isWindowCaptureAvailable) coordinator.targets() else emptyList()

    override suspend fun resolveWindow(id: io.aequicor.heartbeat.feature.computeruse.api.WindowId): WindowTarget? =
        if (access.probe().isWindowCaptureAvailable) coordinator.resolveTarget(id) else null

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        val failure = access.captureFailure()
        if (failure != null) return CaptureResult(failure = failure)
        return dispatch { requestId, state ->
            ComputerUseIntent.Public.Capture(request, requestId, state.session)
        }.captureAnswer()
    }

    override suspend fun crop(request: CropRequest): CaptureResult {
        val failure = access.captureFailure()
        if (failure != null) return CaptureResult(failure = failure)
        return dispatch { requestId, state ->
            ComputerUseIntent.Public.Crop(request, requestId, state.session)
        }.captureAnswer()
    }

    override suspend fun input(action: InputAction): InputOutcome {
        val failure = access.inputFailure()
        if (failure != null) return InputOutcome.Rejected(failure)
        val output = dispatch(inputWaitLimitMillis(action)) { requestId, state ->
            ComputerUseIntent.Public.Input(action, requestId, state.session, state.lastPreview?.id)
        }
        return when (output) {
            is ComputerUseOutput.InputApplied -> InputOutcome.Applied

            is ComputerUseOutput.Rejected -> InputOutcome.Rejected(output.reason)

            is ComputerUseOutput.FrameReady, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.SessionClosed, is ComputerUseOutput.PermissionRequired, ComputerUseOutput.Revoked,
            -> InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
    }

    /** Subscribes before sending, and fences cancellation to the session that accepted this operation. */
    private suspend fun dispatch(
        timeoutMillis: Long = OPERATION_TIMEOUT_MILLIS,
        intent: (String, ComputerUseState.Capturing) -> ComputerUseIntent.Public,
    ): ComputerUseOutput = coroutineScope {
        val machine = machines.find(ComputerUseMachineKey)
            ?: return@coroutineScope ComputerUseOutput.Rejected(ComputerUseFailure.Unavailable)
        val capture = access.active()
            ?: return@coroutineScope ComputerUseOutput.Rejected(ComputerUseFailure.Unavailable)
        val requestId = Uuid.random().toString()
        val awaited = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.mapNotNull { output ->
                output.answer(
                    requestId,
                    capture.session,
                )
            }.first()
        }
        var isAccepted = false
        var hasAnswer = false
        try {
            val command = intent(requestId, capture)
            isAccepted = machine.send(command) == SendResult.Accepted
            if (!isAccepted) return@coroutineScope ComputerUseOutput.Rejected(command.refusal(capture))
            val answer = withTimeoutOrNull(timeoutMillis) { awaited.await() }
            hasAnswer = answer != null
            answer ?: ComputerUseOutput.Rejected(ComputerUseFailure.Timeout)
        } finally {
            awaited.cancel()
            if (isAccepted && !hasAnswer) {
                withContext(NonCancellable) { cancelSession(machine, capture.session) }
            }
        }
    }

    private fun ComputerUseIntent.Public.refusal(capture: ComputerUseState.Capturing): ComputerUseFailure =
        if (this is ComputerUseIntent.Public.Input) {
            ComputerUseFailure.InputRejected
        } else if (this is ComputerUseIntent.Public.Crop && capture.master != null) {
            ComputerUseFailure.RegionOutOfBounds
        } else {
            ComputerUseFailure.Unavailable
        }

    override suspend fun revoke() = coroutineScope {
        val machine = machines.find(ComputerUseMachineKey) ?: return@coroutineScope
        val session = access.active()?.session
        val closed = session?.let {
            async(start = CoroutineStart.UNDISPATCHED) {
                machine.outputs.first { output ->
                    output is ComputerUseOutput.SessionClosed && output.session == session
                }
            }
        }
        try {
            val sent = machine.send(ComputerUseIntent.Public.Revoke)
            log.i { "computer use revoke result=$sent" }
            if (sent == SendResult.Accepted && closed != null) {
                val cleanup = withTimeoutOrNull(OPERATION_TIMEOUT_MILLIS) { closed.await() }
                if (cleanup == null) {
                    log.w { "capture cleanup acknowledgement timed out session=$session" }
                    error("CleanupTimedOut")
                }
            }
        } finally {
            closed?.cancel()
        }
    }

    private suspend fun cancelSession(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        session: CaptureSessionId,
    ) {
        val sent = machine.send(ComputerUseIntent.Public.CancelSession(session))
        log.i { "computer use operation cancelled session=$session result=$sent" }
    }

    private fun ComputerUseOutput.answer(requestId: String, session: CaptureSessionId): ComputerUseOutput? =
        when (this) {
            is ComputerUseOutput.InputApplied -> takeIf { this.requestId == requestId }

            is ComputerUseOutput.Rejected -> takeIf { this.requestId == requestId }

            is ComputerUseOutput.FrameReady -> takeIf { this.requestId == requestId }

            is ComputerUseOutput.CaptureChanged, ComputerUseOutput.Revoked -> null

            is ComputerUseOutput.SessionClosed -> if (this.session == session) {
                ComputerUseOutput.Rejected(reason ?: ComputerUseFailure.Unavailable)
            } else {
                null
            }

            is ComputerUseOutput.PermissionRequired -> null
        }

    private fun ComputerUseOutput.captureAnswer(): CaptureResult = when (this) {
        is ComputerUseOutput.FrameReady -> CaptureResult(reference = capture, master = master, tiles = tiles)

        is ComputerUseOutput.Rejected -> CaptureResult(failure = reason)

        is ComputerUseOutput.InputApplied, is ComputerUseOutput.CaptureChanged,
        is ComputerUseOutput.SessionClosed, is ComputerUseOutput.PermissionRequired, ComputerUseOutput.Revoked,
        -> CaptureResult(failure = ComputerUseFailure.CaptureFailed)
    }

    private companion object {
        const val OPERATION_TIMEOUT_MILLIS = 30_000L
    }
}
