package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTimes
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal val KoogSessionSource = SessionSource(
    SessionSourceId("koog.profile"),
    KoogEngineId,
    "Koog",
)

internal class KoogRuntime(
    override val identity: RuntimeIdentity,
    private val access: KoogAccess,
    private val records: KoogSessionRecords,
    private val scope: CoroutineScope,
    private val cache: KoogSessionCache,
    private val search: SearchEngine,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val log = Log.tag("KoogRuntime")
    private val mutex = Mutex()
    private val sessions = mutableMapOf<SessionRef, KoogNativeSession>()
    var isClosed = false
        private set
    override val features: EngineFeatures = KoogFeatures(CreatesSessions to this, AttachesSessions to this)

    override suspend fun create(request: CreateSessionRequest): ActiveSession = onMain {
        mutex.withLock {
            checkOpen()
            validateTarget(request.target)
            val connection = access.route(request.target.binding, identity)
            access.validate(connection)
            checkOpen()
            val now = Clock.System.now()
            val ref = SessionRef(identity.engine, KoogSessionSource.id, Uuid.random().toString())
            val route = ExecutionRoute(
                identity.engine,
                connection.binding.id,
                identity.source,
                identity.revision,
                request.workspace,
            )
            val record = KoogRecord(
                SessionSummary(
                    ref,
                    workspace = request.workspace,
                    origin = SessionOrigin.Heartbeat,
                    times = SessionTimes(now, now),
                    lastRoute = route,
                    features = setOf(SessionHistory.id, ResumesSessions.id),
                ),
                request.target.model,
            )
            koogCall { records.save(record) }
            checkOpen()
            log.i { "Created native session" }
            native(record).attach()
        }
    }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession = onMain {
        mutex.withLock {
            checkOpen()
            validateTarget(request.target)
            access.validate(access.route(request.target.binding, identity))
            checkOpen()
            val record = koogCall { records.get(ref) } ?: fail(EngineFailure.Session(SessionFailureReason.NotFound))
            val route = record.summary.lastRoute ?: fail(EngineFailure.Session(SessionFailureReason.NotResumable))
            val isSameStore = ref.engine == identity.engine && ref.source == KoogSessionSource.id
            val isSameCredentials = route.authSource == identity.source && route.binding == request.target.binding
            val isSameContext = route.workspace == request.workspace && record.model == request.target.model
            if (!isSameStore || !isSameCredentials || !isSameContext) {
                fail(EngineFailure.Session(SessionFailureReason.NotResumable))
            }
            val session = sessions[ref] ?: run {
                val recovered = recover(record, route)
                koogCall { records.save(recovered) }
                native(recovered)
            }
            checkOpen()
            log.i { "Attached native session" }
            session.attach()
        }
    }

    private fun recover(record: KoogRecord, route: ExecutionRoute): KoogRecord {
        val isUnfinished = record.lastTurn != null && record.lastTurn.outcome == null
        return record.copy(
            coverage = if (isUnfinished) {
                HistoryCoverage.Partial
            } else {
                record.coverage
            },
            summary = record.summary.copy(lastRoute = route.copy(revision = identity.revision)),
            lastTurn = if (isUnfinished) record.lastTurn.copy(outcome = TurnOutcome.Unknown) else record.lastTurn,
        )
    }

    private fun native(record: KoogRecord): KoogNativeSession {
        checkOpen()
        val snapshot = cache.restore(record)
        snapshot.history.coverage = record.coverage
        snapshot.update(record.summary)
        cache.pin(record.summary.ref)
        return KoogNativeSession(record, identity, access, records, scope, snapshot, search).also {
            it.onIdle = ::release
            sessions[record.summary.ref] = it
        }
    }

    /** Main dispatcher only, like every mutation of this runtime. */
    fun dispose() {
        log.i { "Closing runtime" }
        isClosed = true
        sessions.values.toList().forEach { it.dispose() }
    }

    override suspend fun close() = onMain {
        val closing = mutex.withLock {
            dispose()
            sessions.values.toList()
        }
        closing.forEach { it.shutdown() }
        closing.forEach(::release)
    }

    /** Forgets a session without leases or a running turn; the next attach rebuilds it from storage. */
    private fun release(session: KoogNativeSession) {
        if (sessions[session.ref] !== session) return
        log.d { "Releasing idle native session" }
        sessions.remove(session.ref)
        cache.unpin(session.ref)
    }

    private fun validateTarget(target: EngineTarget) {
        if (target.engine != identity.engine) fail(EngineFailure.Session(SessionFailureReason.NotResumable))
    }

    private fun checkOpen() {
        if (isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    private suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) { block() }
}
