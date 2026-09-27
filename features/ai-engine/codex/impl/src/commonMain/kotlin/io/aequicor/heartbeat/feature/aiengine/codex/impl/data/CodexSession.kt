package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.activeSessionMachineSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

internal class CodexSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    val runtime: CodexRuntime,
    private val rpc: CodexRpc,
) {
    private val log = Log.tag("CodexSession")
    val history = CodexHistory()
    private val scope = runtime.host.scopes.child(runtime.profile, "codex-${Uuid.random()}")
    private val nativeTurns = mutableMapOf<String, TurnId>()
    private var nativeTurn: String? = null
    private val submitLock = Mutex()
    private val submissions = mutableMapOf<TurnId, CompletableDeferred<TurnId>>()
    private val permissions = mutableMapOf<PermissionRequestId, JsonElement>()
    private val finished = mutableSetOf<TurnId>()
    val machine = runtime.host.launcher.launch(
        activeSessionMachineSpec(ActiveSessionMachineKey(Uuid.random().toString()), ActiveSessionState.Ready()),
        scope,
        EffectHandler { effect, _ ->
            // Handoff to the profile-owned scope. State changes must not cancel accepted native work.
            scope.coroutineScope.launch { execute(effect) }
        },
    )

    private val leases = mutableSetOf<CodexLease>()
    fun lease(): ActiveSession = CodexLease(this).also { leases += it }
    fun release(lease: CodexLease) {
        leases -= lease
    }

    suspend fun send(request: PromptRequest): TurnId {
        if (!submitLock.tryLock()) fail(EngineFailure.Session(SessionFailureReason.Busy))
        try {
            runtime.gate()
            if (request.parts.any { it !is ContentPart.Text }) {
                fail(
                    EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id),
                )
            }
            if (machine.state.value !is ActiveSessionState.Ready) fail(EngineFailure.Session(SessionFailureReason.Busy))
            val turn = Turn(TurnId(Uuid.random().toString()), request.id, target)
            val accepted = CompletableDeferred<TurnId>()
            submissions[turn.id] = accepted
            nativeTurn = null
            val result = machine.send(ActiveSessionIntent.Public.Submit(request, turn))
            if (result != SendResult.Accepted) fail(EngineFailure.Session(SessionFailureReason.Busy))
            return accepted.await()
        } finally {
            submitLock.unlock()
        }
    }

    suspend fun cancel(turn: TurnId) {
        runtime.ensureOpen()
        if (machine.send(
                ActiveSessionIntent.Public.Cancel(turn),
            ) != SendResult.Accepted
        ) {
            fail(EngineFailure.Session(SessionFailureReason.Changed))
        }
    }

    suspend fun respond(decision: PermissionDecision) {
        runtime.ensureOpen()
        if (machine.send(
                ActiveSessionIntent.Public.Decide(decision),
            ) != SendResult.Accepted
        ) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid))
        }
    }

    private suspend fun execute(effect: ActiveSessionEffect) {
        try {
            val id = effect.turnId()
            if (id != null && id != currentTurn()?.id) return
            executeCommand(effect)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex operation failed" }
            val failure = if (effect is ActiveSessionEffect.Submit && e.failure !is EngineFailure.Request) {
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, effect.request.id)
            } else {
                e.failure
            }
            val id = effect.turnId()
            if (id != null) submissions.remove(id)?.completeExceptionally(EngineException(failure))
            machine.send(ActiveSessionIntent.Internal.Failed(id, failure))
        }
    }

    private suspend fun executeCommand(effect: ActiveSessionEffect) {
        when (effect) {
            is ActiveSessionEffect.Submit -> submit(effect)

            is ActiveSessionEffect.Cancel -> rpc.request(
                "turn/interrupt",
                json(
                    "threadId" to ref.nativeId.json(),
                    "turnId" to
                        (
                            nativeTurns.entries.firstOrNull {
                                it.value == effect.turn
                            }?.key ?: protocolFailure()
                        ).json(),
                ),
            )

            is ActiveSessionEffect.Decide -> {
                val id = permissions[effect.decision.request] ?: protocolFailure()
                rpc.respond(id, json("decision" to effect.decision.option.value.json()))
            }

            is ActiveSessionEffect.Recheck -> unsupported()

            ActiveSessionEffect.Release -> machine.send(ActiveSessionIntent.Internal.Released)
        }
    }

    private suspend fun submit(effect: ActiveSessionEffect.Submit) {
        val input = JsonArray(
            effect.request.parts.map { json("type" to "text".json(), "text" to (it as ContentPart.Text).text.json()) },
        )
        val response = rpc.request(
            "turn/start",
            json("threadId" to ref.nativeId.json(), "model" to target.model.value.json(), "input" to input),
        )
        val id = response.obj("turn").text("id") ?: protocolFailure()
        nativeTurns[id] = effect.turn.id
        if (currentTurn()?.id == effect.turn.id) nativeTurn = id
        accept(effect.turn)
    }

    private suspend fun accept(turn: Turn) {
        if (submissions.remove(
                turn.id,
            )?.complete(turn.id) == true
        ) {
            history.publish { SessionEvent.TurnStarted(it, turn) }
        }
        machine.send(ActiveSessionIntent.Internal.Accepted(turn.id))
    }

    fun load(turns: List<JsonElement>) {
        for (value in turns) {
            val turn = value as? JsonObject ?: protocolFailure()
            val id = TurnId(turn.text("id") ?: protocolFailure())
            (turn["items"] as? JsonArray).orEmpty().forEach {
                history.nativeItem(
                    it as? JsonObject ?: protocolFailure(),
                    id,
                )
            }
        }
    }

    suspend fun event(message: JsonObject) {
        val params = message.obj("params")
        val method = message.text("method")
        val turn = currentTurn()
        val turnId = correlate(params, turn)
        when (method) {
            "turn/started" -> if (turn != null && turn.id == turnId) accept(turn)
            "turn/completed" -> if (turnId != null) complete(turnId, params.obj("turn"))
            "item/started", "item/completed" -> history.nativeItem(params.obj("item"), turnId)
            "item/agentMessage/delta" -> history.delta(params, turnId)
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> approval(message, turn)
            "serverRequest/resolved" -> resolved(params, turn)
            else -> if (message["id"] != null) rpc.reject(checkNotNull(message["id"]))
        }
    }

    private fun correlate(params: JsonObject, turn: Turn?): TurnId? {
        val native = params.text("turnId") ?: (params["turn"] as? JsonObject)?.text("id") ?: return null
        if (native in nativeTurns) return nativeTurns[native]
        if (turn == null || nativeTurn != null) return null
        if (machine.state.value !is ActiveSessionState.Submitting) return null
        nativeTurn = native
        nativeTurns[native] = turn.id
        return turn.id
    }

    private suspend fun complete(id: TurnId, native: JsonObject) {
        if (!finished.add(id)) return
        val outcome = when (native.text("status")) {
            "completed" -> TurnOutcome.Completed
            "interrupted" -> TurnOutcome.Cancelled
            "failed" -> TurnOutcome.Failed(EngineFailure.Unknown())
            else -> TurnOutcome.Unknown
        }
        currentTurn()?.takeIf { it.id == id }?.let { accept(it) }
        machine.send(ActiveSessionIntent.Internal.Finished(id, outcome))
        history.publish { SessionEvent.TurnFinished(it, id, outcome) }
        nativeTurn = null
        permissions.clear()
    }

    private suspend fun approval(message: JsonObject, turn: Turn?) {
        val id = message["id"] ?: protocolFailure()
        if (turn == null || message.obj("params").text("turnId") != nativeTurn) {
            rpc.respond(id, json("decision" to "decline".json()))
            return
        }
        val params = message.obj("params")
        val requestId = PermissionRequestId(id.toString())
        val offered = (params["availableDecisions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: listOf("accept", "decline", "cancel")
        val options = offered.filter {
            it in setOf(
                "accept",
                "decline",
                "cancel",
            )
        }.map { PermissionOption(PermissionOptionId(it), it) }
        if (options.isEmpty()) {
            rpc.reject(id)
            return
        }
        val request = PermissionRequest(
            requestId,
            turn.id,
            params.text("command") ?: params.text("reason") ?: "Codex file change",
            options,
        )
        permissions[requestId] = id
        accept(turn)
        machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request))
        history.publish { SessionEvent.PermissionRequested(it, request) }
    }

    private suspend fun resolved(params: JsonObject, turn: Turn?) {
        if (turn == null) return
        val id = PermissionRequestId((params["requestId"] ?: protocolFailure()).toString())
        permissions.remove(id)
        machine.send(ActiveSessionIntent.Internal.PermissionResolved(turn.id, id))
    }

    private fun failPending(failure: EngineFailure) {
        submissions.forEach { (id, pending) ->
            val request = currentTurn()?.takeIf { it.id == id }?.request
            val reason = if (request != null) {
                EngineFailure.Request(
                    RequestFailureReason.OutcomeUnknown,
                    request,
                )
            } else {
                failure
            }
            pending.completeExceptionally(EngineException(reason))
        }
        submissions.clear()
    }

    fun shutdown(failure: EngineFailure) {
        failPending(failure)
        history.invalidate()
        val last = when (val state = machine.state.value) {
            is ActiveSessionState.Ready -> state.lastTurn
            is ActiveSessionState.Unavailable -> state.lastTurn
            else -> null
        }
        val finalState = ActiveSessionState.Unavailable(failure, currentTurn(), last)
        leases.forEach { it.terminate(finalState) }
        leases.clear()
        scope.close()
    }

    private fun ActiveSessionEffect.turnId(): TurnId? = when (this) {
        is ActiveSessionEffect.Submit -> turn.id
        is ActiveSessionEffect.Cancel -> turn
        is ActiveSessionEffect.Decide -> decision.turn
        is ActiveSessionEffect.Recheck -> turn
        ActiveSessionEffect.Release -> null
    }

    private fun currentTurn(): Turn? = when (val state = machine.state.value) {
        is ActiveSessionState.Submitting -> state.turn
        is ActiveSessionState.Running -> state.turn
        is ActiveSessionState.AwaitingUserAction -> state.turn
        is ActiveSessionState.Interrupting -> state.turn
        is ActiveSessionState.Unavailable -> state.activeTurn
        is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
    }
}
