package io.aequicor.heartbeat.feature.agentlearning.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedScope
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningRoute
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningComponent
import io.aequicor.heartbeat.feature.agentlearning.impl.ui.AgentLearningUiComponent

/** The registry of learned instructions, reachable as a profile route from the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AgentLearningRouteEntry(
    private val factory: AgentLearningComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<AgentLearningRoute>(AgentLearningRoute::class, AgentLearningRoute.serializer()) {
    override fun create(route: AgentLearningRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "agent-learning")
        return AgentLearningUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
