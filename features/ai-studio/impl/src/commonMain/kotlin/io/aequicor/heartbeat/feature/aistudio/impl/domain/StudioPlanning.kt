package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementation
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
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

    /** Lifts planning in [chatId] for implementing [revision]; false when the plan is no longer at that revision. */
    suspend fun startImplementation(chatId: String, revision: Long): Boolean

    /** Approves [revision] of the plan of [chatId]; false when it is no longer current. */
    suspend fun approve(chatId: String, revision: Long): Boolean = false

    /** Saves the user's [text] of the plan of [chatId] edited from [base]; false when the plan changed meanwhile. */
    suspend fun write(chatId: String, text: String, base: Long): Boolean = false

    /** No planning mode: the feature is not part of the graph. */
    object None : StudioPlanning {
        override val availability: Flow<Boolean> = flowOf(false)
        override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())

        override suspend fun enter(chatId: String): Boolean = false

        override suspend fun leave(chatId: String): Boolean = false

        override suspend fun bind(chatId: String, session: SessionRef) = Unit

        override suspend fun startImplementation(chatId: String, revision: Long): Boolean = false
    }
}

/** Implementation of the plans of planning chats in the same chat. */
fun interface StudioPlanRuntime {
    /**
     * Applies [implementation] to planning chat [sessionId] and runs its first implementation turn; [settings] are the
     * screen's start-page settings for what the implementation does not choose.
     */
    suspend fun implementPlan(sessionId: String, implementation: PlanImplementation, settings: RunSettings): RunOutcome

    /** No planning mode. */
    object None : StudioPlanRuntime {
        override suspend fun implementPlan(
            sessionId: String,
            implementation: PlanImplementation,
            settings: RunSettings,
        ): RunOutcome = RunOutcome.Failed
    }
}
