package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppQuickActionsUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceInputBinding
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** A surface a place draws; [key] names this drawing of it and finds its store. */
@Immutable
internal data class PlaceSurfaceUi(val key: String, val title: String)

/** A button that opens the panel or modal [surface] (a surface id). */
@Immutable
internal data class LauncherUi(val surface: String, val title: String)

/**
 * A place: the [surfaces] drawn in it, [launchers] of panels and modals, the [modal] surface over them, and the
 * [panelTitle] of the panel open in a dock. The composer slot also draws the [quickActions] of the canonical app of
 * its host.
 */
@Immutable
internal data class PlaceState(
    val surfaces: ImmutableList<PlaceSurfaceUi> = persistentListOf(),
    val launchers: ImmutableList<LauncherUi> = persistentListOf(),
    val modal: PlaceSurfaceUi? = null,
    val panelTitle: String? = null,
    val quickActions: AppQuickActionsUi = AppQuickActionsUi(),
) : MVIState {
    /** Whether the place shows nothing: no surface, launcher, modal or quick action. */
    val isEmpty: Boolean
        get() = surfaces.isEmpty() && launchers.isEmpty() && modal == null && quickActions.isEmpty

    override fun toString(): String = "PlaceState(surfaces=${surfaces.size}, launchers=${launchers.size}, " +
        "hasModal=${modal != null}, quickActions=${quickActions.actions.size})"
}

/** User input of a place. */
internal sealed interface PlaceIntent : MVIIntent {
    /** A launcher of the surface [surface] was pressed. */
    data class Launch(val surface: String) : PlaceIntent

    /** Closes the modal surface. */
    data object CloseModal : PlaceIntent

    /** Closes the panel this place shows. */
    data object ClosePanel : PlaceIntent

    /**
     * A quick [action] of the app was pressed (after its confirmation, if any). [authority] is the one of the state
     * the button was drawn from, so a press that outlives its app's context is dropped.
     */
    data class RunQuickAction(val action: QuickActionUi, val authority: SurfaceInputBinding?) : PlaceIntent {
        override fun toString(): String = "RunQuickAction"
    }

    /** Hides the notice of the app shown above its quick actions. */
    data class DismissQuickNotice(val authority: SurfaceInputBinding?) : PlaceIntent
}

/** A place has no one-shot effects; its surfaces have their own. */
internal sealed interface PlaceAction : MVIAction
