package io.aequicor.heartbeat.feature.browser.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineSpec
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRoute
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAvailability
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserEffects
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserSession
import io.aequicor.heartbeat.feature.browser.impl.presentation.component.BrowserComponent
import io.aequicor.heartbeat.feature.browser.impl.ui.BrowserUiComponent

/** Retains the browser model and native surface adapter inside the active profile. */
@GraphExtension(BrowserScope::class)
interface BrowserGraph {
    val factory: BrowserComponent.Factory

    /** Factory contributed to the profile graph. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the owner of one navigation entry. */
        fun createBrowser(
            @Provides @ForScope(BrowserScope::class) scope: ScopeHandle,
        ): BrowserGraph
    }
}

/** Launches the browser machine with domain ports. */
@ContributesTo(BrowserScope::class)
@BindingContainer
object BrowserBindings {
    @Provides
    internal fun effects(availability: BrowserAvailability, session: BrowserSession): BrowserEffects =
        BrowserEffects(availability, session)

    @Provides
    @SingleIn(BrowserScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(BrowserScope::class) scope: ScopeHandle,
        effects: BrowserEffects,
    ): Machine<BrowserState, BrowserIntent, BrowserOutput> = launcher.launch(BrowserMachineSpec, scope, effects)
}

/** Registers the opt-in browser flag in the existing toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object BrowserToggleBindings {
    /** Off by default until explicitly enabled. */
    @Provides
    @IntoSet
    fun browser(): FeatureToggle<*> = BrowserEnabled
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class BrowserRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: BrowserGraph.Factory,
) : RouteEntry<BrowserRoute>(BrowserRoute::class, BrowserRoute.serializer()) {
    override fun create(route: BrowserRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "browser") { graphs.createBrowser(it) }
            .factory.create(context).let { BrowserUiComponent(it) }
}
