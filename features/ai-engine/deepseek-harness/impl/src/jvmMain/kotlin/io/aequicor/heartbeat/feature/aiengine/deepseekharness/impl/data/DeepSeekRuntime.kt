package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

/** One profile-owned ACP connection. Accepted turns outlive individual session handles. */
internal class DeepSeekRuntime(
    override val identity: RuntimeIdentity,
    internal val connection: AcpConnection,
    internal val scope: CoroutineScope,
    private val directory: suspend (CreateSessionRequest) -> String,
    internal val tools: ProfileAgentTools = NoAgentTools,
    internal val resources: ResourceResolver = ResourceResolver { null },
    private val capabilities: JsonObject = JsonObject(emptyMap()),
    private val ownedScope: Job? = null,
    private val observedRuntimeVersion: String? = null,
    private val observedProtocol: EngineProtocolIdentity? = null,
    override val selectedExecutable: String? = null,
    override val compatibilityStamp: EngineCompatibilityStamp? = null,
    sessionTrees: SessionTrees? = null,
    private val bridge: AgentToolBridge = UnavailableAgentToolBridge,
    internal val isSingleSession: Boolean = false,
    private val onRetired: suspend (DeepSeekRuntime) -> Unit = {},
) : EngineRuntime,
    DshAdmittedRuntime,
    CreatesSessions,
    AttachesSessions,
    AcpClientHandler {
    /** Input support from the ACP `initialize` result; only the configured route's exact selector is confirmed. */
    internal val inputSupport: PromptInputSupport = deepSeekPromptInputSupport(capabilities)

    /** Support confirmed for [model]; DSH validates the exact route before accepting an image. */
    internal fun inputSupportFor(model: ModelId): PromptInputSupport =
        if (dshSelectedModel(dshModelSelector(model.value)) in DeepSeekImageModelIds) {
            inputSupport
        } else {
            PromptInputSupport()
        }

    /** Reports what this process's own ACP `initialize` established; IO-free, so promotion can compare it. */
    override suspend fun observeOrdinaryStartup(): DshProcessObservation? =
        DshProcessObservation(observedRuntimeVersion, observedProtocol, selectedExecutable)

    /** The read-only tree capability is optional: a runtime created without a store does not expose it. */
    override val features = sessionTrees?.let { trees ->
        DeepSeekFeatures(CreatesSessions to this, AttachesSessions to this, SessionTrees to trees)
    } ?: DeepSeekFeatures(CreatesSessions to this, AttachesSessions to this)

    private val sessions = MutableStateFlow<Map<String, DeepSeekSession>>(emptyMap())
    private val mutex = Mutex()
    private val loading = Mutex()
    private val log = Log.tag("DeepSeekRuntime")
    private val controls = DeepSeekControls(scope) { close() }

    private val isShutdown = AtomicBoolean(false)
    private val retirement = CompletableDeferred<Unit>()

    @Volatile
    private var isDisconnected = false

    override val isClosed: Boolean
        get() = isShutdown.get() || isDisconnected || scope.coroutineContext[Job]?.isActive == false

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
        var nativeId: String? = null
        var created: DeepSeekSession? = null
        try {
            val native = controls.run("create session", onAbandoned = { closeAbandoned(it.sessionId) }) {
                val opened = connection.newSession(cwd, hosted.servers)
                try {
                    configure(opened.sessionId, opened.metadata, request.target)
                    opened
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    closeAbandoned(opened.sessionId)
                    throw error
                }
            }
            nativeId = native.sessionId
            val registered = session(native.sessionId, request.target, request.workspace, cwd, hosted)
            created = registered
            return registered.lease().also { isTransferred = true }
        } finally {
            if (!isTransferred) {
                hosted.close()
                withContext(NonCancellable) {
                    created?.let { unregister(it) }
                    nativeId?.let { closeAbandoned(it) }
                }
            }
        }
    }

    /** Lists the native models and prompt input support the agent advertises; the adapter never starts a turn. */
    suspend fun discoverCatalog(binding: EngineBindingId): DeepSeekModelCatalog {
        val target = EngineTarget(identity.engine, binding, ModelId("catalog"))
        val cwd = directory(CreateSessionRequest(target))
        val native = controls.run("discover catalog") { connection.newSession(cwd) }
        return deepSeekNativeCatalog(native.metadata, capabilities, DeepSeekImageModelIds)
    }

    /** Selects the requested model on a freshly created or resumed session; an unsupported model fails the open. */
    private suspend fun configure(sessionId: String, metadata: JsonObject, target: EngineTarget) {
        if (!metadata.hasConfigOption("model")) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
        }
        connection.setConfigOption(sessionId, "model", dshModelSelector(target.model.value))
    }

    /** Maps a stored model id onto the opaque `["provider","model"]` selector. Ids persisted before selector values
     * became opaque are bare model names and resolve through the account route. */
    private fun dshModelSelector(value: String): String =
        if (value.startsWith("[")) value else "[\"$ACCOUNT_ROUTE\",\"$value\"]"

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        if (ref.engine != DeepSeekEngine.Id || ref.source != DeepSeekEngine.SessionSource) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        return resume(request, ref.nativeId)
    }

    /** Resumes a persisted DSH session; ACP replay is deliberately not loaded, so history is partial. */
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
        val cwd = directory(CreateSessionRequest(request.target, request.workspace))
        // Register before the RPC: updates and permission requests arriving during resume reach the session.
        val ref = SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, nativeId)
        val hosted = hosted(request.target, request.workspace, ref, request.areDetachedToolsEnabled, emptyMap())
        val created = try {
            session(nativeId, request.target, request.workspace, cwd, hosted)
        } catch (error: CancellationException) {
            hosted.close()
            throw error
        } catch (error: Exception) {
            hosted.close()
            throw error
        }
        try {
            // The connection completes an abandoned RPC in its own scope; keep the local bookkeeping aligned.
            withContext(NonCancellable) {
                controls.run("resume session") {
                    val native = connection.resumeSession(nativeId, cwd, hosted.servers)
                    configure(nativeId, native.metadata, request.target)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { unregister(created) }
            throw error
        }
        created.lease()
    }

    /** Retires an idle session: unregisters it, cancels its lifetime and closes the native session. */
    internal suspend fun releaseSession(session: DeepSeekSession) {
        val isRemoved = mutex.withLock {
            if (sessions.value[session.ref.nativeId] !== session) return@withLock false
            sessions.value = sessions.value - session.ref.nativeId
            true
        }
        if (!isRemoved) return
        log.i { "DeepSeek Harness session released" }
        session.revoke()
        try {
            controls.run("close session") { connection.closeSession(session.ref.nativeId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "DeepSeek Harness session close failed" }
        } finally {
            if (isSingleSession) withContext(NonCancellable) { close() }
        }
    }

    private suspend fun unregister(session: DeepSeekSession) {
        log.i { "DeepSeek Harness session unregistered" }
        mutex.withLock {
            if (sessions.value[session.ref.nativeId] === session) {
                sessions.value = sessions.value - session.ref.nativeId
            }
        }
        session.revoke()
    }

    @Suppress("LongParameterList")
    private suspend fun session(
        nativeId: String,
        target: EngineTarget,
        workspace: WorkspaceRef?,
        workingDirectory: String,
        hosted: DeepSeekHosted,
    ): DeepSeekSession = mutex.withLock {
        checkActive()
        check(nativeId !in sessions.value) { "DeepSeek Harness session identity already registered" }
        val ref = SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, nativeId)
        val route = ExecutionRoute(DeepSeekEngine.Id, target.binding, identity.source, identity.revision, workspace)
        log.i { "DeepSeek Harness session registered" }
        val created = DeepSeekSession(ref, route, target, this, workingDirectory, hosted)
        hosted.session = created
        sessions.value = sessions.value + (nativeId to created)
        created
    }

    private suspend fun hosted(
        target: EngineTarget,
        workspace: WorkspaceRef?,
        ref: SessionRef?,
        enabled: Boolean,
        preselected: Map<String, Set<String>>,
    ): DeepSeekHosted {
        if (workspace == null && !enabled) return DeepSeekHosted()
        val toolScope = AgentToolScope(workspace, target, session = ref, preselected = preselected)
        val names = tools.specifications(toolScope).associate { dshHostedPublicName(it.name) to it.name }
        if (names.isEmpty()) return DeepSeekHosted()
        check(bridge.isAvailable) { "DeepSeek hosted tools bridge is unavailable" }
        val holder = AtomicReference<DeepSeekHosted?>(null)
        val attachment = bridge.attachCorrelated(toolScope.copy(declared = names.values.toSet())) { call, name ->
            holder.get()?.session?.hostedContext(call, name)
        }
        return DeepSeekHosted(names, attachment).also { holder.set(it) }
    }

    @HighFrequency
    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
        mutex.withLock { sessions.value[update.sessionId] }?.update(update.update)
    }

    override fun onPromptSubmitted(sessionId: String) {
        sessions.value[sessionId]?.promptSubmitted()
    }

    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome {
        val session = mutex.withLock { sessions.value[request.sessionId] } ?: return AcpPermissionOutcome.Cancelled
        return session.permission(request)
    }

    override suspend fun close() {
        if (!isShutdown.compareAndSet(false, true)) return
        sessions.value.values.forEach { it.revoke() }
        withContext(NonCancellable) {
            try {
                val detached = mutex.withLock {
                    log.i { "DeepSeek Harness runtime closed" }
                    val previous = sessions.value.values.toList()
                    sessions.value = emptyMap()
                    previous
                }
                detached.forEach { it.disconnected() }
            } finally {
                withContext(NonCancellable) {
                    try {
                        connection.close()
                    } finally {
                        ownedScope?.cancel()
                        try {
                            onRetired(this@DeepSeekRuntime)
                        } finally {
                            retirement.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    internal suspend fun awaitRetired() = retirement.await()

    /** Claims an unresponsive connection before releasing the exact-turn lock, preventing new admissions. */
    internal fun markUnresponsive() {
        isDisconnected = true
    }

    internal fun connectionClosed() {
        isDisconnected = true
        sessions.value.values.forEach { it.revoke() }
    }

    /** A cancelled opening waiter must not leak a session that the independently owned RPC opens later. */
    private suspend fun closeAbandoned(nativeId: String) {
        if (isClosed) return
        try {
            controls.run("close abandoned session") { connection.closeSession(nativeId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(EngineException(dshEngineFailure(error, null))) {
                "DeepSeek Harness abandoned session close failed"
            }
        }
    }

    internal fun checkActive() {
        scope.coroutineContext.ensureActive()
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 30_000L
        private const val ACCOUNT_ROUTE = "deepseek-account"

        private val log = Log.tag("DeepSeekRuntime")

        private suspend fun initializeConnection(connection: AcpConnection): AcpInitialization {
            try {
                return withTimeout(INITIALIZE_TIMEOUT_MS) {
                    connection.initialize(AcpImplementation("heartbeat", "1", "Heartbeat"))
                }
            } catch (error: TimeoutCancellationException) {
                log.w(error) { "DeepSeek Harness ACP initialize timed out" }
                throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
            }
        }

        @Suppress("LongMethod")
        suspend fun open(
            identity: RuntimeIdentity,
            transport: AcpTransport,
            clients: AcpClientFactory,
            scope: CoroutineScope,
            directory: suspend (CreateSessionRequest) -> String,
            tools: ProfileAgentTools = NoAgentTools,
            resources: ResourceResolver = ResourceResolver { null },
            sessionTrees: SessionTrees? = null,
            bridge: AgentToolBridge = UnavailableAgentToolBridge,
            isSingleSession: Boolean = false,
            onRetired: suspend (DeepSeekRuntime) -> Unit = {},
            compatibilityStamp: EngineCompatibilityStamp? = null,
            selectedExecutable: String? = null,
        ): DeepSeekRuntime {
            val runtime = MutableStateFlow<DeepSeekRuntime?>(null)
            val disconnected = MutableStateFlow(false)
            val runtimeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val connection = try {
                clients.connect(
                    transport,
                    runtimeScope,
                    object : AcpClientHandler {
                        override fun onDisconnected() {
                            disconnected.value = true
                            runtime.value?.let { created ->
                                created.connectionClosed()
                                scope.launch { created.close() }
                            }
                        }

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
            } catch (error: CancellationException) {
                runtimeScope.coroutineContext[Job]?.cancel()
                transport.close()
                throw error
            } catch (error: Exception) {
                runtimeScope.coroutineContext[Job]?.cancel()
                transport.close()
                throw error
            }
            var isTransferred = false
            try {
                val initialization = initializeConnection(connection)
                return DeepSeekRuntime(
                    identity = identity,
                    connection = connection,
                    scope = runtimeScope,
                    directory = directory,
                    tools = tools,
                    resources = resources,
                    capabilities = initialization.agentCapabilities,
                    ownedScope = runtimeScope.coroutineContext[Job],
                    observedRuntimeVersion = initialization.agentInfo?.version,
                    observedProtocol = initialization.protocolVersion.takeIf { it > 0 }?.let {
                        EngineProtocolIdentity.Negotiated("acp", it)
                    },
                    selectedExecutable = selectedExecutable,
                    compatibilityStamp = compatibilityStamp,
                    sessionTrees = sessionTrees,
                    bridge = bridge,
                    isSingleSession = isSingleSession,
                    onRetired = onRetired,
                ).also { created ->
                    runtime.value = created
                    if (disconnected.value) {
                        created.connectionClosed()
                        scope.launch { created.close() }
                    }
                    isTransferred = true
                }
            } finally {
                if (!isTransferred) {
                    withContext(NonCancellable) {
                        try {
                            connection.close()
                        } finally {
                            runtimeScope.coroutineContext[Job]?.cancel()
                        }
                    }
                }
            }
        }
    }
}

/** Deadlines for control/recovery only; native commands and prompts retain their own execution policy. */
internal const val DSH_CONTROL_TIMEOUT_MS = 30_000L
internal const val DSH_CANCEL_TIMEOUT_MS = 10_000L
