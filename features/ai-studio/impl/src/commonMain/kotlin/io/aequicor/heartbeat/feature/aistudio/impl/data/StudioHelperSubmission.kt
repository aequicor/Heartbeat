package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.isRejection
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** The coordinator retains this exact gate; cancel and native begin share the durable journal barrier. */
internal class StudioHelperSubmission(
    private val attempts: StudioHelperAttempts,
    private val admissions: StudioHelperPromptAdmissions,
    private val helper: HelperId,
    private val request: RequestId,
    private val prior: StudioSubmissionGate? = null,
) : StudioSubmissionGate {
    private val log = Log.tag("StudioHelperSubmission")
    private val cancelled = MutableStateFlow(false)
    private val started = MutableStateFlow(false)
    private val claim = Uuid.random().toString()
    private var contextGuard: StudioHelperPromptGuard? = null
    private var boundSession: SessionRef? = null
    override val isCancelled: Boolean get() = cancelled.value

    /** All production helper sends enter this scope, including manual turns in a marked chat. */
    suspend fun <T> withPreparation(session: SessionRef, workspace: WorkspaceRef?, block: suspend () -> T): T {
        check(started.compareAndSet(false, true)) { "Helper submission is already owned" }
        log.v { "Claimed helper preparation" }
        var guard: StudioHelperPromptGuard? = null
        try {
            val bound = attempts.bindSession(helper, request, session)
                ?: throw CancellationException("Helper preparation is revoked or already submitted")
            boundSession = session
            val preparedGuard = StudioHelperPromptGuard(admissions.decisions(helper, session, bound, workspace))
            guard = preparedGuard
            contextGuard = preparedGuard
            return preparedGuard.watch {
                coroutineScope {
                    val preparation = this
                    val revocation = launch(start = CoroutineStart.UNDISPATCHED) {
                        awaitRevocation()?.let { preparation.cancel(it) }
                    }
                    try {
                        block()
                    } finally {
                        revocation.cancel()
                    }
                }
            }
        } finally {
            if (guard?.isSubmitted != true) {
                withContext(NonCancellable) {
                    cancelled.value = attempts.cancelBeforeNative(helper, request, session, claim)
                }
            }
        }
    }

    override suspend fun begin() = begin {}

    /**
     * Final configuration refresh remains cancellable until this sender owns native submission. The helper's
     * durable boundary is crossed first: a revoked receipt must leave a prior gate (a scheduled wake) unsubmitted.
     */
    suspend fun <T> begin(prepare: suspend () -> T): T {
        if (!attempts.begin(helper, request, claim)) {
            throw CancellationException("Helper native submission is revoked or owned")
        }
        var isPriorBegun = false
        try {
            prior?.begin()
            isPriorBegun = true
        } finally {
            // A refusing prior gate retires this sender's own claim before its failure propagates.
            if (!isPriorBegun) withContext(NonCancellable) { retire() }
        }
        val guard = contextGuard
        return if (guard == null) prepare() else guard.submitted(prepare)
    }

    /**
     * Enters the native send. Only an engine failure that proves rejection ([isRejection]) retires the receipt as
     * NotSubmitted; a failure that may follow delivery (closed profile, timeout, crash) keeps acceptance unresolved.
     */
    suspend fun <T> send(native: suspend () -> T): T = try {
        if (!enterNative()) {
            throw CancellationException("The conversation native entry was revoked or already owned")
        }
        native()
    } catch (e: EngineException) {
        if (e.failure.isRejection()) {
            val isRetired = withContext(NonCancellable) { retire() }
            log.i { "Helper native send refused before acceptance; not submitted=$isRetired" }
        }
        throw e
    }

    override suspend fun cancel(): Boolean {
        prior?.cancel()
        // This gate's own rollback: a later delivery of the same request may prepare it again.
        val receipt = attempts.cancelBeforeSubmission(helper, request, isFinal = false)
        val isRevoked = receipt.phase == StudioHelperPhase.NotSubmitted
        log.v { "Helper preparation revocation confirmed=$isRevoked" }
        cancelled.value = isRevoked
        return isRevoked
    }

    fun matches(helper: HelperId, request: RequestId): Boolean = this.helper == helper && this.request == request

    /** Exact immutable request provenance; a missing journal is never an ordinary conversation fallback. */
    suspend fun policy(): StudioAttemptPolicy? =
        checkNotNull(attempts.receipt(helper, request)) { "Conversation request evidence is unavailable" }.policy

    override suspend fun awaitRevocation(): CancellationException? =
        if (attempts.awaitNativeEntryBoundary(helper, request) == StudioHelperPhase.NotSubmitted) {
            log.v { "Observed durable helper preparation revocation" }
            cancelled.value = true
            CancellationException("Helper preparation was revoked")
        } else {
            null
        }

    /** Only this sender's own claim can be retired, and only while no native acceptance was journaled. */
    private suspend fun retire(): Boolean = attempts.cancelBeforeNative(helper, request, boundSession, claim)

    /** A lost entry-write acknowledgement still precedes native(), so this exact sender can retire its claim. */
    private suspend fun enterNative(): Boolean {
        var isAcknowledged = false
        try {
            val isEntered = attempts.enterNative(helper, request, claim)
            isAcknowledged = true
            return isEntered
        } finally {
            if (!isAcknowledged) withContext(NonCancellable) { retire() }
        }
    }
}
