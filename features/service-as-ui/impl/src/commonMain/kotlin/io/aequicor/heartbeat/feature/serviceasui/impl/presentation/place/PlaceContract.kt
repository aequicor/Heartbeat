package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import androidx.compose.runtime.Immutable
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
 * [panelTitle] of the panel open in a dock.
 */
@Immutable
internal data class PlaceState(
    val surfaces: ImmutableList<PlaceSurfaceUi> = persistentListOf(),
    val launchers: ImmutableList<LauncherUi> = persistentListOf(),
    val modal: PlaceSurfaceUi? = null,
    val panelTitle: String? = null,
) : MVIState {
    /** Whether the place shows nothing: no surface, launcher or modal. */
    val isEmpty: Boolean get() = surfaces.isEmpty() && launchers.isEmpty() && modal == null

    override fun toString(): String =
        "PlaceState(surfaces=${surfaces.size}, launchers=${launchers.size}, hasModal=${modal != null})"
}

/** User input of a place. */
internal sealed interface PlaceIntent : MVIIntent {
    /** A launcher of the surface [surface] was pressed. */
    data class Launch(val surface: String) : PlaceIntent

    /** Closes the modal surface. */
    data object CloseModal : PlaceIntent

    /** Closes the panel this place shows. */
    data object ClosePanel : PlaceIntent
}

/** A place has no one-shot effects; its surfaces have their own. */
internal sealed interface PlaceAction : MVIAction
