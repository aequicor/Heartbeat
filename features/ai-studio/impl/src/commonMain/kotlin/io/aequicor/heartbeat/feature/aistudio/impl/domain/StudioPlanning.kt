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

    /**
     * Whether a new chat could start planning now: the feature is on, plans are loaded in time and there is room for
     * one more plan. Checked before the chat is created, so a refusal leaves no chat behind.
     */
    suspend fun canEnter(): Boolean

    /** Starts planning in [chatId]; false when planning is unavailable or the plan could not be created. */
    suspend fun enter(chatId: String): Boolean

    /**
     * Detaches the plan of [chatId] from its context; false when planning is off, plans are unreadable (failed to
     * load) or it has no attached plan.
     */
    suspend fun leave(chatId: String): Boolean

    /**
     * Binds [session] to the plan of [chatId] before a prompt is sent, so the plan and its read-only rule follow the
     * chat into every native session. Fails closed: throws while plans are still loading after a short wait, and
     * when a planning chat's plan cannot be bound, so the prompt is never sent. The loading refusal applies to every
     * turn of the chat, service turns included, whether or not the chat plans, and the caller records the failure in
     * the chat like any other failed submission. A session the same chat's plan is already bound to (including by a
     * concurrent turn) is not an error. With the feature off, unreadable plans (the read-only hook then asks before
     * changes of every session) or a chat without a plan, the session stays unbound.
     */
    suspend fun bind(chatId: String, session: SessionRef)

    /** Lifts planning in [chatId] for implementing [revision]; false when the plan is no longer at that revision. */
    suspend fun startImplementation(chatId: String, revision: Long): Boolean

    /** Approves [revision] of the plan of [chatId]; false when planning is off or it is no longer current. */
    suspend fun approve(chatId: String, revision: Long): Boolean

    /** Saves the user's [text] of the plan of [chatId] edited from [base]; false when the plan changed meanwhile. */
    suspend fun write(chatId: String, text: String, base: Long): Boolean

    /** No planning mode: the feature is not part of the graph. */
    object None : StudioPlanning {
        override val availability: Flow<Boolean> = flowOf(false)
        override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())

        override suspend fun canEnter(): Boolean = false

        override suspend fun enter(chatId: String): Boolean = false

        override suspend fun leave(chatId: String): Boolean = false

        override suspend fun bind(chatId: String, session: SessionRef) = Unit

        override suspend fun startImplementation(chatId: String, revision: Long): Boolean = false

        override suspend fun approve(chatId: String, revision: Long): Boolean = false

        override suspend fun write(chatId: String, text: String, base: Long): Boolean = false
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
