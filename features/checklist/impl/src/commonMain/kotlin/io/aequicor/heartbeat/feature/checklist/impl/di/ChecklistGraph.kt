package io.aequicor.heartbeat.feature.checklist.impl.di

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
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistMachineSpec
import io.aequicor.heartbeat.feature.checklist.api.ChecklistOutput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRoute
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.impl.data.ChecklistBus
import io.aequicor.heartbeat.feature.checklist.impl.data.ChecklistEffects
import io.aequicor.heartbeat.feature.checklist.impl.di.scope.ChecklistScope
import io.aequicor.heartbeat.feature.checklist.impl.presentation.component.ChecklistComponent
import io.aequicor.heartbeat.feature.checklist.impl.ui.ChecklistUiComponent
import kotlinx.coroutines.launch

/** Screen graph of one checklist card, retained by its navigation entry under the profile. */
@GraphExtension(ChecklistScope::class)
interface ChecklistGraph {
    val factory: ChecklistComponent.Factory

    /** Creates the graph of a checklist card. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the shown card and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: ChecklistRoute,
            @Provides @ForScope(ChecklistScope::class) scope: ScopeHandle,
        ): ChecklistGraph
    }
}

/** The profile machine owns persistence and survives screen closure. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ChecklistBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: ChecklistEffects,
    ): Machine<ChecklistState, ChecklistIntent, ChecklistOutput> = launcher.launch(ChecklistMachineSpec, scope, effects)
}

/** Loads the profile journal and starts durable EventBus delivery before any card is opened. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ChecklistStartup(
    private val machine: Lazy<Machine<ChecklistState, ChecklistIntent, ChecklistOutput>>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val bus: Lazy<ChecklistBus>,
) : ProfileStartup {
    override fun start() {
        val queue = machine.value
        scope.coroutineScope.launch { queue.send(ChecklistIntent.Internal.Start) }
        bus.value.start(scope.coroutineScope)
    }
}

/** Registers the checklist flag in the toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object ChecklistToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun checklist(): FeatureToggle<*> = ChecklistEnabled
}

/** Route of the checklist screen of one card. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ChecklistRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ChecklistGraph.Factory,
) : RouteEntry<ChecklistRoute>(ChecklistRoute::class, ChecklistRoute.serializer()) {
    override fun create(route: ChecklistRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "checklist") { graphs.create(route, it) }
            .factory.create(context).let { ChecklistUiComponent(it) }
}
