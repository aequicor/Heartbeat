package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSurfaceRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryModel

/** Navigation entry of the surface library; the library belongs to the profile and outlives the screen. */
@AssistedInject
class LibraryComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    internal val model: LibraryModel,
) : ComponentContext by context {
    /** Opens the editor of the surface [id]. */
    internal fun openSurface(id: String) = navigator.navigate(ServiceUiSurfaceRoute(id))

    /** Leaves the standalone library. */
    internal fun close() = navigator.close()

    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and the navigator of the entry. */
        fun create(context: ComponentContext, navigator: Navigator): LibraryComponent
    }
}
