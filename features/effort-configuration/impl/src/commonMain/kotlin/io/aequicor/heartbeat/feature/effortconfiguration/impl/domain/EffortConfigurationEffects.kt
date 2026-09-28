package io.aequicor.heartbeat.feature.effortconfiguration.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationEffect
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Storage of effort choices owned by the profile. */
internal interface EffortChoices {
    /** Stored choices; empty when nothing was saved. */
    suspend fun load(): List<EffortChoice>

    /** Replaces stored choices. */
    suspend fun save(choices: List<EffortChoice>)
}

/**
 * Executes effort effects; failures are mapped by the spec's `onEffectFailure`. While [isPersistent] is false the
 * store is neither read nor written, so choices stay in the machine only. Saves run one at a time and a save older
 * than the last written revision is dropped, so concurrent effects never leave a stale selection in the store.
 */
internal class EffortConfigurationEffects(
    private val store: EffortChoices,
    private val isPersistent: suspend () -> Boolean,
) : EffectHandler<EffortConfigurationEffect, EffortConfigurationIntent> {

    private val log = Log.tag("EffortConfigurationEffects")
    private val saving = Mutex()
    private var savedRevision = -1L

    override suspend fun handle(effect: EffortConfigurationEffect, machine: EffectScope<EffortConfigurationIntent>) {
        when (effect) {
            EffortConfigurationEffect.Load -> {
                val choices = if (isPersistent()) store.load() else emptyList()
                log.i { "effort choices loaded: ${choices.size}" }
                machine.send(EffortConfigurationIntent.Internal.Loaded(choices))
            }

            is EffortConfigurationEffect.Save -> saving.withLock {
                when {
                    effect.revision <= savedRevision ->
                        log.d { "stale effort save skipped: ${effect.revision} <= $savedRevision" }

                    isPersistent() -> {
                        store.save(effect.choices)
                        savedRevision = effect.revision
                        log.i { "effort choices saved: ${effect.choices.size}, revision ${effect.revision}" }
                    }

                    else -> log.d { "effort choices kept in memory: persistence toggle is off" }
                }
            }
        }
    }
}
