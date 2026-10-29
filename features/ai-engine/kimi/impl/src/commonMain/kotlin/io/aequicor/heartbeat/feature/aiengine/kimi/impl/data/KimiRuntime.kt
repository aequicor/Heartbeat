package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.HighFrequency
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStep
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStepStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
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
    private val toggles: FeatureToggles,
    internal val tools: ProfileAgentTools = NoAgentTools,
    internal val resources: ResourceResolver = ResourceResolver { null },
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions,
    AcpClientHandler {
    private val providerUsage = KimiProviderUsage { toggles.get(EngineUsageEnabled) }
    override val features = KimiFeatures(
        CreatesSessions to this,
        AttachesSessions to this,
        ReportsProviderUsage to providerUsage,
    )
    private val sessions = MutableStateFlow<Map<String, KimiSession>>(emptyMap())
    private val mutex = Mutex()
    private val loading = Mutex()
    private val log = Log.tag("KimiRuntime")

    @Volatile
    private var isShutdown = false
    override val isClosed: Boolean get() = isShutdown

    private val usageToggleObserver = scope.launch {
        try {
            toggles.observe(EngineUsageEnabled).collect { enabled -> if (!enabled) clearUsage() }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Kimi usage toggle observer failed" }
        }
    }

    override suspend fun create(request: CreateSessionRequest): ActiveSession {
        checkActive()
        val cwd = directory(request)
        val native = connection.newSession(cwd)
        configureCatching(native.sessionId, native.metadata, request.target)
        return session(native.sessionId, request.target, request.workspace, native.metadata, cwd).lease()
    }

    suspend fun discoverCatalog(
        binding: io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId,
    ): KimiModelCatalog {
        val target = EngineTarget(identity.engine, binding, ModelId("catalog"))
        val native = connection.newSession(directory(CreateSessionRequest(target)))
        return kimiNativeCatalog(native.metadata, initialization.agentCapabilities)
    }

    private suspend fun configure(sessionId: String, metadata: JsonObject, target: EngineTarget) {
        if (metadata.hasConfigOption("model")) {
            connection.setConfigOption(sessionId, "model", nativeKimiModel(target.model))
        }
        if (metadata.hasConfigOption("mode")) {
            connection.setConfigOption(sessionId, "mode", "default")
        }
    }

    /** Maps native configuration failures to the facade contract; the native session has no partial-cleanup path. */
    @Suppress("SwallowedException") // The original is logged above; EngineException carries only the domain failure.
    private suspend fun configureCatching(sessionId: String, metadata: JsonObject, target: EngineTarget) {
        try {
            configure(sessionId, metadata, target)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Kimi session configuration failed" }
            throw EngineException(error.engineFailure())
        }
    }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        if (ref.engine != KimiEngine.Id || ref.source != KimiEngine.SessionSource) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        return resume(request, ref.nativeId)
    }

    /**
     * Loading omits ACP replay deliberately: ACP has no stable message IDs, so replay would duplicate the
     * host's saved transcript. Partial history exposes only observations from this runtime generation.
     */
    suspend fun resume(request: ResumeSessionRequest, nativeId: String): ActiveSession = loading.withLock {
        checkActive()
        val existing = mutex.withLock { sessions.value[nativeId] }
        if (existing != null) {
            if (existing.route.binding != request.target.binding || existing.route.workspace != request.workspace ||
                existing.target != request.target
            ) {
                throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
            }
            return@withLock existing.lease()
        }
        if (!initialization.agentCapabilities["loadSession"].supportsLoad()) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        val cwd = directory(CreateSessionRequest(request.target, request.workspace))
        val native = connection.loadSession(nativeId, cwd)
        configureCatching(nativeId, native.metadata, request.target)
        session(nativeId, request.target, request.workspace, native.metadata, cwd).lease()
    }

    private suspend fun session(
        nativeId: String,
        target: EngineTarget,
        workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef?,
        metadata: JsonObject,
        workingDirectory: String,
    ): KimiSession = mutex.withLock {
        checkActive()
        sessions.value[nativeId] ?: run {
            val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, nativeId)
            val route = ExecutionRoute(
                KimiEngine.Id,
                target.binding,
                identity.source,
                identity.revision,
                workspace,
            )
            log.i { "Kimi session registered" }
            val created = KimiSession(
                ref,
                route,
                target,
                this,
                kimiNativeCatalog(metadata, initialization.agentCapabilities),
                workingDirectory,
            )
            sessions.value = sessions.value + (nativeId to created)
            created
        }
    }

    @HighFrequency
    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
        mutex.withLock { sessions.value[update.sessionId] }?.update(update.update)
    }

    internal suspend fun receiveUsage(session: KimiSession, update: JsonObject) {
        if (!toggles.get(EngineUsageEnabled)) return
        session.contextUsage.receive(update)
        providerUsage.receive(update)
    }

    private fun clearUsage() {
        providerUsage.clear()
        sessions.value.values.forEach { it.contextUsage.clear() }
    }

    override fun onPromptSubmitted(sessionId: String) {
        sessions.value[sessionId]?.promptSubmitted()
    }

    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val session = mutex.withLock { sessions.value[request.sessionId] } ?: return AcpPermissionOutcome.Cancelled
        return session.permission(request)
    }

    override suspend fun close() {
        if (isShutdown) return
        isShutdown = true
        usageToggleObserver.cancel()
        try {
            withContext(NonCancellable) { connection.close() }
        } finally {
            withContext(NonCancellable) {
                val detached = mutex.withLock {
                    log.i { "Kimi runtime closed" }
                    val previous = sessions.value.values.toList()
                    sessions.value = emptyMap()
                    previous
                }
                detached.forEach { it.disconnected() }
            }
        }
    }

    internal fun checkActive() {
        scope.coroutineContext.ensureActive()
        if (isShutdown) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    companion object {
        @Suppress("LongParameterList") // The factory stitches independent DI-provided profile dependencies.
        suspend fun open(
            identity: RuntimeIdentity,
            transport: io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport,
            clients: AcpClientFactory,
            scope: CoroutineScope,
            directory: suspend (CreateSessionRequest) -> String,
            toggles: FeatureToggles,
            tools: ProfileAgentTools = NoAgentTools,
            resources: ResourceResolver = ResourceResolver { null },
        ): KimiRuntime {
            val runtime = MutableStateFlow<KimiRuntime?>(null)
            val connection = clients.connect(
                transport,
                scope,
                object : AcpClientHandler {
                    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
                        runtime.value?.onSessionUpdate(update)
                    }

                    override fun onPromptSubmitted(sessionId: String) {
                        runtime.value?.onPromptSubmitted(sessionId)
                    }

                    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome =
                        runtime.value?.requestPermission(request) ?: AcpPermissionOutcome.Cancelled
                },
            )
            var isTransferred = false
            try {
                val initialization = connection.initialize(AcpImplementation("heartbeat", "1", "Heartbeat"))
                return KimiRuntime(
                    identity,
                    connection,
                    scope,
                    initialization,
                    directory,
                    toggles,
                    tools,
                    resources,
                ).also {
                    runtime.value = it
                    isTransferred = true
                }
            } finally {
                if (!isTransferred) withContext(NonCancellable) { connection.close() }
            }
        }
    }
}

