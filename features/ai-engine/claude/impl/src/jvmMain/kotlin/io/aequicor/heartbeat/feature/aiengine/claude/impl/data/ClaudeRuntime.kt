package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Profile-owned native turns; catalog restoration reuses the same CLI UUID and never clones or resends. */
internal class ClaudeRuntime(
    override val identity: RuntimeIdentity,
    transport: ClaudeTransport,
    private val account: ClaudeAccount,
    private val toggles: FeatureToggles,
    parent: CoroutineScope,
    private val catalog: ClaudeCatalog,
    tools: ProfileAgentTools = NoAgentTools,
    bridge: AgentToolBridge = UnavailableAgentToolBridge,
    native: ClaudeNativeSupport = MissingClaudeNativeSupport,
    resources: ResourceResolver = ResourceResolver { null },
    resourceHistory: PromptResourceHistory = PromptResourceHistory.None,
    efforts: (ModelId) -> List<String>? = { null },
    inputSupport: (ModelId) -> PromptInputSupport = { model ->
        claudeInputSupport(
            kotlinx.serialization.json.JsonObject(
                mapOf("value" to kotlinx.serialization.json.JsonPrimitive(model.value)),
            ),
        )
    },
    /** A runtime that only shows the history of stored sessions: their resumption is left to the owning runtime. */
    private val isReadOnly: Boolean = false,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions,
    SessionTrees {
    private val log = Log.tag("ClaudeRuntime")
    private val nativeStore = transport.nativeStore
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val providerUsage = ClaudeProviderUsage {
        if (toggles.get(EngineUsageEnabled)) {
            if (isClosed) throw EngineException(closeFailure)
            requireClaudeEnabled(toggles)
            account.validate(identity.revision)
            true
        } else {
            false
        }
    }
    private val environment = ClaudeSessionEnvironment(
        transport,
        account,
        toggles,
        scope,
        closeFailure = { closeFailure },
        catalog = catalog,
        tools = tools,
        bridge = bridge,
        native = native,
        onReleased = ::released,
        onUsage = providerUsage::receive,
        resources = resources,
        resourceHistory = resourceHistory,
        inputSupport = inputSupport,
        efforts = efforts,
    )
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<SessionRef, ClaudeSession>()

    /** Sessions without handles or running turns, oldest release first; kept for reopen up to [MAX_RELEASED]. */
    private val releasedOrder = LinkedHashSet<SessionRef>()

    /** Number of sessions this runtime still keeps for attach and lookup. */
    internal val retainedSessions: Int get() = sessions.size
    override val isClosed: Boolean get() = !owner.isActive

    @Volatile
    private var closeFailure: EngineFailure = EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)
    override val features = ClaudeFeatures(
        CreatesSessions to this,
        AttachesSessions to this,
        ReportsProviderUsage to providerUsage,
        SessionTrees to this,
    )

    init {
        scope.launch {
            toggles.observe(EngineUsageEnabled).collect { enabled ->
                if (!enabled) {
                    providerUsage.clear()
                    sessions.values.forEach { it.contextUsage.clear() }
                }
            }
        }
        owner.invokeOnCompletion { _ ->
            sessions.values.forEach { it.shutdown(closeFailure) }
        }
    }

    override suspend fun create(request: CreateSessionRequest): ActiveSession = mutex.withLock {
        requireWritable()
        validate(request.target)
        log.i { "Creating idle Claude session" }
        val ref = SessionRef(ClaudeEngine.Id, ClaudeEngine.SessionSource, UUID.randomUUID().toString())
        val route = ExecutionRoute(
            identity.engine,
            request.target.binding,
            identity.source,
            identity.revision,
            request.workspace,
        )
        val session = ClaudeSession(ref, route, request.target, environment)
        session.persist()
        sessions[ref] = session
        session.lease(request.areDetachedToolsEnabled, request.areSessionHooksEnabled)
    }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession = mutex.withLock {
        requireWritable()
        validate(request.target)
        val session = find(ref) ?: run {
            log.w { "Claude session is unknown to this runtime" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        if (session.route.binding != request.target.binding || session.route.workspace != request.workspace ||
            session.target.model != request.target.model
        ) {
            log.w { "Claude session route does not match the resume request" }
            authFailure(AuthFailureReason.AuthMismatch)
        }
        log.i { "Attaching existing Claude session" }
        // A session kept in memory or restored from the catalog without holders takes this request's choice.
        session.lease(request.areDetachedToolsEnabled, request.areSessionHooksEnabled)
    }

    suspend fun stored(ref: SessionRef): EngineSession = mutex.withLock {
        log.d { "Looking up stored Claude session" }
        if (isClosed) {
            log.w { "Claude runtime is closed; stored session is unavailable" }
            throw EngineException(closeFailure)
        }
        val session = find(ref) ?: run {
            log.w { "Stored Claude session is unknown to this runtime" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        session.stored(if (isReadOnly) null else { request -> attach(ref, request) })
    }

    /**
     * Polls the observed subagent tree of [root]. Each login probe spawns the CLI, so polling must not do that twice
     * a second: the account is validated on the first poll and then every [CLAUDE_ACCOUNT_RECHECK_MILLIS], so a
     * logout or account switch still ends a long subscription within that interval. A recheck that cannot reach a
     * verdict keeps the subscription and is retried at the next interval.
     */
    override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = flow {
        var polls = 0L
        while (true) {
            when {
                polls == 0L -> validate(access.target)
                polls % ACCOUNT_RECHECK_POLLS == 0L -> recheckAccount(access.target)
            }
            emit(treeSession(root, access, isAccountChecked = true).treeSnapshot())
            polls++
            delay(TREE_REFRESH_MILLIS)
        }
    }.distinctUntilChanged()

    /** Only a confirmed logout or account change ends a running subscription; a failed probe is not a verdict. */
    private suspend fun recheckAccount(target: EngineTarget) {
        try {
            validate(target)
        } catch (e: EngineException) {
            if (e.failure is EngineFailure.Authentication) throw e
            log.w(e.redacted()) { "Claude login recheck failed; the session tree subscription continues" }
        }
    }

    override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory {
        val session = treeSession(root, access)
        return if (ref == root) {
            session.history
        } else {
            session.tree.history(ref)
                ?: throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
    }

    /**
     * The login probe runs the CLI for up to [PROBE_TIMEOUT_MS], so it runs before the lock: creation, attach,
     * lookups and close are not held behind it. Inside the lock only the cheap checks are repeated.
     */
    private suspend fun treeSession(
        root: SessionRef,
        access: SessionTreeAccess,
        isAccountChecked: Boolean = false,
    ): ClaudeSession {
        if (!isAccountChecked) validate(access.target)
        return mutex.withLock { lockedTreeSession(root, access) }
    }

    private suspend fun lockedTreeSession(root: SessionRef, access: SessionTreeAccess): ClaudeSession {
        validate(access.target, isAccountChecked = true)
        if (root.engine != identity.engine || root.source != ClaudeEngine.SessionSource) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        val session = find(root) ?: throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        if (session.route.binding != access.target.binding || session.route.workspace != access.workspace) {
            authFailure(AuthFailureReason.AuthMismatch)
        }
        return session
    }

    private suspend fun find(ref: SessionRef): ClaudeSession? = sessions[ref] ?: restore(ref)

    private suspend fun restore(ref: SessionRef): ClaudeSession? {
        val saved = catalog.find(ref) ?: return null
        if (saved.nativeStore != nativeStore) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        if (saved.route.engine != identity.engine || saved.route.authSource != identity.source ||
            saved.route.revision != identity.revision
        ) {
            authFailure(AuthFailureReason.AuthMismatch)
        }
        return ClaudeSession(ref, saved.route, saved.target, environment, saved).also {
            it.persist()
            sessions[ref] = it
            released(it)
        }
    }

    override suspend fun close() = mutex.withLock {
        log.i { "Closing Claude runtime" }
        providerUsage.clear()
        owner.cancelAndJoin()
        sessions.clear()
        synchronized(releasedOrder) { releasedOrder.clear() }
    }

    /**
     * Released sessions stay resumable while few; beyond [MAX_RELEASED] the oldest released ones are dropped,
     * and are restored from the durable catalog when addressed again.
     * Called outside session locks; only this method takes [releasedOrder] and then a session lock.
     */
    private fun released(session: ClaudeSession) {
        if (isClosed) return
        synchronized(releasedOrder) {
            releasedOrder.remove(session.ref)
            if (session.isReleased) releasedOrder.add(session.ref)
            val iterator = releasedOrder.iterator()
            var excess = releasedOrder.size - MAX_RELEASED
            while (excess > 0 && iterator.hasNext()) {
                val ref = iterator.next()
                val candidate = sessions[ref]
                iterator.remove()
                excess--
                // A session leased again meanwhile is skipped; it re-registers on its next release.
                if (candidate != null && candidate.evict()) {
                    sessions.remove(ref, candidate)
                    log.d { "Dropped released Claude session; retained=${sessions.size}" }
                }
            }
        }
    }

    /** Replaced by a runtime for another account revision: its sessions report the changed source. */
    suspend fun retire() {
        log.i { "Retiring Claude runtime after an account change" }
        closeFailure = EngineFailure.Authentication(
            AuthFailure(AuthFailureReason.SourceChanged, ClaudeEngine.AuthSource),
        )
        close()
    }

    /** A read-only runtime never starts or resumes a session. */
    private fun requireWritable() {
        if (isReadOnly) {
            log.w { "Claude reading runtime refused to start or resume a session" }
            throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
    }

    private suspend fun validate(target: EngineTarget, isAccountChecked: Boolean = false) {
        try {
            if (isClosed) throw EngineException(closeFailure)
            requireClaudeEnabled(toggles)
            if (target.engine != identity.engine) authFailure(AuthFailureReason.AuthMismatch)
            if (!isAccountChecked) account.validate(identity.revision)
        } catch (e: EngineException) {
            log.w(e.redacted()) { "Claude session request rejected: ${e.failure::class.simpleName.orEmpty()}" }
            throw e
        }
    }
}

/** Released sessions a runtime keeps resumable; older released ones are dropped. */
internal const val MAX_RELEASED = 16

internal suspend fun requireClaudeEnabled(toggles: FeatureToggles) {
    if (!toggles.get(ClaudeEngine.Enabled)) {
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }
}

private const val TREE_REFRESH_MILLIS = 500L

/** How often a session tree subscription re-validates the CLI login. */
internal const val CLAUDE_ACCOUNT_RECHECK_MILLIS = 300_000L
private const val ACCOUNT_RECHECK_POLLS = CLAUDE_ACCOUNT_RECHECK_MILLIS / TREE_REFRESH_MILLIS
