package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile

internal class CodexRuntime(
    override val identity: RuntimeIdentity,
    private val rpc: CodexRpc,
    val host: CodexRuntimeEnvironment,
    /**
     * Search availability captured at app-server creation. A later toggle change never restarts it;
     * turning the toggle off stops search declarations and calls. Hosted tools always use the experimental API.
     */
    val isSearchToolsEnabled: Boolean,
    private val launch: PreparedCodexLaunch,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val config get() = host.config
    internal val historySource get() = config.historySource
    private val toggles get() = host.toggles
    val dispatchers get() = host.dispatchers
    val profile get() = host.profile
    private val log = Log.tag("CodexRuntime")
    private val providerUsage = CodexProviderUsage(dispatchers.main) {
        if (usageEnabled()) {
            gate()
            val accountEpoch = usageAccountEpoch
            val snapshot = rpc.request("account/rateLimits/read", JsonObject(emptyMap()))
            checkAccount()
            if (accountEpoch == usageAccountEpoch) {
                validateUsageAccount(snapshot)
                isUsageAccountTrusted = true
                snapshot
            } else {
                log.d { "Codex quota response discarded after account observation changed" }
                null
            }
        } else {
            isUsageAccountTrusted = false
            null
        }
    }
    override val features: EngineFeatures = CodexFeatures(this, providerUsage, CodexSessionTrees(this, rpc), blocked = {
        if (isClosed) EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed) else null
    })

    /** Broadcast invalidations, never a second consumer of the RPC request/event channel. */
    internal val treeChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val threads = CodexThreadSetup(this)
    private val sessions = mutableMapOf<String, CodexSession>()
    private val connections = mutableSetOf<CodexConnection>()
    private val commands = Mutex()
    private var account: List<String?>? = null
    private var isUsageAccountTrusted = false
    private var usageAccountEpoch = 0L
    private var usageAccountId: String? = null

    @Volatile
    override var isClosed = false
        private set
    private var cleanup: DisposableHandle? = null
    init {
        cleanup = profile.onClose { shutdown(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)) }
    }
    private val observer = profile.coroutineScope.launch {
        try {
            rpc.notifications.collect { event -> event(event) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex observation interrupted" }
            shutdown(e.failure)
        }
    }

    private val usageObserver = profile.coroutineScope.launch {
        toggles.observe(EngineUsageEnabled).collect { enabled ->
            if (!enabled) {
                isUsageAccountTrusted = false
                providerUsage.clear()
                sessions.values.forEach { it.contextUsage.clear() }
            }
        }
    }

    suspend fun usageEnabled(): Boolean = toggles.get(EngineUsageEnabled)
    suspend fun questionsEnabled(): Boolean = toggles.get(QuestionnaireEnabled)

    suspend fun checkAccount(connection: CodexRpc = rpc) {
        ensureOpen()
        val current = connection.request(
            "account/read",
            json("refreshToken" to JsonPrimitive(false)),
        )["account"] as? JsonObject
        if (current == null) {
            isUsageAccountTrusted = false
            fail(
                EngineFailure.Authentication(AuthFailure(AuthFailureReason.NotAuthenticated, identity.source)),
            )
        }
        if (current.text(
                "type",
            ) != "chatgpt"
        ) {
            isUsageAccountTrusted = false
            fail(
                EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch, identity.source)),
            )
        }
        // Only identifying fields: plan and other volatile attributes must not retire the runtime.
        val login = listOf(current.text("type"), current.text("email"))
        val previous = account
        if (previous != null && previous != login) {
            val failure = EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged, identity.source))
            log.i { "Codex login changed; retiring runtime" }
            // A retired runtime lets the factory start a fresh one bound to the new login.
            shutdown(failure)
            fail(failure)
        }
        account = login
    }

    private fun validateUsageAccount(snapshot: JsonObject) {
        val id = snapshot.text("accountId") ?: return
        val previous = usageAccountId
        if (previous != null && previous != id) {
            val failure = EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged, identity.source))
            shutdown(failure)
            fail(failure)
        }
        usageAccountId = id
    }

    suspend fun gate() {
        ensureOpen()
        if (!toggles.get(CodexEngine.Enabled)) {
            isUsageAccountTrusted = false
            fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        checkAccount()
    }

    private val inputSupports = mutableMapOf<ModelId, PromptInputSupport>()
    fun inputSupport(model: ModelId): PromptInputSupport = inputSupports[model] ?: PromptInputSupport.TextDocuments

    suspend fun models(binding: EngineBindingId): List<ModelInfo> = withContext(dispatchers.main) {
        gate()
        inputSupports.clear()
        val result = mutableListOf<ModelInfo>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response = rpc.request("model/list", json("cursor" to (cursor?.json() ?: JsonNull)))
            result += response.array("data").map { item ->
                val model = item as? JsonObject ?: protocolFailure()
                ModelInfo(
                    EngineTarget(identity.engine, binding, ModelId(model.text("model") ?: protocolFailure())),
                    model.text("displayName").orEmpty(),
                    reasoningEfforts = model.reasoningEfforts(),
                    defaultReasoningEffort = model.text("defaultReasoningEffort"),
                    inputSupport = codexInputSupport(model).also {
                        inputSupports[ModelId(model.text("model") ?: protocolFailure())] = it
                    },
                )
            }
            cursor = response.text("nextCursor")
            if (cursor != null && !cursors.add(cursor)) protocolFailure()
        } while (cursor != null)
        result.distinctBy { it.target.model }
    }

    override suspend fun create(request: CreateSessionRequest): ActiveSession = open(
        null,
        request.target,
        request.workspace,
        request.areDetachedToolsEnabled,
    )

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        if (ref.engine != identity.engine || ref.source != config.historySource) {
            fail(
                EngineFailure.Session(SessionFailureReason.NotFound),
            )
        }
        return open(ref.nativeId, request.target, request.workspace, request.areDetachedToolsEnabled)
    }

    /**
     * Hosted tools are fixed when a thread starts: a thread without a project declares detached tools only when the
     * creating request enabled [areDetachedToolsEnabled]. A thread already open in this runtime is reused with its
     * choice; a resume after a restart never adds or removes declarations, and without the resuming caller's opt-in
     * a chat's declared tools are refused when called.
     */
    private suspend fun open(
        nativeId: String?,
        target: EngineTarget,
        workspace: WorkspaceRef?,
        areDetachedToolsEnabled: Boolean,
    ): ActiveSession = withContext(dispatchers.main) {
        commands.withLock {
            gate()
            if (target.engine != identity.engine) {
                fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
            }
            val route =
                ExecutionRoute(identity.engine, target.binding, identity.source, identity.revision, workspace)
            val existing = sessions[nativeId]
            if (existing != null) {
                if (existing.route != route || existing.target != target) {
                    fail(EngineFailure.Session(SessionFailureReason.Changed))
                }
                existing.refreshHistory()
                ensureOpen()
                existing.lease().also { existing.recheck() }
            } else {
                openNative(nativeId, target, route, areDetachedToolsEnabled)
            }
        }
    }

    private suspend fun openNative(
        nativeId: String?,
        target: EngineTarget,
        route: ExecutionRoute,
        areDetachedToolsEnabled: Boolean,
    ): ActiveSession {
        val request = threads.request(nativeId, target, route, areDetachedToolsEnabled)
        val connection = openConnection(request.off)
        var attached: CodexSession? = null
        var isTransferred = false
        try {
            val session = threads.attach(request, connection)
            attached = session
            ensureOpen()
            sessions[session.ref.nativeId] = session
            connection.bind(session)
            val lease = session.lease()
            isTransferred = true
            log.i { "Codex session attached" }
            return lease
        } finally {
            if (!isTransferred) {
                attached?.shutdown(EngineFailure.Session(SessionFailureReason.NotResumable))
                connections.remove(connection)
                connection.close()
            }
        }
    }

    /** Shared metadata observations never route execution frames into a session. */
    private suspend fun event(message: JsonObject) {
        observeMetadata(message)
        message["id"]?.let { rpc.reject(it) }
    }

    private suspend fun observeMetadata(message: JsonObject) {
        if (message.isTreeChange()) treeChanges.tryEmit(Unit)
        val params = message["params"] as? JsonObject ?: return
        if (message.text("method") == "account/rateLimits/updated" && isUsageAccountTrusted && usageEnabled()) {
            providerUsage.receive(params)
        }
        if (message.text("method") == "account/updated") {
            providerUsage.clear()
            isUsageAccountTrusted = false
            usageAccountEpoch++
            log.i { "Codex account observation changed" }
        }
    }

    /** A separate process prevents a policy reload from interrupting another session's active turn. */
    suspend fun openConnection(off: CodexNativeOff): CodexConnection {
        ensureOpen()
        val peer = CodexRpc(launch.open(off), profile.coroutineScope)
        val connection = CodexConnection(peer, profile.coroutineScope, ::observeMetadata, ::connectionFailed)
        connections += connection
        var isTransferred = false
        try {
            ensureOpen()
            peer.initialize(experimentalApi = true)
            if (peer.home != rpc.home) {
                fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
            }
            checkAccount(peer)
            ensureOpen()
            isTransferred = true
            return connection
        } finally {
            if (!isTransferred) {
                connections.remove(connection)
                connection.close()
            }
        }
    }

    /** Last-handle release keeps active native work alive, then retires only that idle execution process. */
    fun release(session: CodexSession) {
        if (isClosed || !session.isUnused) return
        profile.coroutineScope.launch { commands.withLock { retireUnused(session) } }
    }

    private suspend fun retireUnused(session: CodexSession) = session.connectionMutex.withLock {
        retireLocked(session)
    }

    private suspend fun retireLocked(session: CodexSession) {
        if (sessions[session.ref.nativeId] !== session || !session.isUnused) return
        try {
            materialize(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Empty Codex thread could not be persisted before closing its process" }
        } finally {
            if (sessions[session.ref.nativeId] === session && session.isUnused) {
                sessions.remove(session.ref.nativeId)
                connections.remove(session.connection)
                session.shutdown(EngineFailure.Session(SessionFailureReason.NotResumable))
                log.i { "Unused Codex execution process closed" }
            }
        }
    }

    /** Empty legacy threads need a persisted name before cold resume can find their unchanged id. */
    suspend fun materialize(session: CodexSession) {
        if (!session.isMaterializationRequired) return
        val peer = session.connection.rpc
        val thread = peer.request(
            "thread/read",
            json("threadId" to session.ref.nativeId.json(), "includeTurns" to JsonPrimitive(false)),
        ).obj("thread")
        if (thread.text("id") != session.ref.nativeId) protocolFailure()
        peer.request(
            "thread/name/set",
            json("threadId" to session.ref.nativeId.json(), "name" to (thread.text("name") ?: "Heartbeat").json()),
        )
        session.isMaterialized = true
    }

    fun discard(connection: CodexConnection) {
        connections.remove(connection)
        connection.close()
    }

    private fun connectionFailed(connection: CodexConnection, failure: EngineFailure) {
        val affected = sessions.values.filter { it.connection === connection }
        affected.forEach { session ->
            sessions.remove(session.ref.nativeId)
            session.shutdown(failure)
        }
        connections.remove(connection)
        connection.close()
        treeChanges.tryEmit(Unit)
        log.v { "Codex readers invalidated by execution failure" }
    }

    private fun shutdown(failure: EngineFailure) {
        isClosed = true
        // Wake idle tree/history readers so they observe closure and release the retired runtime.
        treeChanges.tryEmit(Unit)
        log.v { "Codex readers invalidated by runtime shutdown" }
        isUsageAccountTrusted = false
        providerUsage.clear()
        usageObserver.cancel()
        sessions.values.forEach { it.shutdown(failure) }
        sessions.clear()
        connections.toList().forEach { it.close() }
        connections.clear()
        rpc.close()
        cleanup?.dispose()
        cleanup = null
    }

    fun ensureOpen() {
        if (isClosed || profile.isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    override suspend fun close() = withContext(dispatchers.main) {
        if (!isClosed) {
            shutdown(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
            observer.cancel()
            log.i { "Codex runtime closed" }
        }
    }
}

/** Only model/list inputModalities confirms images; missing metadata is conservatively text-only. */
internal fun codexInputSupport(model: JsonObject): PromptInputSupport = PromptInputSupport.TextDocuments.copy(
    imageMediaTypes = if ((model["inputModalities"] as? JsonArray).orEmpty().any {
            (it as? JsonPrimitive)?.contentOrNull == "image"
        }
    ) {
        setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    } else {
        emptySet()
    },
)
