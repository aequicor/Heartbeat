package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelModel

/** Lifecycle-bound navigation component rendering the feature screen. */
@AssistedInject
class TogglesPanelComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: TogglesPanelModel,
) : ComponentContext by context {
    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator): TogglesPanelComponent
    }
}
