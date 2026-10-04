package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId

/**
 * The organisms of a profile. Durable truth is the profile journal: the machine wakes from it and every durable
 * change of an organism is written back; native turns belong to the profile runtime, not to this state.
 */
public sealed interface OrganicAiState : MachineState {
    /** Not started, or put to sleep while the toggle is off. */
    public data object Dormant : OrganicAiState

    /** Reading the journal. */
    public data object Awakening : OrganicAiState

    /** Organisms live and develop; finished ones stay for late observers until the journal forgets them. */
    public data class Living(val organisms: Map<OrganismId, Organism> = emptyMap()) : OrganicAiState {
        override fun toString(): String = "Living(organisms=${organisms.size})"
    }

    /** Saving organisms and releasing their sessions before sleep. */
    public data object Hibernating : OrganicAiState

    /** The journal could not be read; it is left untouched until a new awakening. */
    public data object Broken : OrganicAiState
}

/** Commands from other features and results of the host: tools of cells, session drivers and the immune system. */
public sealed interface OrganicAiIntent : MachineIntent {
    /** Commands sent through [OrganicAiMachineKey]. */
    public sealed interface Public : OrganicAiIntent {
        /** Restores the organisms of the journal and continues them. */
        public data object Awaken : Public

        /** Saves the organisms, cancels their turns and stops until the next awakening. */
        public data object Sleep : Public

        /** Conceives an organism: its zygote starts working on the goal. Ignored for an existing id. */
        public data class Conceive(val conception: Conception) :
            Public,
            OrganismIntent {
            override val organism: OrganismId get() = conception.id
        }

        /** Kills a developing organism: every living cell is lysed. The only way an organism dies. */
        public data class Abort(override val organism: OrganismId) :
            Public,
            OrganismIntent

        /** Restarts a stalled zygote: a recovery turn, after resolving the model again when it had none. */
        public data class Resume(override val organism: OrganismId) :
            Public,
            OrganismIntent

        /** The user's answer to a permission request a working [cell] is awaiting. */
        public data class Decide(
            override val organism: OrganismId,
            val cell: CellId,
            val decision: PermissionDecision,
        ) : Public,
            OrganismIntent
    }

    /** Results of effects and requests of cells; stale ones (an ended cell, a replaced request) are ignored. */
    public sealed interface Internal : OrganicAiIntent {
        /** The journal was read. */
        public data class Restored(val organisms: List<Organism>) : Internal {
            override fun toString(): String = "Restored(${organisms.size})"
        }

        /** The journal could not be read. */
        public data object RestoreFailed : Internal

        /** Organisms are saved and their sessions released. */
        public data object Hibernated : Internal

        /** The default model to run an organism conceived without one. */
        public data class Targeted(override val organism: OrganismId, val target: EngineTarget) :
            Internal,
            OrganismIntent

        /** The profile has no default model. */
        public data class Unresolved(override val organism: OrganismId) :
            Internal,
            OrganismIntent

        /** Working [parent] divides: [child] (the next cell id) starts on [task]. */
        public data class Divide(
            override val organism: OrganismId,
            val parent: CellId,
            val child: CellId,
            val name: String,
            val task: String,
        ) : Internal,
            OrganismIntent {
            init {
                require(name.isNotBlank() && name.length <= OrganismBounds.MAX_NAME) { "Invalid cell name" }
                require(task.isNotBlank() && task.length <= OrganismBounds.MAX_TASK) { "Invalid cell task" }
            }

            override fun toString(): String = "Divide(parent=${parent.value}, child=${child.value})"
        }

        /** Working [complaint] plaintiff files the next case. */
        public data class Complain(override val organism: OrganismId, val complaint: ImmuneCase.Complaint) :
            Internal,
            OrganismIntent

        /** Working [dispute] asker files the next case. */
        public data class Dispute(override val organism: OrganismId, val dispute: ImmuneCase.Dispute) :
            Internal,
            OrganismIntent

        /** The germinating [cell] got its native [session] for [request]. */
        public data class SessionBound(
            override val organism: OrganismId,
            override val cell: CellId,
            override val request: RequestId,
            val session: SessionRef,
        ) : Internal,
            TurnIntent

