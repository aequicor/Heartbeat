package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Context compaction of studio chats, offered while context optimization is enabled. */
interface StudioCompactionPort {
    /** Whether the composer offers `/compact` and the context indicator its action. */
    val isAvailable: Flow<Boolean>

    /**
     * Asks to compact chat [chatId] with optional focus [instructions] once it is idle; false when the chat has no
     * native session yet or the feature is off. The compaction then runs like a turn of the chat.
     */
    suspend fun request(chatId: String, instructions: String?): Boolean

    /** Without context optimization nothing is offered. */
    object None : StudioCompactionPort {
        override val isAvailable: Flow<Boolean> = flowOf(false)
        override suspend fun request(chatId: String, instructions: String?): Boolean = false
    }
}
