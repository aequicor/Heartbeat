package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlPanelRoute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId

/** Navigation entry of the engine list; an engine opens its panel. */
@AssistedInject
class EngineControlListComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted val isEmbedded: Boolean,
    val model: EngineControlListModel,
) : ComponentContext by context {
    private val log = Log.tag("EngineControlList")

    /** Opens the panel of [engine]. */
    fun open(engine: String) {
        log.i { "open engine panel engine=$engine" }
        navigator.navigate(EngineControlPanelRoute(EngineId(engine)))
    }

    /** Closes the list outside the settings window. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned engine list. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component. */
        fun create(context: ComponentContext, navigator: Navigator, isEmbedded: Boolean): EngineControlListComponent
    }
}

/** Navigation entry of one engine's panel. */
@AssistedInject
class EngineControlPanelComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: EngineControlPanelModel,
) : ComponentContext by context {
    init {
        // The panel owns back (Esc in settings, system back): it closes a file, a project or a page first and asks
        // before unsaved changes are dropped; the store answers Leave once the panel itself is left.
        backHandler.register(BackCallback { model.store.intent(EngineControlPanelIntent.Back) })
    }

    /** Returns to the engine list. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned engine panel. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component. */
        fun create(context: ComponentContext, navigator: Navigator): EngineControlPanelComponent
    }
}
