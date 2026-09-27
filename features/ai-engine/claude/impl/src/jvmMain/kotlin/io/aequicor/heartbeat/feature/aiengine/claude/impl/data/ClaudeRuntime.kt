package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One pooled source runtime. Attach only reuses sessions already known to this runtime; no native cloning. */
internal class ClaudeRuntime(
    override val identity: RuntimeIdentity,
    transport: ClaudeTransport,
    private val account: ClaudeAccount,
    private val toggles: FeatureToggles,
    parent: CoroutineScope,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val log = Log.tag("ClaudeRuntime")
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val environment = ClaudeSessionEnvironment(transport, account, toggles, scope)
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<SessionRef, ClaudeSession>()
    val isClosed: Boolean get() = !owner.isActive
    override val features = ClaudeFeatures(CreatesSessions to this, AttachesSessions to this)

    init {
        owner.invokeOnCompletion { _ ->
            sessions.values.forEach { it.shutdown() }
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
        sessions[ref] = session
        session.lease()
    }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession = mutex.withLock {
        validate(request.target)
        val session = sessions[ref] ?: run {
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
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        val session = sessions[ref] ?: throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        session.stored { request -> attach(ref, request) }
    }

    override suspend fun close() = mutex.withLock {
        log.i { "Closing Claude runtime" }
        owner.cancelAndJoin()
        sessions.clear()
    }

    private suspend fun validate(target: EngineTarget) {
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        requireClaudeEnabled(toggles)
        if (target.engine != identity.engine) authFailure(AuthFailureReason.AuthMismatch)
        account.validate(identity.revision)
    }
}

internal suspend fun requireClaudeEnabled(toggles: FeatureToggles) {
    if (!toggles.get(ClaudeEngine.Enabled)) {
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }
}
