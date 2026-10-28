package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One live app at the first successful anchor, with the newest receipt id; inline snapshots keep their places. */
@HighFrequency
internal fun List<StudioMessage>.toTranscriptUi(autopilotTurns: Set<String> = emptySet()): List<MessageUi> {
    val messages = map { it.toUi(autopilotTurns) }
    val receipts = messages.filterIsInstance<MessageUi.Reply>().flatMap { it.liveCardAliases }
    val current = receipts.lastOrNull() ?: return messages
    val aliases = receipts.distinct().toImmutableList()
    var isAnchored = false
    return messages.map { message ->
        if (message !is MessageUi.Reply || message.liveCardAliases.isEmpty()) return@map message
        val isAnchor = !isAnchored
        val cards = message.cardIds.mapNotNull { id ->
            if (id !in message.liveCardAliases) {
                id
            } else if (!isAnchored) {
                isAnchored = true
                current
            } else {
                null
            }
        }.distinct().toImmutableList()
        message.copy(cardIds = cards, liveCardAliases = if (isAnchor) aliases else persistentListOf())
    }
}
