package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Profile-owned IO. Uncertain writes retain their exact effect and never become false rollback receipts. */
internal class HarnessEffects(private val storage: HarnessLibraryStorage, private val runtime: HarnessRuntimeControl) :
    EffectHandler<HarnessEffect, HarnessIntent> {
    private val log = Log.tag("HarnessEffects")

    override suspend fun handle(effect: HarnessEffect, machine: EffectScope<HarnessIntent>) {
        when (effect) {
            is HarnessEffect.Load -> {
                val stored = durable { storage.load() }
                machine.send(
                    HarnessIntent.Internal.Loaded(
                        effect.load,
                        stored.harnesses,
                        stored.attachments,
                        stored.approval,
                        stored.approvalRevision,
                        runtime.isAvailable,
                    ),
                )
            }

            is HarnessEffect.Save -> machine.send(
                HarnessIntent.Internal.Saved(
                    durable { storage.save(effect.harness, effect.receipt) },
                ),
            )

            is HarnessEffect.Remove -> remove(effect, machine)

            is HarnessEffect.SaveAttachments -> machine.send(
                HarnessIntent.Internal.AttachmentsSaved(
                    durable { storage.saveAttachments(effect.write) },
                ),
            )

            is HarnessEffect.SaveApproval -> machine.send(
                HarnessIntent.Internal.ApprovalSaved(
                    durable { storage.saveApproval(effect.write) },
                ),
            )

            is HarnessEffect.Activate -> effect.items.forEach { request ->
                val feedback = if (runtime.isAvailable && runtime.activate(request)) {
                    HarnessIntent.Internal.ItemActivated(
                        request.harness.id,
                        request.item.id,
                        request.harness.revision,
                        request.generation,
                    )
                } else {
                    HarnessIntent.Internal.ItemActivationFailed(
                        request.harness.id,
                        request.item.id,
                        request.harness.revision,
                        request.generation,
                    )
                }
                machine.send(feedback)
            }

            is HarnessEffect.Deactivate -> awaitCleanup { durable { runtime.deactivate(effect) } }
        }
    }

    /**
     * Removal waits for author code to drain only for [REMOVAL_DRAIN_LIMIT]. A callback that never returns would
     * otherwise keep the receipt pending (and every library mutation Busy) until restart; instead the deletion is
     * rolled back through RemoveFailed, which restores the harness at a new generation. Retired code keeps its own
     * artifact lease until it actually drains.
     */
    private suspend fun remove(effect: HarnessEffect.Remove, machine: EffectScope<HarnessIntent>) {
        val drain = RemovalDrain()
        val removed = durable {
            when (awaitRemoval(effect, drain)) {
                HarnessRemovalResult.Ready -> storage.remove(effect.harness, effect.receipt)
                HarnessRemovalResult.Retry, HarnessRemovalResult.Obsolete -> null
            }
        }
        when {
            removed != null -> machine.send(HarnessIntent.Internal.Removed(removed))

            drain.isExpired -> {
                log.w { "runtime removal did not drain in time; rolling back the deletion" }
                machine.send(HarnessIntent.Internal.RemoveFailed(effect.receipt))
            }
        }
    }

    /** First uncertainty is a warning; repeats are verbose and back off, while the exact operation is retained. */
    private suspend fun <T> durable(block: suspend () -> T): T {
        var wait = RETRY_DELAY
        var isReported = false
        while (true) {
            try {
                return block()
            } catch (error: HarnessStorageUncertain) {
                if (isReported) {
                    log.v { "storage outcome still uncertain; retrying the exact operation" }
                } else {
                    log.w(error) { "storage outcome uncertain; retaining exact operation for retry" }
                    isReported = true
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    /** Draining owned work is expected: debug first, a single warning once it becomes long, verbose afterwards. */
    private suspend fun awaitCleanup(block: suspend () -> Boolean) {
        var wait = RETRY_DELAY
        var waited = Duration.ZERO
        var isReported = false
        while (!block()) {
            when {
                waited == Duration.ZERO -> log.d { "runtime cleanup unconfirmed; retaining owned-work barrier" }

                waited >= SLOW_CLEANUP && !isReported -> {
                    log.w { "runtime cleanup is still unconfirmed; retaining owned-work barrier for retry" }
                    isReported = true
                }

                else -> log.v { "runtime cleanup still unconfirmed" }
            }
            delay(wait)
            waited += wait
            wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY)
        }
    }

    private suspend fun awaitRemoval(effect: HarnessEffect.Remove, drain: RemovalDrain): HarnessRemovalResult {
        var result = runtime.remove(effect)
        while (result == HarnessRemovalResult.Retry) {
            if (drain.waited >= REMOVAL_DRAIN_LIMIT) {
                drain.isExpired = true
                return result
            }
            if (drain.waited == Duration.ZERO) {
                log.d { "runtime removal awaits owned work; retaining exact operation" }
            } else {
                log.v { "runtime removal still awaits owned work" }
            }
            delay(RETRY_DELAY)
            drain.waited += RETRY_DELAY
            result = runtime.remove(effect)
        }
        return result
    }
}

/** Drain time shared by retries of one removal effect, including retries after uncertain storage reads. */
private class RemovalDrain {
    var waited: Duration = Duration.ZERO
    var isExpired: Boolean = false
}

private val RETRY_DELAY = 5.seconds
private val MAX_RETRY_DELAY = 1.minutes
private val SLOW_CLEANUP = 1.minutes
internal val REMOVAL_DRAIN_LIMIT = 2.minutes
