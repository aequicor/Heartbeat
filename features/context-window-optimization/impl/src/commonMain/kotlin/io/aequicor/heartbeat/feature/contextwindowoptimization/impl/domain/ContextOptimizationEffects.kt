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

/** Storage of the profile's context optimization settings. */
internal interface ContextSettingsStore {
    /** Stored settings, or null when nothing was saved. */
    suspend fun load(): ContextOptimizationSettings?

    /** Replaces the stored settings. */
    suspend fun save(settings: ContextOptimizationSettings)
}

/** Storage of the progress ledgers, one record per session. */
internal interface ContextLedgerStore {
    /** Stored ledgers by session; a record that cannot be read is skipped and left untouched. */
    suspend fun load(): Map<SessionRef, ContextLedger>

    /** Writes the ledger of [session] only. */
    suspend fun save(session: SessionRef, ledger: ContextLedger)

    /** Deletes the ledger of [session] only. */
    suspend fun delete(session: SessionRef)
}

/**
 * Executes the effects of the context optimization machine; failures are mapped by the spec's `onEffectFailure`.
 * Settings and ledgers load independently: an unreadable one does not keep the other from loading. Saves run one at a
 * time; a settings save older than the last written revision, and a ledger save older than the last written revision
 * of its session, are dropped. Every ledger save writes only its session's record, so it never touches the others.
 */
internal class ContextOptimizationEffects(
    private val settings: ContextSettingsStore,
    private val ledgers: ContextLedgerStore,
) : EffectHandler<ContextOptimizationEffect, ContextOptimizationIntent> {
    private val log = Log.tag("ContextOptimizationEffects")
    private val saving = Mutex()
    private var savedRevision = -1L
    private val savedLedgerRevisions = mutableMapOf<SessionRef, Long>()

    // When the stored ledgers could not be read at all, this run never replaces them with its own.
    private var isLedgerStoreUnreadable = false

    override suspend fun handle(effect: ContextOptimizationEffect, machine: EffectScope<ContextOptimizationIntent>) {
        when (effect) {
            ContextOptimizationEffect.Load -> {
                val stored = loadOrNull("settings") { settings.load() }
                val loaded = loadOrNull("ledgers") { ledgers.load() }
                isLedgerStoreUnreadable = loaded == null
                val ledgers = loaded.orEmpty()
                log.i { "context settings loaded: stored=${stored != null} ledgers=${ledgers.size}" }
                machine.send(ContextOptimizationIntent.Internal.Loaded(stored, ledgers))
            }

            is ContextOptimizationEffect.Save -> saving.withLock {
                if (effect.revision <= savedRevision) {
                    log.d { "stale context settings save skipped: ${effect.revision} <= $savedRevision" }
                } else {
                    settings.save(effect.settings)
                    savedRevision = effect.revision
                    log.i {
                        "context settings saved: threshold=${effect.settings.thresholdPercent} " +
                            "strategy=${effect.settings.strategy} revision=${effect.revision}"
                    }
                }
            }

            is ContextOptimizationEffect.SaveLedger -> saving.withLock {
                val saved = savedLedgerRevisions[effect.session] ?: -1L
                if (isLedgerStoreUnreadable) {
                    log.w { "ledger save skipped: the stored ledgers could not be read" }
                } else if (effect.revision <= saved) {
                    log.v { "stale ledger save skipped: ${effect.revision} <= $saved" }
                } else {
                    val ledger = effect.ledger
                    if (ledger == null) ledgers.delete(effect.session) else ledgers.save(effect.session, ledger)
                    savedLedgerRevisions[effect.session] = effect.revision
                    log.v { "ledger saved: deleted=${ledger == null} revision=${effect.revision}" }
                }
            }
        }
    }

    private suspend fun <T> loadOrNull(what: String, load: suspend () -> T): T? = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "stored context $what are unreadable; they are left as they are" }
        null
    }
}
