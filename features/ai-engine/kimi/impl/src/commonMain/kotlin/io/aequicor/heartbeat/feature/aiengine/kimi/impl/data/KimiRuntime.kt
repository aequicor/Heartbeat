package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One profile-owned ACP connection. Accepted turns outlive individual session handles. */
internal class KimiRuntime(
    override val identity: RuntimeIdentity,
    internal val connection: AcpConnection,
    internal val scope: CoroutineScope,
    private val initialization: AcpInitialization,
    private val directory: suspend (CreateSessionRequest) -> String,
) : EngineRuntime,
    CreatesSessions,
    AcpClientHandler {
    override val features = KimiFeatures(CreatesSessions to this)
    private val sessions = mutableMapOf<String, KimiSession>()
    private val mutex = Mutex()

    @Volatile
    private var isShutdown = false
    override val isClosed: Boolean get() = isShutdown

    override suspend fun create(request: CreateSessionRequest): ActiveSession {
        checkActive()
        val native = connection.newSession(directory(request))
        return attach(native.sessionId, request.target, request.workspace)
    }

    suspend fun resume(request: ResumeSessionRequest, nativeId: String): ActiveSession {
        checkActive()
        val existing = mutex.withLock { sessions[nativeId] }
        if (existing != null) return existing
        if (!initialization.agentCapabilities["loadSession"].supportsLoad()) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        connection.loadSession(nativeId, directory(CreateSessionRequest(request.target, request.workspace)))
        return attach(nativeId, request.target, request.workspace)
    }

    fun stored(ref: SessionRef): EngineSession = KimiStoredSession(ref, this)

    private suspend fun attach(
        nativeId: String,
        target: EngineTarget,
        workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef?,
    ): KimiSession = mutex.withLock {
        sessions.getOrPut(nativeId) {
            val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, nativeId)
            val route = ExecutionRoute(
                KimiEngine.Id,
                target.binding,
                identity.source,
                identity.revision,
                workspace,
            )
            KimiSession(ref, route, target, this)
        }
    }

    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
        mutex.withLock { sessions[update.sessionId] }?.update(update.update)
    }

    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val session = mutex.withLock { sessions[request.sessionId] } ?: return AcpPermissionOutcome.Cancelled
        return session.permission(request)
    }

    override suspend fun close() {
        if (isShutdown) return
        isShutdown = true
        connection.close()
        mutex.withLock { sessions.values.toList() }.forEach { it.disconnected() }
    }

    private fun checkActive() {
        if (isShutdown) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    companion object {
        suspend fun open(
            identity: RuntimeIdentity,
            transport: io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport,
            clients: AcpClientFactory,
            scope: CoroutineScope,
            directory: suspend (CreateSessionRequest) -> String,
        ): KimiRuntime {
            var runtime: KimiRuntime? = null
            val connection = clients.connect(
                transport,
                scope,
                object : AcpClientHandler {
                    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
                        runtime?.onSessionUpdate(update)
                    }

                    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome =
                        runtime?.requestPermission(request) ?: AcpPermissionOutcome.Cancelled
                },
            )
            try {
                val initialization = connection.initialize(AcpImplementation("heartbeat", "1", "Heartbeat"))
                return KimiRuntime(identity, connection, scope, initialization, directory).also { runtime = it }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                connection.close()
                throw error
            }
        }
    }
}

private class KimiStoredSession(ref: SessionRef, runtime: KimiRuntime) : EngineSession {
    override val summary = MutableStateFlow(SessionSummary(ref))
    override val features = KimiFeatures(
        ResumesSessions to object : ResumesSessions {
            override suspend fun resume(request: ResumeSessionRequest): ActiveSession = runtime.resume(
                request,
                ref.nativeId,
            )
        },
    )
}

