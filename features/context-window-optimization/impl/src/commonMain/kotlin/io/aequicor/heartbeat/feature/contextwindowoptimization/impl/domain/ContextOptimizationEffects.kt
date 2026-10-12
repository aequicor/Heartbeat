package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedger
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEffect
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Storage of the profile's context optimization settings and progress ledgers. */
internal interface ContextSettingsStore {
    /** Stored settings, or null when nothing was saved. */
    suspend fun load(): ContextOptimizationSettings?

    /** Replaces the stored settings. */
    suspend fun save(settings: ContextOptimizationSettings)

    /** Stored ledgers by session. */
    suspend fun loadLedgers(): Map<SessionRef, ContextLedger> = emptyMap()

    /** Replaces the stored ledgers. */
    suspend fun saveLedgers(ledgers: Map<SessionRef, ContextLedger>) = Unit
}

/**
 * Executes the effects of the context optimization machine; failures are mapped by the spec's `onEffectFailure`.
 * Saves run one at a time and a save older than the last written revision is dropped. Ledgers untouched for
 * [ledgerLifetime] are forgotten when the profile loads, and at most [MAX_LEDGERS] of the newest are kept.
 */
internal class ContextOptimizationEffects(
    private val store: ContextSettingsStore,
    private val clock: Clock = Clock.System,
    private val ledgerLifetime: Duration = LEDGER_LIFETIME,
) : EffectHandler<ContextOptimizationEffect, ContextOptimizationIntent> {
    private val log = Log.tag("ContextOptimizationEffects")
    private val saving = Mutex()
    private var savedRevision = -1L
    private var savedLedgerRevision = -1L

    // Stored ledgers that could not be read are never overwritten by this run's ledgers.
    private var isLedgerStoreUnreadable = false

    override suspend fun handle(effect: ContextOptimizationEffect, machine: EffectScope<ContextOptimizationIntent>) {
        when (effect) {
            ContextOptimizationEffect.Load -> {
                val settings = store.load()
                val stored = try {
                    store.loadLedgers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "stored ledgers are unreadable; they are kept and not overwritten" }
                    isLedgerStoreUnreadable = true
                    emptyMap()
                }
                val ledgers = stored.recent()
                log.i { "context settings loaded: stored=${settings != null} ledgers=${ledgers.size}/${stored.size}" }
                machine.send(ContextOptimizationIntent.Internal.Loaded(settings, ledgers))
            }

            is ContextOptimizationEffect.Save -> saving.withLock {
                if (effect.revision <= savedRevision) {
                    log.d { "stale context settings save skipped: ${effect.revision} <= $savedRevision" }
                } else {
                    store.save(effect.settings)
                    savedRevision = effect.revision
                    log.i {
                        "context settings saved: threshold=${effect.settings.thresholdPercent} " +
                            "strategy=${effect.settings.strategy} revision=${effect.revision}"
                    }
                }
            }

            is ContextOptimizationEffect.SaveLedgers -> saving.withLock {
                if (isLedgerStoreUnreadable) {
                    log.w { "ledger save skipped: the stored ledgers could not be read" }
                } else if (effect.revision <= savedLedgerRevision) {
                    log.d { "stale ledger save skipped: ${effect.revision} <= $savedLedgerRevision" }
                } else {
                    store.saveLedgers(effect.ledgers)
                    savedLedgerRevision = effect.revision
                    log.i { "ledgers saved: ${effect.ledgers.size}, revision ${effect.revision}" }
                }
            }
        }
    }

    private fun Map<SessionRef, ContextLedger>.recent(): Map<SessionRef, ContextLedger> {
        val oldest = clock.now() - ledgerLifetime
        return entries.filter { it.value.updatedAt >= oldest }
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_LEDGERS)
            .associate { it.key to it.value }
    }

    private companion object {
        val LEDGER_LIFETIME = 30.days
        const val MAX_LEDGERS = 200
    }
}
