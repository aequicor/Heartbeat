package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.ds.components.HbChatMessage

/**
 * Workspace-owned card positions, shared by both panes and layouts. Leaving a session discards its rendered
 * transcript cache, but keeps the host cards and their invocation points until the workspace is disposed.
 */
internal class StudioTimelineState {
    private val sessions = mutableMapOf<String, TimelineWeaves>()

    fun session(id: String): TimelineWeaves = sessions.getOrPut(id) { TimelineWeaves() }
}

/**
 * Only host cards are retained; engine messages and prepared timelines belong to the visible transcript.
 * [cards] and [inline] are snapshot state: the timeline writes them while composing, and the pane feed that read
 * [shown] earlier in the same frame recomposes with the current set instead of lagging a frame behind.
 */
internal class TimelineWeaves {
    var cards: Map<String, HbChatMessage> by mutableStateOf(emptyMap())
    var positions: List<TimelineWeave> = emptyList()

    /**
     * For cards placed before any message (a negative [TimelineWeave.afterIndex]): the last message index of the
     * first transcript seen with them. Only calls of the replies loaded then may take such a card into a reply.
     */
    var loadedWith: Map<String, Int> = emptyMap()

    /** Ids of the build cards drawn inside a reply instead of the timeline; [cards] does not list them. */
    var inline: Set<String> by mutableStateOf(emptySet())

    /** Every card the transcript draws, woven or inline: the host keeps offering these beyond its recent window. */
    val shown: Set<String>
        get() = cards.keys + inline
}

/** A host card follows the engine message at [afterIndex], or leads the transcript when it is negative. */
internal data class TimelineWeave(val id: String, val afterIndex: Int)