internal class KimiSession(
    override val ref: SessionRef,
    override val route: ExecutionRoute,
    private val target: EngineTarget,
    private val runtime: KimiRuntime,
) : ActiveSession,
    SendsPrompts,
    CancelsTurns,
    RequestsPermissions,
    ReconcilesSession,
    SessionHistory {
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    override val features = KimiFeatures(
        SendsPrompts to this,
        CancelsTurns to this,
        RequestsPermissions to this,
        ReconcilesSession to this,
        SessionHistory to this,
    )
    private val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    private val items = linkedMapOf<ItemId, SessionItem>()
    private val pending = mutableMapOf<PermissionRequestId, PendingPermission>()
    private val mutex = Mutex()
    private var position = 0L
    private var checkpoint = 0L
    private var currentTurn: Turn? = null
    private var isClosed = false
    private val log = Log.tag("KimiSession")

    override suspend fun send(request: PromptRequest): TurnId {
        if (isClosed || (currentTurn != null && currentTurn?.outcome == null)) busy()
        validate(request)
        val text = request.parts.map { part ->
            when (part) {
                is ContentPart.Text -> part.text
                is ContentPart.Image, is ContentPart.Resource -> unsupportedContent(request)
                is ContentPart.Reasoning -> unsupportedContent(request)
            }
        }.joinToString("\n")
        val turn = Turn(TurnId(newId("turn")), request.id, target, decisionMode = request.decisionMode)
        currentTurn = turn
        log.i { "Kimi turn submitted" }
        state.value = ActiveSessionState.Running(turn)
        publish(SessionEvent.TurnStarted(nextCheckpoint(), turn))
        addMessage(MessageRole.User, request.parts, turn.id)
        runtime.scope.launch {
            try {
                val result = runtime.connection.prompt(ref.nativeId, text)
                finish(if (result.stopReason == "cancelled") TurnOutcome.Cancelled else TurnOutcome.Completed)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Kimi turn failed" }
                finish(TurnOutcome.Failed(error.engineFailure()))
            }
        }
        return turn.id
    }

    private fun validate(request: PromptRequest) {
        val hasUnsupportedOptions = listOf(
            request.compaction != null,
            request.reasoningEffort != null,
            request.trust != null,
            request.reviewTarget != null,
        ).any { it }
        if (hasUnsupportedOptions) {
            throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
    }

    private fun unsupportedContent(request: PromptRequest): Nothing = throw EngineException(
        EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id),
    )

    override suspend fun cancel(turn: TurnId) {
        if (currentTurn?.id != turn || currentTurn?.outcome != null) return
        runtime.connection.cancel(ref.nativeId)
    }

    override suspend fun respond(decision: PermissionDecision) {
        val request = mutex.withLock { pending[decision.request] }
        if (request == null || request.request.turn != decision.turn ||
            request.request.options.none { it.id == decision.option }
        ) {
            return
        }
        request.answer.complete(AcpPermissionOutcome.Selected(decision.option.value))
        mutex.withLock { pending.remove(decision.request) }
        val turn = currentTurn ?: return
        val resolved = turn.copy(resolvedPermissions = turn.resolvedPermissions + decision.request)
        currentTurn = resolved
        log.i { "Kimi permission resolved" }
        state.value = ActiveSessionState.Running(resolved)
    }

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val turn = currentTurn?.takeIf { it.outcome == null } ?: return AcpPermissionOutcome.Cancelled
        val toolId = request.toolCall["toolCallId"]?.jsonPrimitive?.contentOrNull ?: newId("permission")
        val id = PermissionRequestId(toolId)
        val normalized = PermissionRequest(
            id,
            turn.id,
            request.toolCall["title"]?.jsonPrimitive?.contentOrNull ?: "Kimi CLI requests permission",
            request.options.map {
                PermissionOption(PermissionOptionId(it.optionId), it.name, it.kind.startsWith("reject"))
            },
        )
        val answer = CompletableDeferred<AcpPermissionOutcome>()
        mutex.withLock { pending[id] = PendingPermission(normalized, answer) }
        log.i { "Kimi permission requested" }
        state.value = ActiveSessionState.AwaitingUserAction(turn, listOf(normalized))
        publish(SessionEvent.PermissionRequested(nextCheckpoint(), normalized))
        return answer.await()
    }

    fun update(update: JsonObject) {
        val kind = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return
        val turn = currentTurn?.id
        when (kind) {
            "agent_message_chunk", "agent_thought_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
                val role = if (kind == "agent_message_chunk") MessageRole.Assistant else MessageRole.System
                val part = if (kind == "agent_message_chunk") ContentPart.Text(text) else ContentPart.Reasoning(text)
                appendMessage(role, part, turn)
            }

            "tool_call", "tool_call_update" -> updateTool(update, turn)

            "plan" -> updatePlan(update)
        }
    }

    private fun updateTool(update: JsonObject, turn: TurnId?) {
        val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val call = ToolCallId(id)
        val status = when (update["status"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> ToolCallStatus.Succeeded
            "failed" -> ToolCallStatus.Failed
            "in_progress" -> ToolCallStatus.Running
            else -> ToolCallStatus.Pending
        }
        val item = SessionItem.ToolCall(
            ItemInfo(ItemId("tool-$id"), nextPosition(), nextRevision(), turn),
            call,
            update["title"]?.jsonPrimitive?.contentOrNull ?: update["kind"]?.jsonPrimitive?.contentOrNull ?: "tool",
            update["rawInput"]?.toString() ?: "{}",
            status,
        )
        upsert(item)
    }

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
        upsert(SessionItem.Plan(ItemInfo(ItemId("plan"), nextPosition(), nextRevision(), currentTurn?.id), steps))
    }

    private fun addMessage(role: MessageRole, parts: List<ContentPart>, turn: TurnId?) {
        upsert(SessionItem.Message(ItemInfo(ItemId(newId("message")), nextPosition(), 0, turn), role, parts))
    }

    private fun appendMessage(role: MessageRole, part: ContentPart, turn: TurnId?) {
        val id = ItemId(if (role == MessageRole.Assistant) "assistant" else "reasoning")
        val previous = items[id] as? SessionItem.Message
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

    private fun upsert(item: SessionItem) {
        items[item.info.id] = item
        publish(SessionEvent.ItemUpserted(nextCheckpoint(), item))
    }

    private fun finish(outcome: TurnOutcome) {
        val turn = currentTurn ?: return
        if (turn.outcome != null) return
        val finished = turn.copy(outcome = outcome)
        currentTurn = finished
        log.i { "Kimi turn finished" }
        state.value = ActiveSessionState.Ready(finished)
        pending.values.forEach { it.answer.complete(AcpPermissionOutcome.Cancelled) }
        pending.clear()
        publish(SessionEvent.TurnFinished(nextCheckpoint(), finished.id, outcome))
    }

    fun disconnected() {
        if (currentTurn?.outcome == null) finish(TurnOutcome.Unknown)
    }

    override suspend fun synchronize() {
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        log.i { "Kimi session synchronized" }
        state.value = ActiveSessionState.Ready(currentTurn?.takeIf { it.outcome != null })
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        val all = items.values.sortedBy { it.info.position }
        return HistoryPage(
            all.takeLast(request.limit),
            null,
            null,
            HistoryCheckpoint(checkpoint.toString()),
            HistoryCoverage.Partial,
        )
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = events

    override suspend fun close() {
        isClosed = true
    }

    private fun busy(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
    private fun nextPosition() = position++
    private fun nextRevision() = checkpoint + 1
    private fun nextCheckpoint() = HistoryCheckpoint((++checkpoint).toString())
    private fun publish(event: SessionEvent) {
        log.v { "Kimi history event published" }
        check(events.tryEmit(event))
    }

    private data class PendingPermission(
        val request: PermissionRequest,
        val answer: CompletableDeferred<AcpPermissionOutcome>,
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun newId(prefix: String): String = "$prefix-${Uuid.random().toHexString()}"

private fun kotlinx.serialization.json.JsonElement?.supportsLoad(): Boolean =
    (this as? JsonPrimitive)?.contentOrNull == "true"

private fun Throwable.engineFailure(): EngineFailure = when (this) {
    is EngineException -> failure

    is AcpException.Protocol, is AcpException.Remote -> EngineFailure.Transport(
        TransportFailureReason.ProtocolViolation,
    )

    else -> EngineFailure.Engine(EngineFailureReason.Crashed)
}
