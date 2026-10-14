package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage

/** Resolves only cards of this owner in this chat; delayed storage reads cannot act after a host rebind. */
internal suspend fun ServiceUiMachine.focusSurfaceCard(
    cards: UiCardStorage,
    host: UiHostId?,
    owner: UiOwner?,
    name: String,
    expectedContext: UiHostContext,
): Boolean {
    if (host == null || owner == null || !name.matches(SurfaceName)) return false
    val surface = UiSurfaceId.of(owner, UiSurfaceName(name))
    val app = (state.value as? ServiceUiState.Ready)?.apps?.get(host)
    if (app?.surface == surface && app.context == expectedContext) {
        return send(ServiceUiIntent.Public.OpenApp(host, surface, expectedContext)) == SendResult.Accepted
    }
    val chat = expectedContext.call.session ?: return false
    val card = cards.latest(chat, surface) ?: return false
    return send(
        ServiceUiIntent.Internal.RequestFocusCard(host, surface, owner, card.id, expectedContext),
    ) == SendResult.Accepted
}

private val SurfaceName = Regex("[a-z][a-z0-9_]{0,31}")
