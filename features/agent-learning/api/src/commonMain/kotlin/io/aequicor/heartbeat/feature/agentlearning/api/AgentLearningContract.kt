package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState

/** Profile registry of learned instructions. */
public sealed interface AgentLearningState : MachineState {
    /** Not started yet. */
    public data object Idle : AgentLearningState

    /** Reading the stored registry. */
    public data object Loading : AgentLearningState

    /** The stored registry could not be read; nothing is written until a reload succeeds. */
    public data object Failed : AgentLearningState

    /**
     * The registry is known. [revision] grows with every change of [instructions] and [approvalRevision] with every
     * change of [approval], so the effect handler can drop a save that arrives after a newer one. Until the user
     * chooses otherwise every new instruction is confirmed ([LearningApproval.Ask]).
     */
    public data class Ready(
        val instructions: List<LearnedInstruction> = emptyList(),
        val approval: LearningApproval = LearningApproval.Ask,
        val revision: Long = 0,
        val approvalRevision: Long = 0,
    ) : AgentLearningState
}

/** Why the registry did not take an instruction. */
public enum class LearnRejection {
    /** An instruction with the same kind, scope and title already exists. */
    Duplicate,

    /** The registry holds [LearningLimits.INSTRUCTIONS] instructions. */
    Limit,

    /** The registry is not loaded. */
    Unavailable,

    /** The instruction could not be written; it applies only until the profile closes. */
    NotPersisted,
}

/** Correlates a learned instruction with the request that added it until it is written. */
public data class LearnReceipt(val requestId: String, val id: InstructionId)

/** Commands from the feature's tools and screens, and results of effects. */
public sealed interface AgentLearningIntent : MachineIntent {
    /** Commands sent through [AgentLearningMachineKey]. */
    public sealed interface Public : AgentLearningIntent {
        /** Reads the stored registry; sent once by the owner when the machine is created. */
        public data object Start : Public

        /** Reads the registry again after a failed load. */
        public data object Reload : Public

        /** Adds [instruction]; the outcome is reported as an output carrying [requestId]. */
        public data class Learn(val requestId: String, val instruction: LearnedInstruction) : Public

        /** Turns an instruction on or off without removing it. */
        public data class SetEnabled(val id: InstructionId, val isEnabled: Boolean) : Public

        /** Replaces the texts of an instruction; [atMillis] becomes its update time. */
        public data class Edit(
            val id: InstructionId,
            val title: String,
            val description: String,
            val content: String,
            val atMillis: Long,
        ) : Public

        /** Removes an instruction. */
        public data class Delete(val id: InstructionId) : Public

        /** Changes how new instructions are accepted. */
        public data class SetApproval(val approval: LearningApproval) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : AgentLearningIntent {
        /** The stored registry was read. */
        public data class Loaded(val instructions: List<LearnedInstruction>, val approval: LearningApproval) : Internal

        /** The stored registry could not be read. */
        public data object LoadFailed : Internal

        /** The change carrying [receipt] was written, possibly together with a newer one. */
        public data class Saved(val receipt: LearnReceipt) : Internal

        /** A change could not be written; it stays in memory for this run. [receipt] names a learned one. */
        public data class SaveFailed(val receipt: LearnReceipt? = null) : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface AgentLearningEffect : MachineEffect {
    /** Reads the stored registry. */
    public data object Load : AgentLearningEffect

    /**
     * Replaces the stored instructions; ignored when a save of a newer [revision] already ran. [receipt] marks the
     * save of a learned instruction, confirmed with [AgentLearningIntent.Internal.Saved].
     */
    public data class Persist(
        val instructions: List<LearnedInstruction>,
        val revision: Long,
        val receipt: LearnReceipt? = null,
    ) : AgentLearningEffect

    /** Stores the approval level; ignored when a save of a newer [revision] already ran. */
    public data class PersistApproval(val approval: LearningApproval, val revision: Long = 0) : AgentLearningEffect
}

/** One-shot notifications. */
public sealed interface AgentLearningOutput : MachineOutput {
    /** [Public.Learn][AgentLearningIntent.Public.Learn] with [requestId] stored the instruction [id]. */
    public data class Learned(val requestId: String, val id: InstructionId) : AgentLearningOutput

    /** [Public.Learn][AgentLearningIntent.Public.Learn] with [requestId] was not taken. */
    public data class Rejected(val requestId: String, val reason: LearnRejection) : AgentLearningOutput

    /** A change was not persisted. */
    public data object StorageFailed : AgentLearningOutput
}

/** Profile-scoped registry machine; started with the profile's learning bindings. */
public object AgentLearningMachineKey :
    MachineKey<
        AgentLearningState,
        AgentLearningIntent,
        AgentLearningIntent.Public,
        AgentLearningEffect,
        AgentLearningOutput,
    > {
    override val name: String = "agent-learning"
}
