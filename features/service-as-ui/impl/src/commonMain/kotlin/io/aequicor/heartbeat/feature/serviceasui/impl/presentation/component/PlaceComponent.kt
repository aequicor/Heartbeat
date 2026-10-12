package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceModel

/** Navigation entry of one place of surfaces, embedded by its host: a slot or a dock panel. */
@AssistedInject
class PlaceComponent internal constructor(
    @Assisted context: ComponentContext,
    internal val model: PlaceModel,
) : ComponentContext by context {
    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose. */
        fun create(context: ComponentContext): PlaceComponent
    }
}
