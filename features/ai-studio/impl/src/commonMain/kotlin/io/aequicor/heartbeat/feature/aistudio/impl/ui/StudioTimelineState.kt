package io.aequicor.heartbeat.feature.aistudio.impl.ui

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
}

/** A host card follows the engine message at [afterIndex], or leads the transcript when it is negative. */
internal data class TimelineWeave(val id: String, val afterIndex: Int)
