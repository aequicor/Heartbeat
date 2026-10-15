package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.StateBuilder

private typealias SlotStateBuilder =
    StateBuilder<ServiceUiState, ServiceUiState.Ready, ServiceUiIntent, ServiceUiEffect, ServiceUiOutput>

/** Publishes layout presence without accepting projections from another chat or an older library. */
internal fun SlotStateBuilder.slotTransitions() {
    on<ServiceUiIntent.Internal.ResolveSlot>(guard = {
        state.hosts[intent.host] == intent.expectedContext && state.library.revision == intent.expectedLibraryRevision
    }) {
        stay {
            val current = state.slots[intent.host].orEmpty()
            val next = if (intent.isPresent) current + intent.slot else current - intent.slot
            state.copy(slots = if (next.isEmpty()) state.slots - intent.host else state.slots + (intent.host to next))
        }
    }
}
