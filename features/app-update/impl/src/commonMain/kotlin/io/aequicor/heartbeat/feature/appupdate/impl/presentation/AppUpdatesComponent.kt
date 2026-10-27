package io.aequicor.heartbeat.feature.appupdate.impl.presentation

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdatesRoute

/** Owns one page model and delegates closing to its navigation entry. */
@AssistedInject
class AppUpdatesComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: AppUpdatesRoute,
    val model: AppUpdatesModel,
) : ComponentContext by context {
    val isEmbedded: Boolean = route.isEmbedded

    /** Closes this standalone navigation entry. */
    fun close() = navigator.close()

    /** Assisted lifecycle entry into the feature graph. */
    @AssistedFactory
    fun interface Factory {
        /** Creates one page using the retained feature graph. */
        fun create(context: ComponentContext, navigator: Navigator, route: AppUpdatesRoute): AppUpdatesComponent
    }
}
