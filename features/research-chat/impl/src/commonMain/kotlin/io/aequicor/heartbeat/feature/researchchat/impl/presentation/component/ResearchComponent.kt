package io.aequicor.heartbeat.feature.researchchat.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchModel

/** Navigation entry retaining the feature graph while the profile owns native generation. */
@AssistedInject
class ResearchComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    internal val model: ResearchModel,
) : ComponentContext by context {
    /** Returns to the studio while profile-owned runs continue. */
    fun close() = navigator.close()

    /** Creates the navigation component within its retained feature graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and its local navigator. */
        fun create(context: ComponentContext, navigator: Navigator): ResearchComponent
    }
}
