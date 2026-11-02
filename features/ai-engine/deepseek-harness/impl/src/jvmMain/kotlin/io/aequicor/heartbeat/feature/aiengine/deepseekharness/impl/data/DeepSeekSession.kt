package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    internal val runtime: DeepSeekRuntime,
    private val workingDirectory: String,
    private val hosted: DeepSeekHosted = DeepSeekHosted(),
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
    private val hostedCalls = mutableMapOf<String, Pair<TurnId, String>>()
    private val usedHostedCalls = mutableSetOf<String>()
    private val hostedLifetimes = mutableMapOf<String, Job>()
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

    private companion object {
        const val MAX_HOSTED_CALLS = 8192
    }

    suspend fun lease(): ActiveSession = mutex.withLock {
        runtime.checkActive()
        DeepSeekLease(this).also { leases += it }
    }

    suspend fun release(lease: DeepSeekLease) {
        val isIdle = mutex.withLock {
            leases.remove(lease)
            lease.state.value = ActiveSessionState.Closed
            val isIdle = leases.isEmpty() && (currentTurn == null || currentTurn?.outcome != null)
            if (isIdle && runtime.isSingleSession) runtime.markUnresponsive()
            isIdle
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
        val content = dshPromptInputs(request, runtime.inputSupportFor(target.model), runtime.resources)
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
        hostedCalls.clear()
        hostedLifetimes.clear()
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
            } catch (error: AcpException.Remote) {
                log.w(error) { "DeepSeek Harness rejected the turn" }
                failed(turn.id, error)
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
        finish(turn, TurnOutcome.Failed(dshEngineFailure(error, currentRequest?.id)))
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
            val (submitted, delivered) = mutex.withLock {
                checkLease(lease)
                if (isCancelPending || isInterrupting || !isCurrentTurn(turn)) {
                    return@async
                }
                isCancelPending = true
                isInterrupting = true
                turnLifetime?.cancel()
                refreshState()
                val delivered = CompletableDeferred<Unit>()
                watchCancellation(turn, delivered)
                requireNotNull(submission.value) to delivered
            }
            try {
                submitted.await()
                val isCurrent = mutex.withLock {
                    currentTurn?.id == turn && currentTurn?.outcome == null
                }
                if (isCurrent) runtime.connection.cancel(ref.nativeId)
                delivered.complete(Unit)
            } finally {
                withContext(NonCancellable) { mutex.withLock { isCancelPending = false } }
            }
        }.await()
    }

    /**
     * Starts before submission/write waits: a wedged shared pipe must not prevent Stop from retiring the process.
     * A normal terminal acknowledgement cancels this watchdog in finish, preserving other sessions on the runtime.
     */
    private fun watchCancellation(turn: TurnId, delivered: CompletableDeferred<Unit>): Job = runtime.scope.launch {
        val settled = withTimeoutOrNull(DSH_CANCEL_TIMEOUT_MS) {
            state.first { !it.isPending(turn) }
            delivered.await()
        }
        if (settled != null) return@launch
        val watcher = currentCoroutineContext()
        withContext(NonCancellable) {
            val isCurrent = mutex.withLock {
                watcher.ensureActive()
                if (currentTurn?.id != turn || (currentTurn?.outcome != null && !isCancelPending)) return@withLock false
                runtime.markUnresponsive()
                true
            }
            if (!isCurrent) return@withContext
            val failure = EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
            log.w(failure) { "DeepSeek Harness did not acknowledge cancellation; retire shared runtime" }
            // Shutdown records Unknown for unconfirmed turns; it never claims native cancellation succeeded.
            runtime.close()
        }
    }

    private fun isCurrentTurn(turn: TurnId): Boolean = currentTurn?.let { it.id == turn && it.outcome == null } == true

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

    /** Atomically consumes an ACP-admitted call; a late or duplicate MCP request cannot borrow another turn. */
    suspend fun hostedContext(call: String, name: String): AgentToolContext? = mutex.withLock {
        val turn = currentTurn?.takeIf { it.outcome == null } ?: return@withLock null
        val origin = hostedCalls.remove(call) ?: return@withLock null
        if (origin != (turn.id to name) || runtime.isClosed) return@withLock null
        val lifetime = hostedLifetimes[call]?.takeIf { it.isActive } ?: return@withLock null
        val prompt = currentRequest ?: return@withLock null
        AgentToolContext(
            ref, route.workspace, turn.id, prompt.id, prompt.trust ?: TrustLevel.Ask,
            AgentToolPermissions { approval -> requestUserPermission(turn.id, approval) },
            callId = ToolCallId(call), lifetime = lifetime, target = target,
            decisionMode = prompt.decisionMode, reviewTarget = prompt.reviewTarget,
            userInstruction = prompt.userInstruction,
        )
    }

    private suspend fun admitHosted(call: String, name: String, turn: TurnId): Boolean = mutex.withLock {
        if (currentTurn?.id != turn || currentTurn?.outcome != null) return@withLock false
        if (turnLifetime?.isActive != true || runtime.isClosed) return@withLock false
        if (call.isBlank() || usedHostedCalls.size >= MAX_HOSTED_CALLS) return@withLock false
        if (!usedHostedCalls.add(call)) return@withLock false
        hostedCalls[call] = turn to name
        hostedLifetimes[call] = Job(turnLifetime)
        true
    }

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val (turn, prompt, lifetime) = activePermission() ?: return AcpPermissionOutcome.Cancelled
        val toolCallId = request.toolCall["toolCallId"]?.jsonPrimitive?.contentOrNull
            ?: return AcpPermissionOutcome.Cancelled
        val observed = mutex.withLock { toolCalls[toolCallId] } ?: return AcpPermissionOutcome.Cancelled
        // Never persist a CLI allow rule that could bypass later modes.
        val option = request.options.firstOrNull { it.kind == "allow_once" }
            ?: return AcpPermissionOutcome.Cancelled
        val title = mutex.withLock { (history[ItemId("tool-$toolCallId")] as? SessionItem.ToolCall)?.name }
        val hostedName = hosted.names[title]
        if (hostedName != null) {
            return if (admitHosted(toolCallId, hostedName, turn.id)) {
                AcpPermissionOutcome.Selected(option.optionId)
            } else {
                AcpPermissionOutcome.Cancelled
            }
        }
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
                    PermissionOption(PermissionOptionId("allow"), "Allow", false, isOneTime = true),
                    PermissionOption(PermissionOptionId("deny"), "Deny", true, isOneTime = true),
                ),
                description = approval.description,
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
        updateToolResult(id, update, status, turn)
        revokeCompletedHosted(id, status)
    }

    @HighFrequency
    private fun updateToolResult(id: String, update: JsonObject, status: ToolCallStatus, turn: TurnId?) {
        if (status != ToolCallStatus.Succeeded && status != ToolCallStatus.Failed) return
        val content = update["content"] as? kotlinx.serialization.json.JsonArray ?: return
        val parts = content.mapNotNull { entry ->
            val block = (entry as? JsonObject)?.get("content") as? JsonObject ?: return@mapNotNull null
            (block["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let(ContentPart::Text)
        }
        val resultId = ItemId("result-$id")
        val previous = history[resultId]
        upsert(
            SessionItem.ToolResult(
                ItemInfo(
                    resultId,
                    previous?.info?.position ?: nextPosition(),
                    (previous?.info?.revision ?: -1) + 1,
                    turn,
                ),
                ToolCallId(id),
                parts,
                if (status == ToolCallStatus.Failed) EngineFailure.Unknown() else null,
            ),
        )
    }

    private fun revokeCompletedHosted(id: String, status: ToolCallStatus) {
        if (status == ToolCallStatus.Succeeded || status == ToolCallStatus.Failed) {
            hostedCalls.remove(id)
            hostedLifetimes.remove(id)?.cancel()
        }
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

    private suspend fun finish(id: TurnId, outcome: TurnOutcome) {
        val isUnobserved = mutex.withLock {
            val turn = currentTurn?.takeIf { it.id == id && it.outcome == null } ?: return
            val finished = turn.copy(outcome = outcome)
            currentTurn = finished
            turnLifetime?.cancel()
            turnLifetime = null
            hostedCalls.clear()
            hostedLifetimes.clear()
            log.i { "DeepSeek Harness turn finished" }
            pending.values.forEach { it.answer.complete(AcpPermissionOutcome.Cancelled) }
            pending.clear()
            setState(ActiveSessionState.Ready(finished))
            history.publish { SessionEvent.TurnFinished(it, finished.id, outcome) }
            submission.value?.completeExceptionally(
                EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn.request)),
            )
            val isUnobserved = leases.isEmpty() && runtime.isSingleSession
            if (isUnobserved) runtime.markUnresponsive()
            isUnobserved
        }
        if (isUnobserved) runtime.releaseSession(this)
    }

    fun revoke() {
        hosted.close()
        sessionLifetime.cancel()
        turnLifetime?.cancel()
    }

    suspend fun disconnected() {
        revoke()
        val turn = mutex.withLock { currentTurn }
        if (turn != null) finish(turn.id, TurnOutcome.Unknown)
        mutex.withLock {
            // Keep the terminal evidence stable: Ready followed immediately by Closed can be conflated by the
            // facade's StateFlow observer, leaving its accepted turn unresolved forever.
            val finished = currentTurn?.takeIf { it.outcome != null }
            setState(
                if (finished == null) {
                    ActiveSessionState.Closed
                } else {
                    ActiveSessionState.Unavailable(
                        EngineFailure.Transport(TransportFailureReason.ServiceUnavailable),
                        lastTurn = finished,
                    )
                },
            )
        }
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
        AcceptsImages to object : AcceptsImages {
            override val mediaTypes: Set<String>
                get() = session.runtime.inputSupportFor(session.target.model).imageMediaTypes
        },
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

/**
 * Classifies a failed turn. A remote JSON-RPC error rejects this request (DSH answers `-32602` for an image the
 * selected route does not accept), so it must not be reported as a transport failure: the user would be told to
 * check the network for a request the engine refused outright.
 */
internal fun dshEngineFailure(error: Throwable, request: RequestId?): EngineFailure = when (error) {
    is EngineException -> error.failure
    is AcpException.Remote -> EngineFailure.Request(RequestFailureReason.Invalid, request)
    is AcpException.Protocol -> EngineFailure.Transport(TransportFailureReason.ProtocolViolation)
    else -> EngineFailure.Engine(EngineFailureReason.Crashed)
}

/** The watchdog belongs to one turn; it can never time out a subsequent turn on the same session. */
private fun ActiveSessionState.isPending(id: TurnId): Boolean = when (this) {
    is ActiveSessionState.Running -> turn.id == id
    is ActiveSessionState.AwaitingUserAction -> turn.id == id
    is ActiveSessionState.Interrupting -> turn.id == id
    is ActiveSessionState.Submitting -> turn.id == id
    is ActiveSessionState.Unavailable -> activeTurn?.id == id
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> false
}
