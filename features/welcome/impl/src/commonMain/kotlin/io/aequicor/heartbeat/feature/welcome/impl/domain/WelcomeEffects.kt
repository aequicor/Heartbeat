package io.aequicor.heartbeat.feature.welcome.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.welcome.api.WelcomeEffect
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent

/** Executes welcome business effects through the domain settings port. */
class WelcomeEffects(private val settings: WelcomeSettings) : EffectHandler<WelcomeEffect, WelcomeIntent> {
    override suspend fun handle(effect: WelcomeEffect, machine: EffectScope<WelcomeIntent>) {
        when (effect) {
            WelcomeEffect.ReadSettings -> machine.send(WelcomeIntent.Internal.Configured(settings.isIntroEnabled()))
        }
    }
}