        /** The engine accepted [request] of [cell] as [turn]. */
        public data class TurnAccepted(
            override val organism: OrganismId,
            override val cell: CellId,
            override val request: RequestId,
            val turn: TurnId,
        ) : Internal,
            TurnIntent

        /** Permission requests of [cell]'s turn that await the user; empty once answered. */
        public data class PermissionsChanged(
            override val organism: OrganismId,
            override val cell: CellId,
            override val request: RequestId,
            val pending: List<PermissionRequest>,
        ) : Internal,
            TurnIntent

        /** The turn of [request] ended. */
        public data class TurnSettled(
            override val organism: OrganismId,
            override val cell: CellId,
            override val request: RequestId,
            val settlement: Settlement,
        ) : Internal,
            TurnIntent

        /** The immune system decided [case]. */
        public data class Ruled(override val organism: OrganismId, val case: CaseId, val ruling: Ruling) :
            Internal,
            OrganismIntent
    }
}

/** An intent that changes one organism of a living machine. */
public sealed interface OrganismIntent : OrganicAiIntent {
    /** The organism changed. */
    public val organism: OrganismId
}

/** Feedback of the driver of one turn; it applies only while [cell] still works on [request]. */
public sealed interface TurnIntent : OrganismIntent {
    /** The working cell. */
    public val cell: CellId

    /** The request of the turn. */
    public val request: RequestId
}

/** How a turn of a cell ended. */
public sealed interface Settlement {
    /** The final assistant text of the turn, cut to [OrganismBounds.MAX_RESULT]. */
    public data class Answered(val text: String) : Settlement {
        override fun toString(): String = "Answered(${text.length} chars)"
    }

    /** No answer. */
    public data class Broke(val breakdown: Breakdown) : Settlement
}

/** IO commands executed by the feature impl. */
public sealed interface OrganicAiEffect : MachineEffect {
    /** Reads every organism of the journal. */
    public data object Restore : OrganicAiEffect

    /** Saves the awakened [organisms] first, then drives their working cells and judges their open cases. */
    public data class Revive(val organisms: List<Organism>) : OrganicAiEffect

    /** Saves [organisms], cancels their turns and releases their handles, then reports Hibernated. */
    public data class Hibernate(val organisms: List<Organism>) : OrganicAiEffect

    /** Writes [organism] unless the journal has a newer version. */
    public data class Persist(val organism: Organism) : OrganicAiEffect

    /** Looks up the profile's default model for [organism]. */
    public data class Resolve(val organism: OrganismId) : OrganicAiEffect

    /**
     * Runs the turn of [request] of [cell]: opens or creates its session, submits its work, reports acceptance and
     * pending permissions, and settles the turn with its final answer.
     */
    public data class Drive(val organism: Organism, val cell: CellId, val request: RequestId) : OrganicAiEffect

    /** Delivers the user's [decision] to the session of [cell]. */
    public data class Respond(val organism: OrganismId, val cell: CellId, val decision: PermissionDecision) :
        OrganicAiEffect

    /** Judges [case] in a fresh session that only sees the dossier, then reports the ruling. */
    public data class Judge(val organism: Organism, val case: CaseId) : OrganicAiEffect

    /** Lets the sessions of [cells] go as [mode] says. */
    public data class Release(val organism: Organism, val cells: List<CellId>, val mode: ReleaseMode) :
        OrganicAiEffect
}

/** One-shot notifications; the organisms in [OrganicAiState.Living] stay authoritative. */
public sealed interface OrganicAiOutput : MachineOutput {
    /** [organism] completed or was aborted. */
    public data class Finished(val organism: OrganismId, val status: OrganismStatus) : OrganicAiOutput
}

/**
 * Profile-scoped organic AI machine. It is launched only while [OrganicAiEnabled] is on, so `MachineRegistry.send`
 * returns `NotRunning` before that.
 */
public object OrganicAiMachineKey :
    MachineKey<OrganicAiState, OrganicAiIntent, OrganicAiIntent.Public, OrganicAiEffect, OrganicAiOutput> {
    override val name: String = "organic-ai"
}
