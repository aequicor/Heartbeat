package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** A false decision permanently revokes this preparation, including while its durable begin write is pending. */
internal class StudioHelperPromptGuard(private val decisions: Flow<Boolean>) {
    private val phase = MutableStateFlow(Phase.Preparing)
    private val log = Log.tag("StudioHelperPromptGuard")
    val isSubmitted: Boolean get() = phase.value == Phase.Submitted

    suspend fun <T> watch(block: suspend () -> T): T = coroutineScope {
        val preparation = this
        val initial = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observe(preparation, initial)
        }
        try {
            check(
                withTimeoutOrNull(2.seconds) {
                    initial.await()
                    true
                } == true,
            ) { "Helper admission timed out" }
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            revoke()
            observer.cancel()
        }
    }

    private suspend fun observe(preparation: CoroutineScope, initial: CompletableDeferred<Unit>) {
        try {
            decisions.onCompletion { error ->
                if (error is CancellationException && currentCoroutineContext().isActive && revoke()) {
                    preparation.cancel(error)
                }
            }.collect { allowed ->
                if (!allowed && revoke()) {
                    preparation.cancel(
                        CancellationException("Helper context admission was revoked"),
                    )
                }
                initial.complete(Unit)
            }
            check(initial.isCompleted) { "Helper context supplied no initial decision" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                IllegalStateException("Helper admission observer failed (${error::class.simpleName.orEmpty()})"),
            ) {
                "Helper admission observer unavailable"
            }
            if (revoke()) preparation.cancel(CancellationException("Helper context observer failed"))
        }
    }

    /** Called after the durable Submitting write. Only this successful handoff permits entering native send. */
    suspend fun submitted() = submitted {}

    /** Refreshes native configuration after admission IO while revocation still owns preparation. */
    suspend fun <T> submitted(prepare: suspend () -> T): T {
        val isAllowed = withTimeoutOrNull(2.seconds) { decisions.first() } == true
        currentCoroutineContext().ensureActive()
        if (!isAllowed) revoke()
        if (phase.value != Phase.Preparing) throw CancellationException("Helper context admission was revoked")
        val prepared = prepare()
        currentCoroutineContext().ensureActive()
        if (!phase.compareAndSet(Phase.Preparing, Phase.Submitted)) {
            throw CancellationException("Helper context admission was revoked before submission")
        }
        log.v { "Native sender took ownership of the prepared helper context" }
        return prepared
    }

    private fun revoke(): Boolean {
        val isRevoked = phase.compareAndSet(Phase.Preparing, Phase.Revoked)
        if (isRevoked) log.v { "Helper preparation context revoked" }
        return isRevoked
    }

    private enum class Phase { Preparing, Revoked, Submitted }
}
