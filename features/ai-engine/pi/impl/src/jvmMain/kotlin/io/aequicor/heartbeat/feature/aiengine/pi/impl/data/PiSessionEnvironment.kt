package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge

/** Profile services shared by all ephemeral native-session objects. */
@Inject
internal data class PiSessionEnvironment(
    val machines: MachineLauncher,
    val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) val profile: ScopeHandle,
    val dispatchers: DispatcherProvider,
    val toggles: FeatureToggles,
    val tools: ProfileAgentTools = NoAgentTools,
    val bridge: AgentToolBridge = UnavailableAgentToolBridge,
)
