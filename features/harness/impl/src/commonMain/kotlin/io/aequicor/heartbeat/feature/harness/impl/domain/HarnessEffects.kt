package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import kotlinx.coroutines.delay
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

    private suspend fun remove(effect: HarnessEffect.Remove, machine: EffectScope<HarnessIntent>) {
        val removed = durable {
            if (awaitRemoval(effect) == HarnessRemovalResult.Ready) {
                storage.remove(effect.harness, effect.receipt)
            } else {
                null
            }
        }
        if (removed != null) machine.send(HarnessIntent.Internal.Removed(removed))
    }

    private suspend fun <T> durable(block: suspend () -> T): T {
        while (true) {
            try {
                return block()
            } catch (error: HarnessStorageUncertain) {
                log.w(error) { "storage outcome uncertain; retaining exact operation for retry" }
                delay(RETRY_DELAY)
            }
        }
    }

    private suspend fun awaitCleanup(block: suspend () -> Boolean) {
        while (!block()) {
            log.w { "runtime cleanup unconfirmed; retaining owned-work barrier for retry" }
            delay(RETRY_DELAY)
        }
    }

    private suspend fun awaitRemoval(effect: HarnessEffect.Remove): HarnessRemovalResult {
        var result = runtime.remove(effect)
        while (result == HarnessRemovalResult.Retry) {
            log.w { "runtime removal unconfirmed; retaining exact operation for retry" }
            delay(RETRY_DELAY)
            result = runtime.remove(effect)
        }
        return result
    }
}

private val RETRY_DELAY = 5.seconds
