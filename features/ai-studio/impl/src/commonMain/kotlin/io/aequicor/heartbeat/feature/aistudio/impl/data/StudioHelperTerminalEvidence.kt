package io.aequicor.heartbeat.feature.aistudio.impl.data

/** Later native proof may settle Unknown or fill missing marked output; confirmed outcomes never change. */
internal fun StudioHelperTerminal.enrichedBy(incoming: StudioHelperTerminal): StudioHelperTerminal {
    check(session == incoming.session && (turn == null || incoming.turn == null || turn == incoming.turn)) {
        "Native terminal identity changed"
    }
    val enriched = incoming.copy(turn = turn ?: incoming.turn)
    if (outcome == StudioHelperTerminalOutcome.Unknown || incoming.outcome == StudioHelperTerminalOutcome.Unknown) {
        return if (outcome == StudioHelperTerminalOutcome.Unknown) enriched else copy(turn = enriched.turn)
    }
    check(outcome == incoming.outcome) { "A confirmed helper outcome cannot change" }
    check(answer.isEmpty() || incoming.answer.isEmpty() || answer == incoming.answer) {
        "A confirmed helper answer cannot change"
    }
    return if (answer.isEmpty()) enriched else copy(turn = enriched.turn)
}
