package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiPlaceScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceModel

/**
 * Navigation entry of one place of surfaces, embedded by its host: a slot or a dock panel. It hosts the browser
 * documents its surfaces show.
 */
@AssistedInject
class PlaceComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted navigator: Navigator,
    internal val model: PlaceModel,
    hosts: NavHostFactory,
    @ForScope(ServiceUiPlaceScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    internal val documents = WebDocumentHosts(
        this,
        navigator,
        hosts,
        scope.coroutineScope,
        model.surfaces,
        isApp = model.isContext,
    )

    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and the navigator of the entry. */
        fun create(context: ComponentContext, navigator: Navigator): PlaceComponent
    }
}
