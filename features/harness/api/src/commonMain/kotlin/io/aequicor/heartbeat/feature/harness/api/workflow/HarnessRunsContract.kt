package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.time.Instant

/** Profile workflow journal projection. IO persistence belongs to Restore/Drive/StopRun. */
public sealed interface HarnessRunsState : MachineState {
    public val isSuspended: Boolean

    /** No journal has been requested yet. */
    public data class Idle(override val isSuspended: Boolean = false) : HarnessRunsState

    /** The durable run journal is being loaded. */
    public data class Loading(override val isSuspended: Boolean = false) : HarnessRunsState

    /** Journal loading failed; Internal.Start retries it. */
    public data class Failed(override val isSuspended: Boolean = false) : HarnessRunsState

    /** Committed journal projection and transient driver observations. */
    public data class Ready(val runs: List<WorkflowRun> = emptyList(), override val isSuspended: Boolean = false) :
        HarnessRunsState {
        init {
            require(runs.map { it.id }.distinct().size == runs.size)
        }
    }
}

/** Correlated requests and generation-fenced feedback from durable drivers. */
public sealed interface HarnessRunsIntent : MachineIntent {
    /** Host requests admitted by the profile machine. */
    public sealed interface Public : HarnessRunsIntent {
        public val requestId: RequestId

        /** The host generates the run id, pins code, validates input and obtains approval before admission. */
        public data class Start(override val requestId: RequestId, val run: WorkflowRun, val at: Instant) : Public

        /** Cancellation is complete only after StopRun confirms all owned native work has settled. */
        public data class Cancel(override val requestId: RequestId, val run: RunId, val by: WorkflowViewer) : Public
    }

    /** Trusted effect feedback; generation checks reject retired drivers. */
    public sealed interface Internal : HarnessRunsIntent {
        /** Starts or retries journal restoration. */
        public data object Start : Internal

        /** Loaded journal records, evaluated at the host clock instant. */
        public data class Restored(val runs: List<WorkflowRun>, val at: Instant) : Internal

        /** Loading failed without a usable journal snapshot. */
        public data object RestoreFailed : Internal

        /** Stops drivers while preserving recoverable Running records. */
        public data object Suspended : Internal

        /** Resumes recoverable records after the harness toggle is enabled. */
        public data object Resumed : Internal

        /** Already saved by Drive before any helper send; recovery advances attempt and changes request. */
        public data class StepStarted(val run: RunId, val generation: Long, val step: WorkflowStep) : Internal

        /** A durable exact-step result available for replay. */
        public data class StepFinished(val run: RunId, val generation: Long, val step: WorkflowStep) : Internal

        /** A durable typed step failure available for replay. */
        public data class StepFailed(val run: RunId, val generation: Long, val step: WorkflowStep) : Internal

        /** Current live requests; these are never part of the stored journal. */
        public data class PermissionsChanged(
            val run: RunId,
            val generation: Long,
            val awaiting: Map<SessionRef, List<PermissionRequest>>,
        ) : Internal {
            override fun toString(): String = "PermissionsChanged(run=$run, generation=$generation, ***)"
        }

        /** Durable terminal receipt, after helper cleanup; late receipts from another generation are ignored. */
        public data class Finished(
            val run: RunId,
            val generation: Long,
            val status: WorkflowStatus.Completed,
            val at: Instant,
        ) : Internal

        /** Durable terminal failure after confirmed cleanup of all helpers. */
        public data class Failed(val run: RunId, val generation: Long, val reason: WorkflowFailure, val at: Instant) :
            Internal

        /**
         * The previous driver was revoked and the next generation/attempt reserved durably. The machine
         * accepts exactly generation + 1 and launches replay. Retrying a helper inside one driver instead
         * uses StepStarted with a new request/step attempt; it does not restart the driver.
         */
        public data class Recovered(val run: RunId, val generation: Long, val attempt: Int) : Internal
    }
}

/** IO boundaries; implementations serialize generation reservations before running or stopping drivers. */
public sealed interface HarnessRunsEffect : MachineEffect {
    /** Reads profile journal records and reports Restored or RestoreFailed. */
    public data object Restore : HarnessRunsEffect

    /** Persist initial state/generation before replay. Each run is supervised independently. */
    public data class Drive(val runs: List<WorkflowRun>) : HarnessRunsEffect

    /** Persist cancellation, revoke the driver and confirm helper cleanup before Failed feedback/outbox finish. */
    public data class StopRun(val run: WorkflowRun) : HarnessRunsEffect

    /** Stop driver jobs without terminalizing runs or claiming native helper completion. */
    public data class Pause(val runs: List<WorkflowRun>) : HarnessRunsEffect
}

/** Admission and terminal notifications; consumers obtain persisted results from the journal projection. */
public sealed interface HarnessRunsOutput : MachineOutput {
    /** A new run was admitted; Drive owns persistence before native submission. */
    public data class Started(val requestId: RequestId, val run: RunId) : HarnessRunsOutput

    /** Cleanup has been requested; this is not a terminal receipt. */
    public data class CancellationRequested(val requestId: RequestId, val run: RunId) : HarnessRunsOutput

    /** The request had no effect on execution. */
    public data class Rejected(val requestId: RequestId, val reason: WorkflowRejection) : HarnessRunsOutput

    /** One exact run reached a durable terminal outcome. */
    public data class RunFinished(val run: RunId, val status: WorkflowStatus) : HarnessRunsOutput
}

/** Stable safe errors; NotFound includes attempts by a different session. */
public enum class WorkflowRejection { Unavailable, Duplicate, Capacity, InvalidRun, NotFound, AlreadyFinished }

/** Profile-scoped address of workflow execution. */
public object HarnessRunsMachineKey : MachineKey<
    HarnessRunsState,
    HarnessRunsIntent,
    HarnessRunsIntent.Public,
    HarnessRunsEffect,
    HarnessRunsOutput,
> {
    override val name: String = "harness_runs"
}
