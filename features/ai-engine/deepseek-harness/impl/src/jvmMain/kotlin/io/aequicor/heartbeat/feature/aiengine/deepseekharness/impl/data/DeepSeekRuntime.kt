package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
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
import kotlin.concurrent.Volatile

/** One profile-owned ACP connection. Accepted turns outlive individual session handles. */
internal class DeepSeekRuntime(
    override val identity: RuntimeIdentity,
    internal val connection: AcpConnection,
    internal val scope: CoroutineScope,
    private val directory: suspend (CreateSessionRequest) -> String,
    internal val tools: ProfileAgentTools = NoAgentTools,
    private val ownedScope: Job? = null,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions,
    AcpClientHandler {
    override val features = DeepSeekFeatures(CreatesSessions to this, AttachesSessions to this)
    private val sessions = MutableStateFlow<Map<String, DeepSeekSession>>(emptyMap())
    private val mutex = Mutex()
    private val loading = Mutex()
    private val log = Log.tag("DeepSeekRuntime")

    @Volatile
    private var isShutdown = false

    @Volatile
    private var isDisconnected = false

    override val isClosed: Boolean
        get() = isShutdown || isDisconnected || scope.coroutineContext[Job]?.isActive == false

    override suspend fun create(request: CreateSessionRequest): ActiveSession {
        checkActive()
        val cwd = directory(request)
        val native = connection.newSession(cwd)
        configure(native.sessionId, native.metadata, request.target)
        val created = session(native.sessionId, request.target, request.workspace, cwd)
        return created.lease()
    }

    /** Lists the native models the agent advertises; the adapter never starts a turn. */
    suspend fun discoverCatalog(binding: EngineBindingId): List<DeepSeekNativeModel> {
        val target = EngineTarget(identity.engine, binding, ModelId("catalog"))
        val native = connection.newSession(directory(CreateSessionRequest(target)))
        return deepSeekNativeModels(native.metadata)
    }

    /** Selects the requested model on a freshly created or resumed session; an unsupported model fails the open. */
    private suspend fun configure(sessionId: String, metadata: JsonObject, target: EngineTarget) {
        if (!metadata.hasConfigOption("model")) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
        }
        connection.setConfigOption(sessionId, "model", modelSelector(target.model.value))
    }

    /**
     * Maps a stored model id onto the opaque `["provider","model"]` selector. Ids persisted before selector values
     * became opaque are bare model names and resolve through the account route.
     */
    private fun modelSelector(value: String): String =
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
        val created = session(nativeId, request.target, request.workspace, cwd)
        try {
            // The connection completes an abandoned RPC in its own scope; keep the local bookkeeping aligned.
            val native = withContext(NonCancellable) { connection.resumeSession(nativeId, cwd) }
            withContext(NonCancellable) { configure(nativeId, native.metadata, request.target) }
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
            connection.closeSession(session.ref.nativeId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "DeepSeek Harness session close failed" }
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
    ): DeepSeekSession = mutex.withLock {
        checkActive()
        check(nativeId !in sessions.value) { "DeepSeek Harness session identity already registered" }
        val ref = SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, nativeId)
        val route = ExecutionRoute(DeepSeekEngine.Id, target.binding, identity.source, identity.revision, workspace)
        log.i { "DeepSeek Harness session registered" }
        val created = DeepSeekSession(ref, route, target, this, workingDirectory)
        sessions.value = sessions.value + (nativeId to created)
        created
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
        if (isShutdown) return
        isShutdown = true
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
                    }
                }
            }
        }
    }

    internal fun connectionClosed() {
        isDisconnected = true
        sessions.value.values.forEach { it.revoke() }
    }

    internal fun checkActive() {
        scope.coroutineContext.ensureActive()
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 30_000L
        private const val ACCOUNT_ROUTE = "deepseek-account"

        private val log = Log.tag("DeepSeekRuntime")

        private suspend fun initializeConnection(connection: AcpConnection) {
            try {
                withTimeout(INITIALIZE_TIMEOUT_MS) {
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
                initializeConnection(connection)
                return DeepSeekRuntime(
                    identity,
                    connection,
                    runtimeScope,
                    directory,
                    tools,
                    runtimeScope.coroutineContext[Job],
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
