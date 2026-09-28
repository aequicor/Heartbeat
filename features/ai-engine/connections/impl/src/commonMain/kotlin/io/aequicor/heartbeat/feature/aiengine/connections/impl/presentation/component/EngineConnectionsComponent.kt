package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.EngineConnectionsScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.UnifiedSettingsPolicy
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How the space is shown: as a settings section, on its own, or not yet decided (redirect pending). */
enum class ConnectionsPresentation { Pending, Embedded, Standalone }

/** Navigation entry of the settings space; new connections are added through the wizard. */
@AssistedInject
class EngineConnectionsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: EngineConnectionsRoute,
    val model: EngineConnectionsModel,
    policy: UnifiedSettingsPolicy,
    @ForScope(EngineConnectionsScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    private val log = Log.tag("EngineConnectionsComponent")

    private val mutablePresentation = MutableStateFlow(
        if (route.isEmbedded) ConnectionsPresentation.Embedded else ConnectionsPresentation.Pending,
    )

    /** Embedded in the settings window it draws no header or back action; elsewhere it moves there if unified. */
    val presentation: StateFlow<ConnectionsPresentation> = mutablePresentation

    init {
        if (!route.isEmbedded) {
            // The feature scope outlives this entry: stop the redirect if the entry is destroyed first.
            val redirect = scope.coroutineScope.launch {
                if (policy.isUnified()) {
                    log.i { "legacy connections route opens the settings section" }
                    // Open the window, then remove exactly this entry, whatever was pushed meanwhile.
                    navigator.navigate(
                        SettingsRoute(SettingsSection.Models),
                        NavOptions(LaunchMode.SingleTop, NavTarget.Nearest, NavTransition.None),
                    )
                    navigator.close()
                } else {
                    mutablePresentation.value = ConnectionsPresentation.Standalone
                }
            }
            lifecycle.doOnDestroy { redirect.cancel() }
        }
    }

    /** Opens the wizard, skipping the engine step when [engine] is given. */
    fun openWizard(engine: String?) {
        log.i { "open connection wizard preselected=${engine != null}" }
        navigator.navigate(ConnectEngineRoute(engine?.let(::EngineId)))
    }

    /** Closes the settings space. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned settings space. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component. */
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            route: EngineConnectionsRoute,
        ): EngineConnectionsComponent
    }
}
