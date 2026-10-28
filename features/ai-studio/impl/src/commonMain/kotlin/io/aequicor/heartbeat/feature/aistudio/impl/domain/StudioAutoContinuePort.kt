package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Suggestions and autopilot of studio chats, offered while auto-continue is enabled. */
interface StudioAutoContinuePort {
    /** Whether the composer offers auto-continue. */
    val availability: Flow<Boolean>

    /** State of the auto-continue machine. */
    val state: Flow<AutoContinueState>

    /** Replies the human chose to edit; the screen puts them into the chat's composer. */
    val drafts: Flow<AutoContinueOutput.Draft>

    /** Native turns of [chat] whose prompts the autopilot sent. */
    fun autopilotTurns(chat: String): Flow<Set<String>> = flowOf(emptySet())

    /** Sends a command to the auto-continue machine. */
    suspend fun send(intent: AutoContinueIntent.Public)

    /** Without auto-continue nothing is offered. */
    object None : StudioAutoContinuePort {
        override val availability: Flow<Boolean> = flowOf(false)
        override val state: Flow<AutoContinueState> = flowOf(AutoContinueState.Idle)
        override val drafts: Flow<AutoContinueOutput.Draft> = emptyFlow()
        override suspend fun send(intent: AutoContinueIntent.Public) = Unit
    }
}
