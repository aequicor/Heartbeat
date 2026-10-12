package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEffect
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Storage of the profile's context optimization settings. */
internal interface ContextSettingsStore {
    /** Stored settings, or null when nothing was saved. */
    suspend fun load(): ContextOptimizationSettings?

    /** Replaces the stored settings. */
    suspend fun save(settings: ContextOptimizationSettings)
}

/**
 * Executes the effects of the context optimization machine; failures are mapped by the spec's `onEffectFailure`.
 * Saves run one at a time and a save older than the last written revision is dropped.
 */
internal class ContextOptimizationEffects(private val store: ContextSettingsStore) :
    EffectHandler<ContextOptimizationEffect, ContextOptimizationIntent> {
    private val log = Log.tag("ContextOptimizationEffects")
    private val saving = Mutex()
    private var savedRevision = -1L

    override suspend fun handle(effect: ContextOptimizationEffect, machine: EffectScope<ContextOptimizationIntent>) {
        when (effect) {
            ContextOptimizationEffect.Load -> {
                val settings = store.load()
                log.i { "context settings loaded: stored=${settings != null}" }
                machine.send(ContextOptimizationIntent.Internal.Loaded(settings))
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
        }
    }
}
