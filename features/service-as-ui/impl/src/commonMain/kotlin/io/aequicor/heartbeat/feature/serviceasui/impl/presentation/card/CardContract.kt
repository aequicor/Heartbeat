package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card

import androidx.compose.runtime.Immutable
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** How a card in a response shows. */
internal enum class CardPhase {
    /** Reading the card or waiting for the library. */
    Loading,

    /** Its surface is drawn. */
    Drawn,

    /** The card was evicted by the profile limit or never stored. */
    Missing,

    /** Its library surface is gone or disabled, the feature is off, or its spec cannot be read. */
    Unavailable,
}

/** A card: its [phase], [title] and the [key] that finds the store of its drawn surface. */
@Immutable
internal data class CardState(
    val phase: CardPhase = CardPhase.Loading,
    val title: String = "",
    val key: String? = null,
    val isApp: Boolean = false,
    val description: String = "",
    val isOpenEnabled: Boolean = false,
) : MVIState {
    override fun toString(): String = "CardState(phase=$phase)"
}

/** A card has no input of its own; its surface has. */
internal sealed interface CardIntent : MVIIntent {
    /** Opens the canonical app of this card's bound chat; it never mounts another full surface in the receipt. */
    data object OpenApp : CardIntent
}

/** A card has no one-shot effects of its own; its surface has. */
internal sealed interface CardAction : MVIAction
