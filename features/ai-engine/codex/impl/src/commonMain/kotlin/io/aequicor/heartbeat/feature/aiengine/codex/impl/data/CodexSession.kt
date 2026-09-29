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
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
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
    private val permissions = mutableMapOf<PermissionRequestId, Pair<JsonElement, PermissionRequest>>()
    private val finished = mutableSetOf<TurnId>()

    /** Running dynamic tool calls per turn; cancelled when the turn is interrupted or finishes. */
    private val toolJobs = mutableMapOf<TurnId, CompletableJob>()

    /** Turns whose tool jobs were closed; late tool calls for them are refused, never restarted. */
    private val toolsClosed = mutableSetOf<TurnId>()
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
            validateReasoningEffort(request)
            val turn = Turn(TurnId(Uuid.random().toString()), request.id, target)
            val accepted = CompletableDeferred<TurnId>()
            submissions[turn.id] = accepted
            nativeTurn = null
            val result = machine.send(ActiveSessionIntent.Public.Submit(request, turn))
            if (result != SendResult.Accepted) {
                submissions.remove(turn.id)
                fail(EngineFailure.Session(SessionFailureReason.Busy))
            }
            return accepted.await()
        } finally {
            submitLock.unlock()
        }
    }

    private suspend fun validateReasoningEffort(request: PromptRequest) {
        val effort = request.reasoningEffort ?: return
        val model = runtime.models(target.binding).firstOrNull { it.target == target }
        if (effort !in model?.reasoningEfforts.orEmpty()) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
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
            // Recheck reconciles whatever is current; its remembered turn may already have finished.
            if (id != null && effect !is ActiveSessionEffect.Recheck && id != currentTurn()?.id) return
            executeCommand(effect)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex operation failed" }
            recover(effect, e.failure)
        }
    }

    /** Failures that must not reach the machine because native state settles them instead. */
    private fun isSettledElsewhere(effect: ActiveSessionEffect, error: EngineFailure): Boolean = when {
        // A rejected interrupt never proves the turn stopped; its native completion still arrives.
        effect is ActiveSessionEffect.Cancel && error is EngineFailure.Request -> true

        // A stale recheck must not fail a session that another reconciliation already recovered.
        effect is ActiveSessionEffect.Recheck && machine.state.value !is ActiveSessionState.Unavailable -> {
            log.i { "Codex stale recheck failure dropped" }
            true
        }

        else -> false
    }

    private suspend fun recover(effect: ActiveSessionEffect, error: EngineFailure) {
        if (isSettledElsewhere(effect, error)) return
        // A JSON-RPC error on turn/start proves no native turn exists; anything else leaves acceptance unknown.
        val isRejected = effect is ActiveSessionEffect.Submit && error is EngineFailure.Request
        val failure = if (effect is ActiveSessionEffect.Submit && !isRejected) {
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, effect.request.id)
        } else {
            error
        }
        // A recheck reports against the turn that is current now; its remembered one may have finished.
        val id = if (effect is ActiveSessionEffect.Recheck) currentTurn()?.id else effect.turnId()
        if (id != null) submissions.remove(id)?.completeExceptionally(EngineException(failure))
        machine.send(ActiveSessionIntent.Internal.Failed(id, failure))
        val state = machine.state.value as? ActiveSessionState.Unavailable ?: return
        if (isRejected && id != null && state.activeTurn?.id == id) {
            finished += id
            machine.send(
                ActiveSessionIntent.Internal.Synchronized(
                    active = null,
                    completed = ActiveSessionIntent.Internal.Finished(id, TurnOutcome.Failed(failure)),
                ),
            )
        } else if (effect !is ActiveSessionEffect.Recheck) {
            recheck()
        }
    }

    /** Starts native reconciliation of an Unavailable session; a failed probe keeps it Unavailable. */
    suspend fun recheck() {
        if (!runtime.isClosed && machine.state.value is ActiveSessionState.Unavailable) {
            log.i { "Codex session recheck requested" }
            machine.send(ActiveSessionIntent.Public.Recheck)
        }
    }

    private suspend fun executeCommand(effect: ActiveSessionEffect) {
        when (effect) {
            is ActiveSessionEffect.Submit -> submit(effect)

            is ActiveSessionEffect.Cancel -> {
                cancelTools(effect.turn)
                interrupt(effect)
            }

            is ActiveSessionEffect.Decide -> {
                val id = permissions[effect.decision.request]?.first ?: protocolFailure()
                rpc.respond(id, json("decision" to effect.decision.option.value.json()))
            }

            is ActiveSessionEffect.Recheck -> reconcile()

            ActiveSessionEffect.Release -> machine.send(ActiveSessionIntent.Internal.Released)
        }
    }

    private suspend fun interrupt(effect: ActiveSessionEffect.Cancel) {
        rpc.request(
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
    }

    private suspend fun submit(effect: ActiveSessionEffect.Submit) {
        val input = JsonArray(
            effect.request.parts.map { json("type" to "text".json(), "text" to (it as ContentPart.Text).text.json()) },
        )
        val response = rpc.request(
            "turn/start",
            codexTurnParams(ref.nativeId, target.model.value, input, effect.request.reasoningEffort),
        )
        val id = response.obj("turn").text("id") ?: protocolFailure()
        nativeTurns[id] = effect.turn.id
        if (currentTurn()?.id == effect.turn.id) nativeTurn = id
        accept(effect.turn)
    }

    /** Reads the native thread; only an idle thread or our own in-progress turn leaves Unavailable. */
    private suspend fun reconcile() {
        val before = machine.state.value
        if (before !is ActiveSessionState.Unavailable) {
            log.i { "Codex recheck skipped: session already available" }
            return
        }
        val thread = rpc.request(
            "thread/read",
            json("threadId" to ref.nativeId.json(), "includeTurns" to JsonPrimitive(true)),
        ).obj("thread")
        // A completion or a parallel recheck may have moved the session while the read was in flight.
        // A failure-only update (a concurrent Busy probe) is not a move and must not strand this result.
        val now = machine.state.value
        if (now !is ActiveSessionState.Unavailable || now.activeTurn != before.activeTurn) {
            log.i { "Codex recheck superseded" }
            return
        }
        val turns = (thread["turns"] as? JsonArray).orEmpty().map { it as? JsonObject ?: protocolFailure() }
        val running = turns.filter { it.text("status") == IN_PROGRESS }
        val active = before.activeTurn
        // Never adopt an unmapped running turn: it may belong to another client, and its approvals are not ours.
        val native = active?.let { turn -> nativeTurns.entries.firstOrNull { it.value == turn.id }?.key }
        val remembered = turns.firstOrNull { native != null && it.text("id") == native }
        log.i { "Codex session rechecked" }
        when {
            remembered?.text("status") == IN_PROGRESS && active != null -> machine.send(
                ActiveSessionIntent.Internal.Synchronized(
                    active,
                    permissions.values.map { it.second }
                        .filter { it.turn == active.id && it.id !in active.resolvedPermissions },
                ),
            )

            running.isNotEmpty() -> fail(EngineFailure.Session(SessionFailureReason.Busy))

            else -> synchronizeIdle(active, remembered)
        }
    }

    private suspend fun synchronizeIdle(active: Turn?, remembered: JsonObject?) {
        val completed = if (active != null && remembered != null && finished.add(active.id)) {
            ActiveSessionIntent.Internal.Finished(active.id, outcome(remembered))
        } else {
            null
        }
        nativeTurn = null
        permissions.clear()
        active?.let { cancelTools(it.id) }
        machine.send(ActiveSessionIntent.Internal.Synchronized(active = null, completed = completed))
        completed?.let { done -> history.publish { SessionEvent.TurnFinished(it, done.turn, done.outcome) } }
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

            // Any completion on the thread may be what an Unavailable session waits for.
            "turn/completed" -> if (turnId != null) complete(turnId, params.obj("turn")) else recheck()

            "item/started", "item/completed" -> history.nativeItem(
                params.obj("item"),
                turnId,
                isStarted = method == "item/started",
            )

            "item/agentMessage/delta" -> history.delta(params, turnId)

            "item/reasoning/summaryTextDelta" -> history.reasoningDelta(params, turnId)

            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> approval(message, turn)

            "item/tool/call" -> dynamicTool(message, turn)

            "serverRequest/resolved" -> resolved(params, turn)

            else -> if (message["id"] != null) rpc.reject(checkNotNull(message["id"]))
        }
    }

    /**
     * Answers a dynamic tool call without blocking the server-message loop: the search runs in a child job of the
     * turn, so deltas and approvals keep flowing and interrupting the turn cancels the search.
     */
    private suspend fun dynamicTool(message: JsonObject, turn: Turn?) {
        val id = message["id"] ?: protocolFailure()
        val params = message.obj("params")
        val isClosed = turn == null || turn.id in finished || turn.id in toolsClosed
        if (isClosed || params.text("turnId") != nativeTurn) {
            log.i { "Codex tool call refused: turn unavailable" }
            rpc.respond(id, toolFailureResult("TurnUnavailable"))
            return
        }
        if (!runtime.host.toggles.get(SearchEngineTools)) {
            log.i { "Codex tool call refused: search tools disabled" }
            rpc.respond(id, toolFailureResult("Disabled"))
            return
        }
        accept(turn)
        val tool = params.text("tool").orEmpty()
        val arguments = params["arguments"] ?: JsonObject(emptyMap())
        val parent = toolJobs.getOrPut(turn.id) { SupervisorJob(scope.coroutineScope.coroutineContext[Job]) }
        scope.coroutineScope.launch(parent) {
            // The tool reports its own failures. A call cancelled with its turn or by its provider is still
            // answered, a fatal error propagates unanswered: only the completion cause tells them apart.
            val onCancelled = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) answerCancelled(id)
            }
            val result = executeSearchTool(runtime.host.search, tool, arguments)
            onCancelled.dispose()
            respondQuietly(id, result)
        }
    }

    private fun answerCancelled(id: JsonElement) {
        log.i { "Codex tool call cancelled with its turn" }
        scope.coroutineScope.launch { respondQuietly(id, toolFailureResult("Cancelled")) }
    }

    private suspend fun respondQuietly(id: JsonElement, result: JsonObject) {
        try {
            rpc.respond(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The connection is gone; the turn itself reports the failure.
            log.w(e) { "Codex tool response not delivered" }
        }
    }

    private fun closeTools(turn: TurnId) {
        toolsClosed += turn
        // Only recent turns can still receive late calls; older entries are dropped to keep the set bounded.
        if (toolsClosed.size > MAX_CLOSED_TOOL_TURNS) toolsClosed.remove(toolsClosed.first())
    }

    private fun cancelTools(turn: TurnId) {
        closeTools(turn)
        toolJobs.remove(turn)?.cancel()
    }

    /** Normal completion: running calls may still answer, but no new call starts for this turn. */
    private fun completeTools(turn: TurnId) {
        closeTools(turn)
        toolJobs.remove(turn)?.complete()
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
        completeTools(id)
        val outcome = outcome(native)
        currentTurn()?.takeIf { it.id == id }?.let { accept(it) }
        machine.send(ActiveSessionIntent.Internal.Finished(id, outcome))
        history.publish { SessionEvent.TurnFinished(it, id, outcome) }
        nativeTurn = null
        permissions.clear()
        // Finished keeps Unavailable; only Synchronized leaves it.
        recheck()
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
        permissions[requestId] = id to request
        accept(turn)
        if (machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) != SendResult.Accepted) {
            // Interrupting or Unavailable cannot surface the request; an unanswered one blocks the native turn.
            permissions.remove(requestId)
            log.i { "Codex approval declined outside an awaiting state" }
            rpc.respond(id, json("decision" to "decline".json()))
            return
        }
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

    private fun outcome(native: JsonObject): TurnOutcome = when (native.text("status")) {
        "completed" -> TurnOutcome.Completed

        "interrupted" -> TurnOutcome.Cancelled

        "failed" -> {
            val failure = codexTurnFailure(native["error"] as? JsonObject, route.binding)
            log.w { "Codex turn failed code=${failure.code}" }
            TurnOutcome.Failed(failure)
        }

        else -> TurnOutcome.Unknown
    }

    private companion object {
        const val IN_PROGRESS = "inProgress"
    }
}

private const val MAX_CLOSED_TOOL_TURNS = 32
