package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import kotlinx.coroutines.flow.Flow

/** Lifecycle-bound navigation component rendering the feature screen. */
@AssistedInject
class AiStudioComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: AiStudioModel,
    entries: StudioEntries,
) : ComponentContext by context {
    private val log = Log.tag("AiStudioComponent")

    /** Whether the engine connection settings are offered. */
    val showsConnections: Flow<Boolean> = entries.showsConnections

    /** Whether the profile search settings are offered. */
    val showsProfileSettings: Flow<Boolean> = entries.showsProfileSettings

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Opens the feature toggles panel above the studio; the studio keeps its state underneath. */
    fun openToggles() = navigator.navigate(
        TogglesPanelRoute,
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Opens profile-owned search provider settings. */
    fun openProfileSettings() {
        log.i { "open profile settings" }
        navigator.navigate(ProfileSettingsRoute, NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
    }

    /** Opens the engine × connection × model settings of the active profile above the studio. */
    fun openConnections() {
        log.i { "open engine connections" }
        navigator.navigate(EngineConnectionsRoute, NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
    }

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator): AiStudioComponent
    }
}
