package io.aequicor.heartbeat.feature.welcome.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.welcome.api.CinematicIntro
import io.aequicor.heartbeat.feature.welcome.impl.di.scope.WelcomeScope
import io.aequicor.heartbeat.feature.welcome.impl.domain.WelcomeSettings

/** Reads welcome preferences through the app-owned feature toggle service. */
@Inject
@ContributesBinding(WelcomeScope::class)
class LocalWelcomeSettings(private val toggles: FeatureToggles) : WelcomeSettings {
    override suspend fun isIntroEnabled(): Boolean = toggles.get(CinematicIntro)
}
