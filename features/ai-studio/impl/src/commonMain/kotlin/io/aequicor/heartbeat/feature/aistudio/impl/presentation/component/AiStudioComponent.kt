package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute

/** Lifecycle-bound navigation component rendering the feature screen. */
@AssistedInject
class AiStudioComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: AiStudioModel,
) : ComponentContext by context {
    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Opens the feature toggles panel above the studio; the studio keeps its state underneath. */
    fun openToggles() = navigator.navigate(
        TogglesPanelRoute,
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Opens profile-owned search provider settings. */
    fun openProfileSettings() = navigator.navigate(
        ProfileSettingsRoute,
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator): AiStudioComponent
    }
}
