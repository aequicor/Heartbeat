package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/** Persistence and screen projection around a profile-owned request. */
internal interface StudioRunHost {
    suspend fun startedRun(id: String, at: Instant)
    suspend fun prepareRun(id: String) = Unit

    suspend fun executeRun(request: StudioTurnRequest): RunOutcome
    suspend fun finishedRun(id: String)
    suspend fun settledRun(request: StudioTurnRequest, outcome: RunOutcome) = Unit
}

/**
 * Explicit capability for durable recovery publication. Preparation runs outside the coordinator lock, may take
 * the route and journal locks, and must finish before Q becomes visible. Native IO belongs to executeRun.
 */
internal interface StudioRecoveryRunHost : StudioRunHost {
    suspend fun reserveRecovery(request: StudioTurnRequest)

    /** Persists a Stop latched during preparation, before the execution may be published. */
    suspend fun recordRecoveryStop(request: StudioTurnRequest)
}

/**
 * Reserves a chat before action handoff, independently of the lifetime of the UI caller. One per profile: the studio
 * runtime and scheduled runs share its busy set.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioRunCoordinator(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val clock: Clock,
    // Auto-continue runs its replies through this coordinator: deferred to break the cycle.
    private val lifecycle: Lazy<StudioTurnLifecycle> = lazyOf(NoStudioTurnLifecycle),
) {
    private val log = Log.tag("StudioRunCoordinator")
    private val lock = Mutex()
    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val requests = MutableStateFlow<Map<String, RequestId>>(emptyMap())
    private val recoveryReservations = mutableMapOf<String, RecoveryReservation>()
    val occupied: StateFlow<Set<String>> = busy.asStateFlow()

    /** Whether a request or mutation of chat [id] runs now. */
    fun isBusy(id: String): Boolean = id in busy.value

    /** Exact reserved attempt, available even before the host has prepared its journal or native handle. */
    fun currentRequest(id: String): RequestId? = requests.value[id]

    /**
     * Captures a recovery Stop target and waits outside all locks for durable publication or failed initialization.
     * It returns the captured Q even if that observer ended; it never substitutes a later reservation.
     */
    suspend fun requestRecoveryStop(id: String): RequestId? {
        val reservation = lock.withLock {
            recoveryReservations[id]?.also {
                recoveryReservations[id] = it.copy(isStopRequested = true)
            }
        } ?: return null
        reservation.ready.await()
        return reservation.request
    }

    /** Reserves a non-turn mutation without starting an agent or waiting behind a running request. */
    suspend fun <T> mutate(id: String, body: suspend () -> T): T {
        log.i { "Reserve chat mutation until durable completion" }
        lock.withLock {
            check(!profile.isClosed && id !in busy.value) { "Session is busy" }
            busy.value += id
        }
        try {
            return body()
        } finally {
            withContext(NonCancellable) { lock.withLock { busy.value -= id } }
        }
    }

    suspend fun run(
        host: StudioRunHost,
        request: StudioTurnRequest,
        waitForIdle: Boolean = false,
        cancelBeforeSubmission: Boolean = false,
        beforeExecute: suspend () -> Unit = {},
        onCancelledBeforeSubmission: suspend () -> Unit = {},
        admission: Flow<ScheduledWakeAdmission>? = null,
    ): RunOutcome {
        log.i { "Reserve profile-owned execution" }
        require(request.submission == null || admission == null) {
            "A supplied submission gate must own its admission policy"
        }
        val submission = request.submission ?: if (cancelBeforeSubmission || admission != null) {
            StudioRunSubmission(admission)
        } else {
            null
        }
        val reserved = submission?.let { request.copy(submission = it) } ?: request
        while (true) {
            awaitAdmission(request.id, waitForIdle, admission)
            val launch = lock.withLock {
                // A disabled background request waits without reserving the chat or changing its visible state.
                when (admission?.first()) {
                    ScheduledWakeAdmission.Defer -> throw ScheduledWakeDeferredException()
                    ScheduledWakeAdmission.Drop -> throw ScheduledWakeDroppedException()
                    ScheduledWakeAdmission.Allow, null -> Unit
                }
                check(!profile.isClosed) { "Profile is closed" }
                if (waitForIdle && request.id in busy.value) return@withLock null
                check(request.id !in busy.value) { "Session is busy" }
                launchReserved(host, reserved, submission, beforeExecute, onCancelledBeforeSubmission)
            }
            if (launch != null) {
                launch.release.complete(Unit)
                return awaitRun(launch.job, submission)
            }
        }
    }

    /** Called under [lock]; the suspended child installs cleanup before route preparation can start. */
    private suspend fun launchReserved(
        host: StudioRunHost,
        request: StudioTurnRequest,
        submission: StudioSubmissionGate?,
        beforeExecute: suspend () -> Unit,
        onCancelledBeforeSubmission: suspend () -> Unit,
    ): RunLaunch {
        val recovery = (host as? StudioRecoveryRunHost)?.takeIf { request.recoveryRequests.isNotEmpty() }
        val release = CompletableDeferred<Unit>()
        if (recovery == null) {
            reserveRequest(host, request)
            release.complete(Unit)
        } else {
            recoveryReservations[request.id] = RecoveryReservation(request.request)
        }
        busy.value += request.id
        log.v { "Reserved pending profile execution" }
        val job = profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            var outcome = RunOutcome.Failed
            try {
                release.await()
                if (recovery != null) publishRecovery(recovery, request)
                host.prepareRun(request.id)
                beforeExecute()
                outcome = lifecycle.value.observe(request) { host.executeRun(request) }
                outcome
            } finally {
                withContext(NonCancellable) {
                    settleReserved(host, request, outcome, submission, onCancelledBeforeSubmission)
                }
            }
        }
        return RunLaunch(job, release)
    }

    private suspend fun settleReserved(
        host: StudioRunHost,
        request: StudioTurnRequest,
        outcome: RunOutcome,
        submission: StudioSubmissionGate?,
        onCancelledBeforeSubmission: suspend () -> Unit,
    ) {
        try {
            try {
                host.settledRun(request, outcome)
            } finally {
                withContext(NonCancellable) { submission?.cancel() }
            }
        } finally {
            withContext(NonCancellable) { finishRun(host, request.id, submission, onCancelledBeforeSubmission) }
        }
    }

    /** No route/journal acquisition while [lock] is held; Stop waits on the independent ready barrier. */
    private suspend fun publishRecovery(host: StudioRecoveryRunHost, request: StudioTurnRequest) {
        host.reserveRecovery(request)
        while (true) {
            val isPublished = lock.withLock {
                val reservation = checkNotNull(recoveryReservations[request.id])
                check(reservation.request == request.request) { "Recovery reservation changed" }
                if (reservation.isStopRequested && !reservation.isStopRecorded) return@withLock false
                reserveRequest(host, request)
                reservation.ready.complete(Unit)
                true
            }
            if (isPublished) return
            host.recordRecoveryStop(request)
            lock.withLock {
                val reservation = checkNotNull(recoveryReservations[request.id])
                recoveryReservations[request.id] = reservation.copy(isStopRecorded = true)
            }
        }
    }

    /** Called under [lock], so a failed reservation cannot erase a later request. */
    private suspend fun reserveRequest(host: StudioRunHost, request: StudioTurnRequest) {
        requests.value += request.id to request.request
        log.v { "Recorded the exact chat execution reservation" }
        var isStarted = false
        try {
            host.startedRun(request.id, clock.now())
            isStarted = true
        } finally {
            if (!isStarted) requests.value -= request.id
        }
    }

    private suspend fun awaitAdmission(id: String, waitForIdle: Boolean, admission: Flow<ScheduledWakeAdmission>?) {
        if (admission != null) {
            combine(busy, admission) { occupied, decision ->
                decision != ScheduledWakeAdmission.Allow || !waitForIdle || id !in occupied
            }.first { it }
        } else if (waitForIdle) {
            busy.first { id !in it }
        }
    }

    private suspend fun finishRun(
        host: StudioRunHost,
        id: String,
        submission: StudioSubmissionGate?,
        onCancelledBeforeSubmission: suspend () -> Unit,
    ) {
        try {
            if (submission?.isCancelled == true) onCancelledBeforeSubmission()
        } finally {
            withContext(NonCancellable) {
                try {
                    host.finishedRun(id)
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock {
                            busy.value -= id
                            requests.value -= id
                            recoveryReservations.remove(id)?.ready?.complete(Unit)
                        }
                        log.v { "Released the chat execution reservation" }
                    }
                }
            }
        }
    }

    private data class RecoveryReservation(
        val request: RequestId,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val isStopRequested: Boolean = false,
        val isStopRecorded: Boolean = false,
    )

    private data class RunLaunch(val job: Deferred<RunOutcome>, val release: CompletableDeferred<Unit>)

    private suspend fun awaitRun(job: Deferred<RunOutcome>, submission: StudioSubmissionGate?): RunOutcome =
        coroutineScope {
            val caller = currentCoroutineContext()
            val gate = launch(start = CoroutineStart.UNDISPATCHED) {
                submission?.awaitRevocation()?.let { job.cancel(it) }
            }
            try {
                job.await()
            } catch (e: StudioPreparationDropped) {
                caller.ensureActive()
                log.v { "Scheduled preparation dropped before native submission" }
                throw ScheduledWakeDroppedException().also { it.addSuppressed(e) }
            } catch (e: StudioPreparationDeferred) {
                // This cancellation belongs to the admission gate; cancellation of the caller still propagates.
                caller.ensureActive()
                log.v { "Scheduled preparation deferred before native submission" }
                throw ScheduledWakeDeferredException().also { it.addSuppressed(e) }
            } finally {
                gate.cancel()
                if (!caller.isActive) {
                    withContext(NonCancellable) {
                        if (submission?.cancel() == true) {
                            log.i { "Cancel scheduled preparation before native submission" }
                            job.cancel()
                        }
                    }
                }
            }
        }
}

