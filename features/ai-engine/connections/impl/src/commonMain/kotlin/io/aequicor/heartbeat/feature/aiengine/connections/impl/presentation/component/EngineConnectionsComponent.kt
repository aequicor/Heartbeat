package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId

/** Navigation entry of the settings space; new connections are added through the wizard. */
@AssistedInject
class EngineConnectionsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: EngineConnectionsRoute,
    val model: EngineConnectionsModel,
) : ComponentContext by context {
    private val log = Log.tag("EngineConnectionsComponent")

    /** Whether the space is a section of the settings window: then it draws no header or back action. */
    val isEmbedded: Boolean = route.isEmbedded

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
