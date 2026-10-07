package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** The coordinator retains this exact gate; cancel and native begin share the durable journal barrier. */
internal class StudioHelperSubmission(
    private val attempts: StudioHelperAttempts,
    private val helper: HelperId,
    private val request: RequestId,
    private val prior: StudioSubmissionGate? = null,
    private val admissions: StudioHelperPromptAdmissions? = null,
) : StudioSubmissionGate {
    private val log = Log.tag("StudioHelperSubmission")
    private val cancelled = MutableStateFlow(false)
    private val started = MutableStateFlow(false)
    private val claim = Uuid.random().toString()
    private var contextGuard: StudioHelperPromptGuard? = null
    override val isCancelled: Boolean get() = cancelled.value

    /** All production helper sends enter this scope, including manual turns in a marked chat. */
    suspend fun <T> withPreparation(session: SessionRef, workspace: WorkspaceRef? = null, block: suspend () -> T): T {
        check(started.compareAndSet(false, true)) { "Helper submission is already owned" }
        log.v { "Claimed helper preparation" }
        var guard: StudioHelperPromptGuard? = null
        try {
            val bound = attempts.bindSession(helper, request, session)
                ?: throw CancellationException("Helper preparation is revoked or already submitted")
            val source = checkNotNull(admissions) { "Helper prompt admission is unavailable" }
            val preparedGuard = StudioHelperPromptGuard(source.decisions(helper, session, bound, workspace))
            guard = preparedGuard
            contextGuard = preparedGuard
            return preparedGuard.watch(block)
        } finally {
            if (guard?.isSubmitted != true) {
                withContext(NonCancellable) {
                    cancelled.value = attempts.cancelBeforeNative(helper, request, session, claim)
                }
            }
        }
    }

    override suspend fun begin() = begin {}

    /** Final configuration refresh remains cancellable until this sender owns native submission. */
    suspend fun <T> begin(prepare: suspend () -> T): T {
        prior?.begin()
        if (!attempts.begin(helper, request, claim)) {
            throw CancellationException("Helper native submission is revoked or owned")
        }
        val guard = contextGuard
        return if (guard == null) prepare() else guard.submitted(prepare)
    }

    override suspend fun cancel(): Boolean {
        prior?.cancel()
        val receipt = attempts.cancelBeforeSubmission(helper, request)
        val isRevoked = receipt.phase == StudioHelperPhase.NotSubmitted
        log.v { "Helper preparation revocation confirmed=$isRevoked" }
        cancelled.value = isRevoked
        return isRevoked
    }

    fun matches(helper: HelperId, request: RequestId): Boolean = this.helper == helper && this.request == request

    override suspend fun awaitRevocation(): CancellationException? =
        if (attempts.awaitSubmissionBoundary(helper, request) == StudioHelperPhase.NotSubmitted) {
            log.v { "Observed durable helper preparation revocation" }
            cancelled.value = true
            CancellationException("Helper preparation was revoked")
        } else {
            null
        }
}