/**
 * A scheduled caller may revoke preparation until native submission begins. The atomic handoff preserves profile
 * ownership when cancellation races a send: once submitted, even an unknown native outcome must be reconciled.
 * The owning feature may defer or drop Preparing, but cannot revoke Submitted. Begin checks admission again
 * after preparation.
 */
internal class StudioRunSubmission(private val admission: Flow<ScheduledWakeAdmission>? = null) :
    StudioSubmissionGate {
    private val log = Log.tag("StudioRunSubmission")
    private val phase = MutableStateFlow(Phase.Preparing)

    override val isCancelled: Boolean get() = phase.value in setOf(Phase.Cancelled, Phase.Deferred, Phase.Dropped)
    override val hasBegun: Boolean get() = phase.value == Phase.Submitted
    override val isNativeRetentionSupported: Boolean get() = true

    override suspend fun begin() {
        admission?.first()?.let(::revoke)
        if (!phase.compareAndSet(Phase.Preparing, Phase.Submitted)) {
            throw revocation() ?: CancellationException("Scheduled preparation was cancelled before native submission")
        }
        log.v { "Native submission took ownership of the scheduled run" }
    }

    /** Already accepted native work retains profile ownership independently of admission for new submissions. */
    override suspend fun retainNative(): Boolean {
        check(phase.compareAndSet(Phase.Preparing, Phase.Submitted)) { "Native recovery ownership was revoked" }
        log.v { "Recovered native turn retained profile ownership" }
        return false
    }

    override suspend fun cancel(): Boolean {
        val isCancelled = phase.compareAndSet(Phase.Preparing, Phase.Cancelled)
        log.v { "Scheduled preparation cancellation accepted=$isCancelled" }
        return isCancelled
    }

    override suspend fun awaitRevocation(): CancellationException? {
        val decisions = admission ?: return null
        val decision = combine(phase, decisions) { current, next -> current to next }
            .first { (current, next) -> current != Phase.Preparing || next != ScheduledWakeAdmission.Allow }.second
        revoke(decision)
        return revocation()
    }

    private fun revoke(decision: ScheduledWakeAdmission) {
        val next = when (decision) {
            ScheduledWakeAdmission.Allow -> return
            ScheduledWakeAdmission.Defer -> Phase.Deferred
            ScheduledWakeAdmission.Drop -> Phase.Dropped
        }
        val isRevoked = phase.compareAndSet(Phase.Preparing, next)
        log.v { "Scheduled preparation revocation accepted=$isRevoked" }
    }

    private fun revocation(): CancellationException? = when (phase.value) {
        Phase.Deferred -> StudioPreparationDeferred()
        Phase.Dropped -> StudioPreparationDropped()
        Phase.Preparing, Phase.Submitted, Phase.Cancelled -> null
    }

    private enum class Phase { Preparing, Submitted, Cancelled, Deferred, Dropped }
}

/** Internal admission cancellation, translated to the scheduler's retriable deferral after cleanup. */
private class StudioPreparationDeferred : CancellationException("Scheduled preparation deferred")

/** Internal owner cancellation, translated to permanent rejection after cleanup. */
private class StudioPreparationDropped : CancellationException("Scheduled preparation dropped")

/** Builds the exact turn envelope before reserving profile-owned execution. */
internal fun studioTurnRequest(
    sessionId: String,
    prompt: String,
    settings: RunSettings,
    kind: WorktreeRunKind,
    request: RequestId,
    attachments: List<ResourceRef> = emptyList(),
    submissionId: String = "",
    onAccepted: suspend () -> Unit = {},
    environment: StudioChatEnvironment? = null,
    isUserPrompt: Boolean = false,
    userInstruction: String? = null,
): StudioTurnRequest = StudioTurnRequest(sessionId, prompt, settings, kind, request, attachments, onAccepted)
    .copy(
        submissionId = submissionId,
        environment = environment,
        isUserPrompt = isUserPrompt,
        userInstruction = userInstruction,
        origin = if (isUserPrompt) StudioTurnOrigin.Conversation else StudioTurnOrigin.UnknownService,
    )
