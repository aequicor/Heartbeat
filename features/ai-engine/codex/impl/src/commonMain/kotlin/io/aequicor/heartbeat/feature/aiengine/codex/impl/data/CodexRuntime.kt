package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

internal class CodexRuntime(
    override val identity: RuntimeIdentity,
    private val rpc: CodexRpc,
    val host: CodexRuntimeEnvironment,
    /**
     * Search availability captured at app-server creation. A later toggle change never restarts it;
     * turning the toggle off stops search declarations and calls. Hosted tools always use the experimental API.
     */
    val isSearchToolsEnabled: Boolean = false,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val config get() = host.config
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
    override val features: EngineFeatures = CodexFeatures(this, providerUsage, blocked = {
        if (isClosed) EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed) else null
    })
    private val sessions = mutableMapOf<String, CodexSession>()
    private val early = mutableListOf<JsonObject>()
    private var isOpening = false
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

    suspend fun checkAccount() {
        ensureOpen()
        val current = rpc.request(
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
                isOpening = true
                try {
                    openNative(nativeId, target, route, areDetachedToolsEnabled)
                } finally {
                    isOpening = false
                    withContext(NonCancellable) { dropEarly() }
                }
            }
        }
    }

    private suspend fun openNative(
        nativeId: String?,
        target: EngineTarget,
        route: ExecutionRoute,
        areDetachedToolsEnabled: Boolean,
    ): ActiveSession {
        val areToolsEnabled = isSearchToolsEnabled && toggles.get(SearchEngineTools)
        val hosted = if (route.workspace == null && areDetachedToolsEnabled) {
            detachedOpening(nativeId, target)
        } else {
            hostedOpening(nativeId, target, route.workspace, isEligible = route.workspace != null)
        }
        val params = threadParams(nativeId, target, route.workspace, areToolsEnabled, hosted.parameters)
        val response = rpc.request(if (nativeId == null) "thread/start" else "thread/resume", params)
        val thread = validateNativeThread(nativeId, response)
        val id = checkNotNull(thread.text("id"))
        val turns = thread["turns"]?.let { it as? JsonArray ?: protocolFailure() }
        if (nativeId == null && hosted.manifest != null) host.manifests.save(id, hosted.manifest)
        val session = CodexSession(
            SessionRef(identity.engine, config.historySource, id),
            route,
            target,
            this,
            rpc,
            hosted.isServed,
        )
        try {
            val isUnpaged = listOf("turnsBackwardsCursor", "itemsBackwardsCursor").all { field ->
                val cursor = response[field]
                cursor == null || cursor == JsonNull
            }
            session.load(
                turns,
                isNew = nativeId == null,
                isCanonical = thread.text("historyMode") == "paginated" && isUnpaged,
            )
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

    /** Hosted tools of a thread being opened; a thread [isEligible] for them fails to open when they fail. */
    private suspend fun hostedOpening(
        nativeId: String?,
        target: EngineTarget,
        workspace: WorkspaceRef?,
        isEligible: Boolean,
    ): HostedOpening {
        val manifest = if (isEligible) hostedManifest(workspace) else null
        val hosted = validateHostedResume(nativeId, workspace, manifest)
        val parameters = hosted.takeIf { isEligible }?.let { hostedParameters(workspace, target, it) }
        return HostedOpening(manifest, parameters, isServed = isEligible && hosted != null)
    }

    /**
     * Detached hosted tools are optional: a failing contribution opens the chat thread as if its caller had not
     * opted in. Engine failures, such as a stored manifest incompatible with a resume, still fail the open.
     */
    private suspend fun detachedOpening(nativeId: String?, target: EngineTarget): HostedOpening = try {
        hostedOpening(nativeId, target, workspace = null, isEligible = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        throw e
    } catch (e: Exception) {
        // Contribution failures may quote instructions or arguments; only the type is logged.
        log.w(IllegalStateException("Detached hosted tools failed (${e::class.simpleName.orEmpty()})")) {
            "Detached hosted tools unavailable; the chat thread opens without them"
        }
        hostedOpening(nativeId, target, workspace = null, isEligible = false)
    }

    /**
     * Hosted declarations of the thread: [HostedThread.New] for a new thread, the stored tool names on resume, or
     * null for a thread without hosted tools. A resumed thread keeps the tools it was created with: a tool whose
     * declaration changed fails the resume, while added tools stay invisible to it and removed ones are refused
     * when called.
     */
    private suspend fun validateHostedResume(
        nativeId: String?,
        workspace: WorkspaceRef?,
        expected: String?,
    ): HostedThread? {
        if (nativeId == null) return HostedThread.New
        val stored = host.manifests.get(nativeId)
        val isRequired = stored != null || host.manifests.isRequired(nativeId)
        if (isRequired && (stored == null || !isManifestCompatible(stored, workspace, expected))) {
            fail(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        return stored?.let { HostedThread.Resumed(manifestTools(it).keys) }
    }

    private fun validateNativeThread(nativeId: String?, response: JsonObject): JsonObject {
        validateNativeIsolation(response)
        val thread = response.obj("thread")
        val id = thread.text("id") ?: protocolFailure()
        if (nativeId != null && nativeId != id) protocolFailure()
        if (thread.text("modelProvider")?.let { it != "openai" } == true) protocolFailure()
        val turns = thread["turns"]?.let { it as? JsonArray ?: protocolFailure() }
        validateIdle(thread, turns.orEmpty())
        return thread
    }

    private fun validateNativeIsolation(response: JsonObject) {
        val sandbox = response["sandbox"] as? JsonObject
        val isPolicyMatching = response.text("approvalPolicy") == APPROVAL_POLICY
        val isSandboxMatching = sandbox?.text("type") == "readOnly" && sandbox["networkAccess"] == JsonPrimitive(false)
        if (!isPolicyMatching || !isSandboxMatching) fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }

    /** Resume restores native declarations; changing them silently would advertise tools Codex cannot call. */
    private suspend fun hostedManifest(workspace: WorkspaceRef?): String? {
        val tools = host.tools.specifications(workspace)
        if (tools.isEmpty()) return null
        return buildJsonObject {
            put("version", MANIFEST_VERSION)
            put("workspace", workspace?.value)
            put(
                "tools",
                JsonArray(
                    tools.sortedBy { it.name }.map { spec ->
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("schema", spec.inputSchema)
                        }
                    },
                ),
            )
        }.toString()
    }

    private fun validateIdle(thread: JsonObject, turns: List<JsonElement>) {
        val isActive = (thread["status"] as? JsonObject)?.text("type") == "active"
        if (isActive || turns.any { (it as? JsonObject)?.text("status") == "inProgress" }) {
            fail(EngineFailure.Session(SessionFailureReason.Busy))
        }
    }

    private suspend fun threadParams(
        nativeId: String?,
        target: EngineTarget,
        workspace: WorkspaceRef?,
        tools: Boolean,
        hosted: Pair<List<JsonObject>, String>?,
    ): JsonObject {
        val path = workspace?.let {
            host.workspaces.resolve(it) ?: config.workspaces[it]
                ?: fail(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        val declarations = hosted?.first.orEmpty() + if (tools) searchToolSpecs() else emptyList()
        val instructions = hosted?.second.orEmpty()
        val isolation = codexIsolationConfig(rpc, path, tools)
        return buildJsonObject {
            put("model", target.model.value)
            put("modelProvider", "openai")
            put("approvalPolicy", APPROVAL_POLICY)
            put("sandbox", SANDBOX_MODE)
            if (path != null) put("cwd", path)
            if (nativeId == null && declarations.isNotEmpty()) put("dynamicTools", JsonArray(declarations))
            if (instructions.isNotBlank()) put("developerInstructions", instructions)
            put("config", isolation)
            if (nativeId != null) put("threadId", nativeId)
        }
    }

    /**
     * Declarations for a new thread and instructions limited to the tools the thread has. The access preamble
     * describes project edits, so a session without a project gets only the contributions' own instructions.
     */
    private suspend fun hostedParameters(
        workspace: WorkspaceRef?,
        target: EngineTarget,
        thread: HostedThread,
    ): Pair<List<JsonObject>, String> {
        val declarations = host.tools.specifications(workspace).map { spec ->
            buildJsonObject {
                put("type", "function")
                put("name", spec.name)
                put("description", spec.description)
                put("inputSchema", spec.inputSchema)
            }
        }
        val declared = (thread as? HostedThread.Resumed)?.tools
        val instructions = host.tools.instructions(AgentToolScope(workspace, target, declared))
        val hasTools = declared?.isNotEmpty() ?: declarations.isNotEmpty()
        val text = if (hasTools && workspace != null) codexHostedInstructions(instructions) else instructions
        return declarations to text
    }

    private suspend fun event(message: JsonObject) {
        val params = message["params"] as? JsonObject
        if (params == null) {
            message["id"]?.let { rpc.reject(it) }
            return
        }
        if (message.text("method") == "account/rateLimits/updated" && isUsageAccountTrusted && usageEnabled()) {
            providerUsage.receive(params)
        }
        if (message.text("method") == "account/updated") {
            providerUsage.clear()
            isUsageAccountTrusted = false
            usageAccountEpoch++
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
        try {
            dropped.mapNotNull { it["id"] }.forEach { rpc.reject(it) }
        } catch (e: EngineException) {
            // Runs in finally: a broken wire must not replace the open result; the reader retires the runtime.
            log.w(e) { "Codex could not reject dropped requests" }
        }
    }

    private fun shutdown(failure: EngineFailure) {
        isClosed = true
        isUsageAccountTrusted = false
        providerUsage.clear()
        usageObserver.cancel()
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
        const val APPROVAL_POLICY = "never"
        const val SANDBOX_MODE = "read-only"
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
