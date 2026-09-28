package io.aequicor.heartbeat.feature.settings.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.AppDeepLinkBinding
import io.aequicor.heartbeat.core.navigation.AppRouteBinding
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.DeepLinkParams
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavCommand
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings
import io.aequicor.heartbeat.feature.settings.api.settingsSectionOf
import io.aequicor.heartbeat.feature.settings.impl.di.scope.SettingsScope
import io.aequicor.heartbeat.feature.settings.impl.presentation.component.SettingsComponent
import io.aequicor.heartbeat.feature.settings.impl.ui.SettingsUiComponent

/** Feature graph retained by the settings window. */
@GraphExtension(SettingsScope::class)
interface SettingsGraph {
    val factory: SettingsComponent.Factory

    /** Metro factory for a lifecycle-owned settings window. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates the graph owned by the supplied scope. */
        fun createSettings(
            @Provides @ForScope(SettingsScope::class) scope: ScopeHandle,
        ): SettingsGraph
    }
}

/** Registers [UnifiedSettings] in the app-wide toggle catalog. */
@ContributesTo(AppScope::class)
@BindingContainer
object SettingsToggleBindings {
    /** The unified settings entry point. */
    @Provides
    @IntoSet
    fun unifiedSettings(): FeatureToggle<*> = UnifiedSettings
}

/** The settings window is an app route, so the guest tree can show the sections that need no profile. */
@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class SettingsRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: SettingsGraph.Factory,
) : RouteEntry<SettingsRoute>(SettingsRoute::class, SettingsRoute.serializer()) {
    override fun create(route: SettingsRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "settings") { graphs.createSettings(it) }
            .factory.create(context, navigator, route).let { SettingsUiComponent(it) }
}

/** `heartbeat://settings` (also sent by ⌘, on desktop) opens the settings window above the current screen. */
@ContributesIntoSet(AppScope::class, binding = binding<AppDeepLinkBinding>())
@Inject
internal class SettingsDeepLink : DeepLinkEntry("settings") {
    override fun commands(params: DeepLinkParams): List<NavCommand> = listOf(settingsCommand(SettingsRoute()))
}

/** `heartbeat://settings/<section>` opens the window at a section; an unknown section rejects the link. */
@ContributesIntoSet(AppScope::class, binding = binding<AppDeepLinkBinding>())
@Inject
internal class SettingsSectionDeepLink : DeepLinkEntry("settings/{section}") {
    override fun commands(params: DeepLinkParams): List<NavCommand> {
        val section = requireNotNull(settingsSectionOf(params.path("section"))) { "unknown settings section" }
        return listOf(settingsCommand(SettingsRoute(section)))
    }
}

private fun settingsCommand(route: SettingsRoute): NavCommand =
    NavCommand(route, NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
