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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
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
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHandle
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHarnesses
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.ReleaseOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
internal class FacadeCellSessions(private val facade: EngineFacade, private val harnesses: CellHarnesses) :
    CellSessions {
    private val log = Log.tag("FacadeCellSessions")
    private val mutex = Mutex()

    /** Open handles and lysed cells; guarded by [mutex]. */
    private val handles = mutableMapOf<CellKey, ActiveSession>()

    /** The full target each open handle was opened for: the session route has no model. Guarded by [mutex]. */
    private val targets = mutableMapOf<ActiveSession, EngineTarget>()
    private val lysed = mutableSetOf<CellKey>()
    private val generations = mutableMapOf<CellKey, Int>()

    /** Openers of a cell that are creating or resuming a session no cache holds yet; guarded by [mutex]. */
    private val openers = mutableMapOf<CellKey, Int>()

    /**
     * Discarded handles whose turn did not stop. They stay open: closing one would lose the only way to cancel
     * its turn, and the next lysis or sleep tries again. Guarded by [mutex].
     */
    private val lingering = mutableMapOf<CellKey, MutableList<ActiveSession>>()

    /** Admitted sends and handles being stopped; admission and release share [mutex], never native IO. */
    private val submitting = mutableMapOf<ActiveSession, CompletableDeferred<Unit>>()
    private val stopping = mutableSetOf<ActiveSession>()

    /**
     * Replaced handles still to be let go: once a handle is closed its session is archived and its harness
     * connections removed. An entry leaves when the removal returns; a failed removal is retried at the cell's next
     * retirement, lysis or sleep, and a sleep drops the entries of closed handles after its retry. Guarded by [mutex].
     */
    private val replaced = mutableMapOf<ActiveSession, Replaced>()

    /** Last retired native session, so a late duplicate wrapper cannot restore ownership after retirement. */
    private val retired = mutableMapOf<CellKey, Pair<SessionRef, Int>>()

    /** Sleeps so far: a handle opened across a sleep is not kept; guarded by [mutex]. */
    private var sleeps = 0

    override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int): CellHandle {
        val awake = mutex.withLock { sleeps }
        val cached = acquire(key, route, existing, generation, awake)
        if (cached != null) return cached
        // From its creation until it is cached nobody owns the handle: a cancelled caller would drop it unclosed, so
        // neither step may be cancelled.
        return withContext(NonCancellable) {
            try {
                val opened = if (existing == null) create(route) else resume(existing, route)
                retainOpened(key, opened, isCreated = existing == null, generation, awake, checked(route))
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        val others = (openers[key] ?: 1) - 1
                        if (others > 0) openers[key] = others else openers.remove(key)
                    }
                }
            }
        }
    }

    /** Returns a cached handle after earlier sends settle, or reserves a new opener under [mutex]. */
    private suspend fun acquire(
        key: CellKey,
        route: CellRoute,
        existing: SessionRef?,
        generation: Int,
        awake: Int,
    ): CellHandle? {
        while (true) {
            var stale: ActiveSession? = null
            val pending = mutex.withLock {
                checkOpening(key, generation, awake)
                generations[key] = generation
                stale = takeOutdated(key, route, existing)
                val cached = handles[key]?.takeIf { it.isOpen() } ?: recoverHeld(key, existing)
                if (cached != null) {
                    val sending = submitting[cached]
                    if (sending != null) return@withLock sending
                    return wrap(key, cached, checked(route), generation, awake)
                }
                openers[key] = (openers[key] ?: 0) + 1
                null
            }
            stale?.let { replaceStale(key, it, route, generation) }
            if (pending == null) return null
            // Recovery must observe the earlier send's outcome before deciding whether to adopt or submit a turn.
            pending.await()
        }
    }

    /**
     * Takes the cell's open handle out of the cache when it runs another target than [route]; returns it only when it
     * is to be stopped now by [replaceStale]. Called only under [mutex].
     */
    private fun takeOutdated(key: CellKey, route: CellRoute, existing: SessionRef?): ActiveSession? {
        val outdated = handles[key]?.takeIf { it.isOpen() && existing == null && !it.runs(route) } ?: return null
        handles.remove(key)
        val isKeptBusy = recordReplaced(key, outdated, route)
        return outdated.takeIf { !isKeptBusy }
    }

    /** Called only under [mutex]; a handle of unknown target falls back to its engine and binding. */
    private fun ActiveSession.runs(route: CellRoute): Boolean = targets[this]?.let { it == route.target }
        ?: (this.route.engine == route.target.engine && this.route.binding == route.target.binding)

    /**
     * Remembers the harnesses of a replaced handle, removed once it closes. A handle still sending or being stopped is
     * neither stopped nor archived here: it is kept in [lingering] for the cell's next release and `true` is returned.
     * Otherwise the handle is claimed as [stopping] for [replaceStale], so no release lets it go while it is stopped.
     * Called only under [mutex].
     */
    private fun recordReplaced(key: CellKey, stale: ActiveSession, route: CellRoute): Boolean {
        replaced[stale] = Replaced(key, route.harnesses)
        val isBusy = stale in submitting || stale in stopping
        if (isBusy) {
            lingering.getOrPut(key) { mutableListOf() } += stale
            log.d { "replaced cell session on ${stale.ref.engine.value} is busy; kept for the next release" }
        } else {
            stopping += stale
        }
        return isBusy
    }

    /**
     * The cell moved to another model (a provider refused its own one) and the organism forgot the old session, so
     * nothing else would release it: it is let go like the session of a dead cell. Its turn is stopped, the handle
     * closed, and once it is closed the session is archived and the organism's harnesses disconnected from it, so it
     * never holds a harness connection slot. A stop that is not confirmed keeps the handle for the cell's next lysis
     * or sleep, and the session is archived and its connections removed when that release closes it. It runs on
     * the opener's path, so a slow stop of the old turn (up to [LYSIS_WAIT]) delays the cell's new session.
     */
    private suspend fun replaceStale(key: CellKey, stale: ActiveSession, route: CellRoute, generation: Int) =
        withContext(NonCancellable) {
            log.i { "cell session on ${stale.ref.engine.value} replaced for ${route.target.engine.value}" }
            try {
                // Archived once closed, by [releaseReplaced].
                discard(key, stale, generation, isStopped = true, isArchived = false)
            } finally {
                mutex.withLock { stopping -= stale }
            }
        }

    /**
     * Archives the session of a replaced [handle] that is closed now and removes its harness connections; a failed
     * removal keeps the entry for a retry. A release already running for the handle is not repeated, and the session is
     * archived at most once.
     */
    private suspend fun releaseReplaced(handle: ActiveSession) = withContext(NonCancellable) {
        var isToArchive = false
        val entry = mutex.withLock {
            replaced[handle]?.takeUnless { it.isReleasing }?.also {
                it.isReleasing = true
                isToArchive = !it.isArchived
                it.isArchived = true
            }
        } ?: return@withContext
        if (isToArchive) archiveQuietly(handle.ref)
        val key = entry.key
        val isReleased: suspend () -> Boolean = {
            mutex.withLock { handles[key] !== handle && lingering[key]?.contains(handle) != true }
        }
        try {
            if (entry.harnesses.isNotEmpty()) harnesses.disconnect(handle.ref, entry.harnesses, isReleased)
            mutex.withLock { replaced.remove(handle) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "harness connections of a replaced cell session were not removed; retried later" }
        } finally {
            mutex.withLock { entry.isReleasing = false }
        }
    }

    /** Retries removals that failed for closed replaced handles of the cells [of] selects. */
    private suspend fun retryReplaced(of: (CellKey) -> Boolean) {
        val closed = mutex.withLock { closedReplaced(of) }
        closed.forEach { releaseReplaced(it) }
    }

    /** Replaced handles of the cells [of] selects that nothing holds or stops any more; called only under [mutex]. */
    private fun closedReplaced(of: (CellKey) -> Boolean): List<ActiveSession> = replaced.filter { (handle, owner) ->
        of(owner.key) && handles[owner.key] !== handle && lingering[owner.key]?.contains(handle) != true &&
            handle !in stopping
    }.keys.toList()

    /** Lingering replaced handles of [key], whose turn may still run; called only under [mutex]. */
    private fun lingeringReplaced(key: CellKey): List<ActiveSession> =
        lingering[key].orEmpty().filter { it in replaced }

    /** Rechecks an opener after any admission wait; called only under [mutex]. */
    private fun checkOpening(key: CellKey, generation: Int, awake: Int) {
        if (key in lysed || sleeps != awake || generation < (generations[key] ?: 0)) throw closed()
    }

    /** Moves a retained recovery handle back to the live cache; called only under [mutex]. */
    private fun recoverHeld(key: CellKey, existing: SessionRef?): ActiveSession? =
        lingering[key]?.firstOrNull { it.ref == existing && it.isOpen() && it !in stopping }?.also { held ->
            lingering[key]?.remove(held)
            if (lingering[key]?.isEmpty() == true) lingering.remove(key)
            handles[key] = held
            // The cell uses the session again: its connections are kept.
            replaced.remove(held)
        }

    /** Claims an opened handle only if its cell, turn generation and profile lifetime still match. */
    private suspend fun retainOpened(
        key: CellKey,
        opened: ActiveSession,
        isCreated: Boolean,
        generation: Int,
        awake: Int,
        route: CellRoute,
    ): CellHandle {
        var isLysed = false
        var isStale = false
        val kept = mutex.withLock {
            isLysed = key in lysed
            isStale = generations[key] != generation
            when {
                isLysed || isStale || sleeps != awake -> null

                else -> handles[key]?.takeIf { it.isOpen() } ?: opened.also {
                    handles[key] = it
                    targets[it] = route.target
                }
            }
        }
        return when {
            kept === opened -> wrap(key, opened, route, generation, awake)

            // Another opener of the cell won; a session created here is an unused duplicate.
            kept != null -> wrap(key, kept, route, generation, awake).also {
                discard(key, opened, generation, isStopped = false, isArchived = isCreated)
            }

            // The cell was lysed or the cells slept meanwhile: a turn the session runs must stop, and neither a
            // lysed cell's session nor a new one the organism never learnt of may stay listed.
            else -> {
                discard(key, opened, generation, isStopped = !isStale || isLysed, isArchived = isLysed || isCreated)
                throw closed()
            }
        }
    }

    /** A wrapper keeps the generation and sleep epoch in which its driver acquired the handle. */
    private fun wrap(key: CellKey, active: ActiveSession, route: CellRoute, generation: Int, awake: Int): CellHandle =
        FacadeCellHandle(active, route) { send ->
            // A native handoff can outlive its cancelled caller. Keep admission until its outcome is observed.
            withContext(NonCancellable) {
                mutex.withLock {
                    val isCurrent = key !in lysed && sleeps == awake && generations[key] == generation
                    val isAvailable = handles[key] === active && active !in stopping && active !in submitting
                    if (!isCurrent || !isAvailable) throw closed()
                    submitting[active] = CompletableDeferred()
                }
                try {
                    send()
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { submitting.remove(active)?.complete(Unit) }
                    }
                }
            }
        }

    override suspend fun respond(key: CellKey, decision: PermissionDecision) {
        val handle = mutex.withLock { handles[key].takeIf { key !in lysed } } ?: throw closed()
        handle.features.resolve(RequestsPermissions).orThrow().respond(decision)
    }

    override suspend fun release(
        key: CellKey,
        session: SessionRef?,
        mode: ReleaseMode,
        generation: Int,
    ): ReleaseOutcome = when (mode) {
        ReleaseMode.Retire -> retire(key, session, generation)

        // The handles are out of the live cache and nothing else would release them: finish even when cancelled.
        ReleaseMode.Lyse -> withContext(NonCancellable) { lyse(key, session) }
    }

    private suspend fun retire(key: CellKey, session: SessionRef?, generation: Int): ReleaseOutcome {
        val held = mutex.withLock {
            // A completed zygote may already have been resumed in the same native session.
            if (generation < (generations[key] ?: 0)) return ReleaseOutcome.Unconfirmed
            val current = handles[key]
            val ref = current?.ref ?: session
            val duplicates = lingering[key].orEmpty().filter { it.ref == ref }
            val all = listOfNotNull(current) + duplicates
            if (all.any { it in submitting || it in stopping }) return ReleaseOutcome.Unconfirmed
            handles.remove(key)
            lingering[key]?.removeAll(duplicates.toSet())
            if (lingering[key]?.isEmpty() == true) lingering.remove(key)
            if (ref != null) retired[key] = ref to generation
            all to lingeringReplaced(key).filter { it !in all }
        }
        return withContext(NonCancellable) {
            // Completion of this generation proves the shared native turn ended, even if an older wrapper is stale.
            val isClosed = held.first.map { closeHeld(key, it) }.all { it }
            // A replaced session runs another native turn, which the cell's completion says nothing about: it is
            // stopped like a lysed cell's, and a stop that is not confirmed keeps it for the next release.
            val isStopped = held.second.map { stopHeld(key, it) }.all { it }
            retryReplaced { it == key }
            log.d { "cell ${key.cell.value} of ${key.organism.value} released (${ReleaseMode.Retire})" }
            if (isClosed && isStopped) ReleaseOutcome.Stopped else ReleaseOutcome.Unconfirmed
        }
    }

    /**
     * Stops every handle of the lysed cell and archives its session. The cell counts as stopped only when each
     * handle's turn was seen to stop and no opener is still creating a session that its lysis will discard; a handle
     * that did not stop stays in [lingering] for the next lysis. An unconfirmed cell keeps its session listed.
     */
    private suspend fun lyse(key: CellKey, session: SessionRef?): ReleaseOutcome {
        val (held, isOpening) = mutex.withLock {
            lysed += key
            handles.remove(key)?.let { lingering.getOrPut(key) { mutableListOf() } += it }
            lingering[key].orEmpty().toList() to ((openers[key] ?: 0) > 0)
        }
        val isStopped = held.map { stopHeld(key, it) }.all { it } && !isOpening
        retryReplaced { it == key }
        if (isStopped) session?.let { archiveQuietly(it) }
        log.d { "cell ${key.cell.value} of ${key.organism.value} released (${ReleaseMode.Lyse}, stopped=$isStopped)" }
        return if (isStopped) ReleaseOutcome.Stopped else ReleaseOutcome.Unconfirmed
    }

    /** Stops one held handle and closes it once its turn is seen to stop; a failure leaves it owned. */
    private suspend fun stopHeld(key: CellKey, handle: ActiveSession): Boolean {
        mutex.withLock {
            if (handle in submitting || !stopping.add(handle)) return false
        }
        val isStopped = try {
            stopClaimed(key, handle)
        } finally {
            withContext(NonCancellable) { mutex.withLock { stopping -= handle } }
        }
        return isStopped
    }

    /** Admission is revoked for this handle until its stop and close have both completed. */
    private suspend fun stopClaimed(key: CellKey, handle: ActiveSession): Boolean {
        val isStopped = try {
            handle.stopTurn(LYSIS_WAIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "cell ${key.cell.value} of ${key.organism.value} could not be stopped" }
            false
        }
        if (isStopped) {
            mutex.withLock {
                if (handles[key] === handle) handles.remove(key)
                lingering[key]?.remove(handle)
                if (lingering[key]?.isEmpty() == true) lingering.remove(key)
            }
            return closeHeld(key, handle)
        }
        return false
    }

    override suspend fun isLysed(key: CellKey): Boolean = mutex.withLock { key in lysed }

    override suspend fun isReleased(key: CellKey, generation: Int): Boolean = mutex.withLock {
        key !in handles && key !in lingering && key !in openers &&
            (key in lysed || (generations[key] ?: 0) <= generation)
    }

    override suspend fun hasPendingRelease(organism: OrganismId): Boolean = mutex.withLock {
        handles.keys.any { it.organism == organism } || lingering.keys.any { it.organism == organism } ||
            openers.keys.any { it.organism == organism }
    }

    override suspend fun releaseAll() {
        val all = mutex.withLock {
            sleeps++
            handles.map { it.key to it.value } + lingering.flatMap { (key, held) -> held.map { key to it } }
        }
        // Keep ownership throughout stopping: a failed stop must remain visible after Sleep/Awaken.
        withContext(NonCancellable) {
            coroutineScope {
                all.forEach { (key, handle) -> launch { stopHeld(key, handle) } }
            }
            retryReplaced { true }
            // A removal that failed twice is not retried again: the entries stay bounded across sleeps. Replaced
            // handles still held keep theirs for the release that finally closes them.
            val dropped = mutex.withLock { closedReplaced { true }.onEach { replaced.remove(it) }.size }
            if (dropped > 0) log.w { "$dropped replaced cell sessions keep harness connections that were not removed" }
        }
        log.i { "attempted release of ${all.size} cell sessions before sleep" }
    }

    /**
     * A handle no cell uses; its turn is stopped when [isStopped], since recovery may find one running. An
     * unconfirmed stop retains ownership even on exceptions or a sleep, and the session stays listed for retry.
     */
    private suspend fun discard(
        key: CellKey,
        opened: ActiveSession,
        generation: Int,
        isStopped: Boolean,
        isArchived: Boolean,
    ) {
        var isKept = isStopped
        try {
            if (isStopped) isKept = !opened.stopTurn(LYSIS_WAIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "unused cell session on ${opened.ref.engine.value} was not stopped" }
        } finally {
            withContext(NonCancellable) {
                if (isKept) isKept = retainDiscarded(key, opened, generation)
                if (!isKept) isKept = !closeHeld(key, opened)
            }
        }
        if (isArchived && !isKept) archiveQuietly(opened.ref)
    }

    /** Recovery can adopt or retire the same native session while an older opener is still stopping it. */
    private suspend fun retainDiscarded(key: CellKey, opened: ActiveSession, generation: Int): Boolean =
        mutex.withLock {
            val isOwnedElsewhere = handles[key]?.let { it !== opened && it.ref == opened.ref } == true
            val isRetired = retired[key]?.let { it.first == opened.ref && it.second >= generation } == true
            if (isOwnedElsewhere || isRetired) return@withLock false
            lingering.getOrPut(key) { mutableListOf() } += opened
            true
        }

    /** A failed close retains a retryable resource even after its native turn is known to have ended. */
    private suspend fun closeHeld(key: CellKey, handle: ActiveSession): Boolean {
        val isClosed = closeQuietly(handle)
        if (!isClosed) {
            mutex.withLock {
                val held = lingering.getOrPut(key) { mutableListOf() }
                if (handle !in held) held += handle
            }
        } else {
            releaseReplaced(handle)
        }
        return isClosed
    }

    private suspend fun closeQuietly(handle: ActiveSession): Boolean = try {
        withContext(NonCancellable) {
            handle.close()
            mutex.withLock { targets.remove(handle) }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "session on ${handle.ref.engine.value} was not closed" }
        false
    }

    /** An effort the model no longer advertises is dropped: the engine would refuse the turn. */
    private fun checked(route: CellRoute): CellRoute {
        val effort = route.reasoningEffort ?: return route
        val supported = facade.models.observe(route.target.engine, route.target.binding).value.models
            .firstOrNull { it.target == route.target }?.reasoningEfforts.orEmpty()
        if (effort in supported) return route
        log.d { "cell effort no longer advertised by ${route.target.model.value}; using the default" }
        return route.copy(reasoningEffort = null)
    }

    private suspend fun create(route: CellRoute): ActiveSession {
        val creates = facade.engines.features(route.target.engine).resolve(CreatesSessions).orThrow()
        // The organism's own choice, never anything a cell or a prompt names: engines that declare tools when they
        // open a session (Codex) see the harnesses from the start, the others read the connection made afterwards.
        val session = creates.create(
            CreateSessionRequest(
                route.target,
                route.workspace,
                areDetachedToolsEnabled = true,
                areSessionHooksEnabled = route.harnesses.isNotEmpty(),
                preselected = route.harnesses.takeIf { it.isNotEmpty() }?.let { mapOf(HarnessTools.GROUP to it) }
                    .orEmpty(),
            ),
        )
        log.i { "cell session created on ${route.target.engine.value} harnesses=${route.harnesses.size}" }
        return session
    }

    private suspend fun resume(existing: SessionRef, route: CellRoute): ActiveSession {
        val resumes = facade.sessions.get(existing).features.resolve(ResumesSessions).orThrow()
        val request = ResumeSessionRequest(
            route.target,
            route.workspace,
            areDetachedToolsEnabled = true,
            areSessionHooksEnabled = route.harnesses.isNotEmpty(),
        )
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

/**
 * A replaced handle of [key] still to be archived ([isArchived]) and disconnected from [harnesses]; [isReleasing] while
 * a release runs. Mutated only under the owner's mutex.
 */
private class Replaced(
    val key: CellKey,
    val harnesses: Set<String>,
    var isArchived: Boolean = false,
    var isReleasing: Boolean = false,
)

/** One cell's open session; its turns run at the [route]'s effort and review model. */
private class FacadeCellHandle(
    private val active: ActiveSession,
    private val route: CellRoute,
    private val submission: suspend (suspend () -> TurnId) -> TurnId,
) : CellHandle {
    /** Turns followed only because the session remembered them after an ambiguous delivery. */
    private val doubtful = mutableSetOf<TurnId>()

    override val session: SessionRef get() = active.ref

    override fun activeTurn(): TurnId? = active.state.value.activeTurn()?.id

    override suspend fun submit(
        request: RequestId,
        text: String,
        trust: TrustLevel?,
        attachments: List<ResourceRef>,
    ): TurnId = submission {
        active.submit(
            request,
            text,
            trust,
            attachments,
            route.reasoningEffort,
            route.reviewTarget,
            route.decisionMode,
        ).also {
            if (active.state.value is ActiveSessionState.Unavailable) doubtful += it
        }
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
