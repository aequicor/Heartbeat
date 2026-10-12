package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardModel

/** Navigation entry of one card in a response, embedded by the message that shows it. */
@AssistedInject
class CardComponent internal constructor(
    @Assisted context: ComponentContext,
    internal val model: CardModel,
) : ComponentContext by context {
    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose. */
        fun create(context: ComponentContext): CardComponent
    }
}
