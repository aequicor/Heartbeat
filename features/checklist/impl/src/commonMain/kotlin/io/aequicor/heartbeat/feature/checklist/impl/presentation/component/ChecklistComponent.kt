package io.aequicor.heartbeat.feature.checklist.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistModel

/** Navigation entry of one durable checklist card, embedded by the message host. */
@AssistedInject
class ChecklistComponent internal constructor(
    @Assisted context: ComponentContext,
    internal val model: ChecklistModel,
) : ComponentContext by context {
    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose. */
        fun create(context: ComponentContext): ChecklistComponent
    }
}
