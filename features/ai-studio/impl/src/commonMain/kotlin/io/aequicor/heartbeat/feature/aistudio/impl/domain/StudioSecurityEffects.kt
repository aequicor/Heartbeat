package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityEffect
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent

/** Serial writes are enforced by the machine; storage failures are logged and mapped by the machine runtime. */
internal class StudioSecurityEffects(private val settings: StudioSecurityStore) :
    EffectHandler<StudioSecurityEffect, StudioSecurityIntent> {
    private val log = Log.tag("StudioSecurityEffects")

    override suspend fun handle(effect: StudioSecurityEffect, machine: EffectScope<StudioSecurityIntent>) {
        when (effect) {
            StudioSecurityEffect.Load -> {
                val level = settings.load()
                log.i { "Studio security settings loaded: level=${level ?: "default"}" }
                machine.send(StudioSecurityIntent.Internal.Loaded(level))
            }

            is StudioSecurityEffect.Save -> {
                settings.save(effect.level)
                log.i { "Studio security settings saved: level=${effect.level}" }
                machine.send(StudioSecurityIntent.Internal.Saved)
            }
        }
    }
}
