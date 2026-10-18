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

        /**
         * Creates one checkout per chat from the selected project's current committed HEAD. [isSandbox] is the chat's
         * choice: false keeps a plain worktree, true asks for a sandbox, null follows [WorktreeSandboxEnabled]. A
         * sandbox is created only while that toggle is on; an existing task keeps the isolation it was created with.
         * A pinned request requires sandbox support and refuses a fallback. It fixes a physical sandbox to
         * [baseCommit] or the initially captured HEAD, with builds in the existing snapshot pool. Repeated pinned
         * requests must match the saved project and base.
         */
        public data class Prepare(
            val chatId: String,
            val project: WorkspaceRef,
            val isSandbox: Boolean? = null,
            val parentWorkspace: WorkspaceRef? = null,
            /** Fix this sandbox to its initial committed base; it never follows source rebases. */
            val isBasePinned: Boolean = false,
            /** Optional full Git commit; accepted only for a pinned sandbox. */
            val baseCommit: String? = null,
        ) : Public

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
            val target: io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget? = null,
        ) : Public

        /** Authoritative request acceptance binds hosted tools to a native session and turn. */
        public data class RunAccepted(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
        ) : Public

        /**
         * Proven rejection before any native session/turn is recorded; no synthetic native outcome is invented.
         * An uncertain submission with a recorded session needs [RunSubmissionReconciled] after native idle inspection.
         */
        public data class RunRejected(val chatId: String, val request: RequestId, val reason: String) : Public

        /**
         * Submission observation ended before acceptance was confirmed. Retains the request and session without
         * inventing a turn or a rejection; native reconciliation is required before releasing any merge lease.
         */
        public data class RunSubmissionObservationLost(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val reason: String,
        ) : Public

        /**
         * A fresh native inspection proved the same session idle after uncertain submission. Clears only the
         * matching unaccepted recovery request and releases its lease without claiming rejection or completion.
         */
        public data class RunSubmissionReconciled(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
        ) : Public

        /** Native supervision was lost; identity is retained and an active merge lease must remain held. */
        public data class RunObservationLost(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
            val reason: String,
        ) : Public

        /**
         * A restarted host found the native turn of a pending Coding run still running and observes it again. Takes the
         * run out of recovery only when [session] and [turn] are exactly the identity the run already recorded;
         * a turn the run never recorded is never adopted, since nothing proves it belongs to [request].
         */
        public data class RunAdopted(
            val chatId: String,
            val request: RequestId,
            val session: SessionRef,
            val turn: TurnId,
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
        public data class Recheck(
            val chatId: String,
            val isResume: Boolean = false,
            // Existing public named argument retained for source compatibility.
            @Suppress("BooleanPropertyNaming")
            val resolveConflicts: Boolean = false,
        ) : Public

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
            val expectedSnapshot: String? = null,
            val expectedProjectRevision: Long? = null,
        ) : Public

        /** Cancels a queued build or requests worker termination before releasing resources. */
        public data class CancelBuild(val chatId: String, val operation: String) : Public

        /**
         * Captures the files of the isolated checkout as an immutable commit on top of the task branch: the work the
         * agent committed and whatever it left uncommitted (ignored files excluded). The branch, the index and the
         * files stay untouched, so a sibling can integrate the result safely. Refused while a native run of the task
         * is active. The outcome lands in [WorktreeTask.seal] under [operation], the failure included.
         */
        // Existing public named argument retained for source compatibility.
        @Suppress("BooleanPropertyNaming")
        public data class Seal(val chatId: String, val operation: String, val handoff: Boolean = false) : Public
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
