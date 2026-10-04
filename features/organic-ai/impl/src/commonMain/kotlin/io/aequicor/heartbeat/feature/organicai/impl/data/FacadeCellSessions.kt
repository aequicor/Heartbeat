package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHandle
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.answerOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Cell sessions through the profile engine facade. Each living cell keeps one handle open; turns belong to the
 * profile runtime, so closing a handle never stops a turn and lysis cancels it first. Cells get the detached hosted
 * tools: the organism escalates their permission requests to the user through its machine.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class FacadeCellSessions(private val facade: EngineFacade) : CellSessions {
    private val log = Log.tag("FacadeCellSessions")
    private val mutex = Mutex()

    /** Open handles and lysed cells; guarded by [mutex]. */
    private val handles = mutableMapOf<CellKey, ActiveSession>()
    private val lysed = mutableSetOf<CellKey>()

    override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?): CellHandle {
        mutex.withLock {
            if (key in lysed) throw closed()
            handles[key]?.takeIf { it.isOpen() }?.let { return FacadeCellHandle(it) }
        }
        val opened = if (existing == null) create(route) else resume(existing, route)
        val kept = mutex.withLock {
            if (key in lysed) null else handles[key]?.takeIf { it.isOpen() } ?: opened.also { handles[key] = it }
        }
        if (kept !== opened) {
            withContext(NonCancellable) {
                opened.close()
                // The cell was lysed while its session was being created: the new session must not stay listed.
                if (kept == null) archiveQuietly(opened.ref)
            }
        }
        return FacadeCellHandle(kept ?: throw closed())
    }

    override suspend fun respond(key: CellKey, decision: PermissionDecision) {
        val handle = mutex.withLock { handles[key] } ?: throw closed()
        handle.features.resolve(RequestsPermissions).orThrow().respond(decision)
    }

    override suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode) {
        val handle = mutex.withLock {
            if (mode == ReleaseMode.Lyse) lysed += key
            handles.remove(key)
        }
        // The handle is already out of the cache, so nothing else would release it: finish even when cancelled.
        withContext(NonCancellable) {
            try {
                when (mode) {
                    ReleaseMode.Retire -> handle?.close()

                    ReleaseMode.Lyse -> {
                        handle?.stop(LYSIS_WAIT)
                        session?.let { archive(it) }
                    }
                }
                log.d { "cell ${key.cell.value} of ${key.organism.value} released ($mode)" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "cell ${key.cell.value} of ${key.organism.value} was not fully released ($mode)" }
            }
        }
    }

    override suspend fun releaseAll() {
        val all = mutex.withLock { handles.values.toList().also { handles.clear() } }
        all.forEach { handle ->
            try {
                handle.stop(LYSIS_WAIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "session on ${handle.ref.engine.value} was not released before sleep" }
            }
        }
        log.i { "released ${all.size} cell sessions before sleep" }
    }

    private suspend fun create(route: CellRoute): ActiveSession {
        val creates = facade.engines.features(route.target.engine).resolve(CreatesSessions).orThrow()
        val session = creates.create(
            CreateSessionRequest(route.target, route.workspace, areDetachedToolsEnabled = true),
        )
        log.i { "cell session created on ${route.target.engine.value}" }
        return session
    }

    private suspend fun resume(existing: SessionRef, route: CellRoute): ActiveSession {
        val resumes = facade.sessions.get(existing).features.resolve(ResumesSessions).orThrow()
        val session = resumes.resume(
            ResumeSessionRequest(route.target, route.workspace, areDetachedToolsEnabled = true),
        )
        log.i { "cell session resumed on ${existing.engine.value}" }
        return session
    }

    /** History of a killed cell stays readable but leaves the active session lists. */
    private suspend fun archive(session: SessionRef) {
        when (val archives = facade.sessions.get(session).features.resolve(ArchivesSessions)) {
            is FeatureAccess.Available -> archives.feature.setArchived(true)
            is FeatureAccess.Unavailable -> log.w(EngineException(archives.reason)) { "session was not archived" }
            FeatureAccess.Unsupported -> log.d { "${session.engine.value} sessions cannot be archived" }
        }
    }

    private suspend fun archiveQuietly(session: SessionRef) {
        try {
            archive(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "abandoned session on ${session.engine.value} was not archived" }
        }
    }

    private fun ActiveSession.isOpen(): Boolean =
        state.value !is ActiveSessionState.Closing && state.value != ActiveSessionState.Closed

    private fun closed() = EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))

    private companion object {
        val LYSIS_WAIT = 30.seconds
    }
}

/** One cell's open session. */
private class FacadeCellHandle(private val active: ActiveSession) : CellHandle {
    override val session: SessionRef get() = active.ref

    override fun activeTurn(): TurnId? = active.state.value.activeTurn()?.id

    override suspend fun submit(request: RequestId, text: String, trust: TrustLevel?): TurnId =
        active.submit(request, text, trust)

    override suspend fun cancel(turn: TurnId) = active.cancelQuietly(turn)

    override suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome =
        active.awaitTurn(turn, onPending)

    override suspend fun answer(turn: TurnId): String? {
        val history = active.features.resolve(SessionHistory).orThrow()
        return answerOf(history.page(HistoryPageRequest(limit = ANSWER_ITEMS)).items, turn)
    }

    private companion object {
        const val ANSWER_ITEMS = 100
    }
}
