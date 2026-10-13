package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Whether the library can be shown. */
internal enum class LibraryPhase { Loading, Disabled, Failed, Ready }

/** Where a surface appears. */
internal enum class PlacementUi { Composer, Start, Panel, Modal }

/** One surface of the library list. */
@Immutable
internal data class SurfaceRowUi(
    val id: String,
    val title: String,
    val description: String,
    val placement: PlacementUi,
    val isEnabled: Boolean,
)

/**
 * The surfaces of one owner. [isKnown] is false while its provider does not list the owner, [isEnabled] tells
 * whether the owner itself is on; surfaces of an unknown or switched-off owner are hidden everywhere.
 */
@Immutable
internal data class OwnerGroupUi(
    val key: String,
    val title: String,
    val isKnown: Boolean,
    val isEnabled: Boolean,
    val surfaces: ImmutableList<SurfaceRowUi>,
)

/** The library screen: owners by title with their surfaces; [pending] surfaces are being switched. */
@Immutable
internal data class LibraryState(
    val phase: LibraryPhase = LibraryPhase.Loading,
    val groups: ImmutableList<OwnerGroupUi> = persistentListOf(),
    val pending: ImmutableSet<String> = persistentSetOf(),
    val notice: WriteNoticeUi? = null,
) : MVIState {
    override fun toString(): String = "LibraryState(phase=$phase, groups=${groups.size}, pending=${pending.size})"
}

/** Controls of the library screen. */
internal sealed interface LibraryIntent : MVIIntent {
    /** Shows or hides the surface [id] everywhere. */
    data class SetEnabled(val id: String, val isEnabled: Boolean) : LibraryIntent

    /** Opens the editor of the surface [id]. */
    data class Open(val id: String) : LibraryIntent

    /** Reads the library again after it failed to load. */
    data object Reload : LibraryIntent

    /** Hides the notice. */
    data object DismissNotice : LibraryIntent
}

/** Navigation the component performs. */
internal sealed interface LibraryAction : MVIAction {
    /** Opens the editor of the surface [id]. */
    data class OpenSurface(val id: String) : LibraryAction
}

/** Presentation of this placement. */
internal fun UiPlacement.toUi(): PlacementUi = when (this) {
    UiPlacement.Composer -> PlacementUi.Composer
    UiPlacement.Start -> PlacementUi.Start
    UiPlacement.Panel -> PlacementUi.Panel
    UiPlacement.Modal -> PlacementUi.Modal
}
