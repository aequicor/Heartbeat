package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.activeSessionMachineSpec
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

internal class CodexSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    val runtime: CodexRuntime,
    connection: CodexConnection,
    val areHostedToolsEnabled: Boolean = true,
    opening: CodexPreparedThread? = null,
) {
    val connectionMutex = Mutex()
    private val execution = CodexExecution(this, connection, opening, ::connectionChanged, ::auditReopened)
    val connection get() = execution.connection
    private val rpc get() = connection.rpc
    val contextUsage = CodexContextUsage()
    private val log = Log.tag("CodexSession")
    val history = CodexHistory()
    private val inputs = mutableMapOf<TurnId, CodexPromptInputs>()
    private val scope = runtime.host.scopes.child(runtime.profile, "codex-${Uuid.random()}")
    private val nativeTurns = mutableMapOf<String, TurnId>()
    private val nativeHistory = CodexNativeHistory(ref, history, runtime.host)
    private var isCreatedHere = false
    var isMaterialized = false
    val isMaterializationRequired get() = isCreatedHere && !isMaterialized && nativeTurns.isEmpty()
    val isUnused get() = leases.isEmpty() && submissions.isEmpty() && currentTurn() == null
    private var nativeTurn: String? = null
    private val submitLock = Mutex()
    private val submissions = mutableMapOf<TurnId, CompletableDeferred<TurnId>>()
    private val hostedRequests = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val hostedPermissions = mutableMapOf<PermissionRequestId, CompletableDeferred<PermissionDecision?>>()
    private var questions = CodexUserInput(scope.coroutineScope, connection::respondQuietly, ::awaitDecision)
    private var trust = TrustLevel.Ask
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
        if (leases.isEmpty()) hostedPermissions.values.forEach { it.complete(null) }
        runtime.release(this)
    }

    suspend fun send(request: PromptRequest): TurnId {
        if (!submitLock.tryLock()) fail(EngineFailure.Session(SessionFailureReason.Busy))
        try {
            return connectionMutex.withLock {
                runtime.gate()
                if (request.parts.any { it !is ContentPart.Text }) runtime.models(target.binding)
                val prepared = codexPromptInputs(request, runtime.inputSupport(target.model), runtime.host.resources)
                if (machine.state.value !is ActiveSessionState.Ready) {
                    fail(
                        EngineFailure.Session(SessionFailureReason.Busy),
                    )
                }
                validateReasoningEffort(request)
                execution.prepare()
                val turn = Turn(TurnId(Uuid.random().toString()), request.id, target)
                runtime.host.resourceHistory.remember(ref, "request:" + request.id.value, request.parts)
                history.rememberOriginals(turn.id, request.parts)
                inputs[turn.id] = prepared
                val accepted = CompletableDeferred<TurnId>()
                submissions[turn.id] = accepted
                nativeTurn = null
                val result = machine.send(ActiveSessionIntent.Public.Submit(request, turn))
                if (result != SendResult.Accepted) {
                    submissions.remove(turn.id)
                    fail(EngineFailure.Session(SessionFailureReason.Busy))
                }
                accepted.await()
            }
        } finally {
            submitLock.unlock()
        }
    }

    fun ensureReadyForPolicy() {
        runtime.ensureOpen()
        if (scope.isClosed) fail(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (machine.state.value !is ActiveSessionState.Ready) fail(EngineFailure.Session(SessionFailureReason.Busy))
    }

    private fun connectionChanged(source: CodexConnection) {
        questions = CodexUserInput(scope.coroutineScope, source::respondQuietly, ::awaitDecision)
    }

    private fun auditReopened(thread: JsonObject) {
        nativeHistory.audit(thread, nativeTurns.keys)
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
        runtime.release(this)
    }

    /** Starts native reconciliation of an Unavailable session; a failed probe keeps it Unavailable. */
    suspend fun recheck() {
        if (!runtime.isClosed && machine.state.value is ActiveSessionState.Unavailable) {
            log.i { "Codex session recheck requested" }
            machine.send(ActiveSessionIntent.Public.Recheck)
        }
    }

    /** A reused idle session may have been changed by another client; a failed audit keeps its live lease usable. */
    suspend fun refreshHistory() {
        if (runtime.isClosed || machine.state.value !is ActiveSessionState.Ready) return
        try {
            readNativeHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex history audit unavailable; preserving observed history" }
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
                val pending = hostedPermissions.remove(effect.decision.request) ?: protocolFailure()
                pending.complete(effect.decision)
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
        trust = effect.request.trust ?: TrustLevel.Ask
        val prepared = inputs.remove(effect.turn.id) ?: protocolFailure()
        val input = JsonArray(prepared.parts)
        confirmSubmission(effect)
        val response = rpc.request(
            "turn/start",
            codexTurnParams(ref.nativeId, target.model.value, input, effect.request.reasoningEffort),
        )
        val id = response.obj("turn").text("id") ?: protocolFailure()
        nativeTurns[id] = effect.turn.id
        if (currentTurn()?.id == effect.turn.id) nativeTurn = id
        rememberNativeInput(id, effect.request.parts)
        accept(effect.turn)
    }

    // This proven pre-send rejection must settle profile-owned work before propagating provider cancellation.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun confirmSubmission(effect: ActiveSessionEffect.Submit) {
        try {
            execution.confirm(effect.request.id)
        } catch (e: CancellationException) {
            log.i { "Codex policy confirmation cancelled before native submission" }
            withContext(NonCancellable) {
                submissions.remove(effect.turn.id)?.completeExceptionally(e)
                recover(effect, EngineFailure.Request(RequestFailureReason.Invalid, effect.request.id))
            }
            throw e
        }
    }

    /** Reads the native thread; only an idle thread or our own in-progress turn leaves Unavailable. */
    private suspend fun reconcile() {
        val before = machine.state.value
        if (before !is ActiveSessionState.Unavailable) {
            log.i { "Codex recheck skipped: session already available" }
            return
        }
        val snapshot = readNativeHistory() ?: return
        val turns = snapshot.turns.orEmpty()
        // A completion or a parallel recheck may have moved the session while the read was in flight.
        // A failure-only update (a concurrent Busy probe) is not a move and must not strand this result.
        val now = machine.state.value
        if (now !is ActiveSessionState.Unavailable || now.activeTurn != before.activeTurn) {
            log.i { "Codex recheck superseded" }
            return
        }
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
                    hostedRequests.values
                        .filter { it.turn == active.id && it.id !in active.resolvedPermissions },
                ),
            )

            running.isNotEmpty() -> fail(EngineFailure.Session(SessionFailureReason.Busy))

            else -> synchronizeIdle(active, remembered)
        }
    }

    /** Audits identity and content without replacing live items with a potentially thinner rollout projection. */
    private suspend fun readNativeHistory(): NativeRead? {
        val origin = connection
        return try {
            val thread = origin.rpc.request(
                "thread/read",
                json("threadId" to ref.nativeId.json(), "includeTurns" to JsonPrimitive(true)),
            ).obj("thread")
            if (origin !== connection || origin.isClosed) {
                null
            } else {
                NativeRead(
                    nativeHistory.audit(thread, nativeTurns.keys),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            if (origin !== connection || origin.isClosed) {
                log.w(e) { "Retired Codex history read discarded" }
                null
            } else {
                history.seeded(isComplete = false)
                throw e
            }
        }
    }

    private suspend fun synchronizeIdle(active: Turn?, remembered: JsonObject?) {
        val completed = if (active != null && remembered != null && finished.add(active.id)) {
            ActiveSessionIntent.Internal.Finished(active.id, outcome(remembered))
        } else {
            null
        }
        nativeTurn = null
        hostedRequests.clear()
        active?.let { cancelTools(it.id) }
        machine.send(ActiveSessionIntent.Internal.Synchronized(active = null, completed = completed))
        completed?.let { done -> history.publish { SessionEvent.TurnFinished(it, done.turn, done.outcome) } }
        runtime.release(this)
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

    suspend fun load(turns: List<JsonElement>?, isNew: Boolean, isCanonical: Boolean) {
        isCreatedHere = isNew
        nativeHistory.load(turns, isNew, isCanonical)
    }

    suspend fun event(message: JsonObject, source: CodexConnection = connection) {
        if (source !== connection || source.isClosed) {
            message["id"]?.let { source.rpc.reject(it) }
            return
        }
        receive(message, source)
    }

    private suspend fun receive(message: JsonObject, source: CodexConnection) {
        val params = message.obj("params")
        val method = message.text("method")
        if (usageEvent(method, params)) return
        val turn = currentTurn()
        val turnId = correlate(params, turn)
        when (method) {
            "turn/started" -> acceptStarted(turn, turnId)

            // Any completion on the thread may be what an Unavailable session waits for.
            "turn/completed" -> if (turnId != null) complete(turnId, params.obj("turn")) else recheck()

            "item/started", "item/completed" -> {
                val item = params.obj("item")
                history.nativeItem(item, turnId, isStarted = method == "item/started")
            }

            "item/agentMessage/delta" -> history.delta(params, turnId)

            "item/reasoning/summaryTextDelta" -> history.reasoningDelta(params, turnId)

            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> approval(message, source)

            "item/tool/call" -> dynamicTool(message, turn, source)

            "item/tool/requestUserInput" -> userInput(message, turn)

            "serverRequest/resolved" -> resolved(params, turn)

            else -> if (message["id"] != null) source.rpc.reject(checkNotNull(message["id"]))
        }
    }

    private suspend fun acceptStarted(turn: Turn?, turnId: TurnId?) {
        if (turn != null && turn.id == turnId) {
            history.originals(turn.id)?.let { parts ->
                rememberNativeInput(nativeTurn ?: protocolFailure(), parts)
            }
            accept(turn)
        }
    }

    private suspend fun rememberNativeInput(id: String, parts: List<ContentPart>) {
        try {
            runtime.host.resourceHistory.remember(ref, id, parts)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Codex original input could not be persisted: ${error::class.simpleName ?: "Failure"}" }
            fail(
                EngineFailure.History(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason.Unavailable,
                ),
            )
        }
    }

    private suspend fun usageEvent(method: String?, params: JsonObject): Boolean {
        when (method) {
            "thread/tokenUsage/updated" -> if (runtime.usageEnabled()) contextUsage.receive(params)

            "thread/compacted" -> contextUsage.clear()

            else -> {
                if (method == "item/started" || method == "item/completed") {
                    val item = params["item"] as? JsonObject
                    if (item?.text("type") == "contextCompaction") contextUsage.clear()
                }
                return false
            }
        }
        return true
    }

    /**
     * Answers a dynamic tool call without blocking the server-message loop: the search runs in a child job of the
     * turn, so deltas and approvals keep flowing and interrupting the turn cancels the search.
     */
    private suspend fun dynamicTool(message: JsonObject, turn: Turn?, source: CodexConnection) {
        val id = message["id"] ?: protocolFailure()
        val params = message.obj("params")
        val isClosed = turn == null || turn.id in finished || turn.id in toolsClosed
        if (isClosed || params.text("turnId") != nativeTurn) {
            log.i { "Codex tool call refused: turn unavailable" }
            source.rpc.respond(id, toolFailureResult("TurnUnavailable"))
            return
        }
        val tool = params.text("tool").orEmpty()
        val isSearch = tool == "web_search" || tool == "web_fetch"
        if (isSearch && !runtime.host.toggles.get(SearchEngineTools)) {
            log.i { "Codex tool call refused: search tools disabled" }
            source.rpc.respond(id, toolFailureResult("Disabled"))
            return
        }
        accept(turn)
        val arguments = params["arguments"] ?: JsonObject(emptyMap())
        val parent = toolJobs.getOrPut(turn.id) { SupervisorJob(scope.coroutineScope.coroutineContext[Job]) }
        var isResponseStarted = false
        scope.coroutineScope.launch(parent) {
            val result = if (isSearch) {
                executeHostedSearch(runtime.host.search, runtime.host.tools, toolContext(turn, params), tool, arguments)
            } else {
                executeHostedTool(turn, params, tool, arguments)
            }
            // Once delivery starts, cancellation must not send another response for the same request.
            isResponseStarted = true
            source.respondQuietly(id, result)
        }.invokeOnCompletion { cause ->
            // Register outside the body: cancellation can happen before the tool's first dispatch.
            // Provider cancellation is answered too; fatal errors still propagate unanswered.
            if (cause is CancellationException && !isResponseStarted) {
                source.answerLater(id, toolFailureResult("Cancelled"))
            }
        }
    }

    private suspend fun executeHostedTool(
        turn: Turn,
        params: JsonObject,
        name: String,
        arguments: JsonElement,
    ): JsonObject {
        val args = arguments as? JsonObject ?: return toolFailureResult("InvalidInput")
        if (!areHostedToolsEnabled) return toolFailureResult("Unavailable")
        val context = toolContext(turn, params)
        return try {
            val result = runtime.host.tools.execute(context, name, args)
            toolResult(!result.isError, result.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Hosted Codex tool failed" }
            toolFailureResult("Unavailable")
        }
    }

    private fun toolContext(turn: Turn, params: JsonObject): AgentToolContext = AgentToolContext(
        ref,
        route.workspace,
        turn.id,
        turn.request,
        trust,
        AgentToolPermissions { hostedApproval(turn, it) },
        params.text("callId")?.let(::ToolCallId),
        lifetime = toolJobs[turn.id],
        target = turn.target,
    )

    private suspend fun userInput(message: JsonObject, turn: Turn?) {
        val active = turn?.takeIf { runtime.questionsEnabled() }?.takeIf {
            it.id !in toolsClosed && message.obj("params").text("turnId") == nativeTurn && leases.isNotEmpty()
        }
        val parent = active?.let {
            accept(it)
            toolJobs.getOrPut(it.id) { SupervisorJob(scope.coroutineScope.coroutineContext[Job]) }
        }
        questions.request(message, active?.id, parent)
    }

    private suspend fun hostedApproval(turn: Turn, approval: AgentToolApproval): Boolean {
        if (currentTurn()?.id != turn.id || turn.id in toolsClosed || leases.isEmpty()) return false
        val request = PermissionRequest(
            PermissionRequestId(Uuid.random().toString()),
            turn.id,
            approval.title,
            listOf(PermissionOption(HOSTED_ALLOW, "Разрешить"), PermissionOption(HOSTED_DENY, "Запретить")),
            description = approval.description,
        )
        return awaitDecision(request)?.option == HOSTED_ALLOW
    }

    /**
     * Shows a hosted request and waits for the user's decision. Null means the request could not be shown or was
     * withdrawn: the turn changed or closed, the machine refused it, or the last lease was released.
     */
    private suspend fun awaitDecision(request: PermissionRequest): PermissionDecision? {
        if (currentTurn()?.id != request.turn || request.turn in toolsClosed || leases.isEmpty()) return null
        val answer = CompletableDeferred<PermissionDecision?>()
        hostedPermissions[request.id] = answer
        hostedRequests[request.id] = request
        if (machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) != SendResult.Accepted) {
            hostedPermissions.remove(request.id)
            hostedRequests.remove(request.id)
            return null
        }
        history.publish { SessionEvent.PermissionRequested(it, request) }
        return try {
            answer.await()
        } finally {
            hostedPermissions.remove(request.id)
            hostedRequests.remove(request.id)
            withContext(NonCancellable) {
                machine.send(ActiveSessionIntent.Internal.PermissionResolved(request.turn, request.id))
            }
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

    /** Normal completion revokes calls and approvals together with the accepted turn's execution identity. */
    private fun completeTools(turn: TurnId) {
        closeTools(turn)
        // Ending the turn revokes execution identity, including pending hosted commands and approvals.
        toolJobs.remove(turn)?.cancel()
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
        hostedRequests.clear()
        // Finished keeps Unavailable; only Synchronized leaves it.
        recheck()
        runtime.release(this)
    }

    private suspend fun approval(message: JsonObject, source: CodexConnection) {
        val id = message["id"] ?: protocolFailure()
        // All mutations go through the hosted trust gate. Never escalate the native read-only sandbox.
        source.rpc.respond(id, json("decision" to "decline".json()))
    }

    private suspend fun resolved(params: JsonObject, turn: Turn?) {
        if (turn == null) return
        val nativeId = params["requestId"] ?: protocolFailure()
        questions.resolved(nativeId)
        val id = PermissionRequestId(nativeId.toString())
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
        connection.close()
        contextUsage.clear()
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
        val HOSTED_ALLOW = PermissionOptionId("hosted.allow")
        val HOSTED_DENY = PermissionOptionId("hosted.deny")
    }
}

private const val MAX_CLOSED_TOOL_TURNS = 32

/** Null wrapper means a stale read; null turns inside a current wrapper mean missing native history. */
private data class NativeRead(val turns: List<JsonObject>?)
