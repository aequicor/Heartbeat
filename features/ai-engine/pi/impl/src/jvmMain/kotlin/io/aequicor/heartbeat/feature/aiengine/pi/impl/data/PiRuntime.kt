package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiActiveSession
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiSessions
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class PiRuntimeCredentials(
    val identity: RuntimeIdentity,
    val source: AuthSource.ManagedKey,
    val fingerprint: String,
)

/** Profile services a runtime validates against, launches processes with and publishes next to its sessions. */
internal data class PiRuntimeServices(
    val settings: PiSettings,
    val processes: PiProcesses,
    val workspaces: LocalWorkspaces,
    val nativeWeb: NativeWebFetch,
)

internal class PiRuntime(
    private val credentials: PiRuntimeCredentials,
    internal val environment: PiSessionEnvironment,
    private val services: PiRuntimeServices,
) : EngineRuntime,
    PiSessions {
    private val log = Log.tag("PiRuntime")
    override val identity get() = credentials.identity
    private val source get() = credentials.source
    internal val credential get() = credentials.fingerprint
    private val profile get() = environment.profile
    internal val dispatchers get() = environment.dispatchers
    private val settings get() = services.settings
    private val processes get() = services.processes
    private val workspaces get() = services.workspaces
    private val mutex = Mutex()
    private val shutdownMutex = Mutex()
    private val sessions: MutableSet<PiSession> = ConcurrentHashMap.newKeySet()

    // Ownership begins before process startup and ends only after confirmed cleanup. The signal completes
    // when startup finishes; live sessions keep it completed until [forgotten] removes their ownership.
    private val ownedSessions = mutableMapOf<PiSession, CompletableDeferred<Unit>>()

    // Reserved before transcript lookup and process startup; only accessed under mutex.
    private val attaching = mutableSetOf<SessionRef>()

    private val ownedTurns = PiOwnedTurns(this, processes, sessions, attaching, mutex)

    // Live handles per served session and sessions whose last handle is detaching; the callback reads them
    // without the mutex, so both are concurrent.
    private val consumers = ConcurrentHashMap<PiSession, Int>()
    private val draining: MutableSet<PiSession> = ConcurrentHashMap.newKeySet()

    @Volatile override var isClosed: Boolean = false
        private set
    override val features: EngineFeatures =
        PiFeatures(
            listOf(
                CreatesSessions to this,
                AttachesSessions to this,
                NativeWebFetch to services.nativeWeb,
                StopsOwnedTurns to ownedTurns,
                ListsComposerAssists to PiComposerAssists(services.workspaces, environment.dispatchers),
            ),
        )

    suspend fun validate() {
        if (isClosed || profile.isClosed) {
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        if (!environment.toggles.get(AiEngines) || !environment.toggles.get(PiEnabled)) {
            piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        if (settings.source(identity.source) != source || processes.credentialFingerprint(source) != credential) {
            authenticationFailure(AuthFailureReason.SourceChanged, identity.source)
        }
    }

    internal fun ensureOpen() {
        if (isClosed || profile.isClosed) piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    internal suspend fun validateStop(access: OwnedTurnAccess) {
        validate()
        val configuration = settings.snapshot()
        if (configuration.bindings[access.target.binding.value] != source) {
            authenticationFailure(AuthFailureReason.AuthMismatch, identity.source)
        }
    }

    override suspend fun create(request: CreateSessionRequest): PiActiveSession = launch(request, null)

    /**
     * Restarts Pi on the stored transcript of [ref] in this profile, e.g. after an application restart.
     * Reserves the ref before any transcript IO, so concurrent attachments cannot start a second process.
     *
     * A transcript is served by at most one live Pi process; a detached one keeps it until its turn settles.
     * A live session is never started twice: once it is served, every attach hands out a borrowing handle over
     * the same process, and the close of the last one closes the session — an owner has no special rights.
     *
     * Pi transcripts and [SessionRef] carry no credential binding. The caller explicitly chooses the target:
     * its binding must resolve to this runtime's exact source, revision and current credential fingerprint
     * through [prepare] and [validate]. Another binding of that same source is allowed; no fallback binding
     * or credentials are selected from the transcript. Owned turns additionally require their persisted route
     * and credential fingerprint. Foreign sources must use their own validated runtime.
     */
    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): PiActiveSession {
        if (ref.engine != identity.engine || ref.source != PiSessionSource ||
            request.target.engine != identity.engine
        ) {
            piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        val shared = mutex.withLock {
            validate()
            validateTarget(request.target, settings.snapshot())
            if (ownedTurns.isReserved(ref.nativeId)) busy()
            val served = sessions.firstOrNull { it.attachedRef == ref }
            when {
                served != null && served !in draining -> {
                    // A live process serves exactly one workspace and detached-tools setting: a request for
                    // another one can never share it, so it fails instead of borrowing a foreign process.
                    if (served.serves(request)) {
                        consumers[served] = consumers.getValue(served) + 1
                        served
                    } else {
                        foreignConfiguration()
                    }
                }

                served != null -> busy()

                !attaching.add(ref) -> busy()

                else -> null
            }
        }
        if (shared != null) {
            log.i { "Sharing the live Pi session of a stored transcript" }
            return PiSessionView(shared) { releaseBorrow(shared) }
        }
        try {
            val file = processes.transcript(ref.nativeId)
                ?: piFailure(EngineFailure.Session(SessionFailureReason.NotFound))
            log.i { "Attaching stored Pi session" }
            val launchRequest = CreateSessionRequest(request.target, request.workspace, request.areDetachedToolsEnabled)
            settleInterrupted(ref, launchRequest)
            return launch(launchRequest, PiTranscript(ref, file))
        } finally {
            withContext(NonCancellable) { mutex.withLock { attaching.remove(ref) } }
        }
    }

    /**
     * Settles what an earlier runtime left before reopening; the reserved ref keeps owned stops and other attaches
     * out meanwhile. A durable stop fence is resumed: it was requested by the turn's owner, and nobody else would
     * retry it once its session is gone. A turn whose whole stored process tree is gone settles as Unknown.
     * Neither happens while hosted work of the earlier runtime still drains; anything else stays for recheck.
     * A turn stored without process identity can be neither proven nor caused to exit: it stays unavailable, and
     * reopening never guesses its outcome from a fresh process.
     */
    private suspend fun settleInterrupted(ref: SessionRef, request: CreateSessionRequest) {
        val route = route(request)
        val journal = PiTurnJournal(environment.turns, ref, route, credential)
        val snapshot = journal.restore() ?: return
        val stopping = snapshot.stopping
        if (stopping != null) {
            val stopRequest = stopping.turn.request ?: return
            if (!isDrained(ref, route, stopping.turn.id)) return
            val stopped = PiStoredTurnStop(journal, processes).stop(stopRequest, stopping.turn.id)
            if (stopped != null) {
                log.i { "Deferred Pi turn stop confirmed before reopening" }
            } else {
                log.i { "Deferred Pi turn stop is still unconfirmed" }
            }
            return
        }
        val active = snapshot.active ?: return
        val owner = active.processOwner ?: return
        if (!processes.hasExited(owner) || !isDrained(ref, route, active.turn.id)) return
        journal.finish(active.turn.id, TurnOutcome.Unknown)
        log.i { "Interrupted Pi turn settled as unknown: its process exited" }
    }

    /** The profile barrier for hosted work an earlier runtime left running for [turn]. */
    private suspend fun isDrained(ref: SessionRef, route: ExecutionRoute, turn: TurnId): Boolean {
        val isDrained = withContext(dispatchers.main) {
            environment.hostedDrains.drain(ref, route, credential, turn)
        }
        if (!isDrained) log.i { "Interrupted Pi turn stays unresolved: earlier hosted work is still draining" }
        return isDrained
    }

    /** Recheck of a turn left by an earlier process: the exact owned stop proves or causes its exit. */
    private suspend fun stopRestored(session: PiSession, turn: Turn): Boolean {
        val ref = session.attachedRef ?: return false
        val request = turn.request ?: return false
        val route = session.route
        val access = OwnedTurnAccess(turn.target.copy(binding = route.binding), route.workspace, turn.id)
        return ownedTurns.stop(ref, request, access) is OwnedTurnStop.Confirmed
    }

    private suspend fun launch(request: CreateSessionRequest, transcript: PiTranscript?): PiActiveSession {
        val session = prepare(request, transcript)
        var isRegistered = false
        try {
            // Startup stays outside the lock so close() and unrelated creations can proceed.
            session.first.start(
                { plan, event, failed -> processes.start(source, session.second, event, failed, plan) },
                transcript,
                processes::transcript,
            )
            // A started process must be registered or shut down even when its caller is cancelled.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (isClosed) piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
                    sessions += session.first
                    consumers[session.first] = 1
                    isRegistered = true
                }
            }
            // Every handle is a borrowing one, so the caller's close only ends its own use of the process.
            return PiSessionView(session.first) { releaseBorrow(session.first) }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!isRegistered) session.first.shutdown()
                } finally {
                    mutex.withLock { ownedSessions[session.first]?.complete(Unit) }
                }
            }
        }
    }

    /** Ends one borrowed handle; the process is closed when its last consumer is gone. */
    private suspend fun releaseBorrow(session: PiSession) {
        // Counting must not be lost to a cancelled caller, or the consumer leaks and the process stays forever.
        val isLast = withContext(NonCancellable) {
            mutex.withLock {
                val previous = consumers.remove(session)
                when {
                    // The process was already forgotten by its own release: nothing is left to drain or close.
                    previous == null -> false

                    // Borrowers remain: keep the count, the process stays with them.
                    previous > 1 -> {
                        consumers[session] = previous - 1
                        false
                    }

                    else -> {
                        draining += session
                        true
                    }
                }
            }
        }
        if (isLast) session.close()
    }

    private fun busy(): Nothing {
        log.w { "Stored Pi session is still served by a running process" }
        piFailure(EngineFailure.Session(SessionFailureReason.Busy))
    }

    private fun foreignConfiguration(): Nothing {
        log.w { "Stored Pi session runs another workspace or detached-tools setting" }
        piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
    }

    private suspend fun prepare(request: CreateSessionRequest, transcript: PiTranscript?): Pair<PiSession, String?> =
        mutex.withLock {
            validate()
            val configuration = settings.snapshot()
            validateTarget(request.target, configuration)
            val directory = resolvePiWorkspace(request.workspace, workspaces, configuration.workspaces)
            val route = route(request)
            val restored = transcript?.let {
                PiTurnJournal(environment.turns, it.ref, route, credential).restoreForOpening()
            }
            val session = withContext(dispatchers.main) {
                PiSession(
                    request,
                    route,
                    environment,
                    credential,
                    ::validate,
                    ::forgotten,
                    restored,
                    ::stopRestored,
                )
            }
            ownedSessions[session] = CompletableDeferred()
            session to directory
        }

    private fun route(request: CreateSessionRequest) =
        ExecutionRoute(identity.engine, request.target.binding, identity.source, identity.revision, request.workspace)

    // Under the runtime mutex: it mutates the same bookkeeping as attach and releaseBorrow, and a racing
    // removal could resurrect a consumer count of an already released process.
    private suspend fun forgotten(session: PiSession) {
        mutex.withLock {
            ownedTurns.forget(session)
            ownedSessions.remove(session)?.complete(Unit)
            sessions.remove(session)
            draining.remove(session)
            consumers.remove(session)
        }
    }

    private fun validateTarget(target: EngineTarget, configuration: PiConfiguration) {
        if (target.engine != identity.engine || configuration.bindings[target.binding.value] != source) {
            authenticationFailure(AuthFailureReason.AuthMismatch, identity.source)
        }
        if (!target.model.value.startsWith(source.scope.provider.value + "/")) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
    }

    /** Stops every session; unconfirmed shutdowns remain owned and a later close retries them. */
    override suspend fun close(): Unit = shutdownMutex.withLock {
        withContext(NonCancellable) {
            // The separate shutdown lock serializes retries without blocking the [forgotten] callback.
            val snapshot = mutex.withLock {
                isClosed = true
                ownedSessions.toList()
            }
            supervisorScope {
                val attempts = snapshot.map { (session, started) ->
                    async {
                        started.await()
                        if (mutex.withLock { session in ownedSessions }) shutdown(session)
                    }
                }
                // One failed or cancelled adapter attempt must not skip stopping the remaining processes.
                attempts.joinAll()
                attempts.forEach { it.await() }
            }
        }
    }

    private suspend fun shutdown(session: PiSession) {
        try {
            session.shutdown()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Pi runtime shutdown retained a session for retry" }
            throw error
        }
    }
}

