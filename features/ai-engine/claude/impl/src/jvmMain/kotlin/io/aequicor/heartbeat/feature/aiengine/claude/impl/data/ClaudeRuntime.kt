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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
    resources: ResourceResolver = ResourceResolver { null },
    resourceHistory: PromptResourceHistory = PromptResourceHistory.None,
    inputSupport: (ModelId) -> PromptInputSupport = { model ->
        claudeInputSupport(
            kotlinx.serialization.json.JsonObject(
                mapOf("value" to kotlinx.serialization.json.JsonPrimitive(model.value)),
            ),
        )
    },
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
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
        onReleased = ::released,
        onUsage = providerUsage::receive,
        resources = resources,
        resourceHistory = resourceHistory,
        inputSupport = inputSupport,
    )
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<SessionRef, ClaudeSession>()

    /** Sessions without handles or running turns, oldest release first; kept for reopen up to [MAX_RELEASED]. */
    private val releasedOrder = LinkedHashSet<SessionRef>()

    /** Number of sessions this runtime still keeps for attach and lookup. */
    internal val retainedSessions: Int get() = sessions.size
    val isClosed: Boolean get() = !owner.isActive

    @Volatile
    private var closeFailure: EngineFailure = EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)
    override val features = ClaudeFeatures(
        CreatesSessions to this,
        AttachesSessions to this,
        ReportsProviderUsage to providerUsage,
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
        session.lease()
    }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession = mutex.withLock {
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
        session.lease()
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
        session.stored { request -> attach(ref, request) }
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

    private suspend fun validate(target: EngineTarget) {
        try {
            if (isClosed) throw EngineException(closeFailure)
            requireClaudeEnabled(toggles)
            if (target.engine != identity.engine) authFailure(AuthFailureReason.AuthMismatch)
            account.validate(identity.revision)
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
