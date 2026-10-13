package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement

/** Where a surface appears. */
internal enum class PlacementUi { Composer, Start, Panel, Modal }

/** Presentation of this placement. */
internal fun UiPlacement.toUi(): PlacementUi = when (this) {
    UiPlacement.Composer -> PlacementUi.Composer
    UiPlacement.Start -> PlacementUi.Start
    UiPlacement.Panel -> PlacementUi.Panel
    UiPlacement.Modal -> PlacementUi.Modal
}
