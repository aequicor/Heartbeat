package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray

/** Exact native turn delivery, independent of the turn-start state machine. */
internal class CodexSteering(private val session: CodexSession, private val nativeId: (TurnId) -> String?) {
    private val log = Log.tag("CodexSteering")

    /** expectedTurnId is checked atomically by app-server; steering cannot start an idle thread. */
    suspend fun send(expected: TurnId, request: SteeringRequest) {
        session.runtime.gate()
        val origin = session.connection
        fun checkTurn(): String {
            val current = when (val state = session.machine.state.value) {
                is ActiveSessionState.Running -> state.turn
                is ActiveSessionState.AwaitingUserAction -> state.turn
                else -> fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
            }
            if (current.id != expected || !session.isActiveExecutionOwned || session.connection !== origin) {
                fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
            }
            return nativeId(expected) ?: protocolFailure()
        }
        checkTurn()
        val input = codexPromptInputs(
            PromptRequest(request.id, request.parts),
            session.runtime.inputSupport(session.target.model),
            session.runtime.host.resources,
        )
        session.runtime.host.resourceHistory.remember(session.ref, "request:" + request.id.value, request.parts)
        val native = checkTurn()
        session.history.inputs.rememberSteering(expected, request)
        try {
            val reply = origin.rpc.request(
                "turn/steer",
                json(
                    "threadId" to session.ref.nativeId.json(),
                    "expectedTurnId" to native.json(),
                    "input" to JsonArray(input.parts),
                ),
            )
            if (reply.text("turnId") != native) {
                fail(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineException) {
            log.w(error) { "Codex steering was not acknowledged" }
            val failure = error.failure
            if (failure is EngineFailure.Request && failure.reason != RequestFailureReason.OutcomeUnknown) {
                session.history.inputs.forgetSteering(expected, request)
                throw error
            }
            fail(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
        }
    }
}
