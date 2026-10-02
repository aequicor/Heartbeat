package io.aequicor.heartbeat.feature.settings.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.routeEntry
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseRoute
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.impl.di.scope.SettingsScope
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsModel
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsScreenIntent
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsScreenState
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsSectionUi
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.toSection
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pro.respawn.flowmvi.dsl.collect

/**
 * The settings window. [sections] is a nested stack that shows the selected section's own route (embedded), so
 * section features keep their components, stores and inner navigation (the connection wizard opens on top of the
 * models section). The window chrome — section list, header, back and Esc — belongs to this component.
 */
@AssistedInject
class SettingsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: SettingsRoute,
    @Assisted blank: NavComponent,
    val model: SettingsModel,
    @ForScope(SettingsScope::class) scope: ScopeHandle,
    hosts: NavHostFactory,
) : ComponentContext by context {
    private val log = Log.tag("SettingsComponent")

    /** Content of the selected section; its base entry is a blank placeholder until a section is known. */
    val sections: StackHost = hosts.stack(
        context = this,
        parent = navigator,
        name = "settings",
        initial = listOf(SettingsBlankRoute),
        local = listOf(routeEntry<SettingsBlankRoute> { _, _, _ -> blank }),
        global = GlobalRoutes.Only(SECTION_ROUTES),
    )

    /** Latest window state, read by [back] from input handlers. */
    private var current = SettingsScreenState()

    init {
        model.store.intent(SettingsScreenIntent.Request(route.section?.toUi()))
        scope.coroutineScope.launch {
            model.store.collect {
                states.onEach { current = it }.map { it.selected }.distinctUntilChanged().collect(::show)
            }
        }
    }

    /** Opens [section] next to the list. */
    fun select(section: SettingsSectionUi) {
        log.i { "select section=$section" }
        model.store.intent(SettingsScreenIntent.Select(section))
    }

    /**
     * Back from the window: first closes a flow opened inside a section (for example the wizard), then on compact
     * layouts returns from a section to the list, and finally closes the settings window.
     */
    fun back(isCompact: Boolean) {
        val state = current
        when {
            sections.stack.value.backStack.isNotEmpty() -> {
                log.i { "back inside section" }
                sections.onBack()
            }

            isCompact && !state.isCompactListShown -> {
                log.i { "back to section list" }
                model.store.intent(SettingsScreenIntent.ShowList)
            }

            else -> close()
        }
    }

    /** Closes the settings window. */
    fun close() {
        log.i { "close settings" }
        navigator.close()
    }

    private fun show(section: SettingsSectionUi?) {
        if (section == null) return
        val route = section.toSection().route()
        // A restored stack already shows the section, possibly with a flow on top (the wizard): keep it.
        if (sections.stack.value.items.first().configuration.route == route) return
        log.i { "show section=$section" }
        sections.navigator.navigate(route, NavOptions(LaunchMode.ReplaceAll, NavTarget.Nearest, NavTransition.Fade))
    }

    /** Metro factory for a lifecycle-owned settings window. */
    @AssistedFactory
    fun interface Factory {
        /**
         * Creates the window for [route], owned by the supplied component. [blank] is the renderable placeholder of
         * the section stack before a section is chosen; the ui layer supplies it.
         */
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            route: SettingsRoute,
            blank: NavComponent,
        ): SettingsComponent
    }

    private companion object {
        val SECTION_ROUTES = setOf(
            EngineConnectionsRoute::class,
            ComputerUseRoute::class,
            AgentLearningRoute::class,
            ConnectEngineRoute::class,
            ProfileSettingsRoute::class,
            TogglesPanelRoute::class,
        )
    }
}

/** The embedded route of each section, owned by the section's feature. */
internal fun SettingsSection.route(): Route = when (this) {
    SettingsSection.Models -> EngineConnectionsRoute(isEmbedded = true)
    SettingsSection.Search -> ProfileSettingsRoute(isEmbedded = true)
    SettingsSection.ComputerUse -> ComputerUseRoute(isEmbedded = true)
    SettingsSection.AgentLearning -> AgentLearningRoute(isEmbedded = true)
    SettingsSection.FeatureFlags -> TogglesPanelRoute(isEmbedded = true)
}

/** Base entry of [SettingsComponent.sections] before a section is chosen. */
@Serializable
@SerialName("settings_blank")
internal data object SettingsBlankRoute : Route
