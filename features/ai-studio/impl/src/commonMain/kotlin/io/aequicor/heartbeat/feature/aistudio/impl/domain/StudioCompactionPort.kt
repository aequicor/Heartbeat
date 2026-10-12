package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Context compaction of studio chats, offered while context optimization is enabled. */
interface StudioCompactionPort {
    /** Whether the composer offers `/compact` and the context indicator its action. */
    val availability: Flow<Boolean>

    /** How the chat feed collapses agent work; only the display changes. */
    val feed: Flow<StudioFeedCollapse> get() = flowOf(StudioFeedCollapse())

    /**
     * Asks to compact chat [chatId] with optional focus [instructions] once it is idle; false when the chat has no
     * native session yet or the feature is off. The compaction then runs like a turn of the chat.
     */
    suspend fun request(chatId: String, instructions: String?): Boolean

    /** Without context optimization nothing is offered. */
    object None : StudioCompactionPort {
        override val availability: Flow<Boolean> = flowOf(false)
        override suspend fun request(chatId: String, instructions: String?): Boolean = false
    }
}

/**
 * The feed hides reasoning ([isReasoningCollapsed]) and shows the tool calls of a reply as one line
 * ([isToolCallsCollapsed]).
 */
data class StudioFeedCollapse(val isReasoningCollapsed: Boolean = false, val isToolCallsCollapsed: Boolean = false)
