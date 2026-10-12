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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHandle
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val generations = mutableMapOf<CellKey, Int>()

    /** Sleeps so far: a handle opened across a sleep is not kept; guarded by [mutex]. */
    private var sleeps = 0

    override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int): CellHandle {
        val awake = mutex.withLock {
            if (key in lysed || generation < (generations[key] ?: 0)) throw closed()
            generations[key] = generation
            handles[key]?.takeIf { it.isOpen() }?.let { return FacadeCellHandle(it) }
            sleeps
        }
        // From its creation until it is cached nobody owns the handle: a cancelled caller would drop it unclosed, so
        // neither step may be cancelled.
        return withContext(NonCancellable) {
            val opened = if (existing == null) create(route) else resume(existing, route)
            retainOpened(key, opened, isCreated = existing == null, generation, awake)
        }
    }

    /** Claims an opened handle only if its cell, turn generation and profile lifetime still match. */
    private suspend fun retainOpened(
        key: CellKey,
        opened: ActiveSession,
        isCreated: Boolean,
        generation: Int,
        awake: Int,
    ): CellHandle {
        var isLysed = false
        var isStale = false
        val kept = mutex.withLock {
            isLysed = key in lysed
            isStale = generations[key] != generation
            when {
                isLysed || isStale || sleeps != awake -> null
                else -> handles[key]?.takeIf { it.isOpen() } ?: opened.also { handles[key] = it }
            }
        }
        return when {
            kept === opened -> FacadeCellHandle(opened)

            // Another opener of the cell won; a session created here is an unused duplicate.
            kept != null -> FacadeCellHandle(kept).also {
                discard(opened, isStopped = false, isArchived = isCreated)
            }

            // The cell was lysed or the cells slept meanwhile: a turn the session runs must stop, and neither a
            // lysed cell's session nor a new one the organism never learnt of may stay listed.
            else -> {
                discard(opened, isStopped = !isStale || isLysed, isArchived = isLysed || isCreated)
                throw closed()
            }
        }
    }

    override suspend fun respond(key: CellKey, decision: PermissionDecision) {
        val handle = mutex.withLock { handles[key] } ?: throw closed()
        handle.features.resolve(RequestsPermissions).orThrow().respond(decision)
    }

    override suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode, generation: Int) {
        val handle = mutex.withLock {
            // A completed zygote may already have been resumed in the same native session.
            if (mode == ReleaseMode.Retire && generation < (generations[key] ?: 0)) return
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
        val all = mutex.withLock {
            sleeps++
            handles.values.toList().also { handles.clear() }
        }
        // The handles are already out of the cache: they are stopped together and even when cancelled.
        withContext(NonCancellable) {
            coroutineScope {
                all.forEach { handle ->
                    launch {
                        try {
                            handle.stop(LYSIS_WAIT)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.w(e) { "session on ${handle.ref.engine.value} was not released before sleep" }
                        }
                    }
                }
            }
        }
        log.i { "released ${all.size} cell sessions before sleep" }
    }

    /** A handle no cell uses; its turn is stopped when [isStopped], since a recovery may have found one running. */
    private suspend fun discard(opened: ActiveSession, isStopped: Boolean, isArchived: Boolean) {
        try {
            if (isStopped) opened.stop(LYSIS_WAIT) else opened.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "unused cell session on ${opened.ref.engine.value} was not closed" }
        }
        if (isArchived) archiveQuietly(opened.ref)
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
        val request = ResumeSessionRequest(route.target, route.workspace, areDetachedToolsEnabled = true)
        val session = resumePatiently(existing, resumes, request)
        log.i { "cell session resumed on ${existing.engine.value}" }
        return session
    }

    /**
     * A stored session is served by at most one live process, and a cell is not its only reader: a transcript
     * view opened on the session or the previous turn's release can hold it right now. Such conflicts are
     * short and a cell owns its session, so `Busy` is waited out instead of breaking the turn; only a conflict
     * lasting all [RESUME_BUSY_ATTEMPTS] attempts fails the resume and with it the cell. Every waited-out
     * attempt keeps its error in the log.
     */
    private suspend fun resumePatiently(
        existing: SessionRef,
        resumes: ResumesSessions,
        request: ResumeSessionRequest,
    ): ActiveSession {
        repeat(RESUME_BUSY_ATTEMPTS - 1) { attempt ->
            try {
                return resumes.resume(request)
            } catch (e: EngineException) {
                val isBusy = (e.failure as? EngineFailure.Session)?.reason == SessionFailureReason.Busy
                if (!isBusy) throw e
                log.w(e) {
                    "cell session on ${existing.engine.value} is busy; waiting for its holder " +
                        "(${attempt + 1}/${RESUME_BUSY_ATTEMPTS})"
                }
            }
            delay(RESUME_BUSY_STEP)
        }
        return resumes.resume(request)
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

    internal companion object {
        val LYSIS_WAIT = 30.seconds

        /** How many times a `Busy` resumption is tried before the turn is allowed to break on it. */
        internal const val RESUME_BUSY_ATTEMPTS = 8

        /** The pause between two `Busy` resumption attempts. */
        internal val RESUME_BUSY_STEP = 1.seconds
    }
}

/** One cell's open session. */
private class FacadeCellHandle(private val active: ActiveSession) : CellHandle {
    /** Turns followed only because the session remembered them after an ambiguous delivery. */
    private val doubtful = mutableSetOf<TurnId>()

    override val session: SessionRef get() = active.ref

    override fun activeTurn(): TurnId? = active.state.value.activeTurn()?.id

    override suspend fun submit(
        request: RequestId,
        text: String,
        trust: TrustLevel?,
        attachments: List<ResourceRef>,
    ): TurnId = active.submit(request, text, trust, attachments).also {
        if (active.state.value is ActiveSessionState.Unavailable) doubtful += it
    }

    override suspend fun cancel(turn: TurnId) = active.cancelQuietly(turn)

    override suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome =
        active.awaitTurn(turn, onPending)

    override suspend fun answer(turn: TurnId, isUnconfirmed: Boolean): String? {
        val history = active.features.resolve(SessionHistory).orThrow()
        val items = history.page(HistoryPageRequest(limit = ANSWER_ITEMS)).items
        return answerOf(
            items,
            turn,
            maxChars = OrganismBounds.MAX_RESULT,
            isMarkedOnly = isUnconfirmed && turn in doubtful,
        )
    }

    private companion object {
        const val ANSWER_ITEMS = 100
    }
}
