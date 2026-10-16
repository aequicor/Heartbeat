package io.aequicor.heartbeat.feature.adaptivesubagents.impl.di

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
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfileRoute
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ProfileEditorComponent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui.ProfileEditorUiComponent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/** The description editor of a model, opened from the subagents section. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ProfileEditorRouteEntry(
    private val factory: ProfileEditorComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ModelProfileRoute>(ModelProfileRoute::class, ModelProfileRoute.serializer()) {
    override fun create(route: ModelProfileRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "model-profile-editor")
        val key = ModelKey(EngineId(route.engine), ModelId(route.model))
        return ProfileEditorUiComponent(factory.create(context, navigator, screen, key))
    }
}
