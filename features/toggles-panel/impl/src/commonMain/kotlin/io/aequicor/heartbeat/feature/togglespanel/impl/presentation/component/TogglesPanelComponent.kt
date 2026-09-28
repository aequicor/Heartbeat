package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the panel is shown: as a settings section, on its own, or not yet decided (redirect pending). */
enum class PanelPresentation { Pending, Embedded, Standalone }

/** Lifecycle-bound navigation component rendering the feature screen; embedded inside the settings window. */
@AssistedInject
class TogglesPanelComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: TogglesPanelRoute,
    val model: TogglesPanelModel,
) : ComponentContext by context {
    /** Current presentation; the screen draws its own header only when [PanelPresentation.Standalone]. */
    val presentation: StateFlow<PanelPresentation> = MutableStateFlow(
        if (route.isEmbedded) PanelPresentation.Embedded else PanelPresentation.Standalone,
    )

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator, route: TogglesPanelRoute): TogglesPanelComponent
    }
}
