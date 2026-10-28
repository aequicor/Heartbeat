package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Navigation entry of the engine list; an engine opens its panel. */
@AssistedInject
class EngineControlListComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted val isEmbedded: Boolean,
    @Assisted entries: List<RouteEntry<*>>,
    hosts: NavHostFactory,
    val model: EngineControlListModel,
) : ComponentContext by context {
    private val log = Log.tag("EngineControlList")

    /** Per-engine entries stay alive while switching so drafts are never discarded by selection. */
    val details = hosts.stack(this, navigator, "engine-details", listOf(EngineControlBlankRoute), entries)

    /** Selected identity for presentation, without exposing route contracts to the UI. */
    fun selectedEngine(): String? =
        (details.stack.value.active.configuration.route as? EngineControlDetailsRoute)?.engine?.value

    /** Routes Escape through the active panel's guards instead of popping its navigation entry directly. */
    fun back(): Boolean {
        val target = details.stack.value.active.instance as? EngineControlBackTarget ?: return false
        target.requestBack()
        return true
    }

    /** Opens the panel of [engine]. */
    fun open(engine: String) {
        log.i { "open engine panel engine=$engine" }
        details.navigator.navigate(
            EngineControlDetailsRoute(EngineId(engine)),
            NavOptions(launch = LaunchMode.BringToFront, transition = NavTransition.None),
        )
    }

    /** Closes the list outside the settings window. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned engine list. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component. */
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            isEmbedded: Boolean,
            entries: List<RouteEntry<*>>,
        ): EngineControlListComponent
    }
}

/** Navigation entry of one engine's panel. */
@AssistedInject
class EngineControlPanelComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted val isEmbedded: Boolean,
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
        fun create(context: ComponentContext, navigator: Navigator, isEmbedded: Boolean): EngineControlPanelComponent
    }
}

/** Empty details entry of the engine browser. */
@Serializable
@SerialName("engine-control-empty")
internal data object EngineControlBlankRoute : Route

/** One retained details entry per engine, private to the engine browser. */
@Serializable
@SerialName("engine-control-details")
internal data class EngineControlDetailsRoute(val engine: EngineId) : Route

/** Lifecycle-owned details entry that can preserve its drafts before leaving. */
internal interface EngineControlBackTarget {
    fun requestBack()
}
