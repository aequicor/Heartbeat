package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorModel

/** Navigation entry of the editor of one surface; deleting the surface closes it. */
@AssistedInject
class EditorComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    internal val model: EditorModel,
) : ComponentContext by context {
    /** Leaves the editor. */
    internal fun close() = navigator.close()

    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and the navigator of the entry. */
        fun create(context: ComponentContext, navigator: Navigator): EditorComponent
    }
}
