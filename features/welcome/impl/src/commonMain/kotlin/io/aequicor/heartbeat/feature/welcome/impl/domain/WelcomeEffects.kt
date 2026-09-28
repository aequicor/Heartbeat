package io.aequicor.heartbeat.feature.welcome.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.welcome.api.WelcomeEffect
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent

/** Executes welcome business effects through the domain settings port and the profile sessions. */
class WelcomeEffects(private val settings: WelcomeSettings, private val profiles: ProfileSessions) :
    EffectHandler<WelcomeEffect, WelcomeIntent> {
    private val log = Log.tag("WelcomeEffects")

    override suspend fun handle(effect: WelcomeEffect, machine: EffectScope<WelcomeIntent>) {
        when (effect) {
            WelcomeEffect.ReadSettings -> machine.send(WelcomeIntent.Internal.Configured(settings.isIntroEnabled()))

            WelcomeEffect.OpenProfile -> {
                if (profiles.active.value == null) {
                    log.i { "Open local profile for studio" }
                    profiles.open(LocalProfile)
                }
                machine.send(WelcomeIntent.Internal.ProfileOpened)
            }
        }
    }

    private companion object {
        /**
         * The single device-local profile the studio uses until sign-in exists. `core:profile-facade` has no
         * default-profile API, so the welcome feature names it here.
         */
        val LocalProfile = ProfileId("local")
    }
}
