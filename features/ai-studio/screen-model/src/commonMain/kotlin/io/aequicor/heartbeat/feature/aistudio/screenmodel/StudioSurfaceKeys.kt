package io.aequicor.heartbeat.feature.aistudio.screenmodel

/** Key of the interface row above a pane's composer. */
fun studioComposerSlotKey(paneId: Int): String = "$paneId.composer"

/** Key of the interface slot in a new chat's start screen. */
fun studioStartSlotKey(paneId: Int): String = "$paneId.start"

/** Key of the compact context panel at the trailing top corner of a pane. */
fun studioContextSlotKey(paneId: Int): String = "$paneId.context"

/** One retained app host per pane, drawn outside the lazy transcript in both start and chat layouts. */
fun studioAppKey(paneId: Int): String = "$paneId.app"

/** Key of one pane's embedded reply card. */
fun studioCardKey(paneId: Int, cardId: String): String = "$paneId:$cardId"

/** Cards of [paneId] keyed by their local card ids. */
fun <T> paneCards(cards: Map<String, T>, paneId: Int): Map<String, T> = "$paneId:".let { prefix ->
    cards.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
}
