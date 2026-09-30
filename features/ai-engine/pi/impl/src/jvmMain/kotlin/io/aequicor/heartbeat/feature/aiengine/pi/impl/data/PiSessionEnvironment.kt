package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineLauncher

/** Profile services shared by all ephemeral native-session objects. */
@Inject
internal data class PiSessionEnvironment(
    val machines: MachineLauncher,
    val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) val profile: ScopeHandle,
    val dispatchers: DispatcherProvider,
    val toggles: FeatureToggles,
)
