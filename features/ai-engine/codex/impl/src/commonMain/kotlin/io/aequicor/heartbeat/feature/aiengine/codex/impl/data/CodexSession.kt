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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    restored: CodexTurnSnapshot? = null,
) {
    val connectionMutex = Mutex()
    private val execution = CodexExecution(this, connection, opening, ::connectionChanged, ::auditReopened)
    val connection get() = execution.connection
    private val rpc get() = connection.rpc
    val contextUsage = CodexContextUsage()
    private val log = Log.tag("CodexSession")
    val history = CodexHistory()
    private val journal = CodexTurnJournal(runtime.host.turns, ref, route, runtime.turnOwnership)
    private var isActiveExecutionOwned = restored?.active == null
    private val inputs = mutableMapOf<TurnId, CodexPromptInputs>()
    private val scope = runtime.host.scopes.child(runtime.profile, "codex-${Uuid.random()}")
    private val nativeTurns = mutableMapOf<String, TurnId>()
    private val nativeHistory = CodexNativeHistory(ref, history, runtime.host)
    private var isCreatedHere = false
    var isMaterialized = false
    val isMaterializationRequired get() = isCreatedHere && !isMaterialized && nativeTurns.isEmpty()
    val isUnused get() = leases.isEmpty() && submissions.isEmpty() && currentTurn() == null &&
        pendingSubmission == null && !hostedJobs.hasPending
    private var nativeTurn: String? = null
    private val submissions = mutableMapOf<TurnId, CompletableDeferred<TurnId>>()

    /** Retained after stop revocation until a durable cancellation receipt releases admission. */
    val pendingSubmission: CodexSubmission? get() = submission.pending
    private val submission = CodexSubmissions(this, scope.coroutineScope, ::prepareSubmission, { request, entry ->
        machine.send(ActiveSessionIntent.Public.Submit(request, entry.turn)) == SendResult.Accepted
    }, { entry ->
        submissions.remove(entry.turn.id)
        inputs.remove(entry.turn.id)
    })
    private val hostedRequests = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val hostedPermissions = mutableMapOf<PermissionRequestId, CompletableDeferred<PermissionDecision?>>()
    private var questions = CodexUserInput(scope.coroutineScope, connection::respondQuietly, ::awaitDecision)
    private var trust = TrustLevel.Ask
    private val finished = mutableSetOf<TurnId>()

    /** Retains revoked hosted work for the explicit stop coordinator to await outside session locks. */
    val hostedJobs = CodexHostedJobs(scope.coroutineScope) { runtime.release(this) }
    val machine = runtime.host.launcher.launch(
        activeSessionMachineSpec(
            ActiveSessionMachineKey(Uuid.random().toString()),
            restored.codexInitialState(),
        ),
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

    suspend fun send(request: PromptRequest): TurnId = submission.send(request)

    private suspend fun prepareSubmission(request: PromptRequest, submission: CodexSubmission) {
        runtime.gate()
        submission.ensureAllowed()
        if (request.parts.any { it !is ContentPart.Text }) runtime.models(target.binding)
        val prepared = codexPromptInputs(request, runtime.inputSupport(target.model), runtime.host.resources)
        ensureReadyForPolicy()
        validateReasoningEffort(request)
        submission.ensureAllowed()
        execution.prepare()
        submission.ensureAllowed()
        val turn = submission.turn
        runtime.host.resourceHistory.remember(ref, "request:" + request.id.value, request.parts)
        submission.ensureAllowed()
        history.rememberOriginals(turn.id, request.parts)
        inputs[turn.id] = prepared
        submissions[turn.id] = submission.accepted
        nativeTurn = null
    }

    fun ensureReadyForPolicy() {
        runtime.ensureOpen()
        if (scope.isClosed) fail(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (pendingSubmission?.isStopRequested == true) fail(EngineFailure.Session(SessionFailureReason.Busy))
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
        val submission = pendingSubmission?.takeIf { effect is ActiveSessionEffect.Submit && it.turn == effect.turn }
        try {
            val id = effect.codexTurnId()
            // Recheck reconciles whatever is current; its remembered turn may already have finished.
            if (id != null && effect !is ActiveSessionEffect.Recheck && id != currentTurn()?.id) return
            executeCommand(effect)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex operation failed" }
            recover(effect, e.failure)
        } finally {
            submission?.let(this.submission::finish)
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
        val id = if (effect is ActiveSessionEffect.Recheck) currentTurn()?.id else effect.codexTurnId()
        if (id != null) submissions.remove(id)?.completeExceptionally(EngineException(failure))
        machine.send(ActiveSessionIntent.Internal.Failed(id, failure))
        val state = machine.state.value as? ActiveSessionState.Unavailable ?: return
        if (isRejected && id != null && state.activeTurn?.id == id) {
            journal.finish(id, TurnOutcome.Failed(failure))
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
        if (!runtime.isClosed && pendingSubmission?.isStopRequested != true &&
            machine.state.value is ActiveSessionState.Unavailable
        ) {
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
        val submission = pendingSubmission?.takeIf { it.turn == effect.turn } ?: protocolFailure()
        submission.ensureAllowed()
        trust = effect.request.trust ?: TrustLevel.Ask
        val prepared = inputs.remove(effect.turn.id) ?: protocolFailure()
        val input = JsonArray(prepared.parts)
        val origin = connection
        journal.begin(effect.turn, trust, origin.rpc.processOwner())
        submission.ensureAllowed()
        confirmSubmission(effect)
        submission.nativeMayStart(origin)
        isActiveExecutionOwned = true
        val response = origin.rpc.request(
            "turn/start",
            codexTurnParams(ref.nativeId, target.model.value, input, effect.request.reasoningEffort),
        )
        val id = response.obj("turn").text("id") ?: protocolFailure()
        journal.bind(effect.turn.id, id)
        nativeTurns[id] = effect.turn.id
        if (submission.isStopRequested) return
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

    /** Reconciles only our mapped turn; a missing turn never proves the remembered execution stopped. */
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
            remembered?.text("status") == IN_PROGRESS && active != null && isActiveExecutionOwned -> machine.send(
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
        // Rollout projections may omit turns or their status. Unknown means proven stopped, not missing data.
        if (active != null && remembered?.text("status") !in TERMINAL_STATUSES) return
        val completed = if (active != null && remembered != null && active.id !in finished) {
            val result = codexTurnOutcome(remembered, route.binding)
            cancelTools(active.id)
            journal.finish(active.id, result)
            if (currentTurn()?.id != active.id || !finished.add(active.id)) return
            ActiveSessionIntent.Internal.Finished(active.id, result)
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
        if (pendingSubmission?.let { it.turn.id == turn.id && it.isStopRequested } == true) return
        if (submissions.remove(
                turn.id,
            )?.complete(turn.id) == true
        ) {
            history.publish { SessionEvent.TurnStarted(it, turn) }
        }
        machine.send(ActiveSessionIntent.Internal.Accepted(turn.id))
    }

    suspend fun load(
        turns: List<JsonElement>?,
        isNew: Boolean,
        isCanonical: Boolean,
        restored: CodexTurnSnapshot? = null,
    ) {
        isCreatedHere = isNew
        nativeTurns.putAll(restored.codexNativeTurns())
        restored?.last?.turn?.id?.let { finished += it }
        nativeHistory.load(turns, isNew, isCanonical, nativeTurns)
        val active = restored?.active ?: return
        val native = turns.orEmpty().filterIsInstance<JsonObject>().firstOrNull { it.text("id") == active.nativeId }
        if (native?.text("status") in TERMINAL_STATUSES) synchronizeIdle(active.turn, native)
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
        if (contextUsage.event(method, params, runtime::usageEnabled)) return
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

    /**
     * Answers a dynamic tool call without blocking the server-message loop: the search runs in a child job of the
     * turn, so deltas and approvals keep flowing and interrupting the turn cancels the search.
     */
    private suspend fun dynamicTool(message: JsonObject, turn: Turn?, source: CodexConnection) {
        val id = message["id"] ?: protocolFailure()
        val params = message.obj("params")
        val isClosed = !isActiveExecutionOwned || turn == null || turn.id in finished || hostedJobs.isClosed(turn.id)
        if (isClosed || params.text("turnId") != nativeTurn) {
            log.i { "Codex tool call refused: turn unavailable" }
            source.rpc.respond(id, toolFailureResult("TurnUnavailable"))
            return
        }
        val tool = params.text("tool").orEmpty()
        val isSearch = tool == "web_search" || tool == "web_fetch"
        val isEnabled = !isSearch || runtime.host.toggles.get(SearchEngineTools)
        if (isEnabled) accept(turn)
        // Both the toggle lookup and acceptance can suspend while completion revokes this turn.
        val parent = if (isEnabled && currentTurn()?.id == turn.id) hostedJobs.parent(turn.id) else null
        if (parent == null) {
            log.i { "Codex tool call refused after admission check" }
            source.rpc.respond(id, toolFailureResult(if (isEnabled) "TurnUnavailable" else "Disabled"))
            return
        }
        dispatchTool(message, turn, source, parent)
    }

    private fun dispatchTool(message: JsonObject, turn: Turn, source: CodexConnection, parent: Job) {
        val id = checkNotNull(message["id"])
        val params = message.obj("params")
        val tool = params.text("tool").orEmpty()
        val isSearch = tool == "web_search" || tool == "web_fetch"
        val arguments = params["arguments"] ?: JsonObject(emptyMap())
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
        lifetime = hostedJobs.lifetime(turn.id),
        target = turn.target,
    )

    private suspend fun userInput(message: JsonObject, turn: Turn?) {
        val active = turn?.takeIf { runtime.questionsEnabled() }?.takeIf {
            !hostedJobs.isClosed(it.id) && message.obj("params").text("turnId") == nativeTurn && leases.isNotEmpty()
        }
        val parent = active?.let {
            accept(it)
            if (currentTurn()?.id == it.id) hostedJobs.parent(it.id) else null
        }
        questions.request(message, active?.id, parent)
    }

    private suspend fun hostedApproval(turn: Turn, approval: AgentToolApproval): Boolean {
        if (currentTurn()?.id != turn.id || hostedJobs.isClosed(turn.id) || leases.isEmpty()) return false
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
        if (currentTurn()?.id != request.turn || hostedJobs.isClosed(request.turn) || leases.isEmpty()) return null
        val answer = CompletableDeferred<PermissionDecision?>()
        hostedPermissions[request.id] = answer
        hostedRequests[request.id] = request
        return try {
            if (machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
                history.publish { SessionEvent.PermissionRequested(it, request) }
                answer.await()
            } else {
                null
            }
        } finally {
            hostedPermissions.remove(request.id)
            hostedRequests.remove(request.id)
            withContext(NonCancellable) {
                machine.send(ActiveSessionIntent.Internal.PermissionResolved(request.turn, request.id))
            }
        }
    }

    private fun cancelTools(turn: TurnId) = hostedJobs.revoke(turn)

    /** Normal completion revokes calls immediately but keeps their jobs available for a later stop barrier. */
    private fun completeTools(turn: TurnId) = hostedJobs.revoke(turn)

    private suspend fun correlate(params: JsonObject, turn: Turn?): TurnId? {
        val native = params.text("turnId") ?: (params["turn"] as? JsonObject)?.text("id") ?: return null
        if (native in nativeTurns) return nativeTurns[native]
        if (turn == null || nativeTurn != null) return null
        if (machine.state.value !is ActiveSessionState.Submitting) return null
        journal.bind(turn.id, native)
        nativeTurn = native
        nativeTurns[native] = turn.id
        return turn.id
    }

    private suspend fun complete(id: TurnId, native: JsonObject) {
        if (id in finished || currentTurn()?.id != id) return
        val outcome = codexTurnOutcome(native, route.binding)
        completeTools(id)
        journal.finish(id, outcome)
        if (!finished.add(id)) return
        currentTurn()?.takeIf { it.id == id }?.let { accept(it) }
        nativeTurn = null
        hostedRequests.clear()
        machine.send(ActiveSessionIntent.Internal.Finished(id, outcome))
        history.publish { SessionEvent.TurnFinished(it, id, outcome) }
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

    private fun currentTurn(): Turn? = machine.state.value.codexCurrentTurn()

    private companion object {
        const val IN_PROGRESS = "inProgress"
        val TERMINAL_STATUSES = setOf("completed", "interrupted", "failed")
        val HOSTED_ALLOW = PermissionOptionId("hosted.allow")
        val HOSTED_DENY = PermissionOptionId("hosted.deny")
    }
}

/** Null wrapper means a stale read; null turns inside a current wrapper mean missing native history. */
private data class NativeRead(val turns: List<JsonObject>?)
