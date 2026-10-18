package io.aequicor.heartbeat.feature.planningmode.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Plans of the profile's chats and the native sessions that currently serve them. */
public sealed interface PlanningState : MachineState {
    /** Not started; storage is untouched until [PlanningModeEnabled] is first on. */
    public data object Idle : PlanningState

    /** Reading plans from the profile. */
    public data object Loading : PlanningState

    /** Plans could not be read; nothing is written until a [PlanningIntent.Public.Retry] succeeds. */
    public data object Failed : PlanningState

    /**
     * Plans are known. [bindings] map the native session of a chat to its plan, so hooks and tools of that session
     * find it; a chat has at most one bound session. [revision] grows with every change and orders the saves.
     */
    public data class Ready(
        val plans: Map<PlanChat, Plan> = emptyMap(),
        val bindings: Map<SessionRef, PlanChat> = emptyMap(),
        val revision: Long = 0,
    ) : PlanningState {
        /** Plan served by [session], if it is bound. */
        public fun planOf(session: SessionRef): Plan? = bindings[session]?.let(plans::get)

        override fun toString(): String = "Ready(plans=${plans.size}, bindings=${bindings.size}, revision=$revision)"
    }
}

/** Commands from the studio, the agent tools and effect results. */
public sealed interface PlanningIntent : MachineIntent {
    /** Commands sent through [PlanningMachineKey]. */
    public sealed interface Public : PlanningIntent {
        /** Reads stored plans; sent once by the owner when [PlanningModeEnabled] is first on. */
        public data object Start : Public

        /** Reads stored plans again after a failure. */
        public data object Retry : Public

        /** Starts planning in [chat] with its existing plan or an empty one. */
        public data class Enter(val chat: PlanChat, val at: Instant) : Public

        /** Detaches the plan of [chat] from its context; the plan is kept. */
        public data class Leave(val chat: PlanChat) : Public

        /** [session] now serves [chat]; earlier sessions of the chat are released. */
        public data class Bind(val chat: PlanChat, val session: SessionRef) : Public

        /**
         * Changes the plan of [chat] made from revision [base]. A stale base, an unchanged or too long result and an
         * agent's write to a detached plan are ignored; writers check them first to report the reason.
         */
        public data class Write(
            val chat: PlanChat,
            val edit: PlanEdit,
            val base: Long,
            val author: PlanAuthor,
            val at: Instant,
        ) : Public

        /** The agent asks the user to review [revision]. */
        public data class Propose(val chat: PlanChat, val revision: Long) : Public

        /** The user approves [revision]. */
        public data class Approve(val chat: PlanChat, val revision: Long) : Public

        /** The user starts implementing [revision]; it counts as approved and the chat may change the workspace. */
        public data class StartImplementation(val chat: PlanChat, val revision: Long) : Public

        /** The chat is gone; its plan and bindings are deleted. */
        public data class Forget(val chat: PlanChat) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : PlanningIntent {
        /**
         * Stored plans were read. [external] plans were changed outside the studio; they arrive already updated with
         * [externallyEdited] and are saved again.
         */
        public data class Loaded(
            val plans: Map<PlanChat, Plan>,
            val bindings: Map<SessionRef, PlanChat>,
            val external: Set<PlanChat> = emptySet(),
        ) : Internal {
            override fun toString(): String =
                "Loaded(plans=${plans.size}, bindings=${bindings.size}, external=${external.size})"
        }

        /** Stored plans could not be read. */
        public data object LoadFailed : Internal

        /** A save failed; the in-memory plans stay current and the next change saves them again. */
        public data object SaveFailed : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface PlanningEffect : MachineEffect {
    /** Reads plans and bindings. */
    public data object Load : PlanningEffect

    /**
     * Writes the files of the [changed] chats (deleting those without a plan) and then the index of [plans] and
     * [bindings]; an index older than the last written [revision] is dropped.
     */
    public data class Persist(
        val plans: Map<PlanChat, Plan>,
        val bindings: Map<SessionRef, PlanChat>,
        val changed: Set<PlanChat>,
        val revision: Long,
    ) : PlanningEffect {
        override fun toString(): String = "Persist(plans=${plans.size}, changed=${changed.size}, revision=$revision)"
    }
}

/** One-shot notifications. */
public sealed interface PlanningOutput : MachineOutput {
    /** Plans were not persisted. */
    public data object SaveFailed : PlanningOutput
}

/**
 * Profile-scoped planning machine. It is created by the first injection of [PlanningView] in the profile; until
 * then `MachineRegistry.send` reports `NotRunning`.
 */
public object PlanningMachineKey :
    MachineKey<PlanningState, PlanningIntent, PlanningIntent.Public, PlanningEffect, PlanningOutput> {
    override val name: String = "planning-mode"
}

/** Read-only plans for the studio, hooks and tools; changes go only through [PlanningMachineKey]. */
public interface PlanningView {
    /** Current machine state. */
    public val state: StateFlow<PlanningState>
}

/** [plan] after its file was changed outside the studio to [text] at [at]. */
public fun externallyEdited(plan: Plan, text: String, at: Instant): Plan = plan.copy(
    text = text,
    revision = plan.revision + 1,
    status = if (plan.mode == PlanMode.Planning) PlanStatus.Draft else plan.status,
    updatedAt = at,
    updatedBy = PlanAuthor.External,
)
