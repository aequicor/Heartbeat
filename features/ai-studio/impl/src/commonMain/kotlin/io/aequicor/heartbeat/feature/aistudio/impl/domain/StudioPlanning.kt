package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Planning mode of the studio's chats, owned by the planning feature. Chats are named by their studio ids; plans
 * are user data and never logged.
 */
interface StudioPlanning {
    /** Whether planning mode is offered. */
    val availability: Flow<Boolean>

    /** Plans by chat id, empty until they are loaded. */
    val plans: Flow<Map<String, Plan>>

    /** Starts planning in [chatId]; false when planning is unavailable or the plan could not be created. */
    suspend fun enter(chatId: String): Boolean

    /** Detaches the plan of [chatId] from its context; false when it has no attached plan. */
    suspend fun leave(chatId: String): Boolean

    /**
     * Binds [session] to the plan of [chatId] before a prompt is sent, so the plan and its read-only rule follow the
     * chat into every native session. A chat without a plan, or plans that cannot be loaded in time, leave the
     * session unbound; the read-only hook then still asks before changes of unknown sessions.
     */
    suspend fun bind(chatId: String, session: SessionRef)

    /** No planning mode: the feature is not part of the graph. */
    object None : StudioPlanning {
        override val availability: Flow<Boolean> = flowOf(false)
        override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())

        override suspend fun enter(chatId: String): Boolean = false

        override suspend fun leave(chatId: String): Boolean = false

        override suspend fun bind(chatId: String, session: SessionRef) = Unit
    }
}
