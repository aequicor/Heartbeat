package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperProgress
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native work stays in the repository; this port never treats a generic RunOutcome as terminal evidence. */
internal interface StudioHelperRuns {
    suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend () -> Unit,
    )

    suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal?
    suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal?
    suspend fun helperProgress(helper: HelperId, request: RequestId): HelperProgress? = null
}

/** One profile owns attempts even when the caller stops awaiting acceptance. Durable receipts prevent replay. */
@SingleIn(ProfileScope::class)
@Inject
internal class EngineStudioHelperChats(
    private val records: StudioHelperChatRecords,
    private val attempts: StudioHelperAttempts,
    private val admissions: StudioHelperPromptAdmissions,
    private val runs: Lazy<StudioHelperRuns>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("StudioHelperChats")
    private val lock = Mutex()
    private val pending = mutableMapOf<Pair<HelperId, RequestId>, CompletableDeferred<HelperSubmission>>()

    suspend fun prompt(helper: HelperId, prompt: HelperPrompt): HelperSubmission {
        checkNotNull(records.helperMetadata(helper)) { "Unknown helper" }
        val key = helper to prompt.request
        val accepted = lock.withLock {
            check(!profile.isClosed) { "Profile is closed" }
            val prepared = attempts.prepare(helper, prompt)
            pending[key]?.let { return@withLock it }
            if (!prepared.isNew) {
                return@withLock CompletableDeferred<HelperSubmission>().apply {
                    complete(checkNotNull(prepared.receipt.submission()) { "Helper acceptance is unresolved" })
                }
            }
            val answer = CompletableDeferred<HelperSubmission>()
            pending[key] = answer
            profile.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { execute(helper, prompt, answer) }
            answer
        }
        return accepted.await()
    }

    private suspend fun execute(helper: HelperId, prompt: HelperPrompt, answer: CompletableDeferred<HelperSubmission>) {
        try {
            runs.value.runHelper(
                helper,
                prompt,
                StudioHelperSubmission(attempts, helper, prompt.request, admissions = admissions),
            ) {
                val receipt = checkNotNull(attempts.receipt(helper, prompt.request))
                answer.complete(checkNotNull(receipt.submission()) { "Missing helper acceptance receipt" })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Helper operation failed (${error::class.simpleName.orEmpty()})")) {
                "Helper execution unavailable"
            }
        } finally {
            withContext(NonCancellable) { settle(helper, prompt.request, answer) }
        }
    }

    private suspend fun settle(helper: HelperId, request: RequestId, answer: CompletableDeferred<HelperSubmission>) {
        try {
            attempts.cancelBeforeSubmission(helper, request).submission()?.let { answer.complete(it) }
        } finally {
            if (!answer.isCompleted) {
                answer.completeExceptionally(
                    IllegalStateException("Helper acceptance is unresolved"),
                )
            }
            val key = helper to request
            withContext(NonCancellable) {
                lock.withLock { if (pending[key] === answer) pending.remove(key) }
            }
        }
    }

    suspend fun result(helper: HelperId, request: RequestId): HelperResult? {
        checkNotNull(records.helperMetadata(helper)) { "Unknown helper" }
        val receipt = attempts.receipt(helper, request)
        return when {
            receipt?.terminal != null -> receipt.terminal.result(request)

            receipt?.phase in NATIVE_PHASES -> runs.value.helperTerminal(helper, request)?.let {
                attempts.terminal(helper, request, it)
                checkNotNull(attempts.receipt(helper, request)?.terminal).result(request)
            }

            else -> null
        }
    }

    suspend fun cancel(helper: HelperId, request: RequestId): HelperCancellation {
        checkNotNull(records.helperMetadata(helper)) { "Unknown helper" }
        val receipt = attempts.cancelBeforeSubmission(helper, request)
        return when {
            receipt.phase == StudioHelperPhase.NotSubmitted -> HelperCancellation.NotSubmitted(request)

            receipt.terminal != null -> HelperCancellation.Terminal(receipt.terminal.result(request))

            else -> {
                // Stop does not await prompt's acceptance: its ACK may never arrive.
                val terminal = runs.value.stopHelper(helper, request)
                if (terminal == null) {
                    HelperCancellation.Unconfirmed(request)
                } else {
                    attempts.terminal(helper, request, terminal)
                    HelperCancellation.Terminal(
                        checkNotNull(attempts.receipt(helper, request)?.terminal).result(request),
                    )
                }
            }
        }
    }

    suspend fun progress(helper: HelperId, request: RequestId): HelperProgress? {
        val metadata = checkNotNull(records.helperMetadata(helper)) { "Unknown helper" }
        val receipt = attempts.receipt(helper, request) ?: return null
        if (receipt.phase != StudioHelperPhase.Accepted) return null
        return runs.value.helperProgress(helper, request)?.also {
            check(it.request == request && it.session == receipt.session && it.session == metadata.session) {
                "Helper progress does not match its accepted receipt"
            }
        }
    }

    private fun StudioHelperReceipt.submission(): HelperSubmission? = when (phase) {
        StudioHelperPhase.NotSubmitted -> HelperSubmission.NotSubmitted(request)

        StudioHelperPhase.Accepted, StudioHelperPhase.Terminal ->
            HelperSubmission.Accepted(request, checkNotNull(session))

        StudioHelperPhase.Preparing, StudioHelperPhase.Submitting -> null
    }

    private companion object {
        val NATIVE_PHASES = setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted)
    }
}