/** Metadata reads never require or start a live runtime. The facade supplies route-checked resumption. */
internal class KimiStoredSession(ref: SessionRef) : EngineSession {
    override val summary = MutableStateFlow(SessionSummary(ref))
    override val features = KimiFeatures()
}

internal class KimiSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    private val runtime: KimiRuntime,
    private val catalog: KimiModelCatalog,
    private val workingDirectory: String,
) : SessionHistory {
    val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    val contextUsage = KimiContextUsage()
    private val history = KimiHistory()
    private val leases = mutableSetOf<KimiLease>()
    private var messageId: ItemId? = null
    private val pending = mutableMapOf<PermissionRequestId, PendingPermission>()
    private val mutex = Mutex()
    private var position = 0L
    private var currentTurn: Turn? = null
    private var currentRequest: PromptRequest? = null
    private val submission = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    private var isCancelPending = false
    private var isInterrupting = false
    private val log = Log.tag("KimiSession")

    suspend fun lease(): ActiveSession = mutex.withLock {
        runtime.checkActive()
        KimiLease(this).also { leases += it }
    }

    suspend fun release(lease: KimiLease) = mutex.withLock {
        leases.remove(lease)
        lease.state.value = ActiveSessionState.Closed
    }

    private fun checkLease(lease: KimiLease) {
        runtime.checkActive()
        if (lease !in leases) {
            throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        }
    }

    suspend fun send(request: PromptRequest, lease: KimiLease): TurnId {
        val (turn, submitted) = mutex.withLock {
            checkLease(lease)
            if (isCancelPending || (currentTurn != null && currentTurn?.outcome == null)) busy()
            start(request)
        }
        submitted.await()
        return turn.id
    }

    private suspend fun start(request: PromptRequest): Pair<Turn, CompletableDeferred<Unit>> {
        validate(request)
        val content = kimiPromptInputs(request, catalog.inputSupport, runtime.resources)
        applyEffort(request)
        val turn = Turn(TurnId(newId("turn")), request.id, target, decisionMode = request.decisionMode)
        currentTurn = turn
        currentRequest = request
        val submitted = CompletableDeferred<Unit>()
        submission.value = submitted
        isInterrupting = false
        messageId = null
        log.i { "Kimi turn submitted" }
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
                log.w(error) { "Kimi connection failed" }
                failed(turn.id, error, retire = true)
            } catch (error: AcpException.Protocol) {
                log.w(error) { "Kimi connection failed" }
                failed(turn.id, error, retire = true)
            } catch (error: Exception) {
                log.w(error) { "Kimi turn failed" }
                failed(turn.id, error)
            } finally {
                withContext(NonCancellable) { finish(turn.id, TurnOutcome.Unknown) }
            }
        }
        return turn to submitted
    }

    @Suppress("SwallowedException") // Mapped to the domain failure; the turn failure path logs the original.
    private suspend fun applyEffort(request: PromptRequest) {
        val effort = request.reasoningEffort ?: return
        try {
            runtime.connection.setConfigOption(ref.nativeId, "thinking", effort)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EngineException(error.engineFailure())
        }
    }

    private suspend fun failed(turn: TurnId, error: Exception, retire: Boolean = false) {
        finish(turn, TurnOutcome.Failed(error.engineFailure()))
        if (retire) runtime.close()
    }

    fun promptSubmitted() {
        submission.value?.complete(Unit)
    }

    private fun validate(request: PromptRequest) {
        val effort = request.reasoningEffort
        if (request.compaction != null || (effort != null && effort !in catalog.efforts)) {
            throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
    }

    suspend fun cancel(turn: TurnId, lease: KimiLease) {
        runtime.scope.async {
            val submitted = mutex.withLock {
                checkLease(lease)
                if (isCancelPending) return@async
                if (currentTurn?.id != turn || currentTurn?.outcome != null) return@async
                isCancelPending = true
                isInterrupting = true
                refreshState()
                requireNotNull(submission.value)
            }
            try {
                submitted.await()
                mutex.withLock {
                    if (currentTurn?.id == turn && currentTurn?.outcome == null) {
                        runtime.connection.cancel(ref.nativeId)
                    }
                }
            } finally {
                withContext(NonCancellable) { mutex.withLock { isCancelPending = false } }
            }
        }.await()
    }

    suspend fun respond(decision: PermissionDecision, lease: KimiLease) = mutex.withLock {
        checkLease(lease)
        val request = pending[decision.request]
        if (request == null || request.request.turn != decision.turn) return@withLock
        if (request.request.options.none { it.id == decision.option } || decision.answer != null) return@withLock
        if (!request.answer.complete(AcpPermissionOutcome.Selected(decision.option.value))) return@withLock
        pending.remove(decision.request)
        val turn = currentTurn ?: return@withLock
        currentTurn = turn.copy(resolvedPermissions = turn.resolvedPermissions + decision.request)
        log.i { "Kimi permission resolved" }
        refreshState()
    }

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val (turn, prompt) = mutex.withLock {
            val active = currentTurn?.takeIf { it.outcome == null } ?: return AcpPermissionOutcome.Cancelled
            active to (currentRequest ?: return AcpPermissionOutcome.Cancelled)
        }
        val permissions = AgentToolPermissions { approval -> requestUserPermission(turn.id, approval) }
        val context = AgentToolContext(
            ref,
            route.workspace,
            turn.id,
            prompt.id,
            prompt.trust ?: TrustLevel.Ask,
            permissions,
            callId = request.toolCall["toolCallId"]?.jsonPrimitive?.contentOrNull?.let(::ToolCallId),
            target = target,
            decisionMode = prompt.decisionMode,
            reviewTarget = prompt.reviewTarget,
            userInstruction = prompt.userInstruction,
        )
        val verdict = runtime.tools.authorizeNative(context, kimiNativeCall(request.toolCall, workingDirectory))
        val isActive = mutex.withLock { currentTurn?.id == turn.id && currentTurn?.outcome == null }
        if (!isActive || verdict is NativeVerdict.Deny) return AcpPermissionOutcome.Cancelled
        val option = request.options.firstOrNull { it.kind == "allow_once" }
            ?: request.options.firstOrNull { it.kind.startsWith("allow") }
            ?: return AcpPermissionOutcome.Cancelled
        return AcpPermissionOutcome.Selected(option.optionId)
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
            log.i { "Kimi permission requested" }
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

            "usage_update" -> runtime.receiveUsage(this, update)
        }
    }

    @HighFrequency
    private fun updateTool(update: JsonObject, turn: TurnId?) {
        val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val call = ToolCallId(id)
        val previous = history[ItemId("tool-$id")] as? SessionItem.ToolCall
        val status = when (update["status"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> ToolCallStatus.Succeeded
            "failed" -> ToolCallStatus.Failed
            "in_progress" -> ToolCallStatus.Running
            else -> previous?.status ?: ToolCallStatus.Pending
        }
        val item = SessionItem.ToolCall(
            ItemInfo(
                ItemId("tool-$id"),
                previous?.info?.position ?: nextPosition(),
                (previous?.info?.revision ?: -1) + 1,
                turn,
            ),
            call,
            update["title"]?.jsonPrimitive?.contentOrNull ?: update["kind"]?.jsonPrimitive?.contentOrNull
                ?: previous?.name
                ?: "tool",
            update["rawInput"]?.takeUnless { it == JsonNull }?.toString() ?: previous?.arguments ?: "{}",
            status,
        )
        upsert(item)
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
        submission.value?.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn.request)),
        )
        log.i { "Kimi turn finished" }
        pending.values.forEach { it.answer.complete(AcpPermissionOutcome.Cancelled) }
        pending.clear()
        setState(ActiveSessionState.Ready(finished))
        history.publish { SessionEvent.TurnFinished(it, finished.id, outcome) }
    }

    suspend fun disconnected() {
        val turn = mutex.withLock { currentTurn }
        if (turn != null) finish(turn.id, TurnOutcome.Unknown)
        mutex.withLock {
            setState(ActiveSessionState.Closed)
        }
    }

    suspend fun synchronize(lease: KimiLease) = mutex.withLock {
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
        log.v { "Kimi session state changed" }
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
internal class KimiLease(private val session: KimiSession) :
    ActiveSession,
    SendsPrompts,
    CancelsTurns,
    RequestsPermissions,
    ReconcilesSession {
    override val ref = session.ref
    override val route = session.route
    override val state = MutableStateFlow(session.state.value)
    override val features = KimiFeatures(
        SendsPrompts to this,
        CancelsTurns to this,
        RequestsPermissions to this,
        ReconcilesSession to this,
        SessionHistory to session,
        SessionContextUsage to session.contextUsage,
    )

    override suspend fun send(request: PromptRequest) = session.send(request, this)
    override suspend fun cancel(turn: TurnId) = session.cancel(turn, this)
    override suspend fun respond(decision: PermissionDecision) = session.respond(decision, this)
    override suspend fun synchronize() = session.synchronize(this)
    override suspend fun close() = session.release(this)
}

@OptIn(ExperimentalUuidApi::class)
private fun newId(prefix: String): String = "$prefix-${Uuid.random().toHexString()}"

private fun kotlinx.serialization.json.JsonElement?.supportsLoad(): Boolean =
    (this as? JsonPrimitive)?.contentOrNull == "true"

private fun JsonObject.hasConfigOption(id: String): Boolean = this["configOptions"]?.jsonArray.orEmpty().any {
    (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == id
}

private fun Throwable.engineFailure(): EngineFailure = when (this) {
    is EngineException -> failure

    is AcpException.Protocol, is AcpException.Remote -> EngineFailure.Transport(
        TransportFailureReason.ProtocolViolation,
    )

    else -> EngineFailure.Engine(EngineFailureReason.Crashed)
}
