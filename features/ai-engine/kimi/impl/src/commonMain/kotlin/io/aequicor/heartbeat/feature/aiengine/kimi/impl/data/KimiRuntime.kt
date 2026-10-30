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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    fetchProviderUsage: (suspend () -> ProviderUsageSnapshot?)? = null,
    private val bridge: AgentToolBridge = UnavailableAgentToolBridge,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions,
    AcpClientHandler {
    private val providerUsage = KimiProviderUsage(fetchProviderUsage) { toggles.get(EngineUsageEnabled) }
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
        val hosted = hosted(
            request.target,
            request.workspace,
            null,
            request.areDetachedToolsEnabled,
            request.preselected,
        )
        var isTransferred = false
        try {
            val native = connection.newSession(cwd, hosted.servers)
            configureCatching(native.sessionId, native.metadata, request.target)
            val session = session(native.sessionId, request.target, request.workspace, native.metadata, cwd, hosted)
            hosted.session.value = session
            isTransferred = true
            return session.lease(request.areDetachedToolsEnabled)
        } finally {
            if (!isTransferred) hosted.close()
        }
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
            return@withLock existing.lease(request.areDetachedToolsEnabled)
        }
        if (!initialization.agentCapabilities["loadSession"].supportsLoad()) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        val cwd = directory(CreateSessionRequest(request.target, request.workspace))
        val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, nativeId)
        val hosted = hosted(request.target, request.workspace, ref, request.areDetachedToolsEnabled, emptyMap())
        var isTransferred = false
        try {
            val native = connection.loadSession(nativeId, cwd, hosted.servers)
            configureCatching(nativeId, native.metadata, request.target)
            val session = session(nativeId, request.target, request.workspace, native.metadata, cwd, hosted)
            hosted.session.value = session
            isTransferred = true
            session.lease(request.areDetachedToolsEnabled)
        } finally {
            if (!isTransferred) hosted.close()
        }
    }

    @Suppress("LongParameterList") // A session is bound to its native identity, route and capability.
    private suspend fun session(
        nativeId: String,
        target: EngineTarget,
        workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef?,
        metadata: JsonObject,
        workingDirectory: String,
        hosted: KimiHosted,
    ): KimiSession = mutex.withLock {
        checkActive()
        check(nativeId !in sessions.value) { "Kimi session identity already registered" }
        run {
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
                hosted,
            )
            sessions.value = sessions.value + (nativeId to created)
            created
        }
    }

    private suspend fun hosted(
        target: EngineTarget,
        workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef?,
        ref: SessionRef?,
        enabled: Boolean,
        preselected: Map<String, Set<String>>,
    ): KimiHosted {
        val holder = KimiHosted()
        if (workspace == null && !enabled) return holder
        val scope = AgentToolScope(workspace, target, session = ref, preselected = preselected)
        val names = tools.specifications(scope).mapTo(mutableSetOf()) { it.name }
        if (names.isEmpty()) return holder
        check(bridge.isAvailable) { "Hosted tools bridge is unavailable" }
        val attachment = bridge.attachCorrelated(scope.copy(declared = names)) { call, name ->
            holder.session.value?.hostedContext(call, name)
        }
        holder.attachment = attachment
        holder.names = names
        val header = buildJsonObject {
            put("name", "Authorization")
            put("value", "Bearer ${attachment.endpoint.token}")
        }
        val server = buildJsonObject {
            put("type", "http")
            put("name", HOSTED_TOOLS_SERVER)
            put("url", "${attachment.endpoint.url.trimEnd('/')}/mcp")
            put("headers", JsonArray(listOf(header)))
        }
        holder.servers = listOf(server)
        return holder
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
        sessions.value.values.forEach { it.revokeHosted() }
        withContext(NonCancellable) {
            try {
                val detached = mutex.withLock {
                    log.i { "Kimi runtime closed" }
                    val previous = sessions.value.values.toList()
                    sessions.value = emptyMap()
                    previous
                }
                detached.forEach { it.disconnected() }
            } finally {
                withContext(NonCancellable) { connection.close() }
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
            fetchProviderUsage: (suspend () -> ProviderUsageSnapshot?)? = null,
            bridge: AgentToolBridge = UnavailableAgentToolBridge,
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
                val meta = initialization.agentCapabilities["_meta"] as? JsonObject
                val heartbeat = meta?.get("heartbeat") as? JsonObject
                if (heartbeat?.get("permissionGate")?.jsonPrimitive?.contentOrNull != "1" ||
                    heartbeat["hostedCallBinding"]?.jsonPrimitive?.contentOrNull != "1"
                ) {
                    throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
                }
                return KimiRuntime(
                    identity,
                    connection,
                    scope,
                    initialization,
                    directory,
                    toggles,
                    tools,
                    resources,
                    fetchProviderUsage,
                    bridge,
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

@Suppress("LongParameterList", "TooManyFunctions") // Session owns handles, protocol callbacks and turn lifecycle.
internal class KimiSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    private val runtime: KimiRuntime,
    private val catalog: KimiModelCatalog,
    private val workingDirectory: String,
    private val hosted: KimiHosted,
) : SessionHistory {
    val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    val contextUsage = KimiContextUsage()
    private val history = KimiHistory()
    private val leases = mutableSetOf<KimiLease>()
    private var messageId: ItemId? = null
    private val pending = mutableMapOf<PermissionRequestId, PendingPermission>()
    private val mutex = Mutex()
    private val admissions = Mutex()
    private var position = 0L
    private var currentTurn: Turn? = null
    private var currentRequest: PromptRequest? = null
    private val sessionLifetime = Job(runtime.scope.coroutineContext[Job])
    private var turnToken: String? = null

    @Volatile
    private var turnLifetime: Job? = null
    private var isHostedEnabled = false
    private val hostedAdmissions = mutableMapOf<String, Pair<TurnId, String>>()
    private val usedHostedCalls = mutableSetOf<String>()
    private val revokedHostedCalls = mutableSetOf<String>()
    private val hostedLifetimes = mutableMapOf<String, Job>()
    private val submission = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    private var isCancelPending = false
    private var isInterrupting = false
    private val log = Log.tag("KimiSession")

    suspend fun lease(areDetachedToolsEnabled: Boolean): ActiveSession = mutex.withLock {
        runtime.checkActive()
        KimiLease(this, areDetachedToolsEnabled).also { leases += it }
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

    suspend fun send(request: PromptRequest, lease: KimiLease): TurnId = admissions.withLock {
        mutex.withLock {
            checkReadyToSend(lease)
            validate(request)
        }
        // ACP may send session updates before answering configuration RPCs. Its reader must not wait for our lock.
        val content = kimiPromptInputs(request, catalog.inputSupport, runtime.resources)
        applyEffort(request)
        val (turn, submitted) = mutex.withLock {
            checkReadyToSend(lease)
            start(request, content, lease.areDetachedToolsEnabled)
        }
        submitted.await()
        turn.id
    }

    private fun checkReadyToSend(lease: KimiLease) {
        checkLease(lease)
        if (isCancelPending || (currentTurn != null && currentTurn?.outcome == null)) busy()
    }

    private fun start(
        request: PromptRequest,
        content: List<JsonObject>,
        areDetachedToolsEnabled: Boolean,
    ): Pair<Turn, CompletableDeferred<Unit>> {
        val turn = Turn(TurnId(newId("turn")), request.id, target, decisionMode = request.decisionMode)
        currentTurn = turn
        currentRequest = request
        isHostedEnabled = route.workspace != null || areDetachedToolsEnabled
        turnLifetime = Job(sessionLifetime)
        val token = newId("host-turn")
        turnToken = token
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
                val metadata = buildJsonObject { put("heartbeatTurn", token) }
                val result = runtime.connection.prompt(ref.nativeId, content, metadata)
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

    suspend fun hostedContext(call: String, name: String): AgentToolContext? = mutex.withLock {
        val turn = currentTurn?.takeIf { it.outcome == null } ?: return@withLock null
        val admission = hostedAdmissions.remove(call) ?: return@withLock null
        if (admission != (turn.id to name)) return@withLock null
        val prompt = currentRequest ?: return@withLock null
        val lifetime = hostedLifetimes[call]?.takeIf { it.isActive } ?: return@withLock null
        if (!isHostedEnabled || runtime.isClosed) return@withLock null
        AgentToolContext(
            ref, route.workspace, turn.id, prompt.id, prompt.trust ?: TrustLevel.Ask,
            AgentToolPermissions { approval -> requestUserPermission(turn.id, approval) },
            lifetime = lifetime, target = target, decisionMode = prompt.decisionMode,
            reviewTarget = prompt.reviewTarget, userInstruction = prompt.userInstruction,
        )
    }

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val (turn, prompt, lifetime) = activePermission(request) ?: return AcpPermissionOutcome.Cancelled
        val meta = request.toolCall["_meta"] as? JsonObject ?: return AcpPermissionOutcome.Cancelled
        if ((meta["heartbeatQuestion"] as? JsonPrimitive)?.contentOrNull == "true" ||
            (meta["heartbeatPlanReview"] as? JsonPrimitive)?.contentOrNull == "true"
        ) {
            return requestQuestion(turn.id, request)
        }
        // Never persist a CLI allow rule that could bypass later modes.
        val option = request.options.firstOrNull { it.kind == "allow_once" }
            ?: return AcpPermissionOutcome.Cancelled
        val title = request.toolCall["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val prefix = "mcp__${HOSTED_TOOLS_SERVER}__"
        if (title.startsWith(prefix)) return admitHosted(request, turn.id, title.removePrefix(prefix), option.optionId)
        val permissions = AgentToolPermissions { approval -> requestUserPermission(turn.id, approval) }
        val context = AgentToolContext(
            ref,
            route.workspace,
            turn.id,
            prompt.id,
            prompt.trust ?: TrustLevel.Ask,
            permissions,
            callId = request.toolCall["toolCallId"]?.jsonPrimitive?.contentOrNull?.let(::ToolCallId),
            lifetime = lifetime,
            target = target,
            decisionMode = prompt.decisionMode,
            reviewTarget = prompt.reviewTarget,
            userInstruction = prompt.userInstruction,
        )
        val verdict = runtime.tools.authorizeNative(context, kimiNativeCall(request.toolCall, workingDirectory))
        val isActive = mutex.withLock {
            currentTurn?.id == turn.id && currentTurn?.outcome == null && lifetime.isActive
        }
        if (!isActive || verdict is NativeVerdict.Deny) return AcpPermissionOutcome.Cancelled
        return AcpPermissionOutcome.Selected(option.optionId)
    }

    private suspend fun activePermission(request: AcpPermissionRequest): Triple<Turn, PromptRequest, Job>? {
        if (runtime.isClosed) return null
        val meta = request.toolCall["_meta"] as? JsonObject ?: return null
        val origin = (meta["heartbeatTurn"] as? JsonPrimitive)?.contentOrNull ?: return null
        return mutex.withLock {
            val turn = currentTurn?.takeIf { it.outcome == null } ?: return@withLock null
            val lifetime = turnLifetime?.takeIf { it.isActive } ?: return@withLock null
            val prompt = currentRequest ?: return@withLock null
            if (origin != turnToken) return@withLock null
            Triple(turn, prompt, lifetime)
        }
    }

    private suspend fun admitHosted(
        request: AcpPermissionRequest,
        turn: TurnId,
        name: String,
        option: String,
    ): AcpPermissionOutcome {
        val meta = request.toolCall["_meta"] as? JsonObject ?: return AcpPermissionOutcome.Cancelled
        val call = (meta["heartbeatCall"] as? JsonPrimitive)?.contentOrNull ?: return AcpPermissionOutcome.Cancelled
        val origin = (meta["heartbeatTurn"] as? JsonPrimitive)?.contentOrNull ?: return AcpPermissionOutcome.Cancelled
        val isAdmitted = mutex.withLock {
            if (currentTurn?.id != turn || currentTurn?.outcome != null || origin != turnToken) return@withLock false
            if (!isHostedEnabled || turnLifetime?.isActive != true || name !in hosted.names) return@withLock false
            if (call.isBlank() || call in revokedHostedCalls || !usedHostedCalls.add(call)) return@withLock false
            hostedLifetimes[call] = Job(turnLifetime)
            hostedAdmissions[call] = turn to name
            true
        }
        return if (isAdmitted) AcpPermissionOutcome.Selected(option) else AcpPermissionOutcome.Cancelled
    }

    private suspend fun requestQuestion(turn: TurnId, request: AcpPermissionRequest): AcpPermissionOutcome {
        val (id, answer) = mutex.withLock {
            if (currentTurn?.id != turn || currentTurn?.outcome != null || turnLifetime?.isActive != true) {
                return@withLock null
            }
            val options = request.options.map {
                PermissionOption(PermissionOptionId(it.optionId), it.name, it.kind.startsWith("reject"))
            }
            if (options.isEmpty() || options.map { it.id }.distinct().size != options.size) {
                return@withLock null
            }
            val title = request.toolCall["content"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "Question"
            val id = PermissionRequestId(newId("question"))
            val normalized = PermissionRequest(id, turn, title, options)
            val answer = CompletableDeferred<AcpPermissionOutcome>()
            pending[id] = PendingPermission(normalized, answer)
            log.i { "Kimi question requested" }
            refreshState()
            history.publish { SessionEvent.PermissionRequested(it, normalized) }
            id to answer
        } ?: return AcpPermissionOutcome.Cancelled
        try {
            return answer.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(id)
                    refreshState()
                }
            }
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

            "heartbeat_hosted_revoked" -> revokeCall(update)

            "plan" -> updatePlan(update)

            "usage_update" -> runtime.receiveUsage(this, update)
        }
    }

    private fun revokeCall(update: JsonObject) {
        val origin = update["heartbeatTurn"]?.jsonPrimitive?.contentOrNull
        val call = update["heartbeatCall"]?.jsonPrimitive?.contentOrNull
        if (origin == turnToken && origin != null && !call.isNullOrBlank()) {
            revokedHostedCalls.add(call)
            hostedAdmissions.remove(call)
            hostedLifetimes.remove(call)?.cancel()
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
        turnLifetime?.cancel()
        turnLifetime = null
        turnToken = null
        hostedAdmissions.clear()
        usedHostedCalls.clear()
        revokedHostedCalls.clear()
        hostedLifetimes.clear()
        submission.value?.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn.request)),
        )
        log.i { "Kimi turn finished" }
        pending.values.forEach { it.answer.complete(AcpPermissionOutcome.Cancelled) }
        pending.clear()
        setState(ActiveSessionState.Ready(finished))
        history.publish { SessionEvent.TurnFinished(it, finished.id, outcome) }
    }

    fun revokeHosted() {
        sessionLifetime.cancel()
        hosted.close()
        turnLifetime?.cancel()
    }

    suspend fun disconnected() {
        revokeHosted()
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

/** Capability exists for the native session; each invocation resolves and captures its active turn. */
internal class KimiHosted : AutoCloseable {
    val session = MutableStateFlow<KimiSession?>(null)
    var attachment: AgentToolBridgeAttachment? = null
    var names: Set<String> = emptySet()
    var servers: List<JsonObject> = emptyList()
    override fun close() {
        session.value = null
        Log.tag("KimiHosted").i { "Hosted session detached" }
        attachment?.close()
        attachment = null
    }
}

/** Independent observation handle; closing it never cancels the profile-owned native turn. */
internal class KimiLease(private val session: KimiSession, val areDetachedToolsEnabled: Boolean) :
    ActiveSession,
    SendsPrompts,
    CancelsTurns,
    RequestsPermissions,
    AppliesTrustLevels,
    ReconcilesSession {
    override val ref = session.ref
    override val route = session.route
    override val state = MutableStateFlow(session.state.value)
    override val features = KimiFeatures(
        SendsPrompts to this,
        CancelsTurns to this,
        RequestsPermissions to this,
        AppliesTrustLevels to this,
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
