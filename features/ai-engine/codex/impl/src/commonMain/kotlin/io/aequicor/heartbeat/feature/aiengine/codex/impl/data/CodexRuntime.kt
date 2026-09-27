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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class CodexRuntime(
    override val identity: RuntimeIdentity,
    private val rpc: CodexRpc,
    val host: CodexRuntimeEnvironment,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val config get() = host.config
    private val toggles get() = host.toggles
    val dispatchers get() = host.dispatchers
    val profile get() = host.profile
    private val log = Log.tag("CodexRuntime")
    override val features: EngineFeatures = CodexFeatures(this, blocked = {
        if (isClosed) EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed) else null
    })
    private val sessions = mutableMapOf<String, CodexSession>()
    private val early = mutableListOf<JsonObject>()
    private var isOpening = false
    private val commands = Mutex()
    private var account: List<String?>? = null
    var isClosed = false
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

    suspend fun checkAccount() {
        ensureOpen()
        val current = rpc.request(
            "account/read",
            json("refreshToken" to JsonPrimitive(false)),
        )["account"] as? JsonObject
        if (current == null) {
            fail(
                EngineFailure.Authentication(AuthFailure(AuthFailureReason.NotAuthenticated, identity.source)),
            )
        }
        if (current.text(
                "type",
            ) != "chatgpt"
        ) {
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

    suspend fun gate() {
        ensureOpen()
        if (!toggles.get(CodexEngine.Enabled)) fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        checkAccount()
    }

    suspend fun models(binding: EngineBindingId): List<ModelInfo> = withContext(dispatchers.main) {
        gate()
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
    )

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        if (ref.engine != identity.engine || ref.source != config.historySource) {
            fail(
                EngineFailure.Session(SessionFailureReason.NotFound),
            )
        }
        return open(ref.nativeId, request.target, request.workspace)
    }

    private suspend fun open(nativeId: String?, target: EngineTarget, workspace: WorkspaceRef?): ActiveSession =
        withContext(dispatchers.main) {
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
                    existing.lease().also { existing.recheck() }
                } else {
                    isOpening = true
                    try {
                        openNative(nativeId, target, route)
                    } finally {
                        isOpening = false
                        withContext(NonCancellable) { dropEarly() }
                    }
                }
            }
        }

    private suspend fun openNative(nativeId: String?, target: EngineTarget, route: ExecutionRoute): ActiveSession {
        val params = threadParams(nativeId, target, route.workspace)
        val response = rpc.request(if (nativeId == null) "thread/start" else "thread/resume", params)
        val thread = response.obj("thread")
        val id = thread.text("id") ?: protocolFailure()
        if (nativeId != null && nativeId != id) protocolFailure()
        if (thread.text("modelProvider")?.let { it != "openai" } == true) protocolFailure()
        val turns = (thread["turns"] as? JsonArray).orEmpty()
        validateIdle(thread, turns)
        val session = CodexSession(
            SessionRef(identity.engine, config.historySource, id),
            route,
            target,
            this,
            rpc,
        )
        try {
            session.load(turns)
        } catch (e: EngineException) {
            session.shutdown(e.failure)
            throw e
        }
        sessions[id] = session
        val queued = early.filter { it.obj("params").text("threadId") == id }
        early.removeAll(queued.toSet())
        queued.forEach { session.event(it) }
        log.i { "Codex session attached" }
        return session.lease()
    }

    private fun validateIdle(thread: JsonObject, turns: List<JsonElement>) {
        val isActive = (thread["status"] as? JsonObject)?.text("type") == "active"
        if (isActive || turns.any { (it as? JsonObject)?.text("status") == "inProgress" }) {
            fail(EngineFailure.Session(SessionFailureReason.Busy))
        }
    }

    private fun threadParams(nativeId: String?, target: EngineTarget, workspace: WorkspaceRef?): JsonObject {
        val path = workspace?.let {
            config.workspaces[it] ?: fail(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        return buildJsonObject {
            put("model", target.model.value)
            put("modelProvider", "openai")
            put("approvalPolicy", APPROVAL_POLICY)
            put("sandbox", SANDBOX_MODE)
            if (path != null) put("cwd", path)
            if (nativeId != null) put("threadId", nativeId)
        }
    }

    private suspend fun event(message: JsonObject) {
        val params = message["params"] as? JsonObject
        if (params == null) {
            message["id"]?.let { rpc.reject(it) }
            return
        }
        if (message.text("method") == "account/updated") {
            // The next operation revalidates account/read; an active turn remains observable.
            log.i { "Codex account observation changed" }
        }
        val id = params.text("threadId")
        val session = sessions[id]
        if (session != null) {
            session.event(message)
        } else if (id != null && isOpening) {
            if (early.size >= EARLY_LIMIT) protocolFailure()
            early += message
        } else if (message["id"] != null) {
            rpc.reject(checkNotNull(message["id"]))
        }
    }

    /** Events of threads nobody is opening are never replayed; unanswered server requests would block Codex. */
    private suspend fun dropEarly() {
        val dropped = early.toList()
        early.clear()
        if (dropped.isNotEmpty()) log.w { "Codex dropped ${dropped.size} events of unopened threads" }
        dropped.mapNotNull { it["id"] }.forEach { rpc.reject(it) }
    }

    private fun shutdown(failure: EngineFailure) {
        isClosed = true
        sessions.values.forEach { it.shutdown(failure) }
        sessions.clear()
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

    private companion object {
        const val EARLY_LIMIT = 512

        // app-server v2 wire spellings (AskForApproval, SandboxMode), not the Rust variant names.
        const val APPROVAL_POLICY = "untrusted"
        const val SANDBOX_MODE = "read-only"
    }
}
