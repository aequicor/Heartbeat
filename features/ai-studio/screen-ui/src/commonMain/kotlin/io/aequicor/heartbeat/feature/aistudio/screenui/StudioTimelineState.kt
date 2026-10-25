package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.ds.components.HbChatMessage

/**
 * Workspace-owned card positions, shared by both panes and layouts. Leaving a session discards its rendered
 * transcript cache, but keeps the host cards and their invocation points until the workspace is disposed.
 */
internal class StudioTimelineState {
    private val sessions = mutableMapOf<String, TimelineWeaves>()

    fun session(id: String): TimelineWeaves = sessions.getOrPut(id) { TimelineWeaves() }
}

/** Only host cards are retained; engine messages and prepared timelines belong to the visible transcript. */
internal class TimelineWeaves {
    var cards: Map<String, HbChatMessage> = emptyMap()
    var positions: List<TimelineWeave> = emptyList()

    /** Ids of the build cards drawn inside a reply instead of the timeline; [cards] does not list them. */
    var inline: Set<String> = emptySet()

    /** Every card the transcript draws, woven or inline: the host keeps offering these beyond its recent window. */
    val shown: Set<String>
        get() = cards.keys + inline
}

/** A host card follows the engine message at [afterIndex], or leads the transcript when it is negative. */
internal data class TimelineWeave(val id: String, val afterIndex: Int)
