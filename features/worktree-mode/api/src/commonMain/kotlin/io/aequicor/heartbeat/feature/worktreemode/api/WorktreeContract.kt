package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Profile machine; journal IO, not scope.savedState, owns restoration after process death. */
public sealed interface WorktreeState : MachineState {
    /** Not started yet. */
    public data object Idle : WorktreeState

    /** Restoring durable tasks and inspecting their checkout/worker state. */
    public data object Loading : WorktreeState

    /** Storage failed closed; retry never discards the journal. */
    public data object LoadError : WorktreeState

    /** Tasks run independently; changes use stay so sibling effects are not cancelled. */
    public data class Ready(val tasks: Map<String, WorktreeTask> = emptyMap()) : WorktreeState
}

/** Public commands and private durable IO results. */
public sealed interface WorktreeIntent : MachineIntent {
    /** Safe entry points for Studio and hosted tools. */
    public sealed interface Public : WorktreeIntent {
        /** Eager profile startup. */
        public data object Start : Public

        /** Retries journal restoration after failure. */
        public data object RetryLoad : Public

        /** Creates one checkout per chat from the selected project's current committed HEAD. */
        public data class Prepare(val chatId: String, val project: WorkspaceRef) : Public

        /** Tracks an original-checkout native session in the shared queue, without a completion card. */
        public data class TrackMainSession(
            val workspace: WorkspaceRef,
            val session: SessionRef,
            val request: RequestId,
            val turn: TurnId,
        ) : Public

        /** Persists execution intent before submitting a prompt; wait for the matching state before sending. */
        public data class RunStarted(
            val chatId: String,
            val request: RequestId,
            val kind: WorktreeRunKind = WorktreeRunKind.Coding,
        ) : Public

        /** Authoritative request acceptance binds hosted tools to a native session and turn. */
        public data class RunAccepted(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
        ) : Public

        /** Proven submission rejection before native acceptance; no synthetic native turn is invented. */
        public data class RunRejected(val chatId: String, val request: RequestId, val reason: String) : Public

        /** Native supervision was lost; identity is retained and an active merge lease must remain held. */
        public data class RunObservationLost(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
            val reason: String,
        ) : Public

        /** Native terminal result; successful completion also requires a signal from the same turn. */
        public data class RunSettled(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
            val outcome: TurnOutcome,
        ) : Public

        /** Explicit agent signal, attributed to trusted host context rather than JSON arguments. */
        public data class TaskCompleteSignaled(
            val chatId: String,
            val session: SessionRef,
            val turn: TurnId,
            val summary: String,
            val pullRequestUrl: String? = null,
        ) : Public

        /**
         * Applies a choice. Optional refinement schedules a follow-up;
         * blank refinement clears the card for the composer.
         */
        public data class ChooseAction(
            val chatId: String,
            val action: WorktreeAction,
            val refinement: String? = null,
        ) : Public

        /** Records handoff before a profile bridge submits it; ambiguity must never invite automatic replay. */
        public data class ActionDelivered(val chatId: String, val operation: String) : Public

        /** Proven handoff failure before a matching native run exists; recovery never replays the prompt. */
        public data class ActionDeliveryFailed(val chatId: String, val operation: String, val reason: String) : Public

        /** Checks checkout and pending workers explicitly without replaying a native prompt. */
        public data class Recheck(val chatId: String) : Public

        /** Proposed build declaration from the current native turn. */
        public data class ProposeBuildPlan(
            val chatId: String,
            val plan: WorktreeBuildPlan,
            val expectedRun: WorktreeRunIdentity? = null,
        ) : Public

        /** Approval applies to the exact currently visible task revision and declaration. */
        public data class ApproveBuildPlan(val chatId: String, val revision: Long, val isApproved: Boolean) : Public

        /** Queues an approved command; operation ids are allocated by the host. */
        public data class RunBuild(
            val chatId: String,
            val operation: String,
            val command: String,
            val expectedConfigurationRevision: Long = 0,
            val expectedRun: WorktreeRunIdentity? = null,
        ) : Public

        /** Cancels a queued build or requests worker termination before releasing resources. */
        public data class CancelBuild(val chatId: String, val operation: String) : Public
    }

    /** Persisted results; earlier revisions cannot overwrite later work. */
    public sealed interface Internal : WorktreeIntent {
        /** Journal restoration has completed. */
        public data class Loaded(val tasks: Map<String, WorktreeTask>) : Internal

        /** Durable task update. */
        public data class Updated(val task: WorktreeTask) : Internal

        /** Journal restoration failed. */
        public data object LoadFailed : Internal
    }
}

/** Commands implemented by the profile-owned repository and worker coordinator. */
public sealed interface WorktreeEffect : MachineEffect {
    /** Restores the journal and reconciles external side effects. */
    public data object Load : WorktreeEffect

    /** Reattaches observation to workers already running when the profile was reopened. */
    public data object ObserveWorkers : WorktreeEffect

    /** Executes a public command; durable state is published only after persistence succeeds. */
    public data class Apply(val command: WorktreeIntent.Public) : WorktreeEffect
}

/** Notifications are supplemental; task state is authoritative for late subscribers. */
public sealed interface WorktreeOutput : MachineOutput {
    /** A task's durable state changed. */
    public data class Changed(val chatId: String) : WorktreeOutput
}

/** Address of the eagerly started profile worktree machine. */
public object WorktreeMachineKey :
    MachineKey<WorktreeState, WorktreeIntent, WorktreeIntent.Public, WorktreeEffect, WorktreeOutput> {
    override val name: String = "worktree-mode"
}
