package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement

/** Where a surface appears. */
internal enum class PlacementUi { Composer, Start, Context, Card, Panel, Modal }

/** Presentation of this placement. */
internal fun UiPlacement.toUi(): PlacementUi = when (this) {
    UiPlacement.Composer -> PlacementUi.Composer
    UiPlacement.Start -> PlacementUi.Start
    UiPlacement.Context -> PlacementUi.Context
    UiPlacement.Card -> PlacementUi.Card
    UiPlacement.Panel -> PlacementUi.Panel
    UiPlacement.Modal -> PlacementUi.Modal
}
