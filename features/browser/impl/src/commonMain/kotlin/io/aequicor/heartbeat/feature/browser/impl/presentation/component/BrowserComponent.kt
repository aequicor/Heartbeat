package io.aequicor.heartbeat.feature.browser.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserModel

/** Decompose entry; its model and surface belong to the retained feature graph. */
@AssistedInject
class BrowserComponent internal constructor(
    @Assisted context: ComponentContext,
    internal val model: BrowserModel,
) : ComponentContext by context {
    /** Creates only the lifecycle wrapper, preserving the graph's model. */
    @AssistedFactory
    fun interface Factory {
        /** Binds the current navigation component context. */
        fun create(context: ComponentContext): BrowserComponent
    }
}
