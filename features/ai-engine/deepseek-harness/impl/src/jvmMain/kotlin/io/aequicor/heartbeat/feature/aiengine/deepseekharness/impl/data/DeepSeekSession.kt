package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStep
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStepStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Suppress("LongParameterList", "TooManyFunctions")
internal class DeepSeekSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    private val runtime: DeepSeekRuntime,
    private val workingDirectory: String,
) : SessionHistory {
    val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val history = DeepSeekHistory()
    private val leases = mutableSetOf<DeepSeekLease>()
    private var messageId: ItemId? = null
    private val pending = mutableMapOf<PermissionRequestId, PendingPermission>()

    /** Observed ACP `kind` and arguments per tool call of the current turn; cleared when a new turn starts. */
    private val toolCalls = mutableMapOf<String, Pair<String?, JsonObject?>>()
    private val mutex = Mutex()
    private val admissions = Mutex()
    private var position = 0L
    private var currentTurn: Turn? = null
    private var currentRequest: PromptRequest? = null
    private val sessionLifetime = Job(runtime.scope.coroutineContext[Job])

    @Volatile
    private var turnLifetime: Job? = null
    private val submission = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    private var isCancelPending = false
    private var isInterrupting = false
    private val log = Log.tag("DeepSeekSession")

    suspend fun lease(): ActiveSession = mutex.withLock {
        runtime.checkActive()
        DeepSeekLease(this).also { leases += it }
    }

    suspend fun release(lease: DeepSeekLease) {
        val isIdle = mutex.withLock {
            leases.remove(lease)
            lease.state.value = ActiveSessionState.Closed
            leases.isEmpty() && (currentTurn == null || currentTurn?.outcome != null)
        }
        // An idle session without observers is retired, so its journal and native handle are released.
        if (isIdle) runtime.releaseSession(this)
    }

    private fun checkLease(lease: DeepSeekLease) {
        runtime.checkActive()
        if (lease !in leases) {
            throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        }
    }

    suspend fun send(request: PromptRequest, lease: DeepSeekLease): TurnId = admissions.withLock {
        mutex.withLock {
            checkReadyToSend(lease)
            validate(request)
        }
        val content = dshPromptInputs(request)
        val (turn, submitted) = mutex.withLock {
            checkReadyToSend(lease)
            start(request, content)
        }
        submitted.await()
        turn.id
    }

    private fun checkReadyToSend(lease: DeepSeekLease) {
        checkLease(lease)
        if (isCancelPending || (currentTurn != null && currentTurn?.outcome == null)) busy()
    }

    private fun start(request: PromptRequest, content: List<JsonObject>): Pair<Turn, CompletableDeferred<Unit>> {
        val turn = Turn(TurnId(newId("turn")), request.id, target, decisionMode = request.decisionMode)
        currentTurn = turn
        currentRequest = request
        turnLifetime = Job(sessionLifetime)
        val submitted = CompletableDeferred<Unit>()
        submission.value = submitted
        isInterrupting = false
        messageId = null
        toolCalls.clear()
        log.i { "DeepSeek Harness turn submitted" }
        setState(ActiveSessionState.Running(turn))
        history.publish { SessionEvent.TurnStarted(it, turn) }
        addMessage(MessageRole.User, request.parts, turn.id)
        runtime.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = runtime.connection.prompt(ref.nativeId, content)
                finish(turn.id, if (result.stopReason == "cancelled") TurnOutcome.Cancelled else TurnOutcome.Completed)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: AcpException.Disconnected) {
                log.w(error) { "DeepSeek Harness connection failed" }
                failed(turn.id, error, retire = true)
            } catch (error: AcpException.Protocol) {
                log.w(error) { "DeepSeek Harness connection failed" }
                failed(turn.id, error, retire = true)
            } catch (error: Exception) {
                log.w(error) { "DeepSeek Harness turn failed" }
                failed(turn.id, error)
            } finally {
                withContext(NonCancellable) { finish(turn.id, TurnOutcome.Unknown) }
            }
        }
        return turn to submitted
    }

    private suspend fun failed(turn: TurnId, error: Exception, retire: Boolean = false) {
        finish(turn, TurnOutcome.Failed(error.engineFailure()))
        if (retire) runtime.close()
    }

    fun promptSubmitted() {
        submission.value?.complete(Unit)
    }

    private fun validate(request: PromptRequest) {
        if (request.compaction != null || request.reasoningEffort != null) {
            throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
    }

    suspend fun cancel(turn: TurnId, lease: DeepSeekLease) {
        runtime.scope.async {
            val submitted = mutex.withLock {
                checkLease(lease)
                if (isCancelPending) return@async
                if (currentTurn?.id != turn || currentTurn?.outcome != null) return@async
                isCancelPending = true
                isInterrupting = true
                turnLifetime?.cancel()
                refreshState()
                requireNotNull(submission.value)
            }
            try {
                submitted.await()
                val isCurrent = mutex.withLock {
                    currentTurn?.id == turn && currentTurn?.outcome == null
                }
                if (isCurrent) runtime.connection.cancel(ref.nativeId)
            } finally {
                withContext(NonCancellable) { mutex.withLock { isCancelPending = false } }
            }
        }.await()
    }

    suspend fun respond(decision: PermissionDecision, lease: DeepSeekLease) = mutex.withLock {
        checkLease(lease)
        val request = pending[decision.request]
        if (request == null || request.request.turn != decision.turn) return@withLock
        if (request.request.options.none { it.id == decision.option } || decision.answer != null) return@withLock
        if (!request.answer.complete(AcpPermissionOutcome.Selected(decision.option.value))) return@withLock
        pending.remove(decision.request)
        val turn = currentTurn ?: return@withLock
        currentTurn = turn.copy(resolvedPermissions = turn.resolvedPermissions + decision.request)
        log.i { "DeepSeek Harness permission resolved" }
        refreshState()
    }

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val (turn, prompt, lifetime) = activePermission() ?: return AcpPermissionOutcome.Cancelled
        val toolCallId = request.toolCall["toolCallId"]?.jsonPrimitive?.contentOrNull
            ?: return AcpPermissionOutcome.Cancelled
        val observed = mutex.withLock { toolCalls[toolCallId] } ?: return AcpPermissionOutcome.Cancelled
        // Never persist a CLI allow rule that could bypass later modes.
        val option = request.options.firstOrNull { it.kind == "allow_once" }
            ?: return AcpPermissionOutcome.Cancelled
        val permissions = AgentToolPermissions { approval -> requestUserPermission(turn.id, approval) }
        val context = AgentToolContext(
            ref,
            route.workspace,
            turn.id,
            prompt.id,
            prompt.trust ?: TrustLevel.Ask,
            permissions,
            callId = ToolCallId(toolCallId),
            lifetime = lifetime,
            target = target,
            decisionMode = prompt.decisionMode,
            reviewTarget = prompt.reviewTarget,
            userInstruction = prompt.userInstruction,
        )
        val nativeCall = dshNativeCall(observed.first, observed.second, workingDirectory)
        val verdict = runtime.tools.authorizeNative(context, nativeCall)
        val isActive = mutex.withLock {
            currentTurn?.id == turn.id && currentTurn?.outcome == null && lifetime.isActive
        }
        if (!isActive || verdict is NativeVerdict.Deny) return AcpPermissionOutcome.Cancelled
        return AcpPermissionOutcome.Selected(option.optionId)
    }

    private suspend fun activePermission(): Triple<Turn, PromptRequest, Job>? {
        if (runtime.isClosed) return null
        return mutex.withLock {
            val turn = currentTurn?.takeIf { it.outcome == null } ?: return@withLock null
            val lifetime = turnLifetime?.takeIf { it.isActive } ?: return@withLock null
            val prompt = currentRequest ?: return@withLock null
            Triple(turn, prompt, lifetime)
        }
    }

    private suspend fun requestUserPermission(turn: TurnId, approval: AgentToolApproval): Boolean {
        val (id, answer) = mutex.withLock {
            if (currentTurn?.id != turn || currentTurn?.outcome != null) return false
            val id = PermissionRequestId(newId("permission"))
            val normalized = PermissionRequest(
                id,
                turn,
                approval.title,
                listOf(
                    PermissionOption(PermissionOptionId("allow"), "Allow", false),
                    PermissionOption(PermissionOptionId("deny"), "Deny", true),
                ),
            )
            val answer = CompletableDeferred<AcpPermissionOutcome>()
            pending[id] = PendingPermission(normalized, answer)
            log.i { "DeepSeek Harness permission requested" }
            refreshState()
            history.publish { SessionEvent.PermissionRequested(it, normalized) }
            id to answer
        }
        try {
            return answer.await() == AcpPermissionOutcome.Selected("allow")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(id)
                    refreshState()
                }
            }
        }
    }

    @HighFrequency
    suspend fun update(update: JsonObject) = mutex.withLock {
        val kind = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return@withLock
        val turn = currentTurn?.id
        when (kind) {
            "user_message_chunk", "agent_message_chunk", "agent_thought_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return@withLock
                val role = if (kind == "user_message_chunk") MessageRole.User else MessageRole.Assistant
                val part = if (kind == "agent_thought_chunk") ContentPart.Reasoning(text) else ContentPart.Text(text)
                appendMessage(role, part, turn)
            }

            "tool_call", "tool_call_update" -> {
                messageId = null
                updateTool(update, turn)
            }

            "plan" -> updatePlan(update)
        }
    }

    @HighFrequency
    private fun updateTool(update: JsonObject, turn: TurnId?) {
        val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val call = ToolCallId(id)
        val previous = history[ItemId("tool-$id")] as? SessionItem.ToolCall
        val status = toolStatus(update["status"]?.jsonPrimitive?.contentOrNull, previous)
        val title = update["title"]?.jsonPrimitive?.contentOrNull
            ?: update["kind"]?.jsonPrimitive?.contentOrNull
            ?: previous?.name
            ?: "tool"
        val kind = update["kind"]?.jsonPrimitive?.contentOrNull
        val rawInput = update["rawInput"]?.takeUnless { it == JsonNull } as? JsonObject
        if (kind != null || rawInput != null) {
            toolCalls[id] = (kind ?: toolCalls[id]?.first) to (rawInput ?: toolCalls[id]?.second)
        }
        val item = SessionItem.ToolCall(
            ItemInfo(
                ItemId("tool-$id"),
                previous?.info?.position ?: nextPosition(),
                (previous?.info?.revision ?: -1) + 1,
                turn,
            ),
            call,
            title,
            update["rawInput"]?.takeUnless { it == JsonNull }?.toString() ?: previous?.arguments ?: "{}",
            status,
        )
        upsert(item)
    }

    private fun toolStatus(status: String?, previous: SessionItem.ToolCall?): ToolCallStatus = when (status) {
        "completed" -> ToolCallStatus.Succeeded
        "failed" -> ToolCallStatus.Failed
        "in_progress" -> ToolCallStatus.Running
        else -> previous?.status ?: ToolCallStatus.Pending
    }

    @HighFrequency
    private fun updatePlan(update: JsonObject) {
        val entries = update["entries"]?.jsonArray ?: return
        val steps = entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                "completed" -> PlanStepStatus.Completed
                "in_progress" -> PlanStepStatus.Running
                else -> PlanStepStatus.Pending
            }
            PlanStep(content, status)
        }
        val id = ItemId("plan-${currentTurn?.id?.value ?: "loaded"}")
        val previous = history[id]
        upsert(
            SessionItem.Plan(
                ItemInfo(
                    id,
                    previous?.info?.position ?: nextPosition(),
                    (previous?.info?.revision ?: -1) + 1,
                    currentTurn?.id,
                ),
                steps,
            ),
        )
    }

    @HighFrequency
    private fun addMessage(role: MessageRole, parts: List<ContentPart>, turn: TurnId?) {
        upsert(SessionItem.Message(ItemInfo(ItemId(newId("message")), nextPosition(), 0, turn), role, parts))
    }

    @HighFrequency
    private fun appendMessage(role: MessageRole, part: ContentPart, turn: TurnId?) {
        val candidate = messageId?.let { history[it] } as? SessionItem.Message
        val previous = candidate?.takeIf { it.role == role && it.info.turn == turn }
        val id = previous?.info?.id ?: ItemId(newId("message")).also { messageId = it }
        val parts = if (previous == null) listOf(part) else previous.parts + part
        val merged = parts.groupBy { it::class }.map { (_, group) ->
            when (val first = group.first()) {
                is ContentPart.Text -> ContentPart.Text(group.joinToString("") { (it as ContentPart.Text).text })

                is ContentPart.Reasoning -> ContentPart.Reasoning(
                    group.joinToString("") { (it as ContentPart.Reasoning).text },
                )

                is ContentPart.Image, is ContentPart.Resource -> first
            }
        }
        upsert(
            SessionItem.Message(
                ItemInfo(id, previous?.info?.position ?: nextPosition(), (previous?.info?.revision ?: -1) + 1, turn),
                role,
                merged,
            ),
        )
    }

    @HighFrequency
    private fun upsert(item: SessionItem) = history.upsert(item)

    private suspend fun finish(id: TurnId, outcome: TurnOutcome) = mutex.withLock {
        val turn = currentTurn?.takeIf { it.id == id && it.outcome == null } ?: return@withLock
        val finished = turn.copy(outcome = outcome)
        currentTurn = finished
        turnLifetime?.cancel()
        turnLifetime = null
        submission.value?.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn.request)),
        )
        log.i { "DeepSeek Harness turn finished" }
        pending.values.forEach { it.answer.complete(AcpPermissionOutcome.Cancelled) }
        pending.clear()
        setState(ActiveSessionState.Ready(finished))
        history.publish { SessionEvent.TurnFinished(it, finished.id, outcome) }
    }

    fun revoke() {
        sessionLifetime.cancel()
        turnLifetime?.cancel()
    }

    suspend fun disconnected() {
        revoke()
        val turn = mutex.withLock { currentTurn }
        if (turn != null) finish(turn.id, TurnOutcome.Unknown)
        mutex.withLock { setState(ActiveSessionState.Closed) }
    }

    suspend fun synchronize(lease: DeepSeekLease) = mutex.withLock {
        checkLease(lease)
        refreshState()
    }

    private fun refreshState() {
        val turn = currentTurn
        setState(
            when {
                runtime.isClosed -> ActiveSessionState.Closed
                turn == null || turn.outcome != null -> ActiveSessionState.Ready(turn)
                isInterrupting -> ActiveSessionState.Interrupting(turn)
                pending.isNotEmpty() -> ActiveSessionState.AwaitingUserAction(turn, pending.values.map { it.request })
                else -> ActiveSessionState.Running(turn)
            },
        )
    }

    private fun setState(value: ActiveSessionState) {
        log.v { "DeepSeek Harness session state changed" }
        state.value = value
        leases.forEach { it.state.value = value }
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage = history.page(request)
    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = history.watch(after)

    private fun busy(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
    private fun nextPosition() = position++

    private data class PendingPermission(
        val request: PermissionRequest,
        val answer: CompletableDeferred<AcpPermissionOutcome>,
    )
}

/** Independent observation handle; closing it never cancels the profile-owned native turn. */
internal class DeepSeekLease(private val session: DeepSeekSession) :
    ActiveSession,
    SendsPrompts,
    CancelsTurns,
    RequestsPermissions,
    AppliesTrustLevels,
    ReconcilesSession {
    override val ref = session.ref
    override val route = session.route
    override val state = MutableStateFlow(session.state.value)
    override val features = DeepSeekFeatures(
        SendsPrompts to this,
        CancelsTurns to this,
        RequestsPermissions to this,
        AppliesTrustLevels to this,
        ReconcilesSession to this,
        SessionHistory to session,
    )

    override suspend fun send(request: PromptRequest) = session.send(request, this)
    override suspend fun cancel(turn: TurnId) = session.cancel(turn, this)
    override suspend fun respond(decision: PermissionDecision) = session.respond(decision, this)
    override suspend fun synchronize() = session.synchronize(this)
    override suspend fun close() = session.release(this)
}

/** Stored session with an optional read-only native transcript; resumption is negotiated through the runtime. */
internal class DeepSeekStoredSession(ref: SessionRef, history: SessionHistory? = null) : EngineSession {
    override val summary = MutableStateFlow(SessionSummary(ref))
    override val features = if (history == null) {
        DeepSeekFeatures()
    } else {
        DeepSeekFeatures(SessionHistory to history)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newId(prefix: String): String = "$prefix-${Uuid.random().toHexString()}"

private fun Throwable.engineFailure(): EngineFailure = when (this) {
    is EngineException -> failure

    is AcpException.Protocol, is AcpException.Remote -> EngineFailure.Transport(
        TransportFailureReason.ProtocolViolation,
    )

    else -> EngineFailure.Engine(EngineFailureReason.Crashed)
}
