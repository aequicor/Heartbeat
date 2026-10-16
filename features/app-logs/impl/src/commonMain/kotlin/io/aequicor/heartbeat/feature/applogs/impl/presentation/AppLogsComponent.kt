package io.aequicor.heartbeat.feature.applogs.impl.presentation

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.applogs.api.AppLogsRoute

/** Owns one feature model and delegates closing to its navigation entry. */
@AssistedInject
class AppLogsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: AppLogsRoute,
    val model: AppLogsModel,
) : ComponentContext by context {
    val isEmbedded: Boolean = route.isEmbedded

    /** Closes this standalone navigation entry. */
    fun close() = navigator.close()

    /** Assisted lifecycle entry into the feature graph. */
    @AssistedFactory
    fun interface Factory {
        /** Creates one screen using the retained feature graph. */
        fun create(context: ComponentContext, navigator: Navigator, route: AppLogsRoute): AppLogsComponent
    }
}