/** Resolves a native process working directory, retaining explicitly configured legacy routes. */
internal suspend fun resolvePiWorkspace(
    ref: WorkspaceRef?,
    workspaces: LocalWorkspaces,
    configured: Map<String, String>,
): String? = ref?.let {
    workspaces.resolve(it) ?: configured[it.value] ?: piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
}

internal class PiFeatures(private val entries: List<Pair<EngineFeatureKey<*>, EngineFeature>>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }?.second
            ?: return FeatureAccess.Unsupported
        if (entry.hasNoSupportedInput()) return FeatureAccess.Unsupported
        // The key's KClass was matched to the declaration and checked against the actual instance.
        @Suppress("UNCHECKED_CAST")
        return if (key.type.isInstance(entry)) FeatureAccess.Available(entry as F) else FeatureAccess.Unsupported
    }
}

private fun EngineFeature.hasNoSupportedInput(): Boolean = when (this) {
    is AcceptsImages -> mediaTypes.isEmpty()
    is AcceptsResources -> mediaTypes.isEmpty()
    else -> false
}

/**
 * A borrowing handle over a live [PiSession] that another consumer attached first: it observes and drives the
 * same native process, while its close only ends the borrow. The process is closed when its last consumer is
 * gone, so a borrower never cuts short a session it does not own.
 */
internal class PiSessionView(val origin: PiSession, private val release: suspend () -> Unit) : PiActiveSession {

    // The borrow ends exactly once, and only the close that took the last borrow owns the origin close: a
    // repeated close after a failure retries the idempotent origin close (the contract keeps `Closing`
    // retryable) and never decrements the consumer count a second time.
    private val isEnded = AtomicBoolean(false)
    private val isOwnClosePending = AtomicBoolean(false)

    override val ref: SessionRef get() = origin.ref
    override val route: ExecutionRoute get() = origin.route
    override val state: StateFlow<ActiveSessionState> get() = origin.state
    override val contextUsage: SessionContextUsage get() = origin.contextUsage
    override val features: EngineFeatures get() = origin.features

    override suspend fun close() {
        if (isEnded.compareAndSet(false, true)) {
            try {
                release()
            } finally {
                isOwnClosePending.compareAndSet(false, origin.state.value is ActiveSessionState.Closing)
            }
            return
        }
        if (isOwnClosePending.get()) origin.close()
    }
}
