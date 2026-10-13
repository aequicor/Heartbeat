package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps live policy and native feedback in the machine while its running state remains active.
 *
 * Request-scoped effects answer exactly once. Results and recovered failures are sent here; a cancellation
 * raised inside a still-active effect (a timeout, a native call revoked by [BrowserEffect.CancelOperation])
 * propagates, and the runtime answers it once through the spec's `onEffectFailure`. Cancellation of the
 * effect itself needs no answer: it only happens when the machine stops and would drop any feedback.
 */
internal class BrowserEffects(private val availability: BrowserAvailability, private val session: BrowserSession) :
    EffectHandler<BrowserEffect, BrowserIntent> {
    private val log = Log.tag("BrowserEffects")

    override suspend fun handle(effect: BrowserEffect, machine: EffectScope<BrowserIntent>) {
        when (effect) {
            is BrowserEffect.Perform -> perform(effect, machine)

            is BrowserEffect.SelectTab -> selectTab(effect.id)

            is BrowserEffect.CancelOperation -> cancelOperation(effect.id, machine)

            BrowserEffect.Observe -> observe(machine)

            is BrowserEffect.Reobserve -> {
                delay(browserObserverRetryDelay(effect.attempt))
                log.i { "Restarting browser observation, attempt ${effect.attempt}" }
                observe(machine)
            }

            is BrowserEffect.Load -> session.execute(BrowserCommand.Load(effect.url))

            BrowserEffect.Back -> session.execute(BrowserCommand.Back)

            BrowserEffect.Forward -> session.execute(BrowserCommand.Forward)

            BrowserEffect.Reload -> session.execute(BrowserCommand.Reload)

            BrowserEffect.Stop -> session.execute(BrowserCommand.Stop)
        }
    }

    /**
     * Observation ending abnormally while the effect is still active revokes the session first: a dead
     * toggle observer must not leave it enabled. The spec then restarts observation after a backoff.
     */
    private suspend fun observe(machine: EffectScope<BrowserIntent>) {
        val effect = currentCoroutineContext().job
        var isFailed = true
        try {
            coroutineScope {
                launch {
                    session.tabs.collect { (tabs, selected) ->
                        machine.send(BrowserIntent.Internal.TabsChanged(tabs, selected))
                    }
                }
                launch {
                    session.pages.collect {
                        machine.send(BrowserIntent.Internal.PageChanged(it))
                    }
                }
                availability.observe().collect { enabled ->
                    session.setEnabled(enabled)
                    machine.send(BrowserIntent.Internal.AvailabilityChanged(enabled))
                    // Restore can publish before the machine is enabled; seed its selected page after availability.
                    if (enabled) machine.send(BrowserIntent.Internal.PageChanged(session.pages.first()))
                }
            }
            isFailed = false
        } finally {
            if (isFailed && effect.isActive) withContext(NonCancellable) { revokeAfterObserverFailure() }
        }
    }

    private suspend fun revokeAfterObserverFailure() {
        try {
            session.setEnabled(false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error.redactedFailure()) { "Browser session could not be revoked after observer failure" }
        }
    }

    private suspend fun selectTab(id: String) {
        try {
            session.selectTab(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Browser tab selection failed" }
        }
    }

    /** The owner waits for the acknowledgement, so it is sent even when cancellation itself failed. */
    private suspend fun cancelOperation(id: String, machine: EffectScope<BrowserIntent>) {
        try {
            session.cancelOperation(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Browser operation cancellation failed" }
        }
        machine.send(BrowserIntent.Internal.OperationCancelled(id))
    }

    private suspend fun perform(effect: BrowserEffect.Perform, machine: EffectScope<BrowserIntent>) {
        val result = try {
            session.perform(effect.request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Browser operation failed" }
            BrowserResult(effect.request.id, error = "Browser operation failed")
        }
        machine.send(BrowserIntent.Internal.Completed(result))
    }
}

/** Exponential backoff for restarting observation: 1 s, 2 s, 4 s … capped at one minute. */
internal fun browserObserverRetryDelay(attempt: Int): Duration =
    (1 shl (attempt.coerceIn(1, MAX_BACKOFF_SHIFT) - 1)).seconds.coerceAtMost(MAX_OBSERVER_RETRY_DELAY)

private const val MAX_BACKOFF_SHIFT = 7
private val MAX_OBSERVER_RETRY_DELAY = 60.seconds
