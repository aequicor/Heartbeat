package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** Profile-owned graphs. Execution is allowed only after the current revision has reached storage. */
public sealed interface TaskGraphState : MachineState {
    /** No task may run before durable state is read. */
    public data object Loading : TaskGraphState

    /** A failed read is retriable and never interpreted as an empty schedule. */
    public data object Unavailable : TaskGraphState

    /** Insertion order is the queue order across graphs and their task declarations. */
    public data class Ready(
        val graphs: List<TaskGraph> = emptyList(),
        val revision: Long = 0,
        val persisted: Long = -1,
        val isStorageFailed: Boolean = false,
    ) : TaskGraphState
}

/** Commands and durable execution feedback. Every attempt-specific message must match its current execution ID. */
public sealed interface TaskGraphIntent : MachineIntent {
    /** Operations exposed to the owning session through authorized hosted tools. */
    public sealed interface Public : TaskGraphIntent {
        /** Installs an already approved, platform-validated immutable graph. */
        public data class Create(val graph: TaskGraph) : Public

        /** Revokes all future launches and requests interruption of the graph's active tasks. */
        public data class Cancel(val graph: String, val owner: SessionRef) : Public

        /** Resolves exactly one interrupted attempt, after the executor confirmed it cannot still be running. */
        public data class Resolve(
            val graph: String,
            val task: String,
            val execution: String,
            val owner: SessionRef,
            val decision: TaskRecoveryDecision,
            val explanation: String,
        ) : Public {
            override fun toString(): String = "Resolve(graph=$graph, task=$task, decision=$decision)"
        }
    }

    /** Feedback from persistence and executors, unavailable to other features. */
    public sealed interface Internal : TaskGraphIntent {
        /** Storage acknowledgements and recovery bookkeeping, without starting a new attempt. */
        public sealed interface Feedback : Internal

        /** Loads persisted graphs before admitting work. */
        public data object Start : Feedback

        /** Atomic snapshot returned by storage. */
        public data class Loaded(val graphs: List<TaskGraph>) : Feedback

        /** Retains an unavailable state; no empty fallback. */
        public data object LoadFailed : Feedback

        /** Confirms the durable revision barrier. */
        public data class Saved(val revision: Long) : Feedback

        /** Suspends launches until a write succeeds. */
        public data object SaveFailed : Feedback

        /** Retries the current snapshot after a storage failure. */
        public data object RetrySave : Feedback

        /** Reserves a ready task for exactly one execution. */
        public data class Claim(val graph: String, val task: String, val execution: String) : Internal

        /** Persists the prepared chat identity before submitting a prompt. */
        public data class Bound(val graph: String, val task: String, val execution: String, val hostTask: String) :
            Internal

        /** Records submission intent before calling a native executor. */
        public data class Starting(val graph: String, val task: String, val execution: String) : Internal

        /** Records process containment before releasing its stdin handshake. */
        public data class ProcessBound(
            val graph: String,
            val task: String,
            val execution: String,
            val process: TaskProcess,
        ) : Internal

        /** Commits a typed result for the current execution only. */
        public data class Finished(
            val graph: String,
            val task: String,
            val execution: String,
            val result: GraphTaskResult,
        ) : Internal

        /** Acknowledges delivery of the recovery request to the owner. */
        public data class RecoveryNotified(val graph: String, val task: String, val execution: String) : Feedback

        /** Confirms the previous native action cannot still be running. */
        public data class RecoveryChecked(val graph: String, val task: String, val execution: String) : Feedback

        /** Acknowledges final notification after every branch settles. */
        public data class CompletionNotified(val graph: String) : Feedback

        /** Returns a never-submitted attempt to its ready queue. */
        public data class Deferred(val graph: String, val task: String, val execution: String) : Feedback
    }
}

/** Persistence is explicit so external effects cannot race ahead of their durable attempts. */
public sealed interface TaskGraphEffect : MachineEffect {
    /** Reads the atomic profile snapshot. */
    public data object Load : TaskGraphEffect

    /** Replaces the profile snapshot and acknowledges its revision. */
    public data class Save(val graphs: List<TaskGraph>, val revision: Long) : TaskGraphEffect
}

/** State is authoritative; there are no transient completion notifications needed by consumers. */
public sealed interface TaskGraphOutput : MachineOutput

/** Profile-scoped graph coordinator, separate from the compatible session wake machine. */
public object TaskGraphMachineKey :
    MachineKey<TaskGraphState, TaskGraphIntent, TaskGraphIntent.Public, TaskGraphEffect, TaskGraphOutput> {
    override val name: String = "scheduler-task-graphs"
}

/**
 * Pure scheduling decisions. The driver claims only when a shared execution slot is reserved.
 *
 * | From | Intent | To / effect |
 * |---|---|---|
 * | Loading / Unavailable | Start | Load |
 * | Loading / Unavailable | Loaded | Ready(recovered graphs); Save |
 * | Loading / Unavailable | LoadFailed | Unavailable |
 * | Ready | Create valid new graph | append immutable graph; Save |
 * | Ready | Claim ready pending/recovering node | Running(new attempt); Save |
 * | Ready | Bound / Starting / ProcessBound | retain execution identity; Save |
 * | Ready | Finished current attempt | settle result and impossible dependencies; Save |
 * | Ready | Cancel owned graph | cancel pending, request active cancellation; Save |
 * | Ready | Resolve checked interrupted attempt | Pending or terminal; Save |
 * | Ready | RecoveryNotified / RecoveryChecked | record recovery progress; Save |
 * | Ready | Saved | acknowledge revision |
 * | Ready | SaveFailed / RetrySave | inhibit execution / retry Save |
 *
 * Duplicate, stale, foreign-owner and invalid operations are ignored. Storage errors cannot start work.
 */
public val TaskGraphMachineSpec: MachineSpec<TaskGraphState, TaskGraphIntent, TaskGraphEffect, TaskGraphOutput> =
    machineSpec(
        TaskGraphMachineKey,
        TaskGraphState.Loading,
    ) {
        state<TaskGraphState.Loading> {
            on<TaskGraphIntent.Internal.Start> { effect { TaskGraphEffect.Load } }
            on<TaskGraphIntent.Internal.Loaded> {
                goto<TaskGraphState.Ready> { TaskGraphState.Ready(intent.graphs.map(TaskGraph::recover).queueReady()) }
                effect { TaskGraphEffect.Save(intent.graphs.map(TaskGraph::recover).queueReady(), 0) }
            }
            on<TaskGraphIntent.Internal.LoadFailed> { goto<TaskGraphState.Unavailable> { TaskGraphState.Unavailable } }
        }
        state<TaskGraphState.Unavailable> {
            on<TaskGraphIntent.Internal.Start> { effect { TaskGraphEffect.Load } }
            on<TaskGraphIntent.Internal.Loaded> {
                goto<TaskGraphState.Ready> { TaskGraphState.Ready(intent.graphs.map(TaskGraph::recover).queueReady()) }
                effect { TaskGraphEffect.Save(intent.graphs.map(TaskGraph::recover).queueReady(), 0) }
            }
            on<TaskGraphIntent.Internal.LoadFailed>()
        }
        state<TaskGraphState.Ready> {
            on<TaskGraphIntent>(guard = { state.reduce(intent) != null }) {
                stay { checkNotNull(state.reduce(intent)) }
                effect {
                    val next = checkNotNull(state.reduce(intent))
                    if (next.revision != state.revision || intent == TaskGraphIntent.Internal.RetrySave) {
                        TaskGraphEffect.Save(next.graphs, next.revision)
                    } else {
                        null
                    }
                }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                TaskGraphEffect.Load -> TaskGraphIntent.Internal.LoadFailed
                is TaskGraphEffect.Save -> TaskGraphIntent.Internal.SaveFailed
            }
        }
    }

private fun TaskGraphState.Ready.reduce(intent: TaskGraphIntent): TaskGraphState.Ready? = when (intent) {
    is TaskGraphIntent.Public.Create -> create(intent)

    is TaskGraphIntent.Public.Cancel -> cancel(intent)

    is TaskGraphIntent.Public.Resolve -> resolve(intent)

    is TaskGraphIntent.Internal.Claim -> claim(intent)

    is TaskGraphIntent.Internal.Bound -> editRun(intent.graph, intent.task, intent.execution) {
        it.takeIf { run -> run.phase == GraphTaskPhase.Running }?.copy(hostTask = intent.hostTask)
    }

    is TaskGraphIntent.Internal.Starting -> editRun(intent.graph, intent.task, intent.execution) {
        it.takeIf { run -> run.phase == GraphTaskPhase.Running && !run.hasStarted }?.copy(hasStarted = true)
    }

    is TaskGraphIntent.Internal.ProcessBound -> editRun(intent.graph, intent.task, intent.execution) {
        it.takeIf { run -> run.phase in setOf(GraphTaskPhase.Running, GraphTaskPhase.Cancelling) }
            ?.copy(process = intent.process)
    }

    is TaskGraphIntent.Internal.Finished -> editRun(intent.graph, intent.task, intent.execution) {
        it.takeIf { run -> run.phase in setOf(GraphTaskPhase.Running, GraphTaskPhase.Cancelling) }
            ?.copy(phase = intent.result.phase, result = intent.result)
    }

    is TaskGraphIntent.Internal.Feedback -> feedback(intent)
}

private fun TaskGraphState.Ready.changed(next: List<TaskGraph>): TaskGraphState.Ready =
    copy(graphs = next.queueReady(), revision = revision + 1)

private fun TaskGraphState.Ready.editGraph(id: String, change: (TaskGraph) -> TaskGraph?): TaskGraphState.Ready? {
    val graph = graphs.find { it.id == id } ?: return null
    val updated = change(graph) ?: return null
    return changed(graphs.map { if (it.id == id) updated else it })
}

private fun TaskGraphState.Ready.editRun(
    graph: String,
    task: String,
    execution: String,
    change: (GraphTaskRun) -> GraphTaskRun?,
): TaskGraphState.Ready? = editGraph(graph) { current ->
    val run = current.runs[task]?.takeIf { it.execution == execution }
    val updated = run?.let(change)
    updated?.let { current.copy(runs = current.runs + (task to it)).settleBlocked() }
}

private fun TaskGraph.recover(): TaskGraph = copy(
    runs = runs.mapValues { (id, run) ->
        if (run.phase != GraphTaskPhase.Running) {
            run
        } else {
            val action = definition.tasks.first { it.id == id }.action
            val phase = when {
                action is GraphAction.Agent -> GraphTaskPhase.Recovering
                !run.hasStarted -> GraphTaskPhase.Pending
                else -> GraphTaskPhase.RecoveryRequired
            }
            run.copy(phase = phase, isRecoveryNotified = false)
        }
    },
)

/** FIFO begins when dependencies first become satisfied, not when a graph happened to be declared. */
private fun List<TaskGraph>.queueReady(): List<TaskGraph> {
    var sequence = flatMap { it.runs.values }.mapNotNull { it.queued }.maxOrNull() ?: 0
    return map { graph ->
        graph.copy(
            runs = graph.runs.mapValues { (id, run) ->
                val task = graph.definition.tasks.first { it.id == id }
                if (run.queued == null && graph.canClaim(task, run)) {
                    run.copy(queued = ++sequence)
                } else {
                    run
                }
            },
        )
    }
}

private fun TaskGraphState.Ready.create(intent: TaskGraphIntent.Public.Create): TaskGraphState.Ready? =
    intent.graph.takeIf {
        it.definition.validationError() == null && graphs.none { graph -> graph.id == it.id } &&
            it.approval.isNotBlank() && it.runs == it.definition.tasks.associate { task ->
                task.id to GraphTaskRun()
            } &&
            !it.isCancelled
    }?.let { changed(graphs + it) }

private fun TaskGraphState.Ready.cancel(intent: TaskGraphIntent.Public.Cancel): TaskGraphState.Ready? = editGraph(
    intent.graph,
) { graph ->
    graph.takeIf { it.owner == intent.owner && !it.isCancelled && !it.isFinished }?.copy(
        isCancelled = true,
        runs = graph.runs.mapValues { (_, run) ->
            when {
                run.phase.isTerminal -> run
                run.phase == GraphTaskPhase.Pending -> run.copy(phase = GraphTaskPhase.Cancelled)
                else -> run.copy(phase = GraphTaskPhase.Cancelling)
            }
        },
    )
}

private fun TaskGraphState.Ready.resolve(intent: TaskGraphIntent.Public.Resolve): TaskGraphState.Ready? = editGraph(
    intent.graph,
) { graph ->
    val run = graph.runs[intent.task]
    val isOwned = graph.owner == intent.owner && !graph.isCancelled
    val isChecked = run?.phase == GraphTaskPhase.RecoveryRequired && !run.hasNativeWork
    val isCurrent = run?.execution == intent.execution
    val hasExplanation = intent.explanation.isNotBlank() && intent.explanation.length <= SchedulerLimits.MAX_PAYLOAD
    if (!listOf(isOwned, isChecked, isCurrent, hasExplanation).all { it }) {
        null
    } else {
        val phase = when (intent.decision) {
            TaskRecoveryDecision.Retry -> GraphTaskPhase.Pending
            TaskRecoveryDecision.Succeeded -> GraphTaskPhase.Succeeded
            TaskRecoveryDecision.Failed -> GraphTaskPhase.Failed
        }
        graph.copy(
            runs = graph.runs + (
                intent.task to checkNotNull(run).copy(
                    phase = phase,
                    resolution = intent.explanation,
                    result = if (phase.isTerminal) GraphTaskResult(phase, intent.explanation) else run.result,
                )
            ),
        ).settleBlocked()
    }
}

private fun TaskGraphState.Ready.claim(intent: TaskGraphIntent.Internal.Claim): TaskGraphState.Ready? = editGraph(
    intent.graph,
) { graph ->
    val task = graph.definition.tasks.find { it.id == intent.task }
    val run = graph.runs[intent.task]
    val isReady = task != null && run != null && graph.canClaim(task, run)
    if (graph.isCancelled || !isReady) {
        null
    } else {
        graph.copy(
            runs = graph.runs + (
                task.id to run.copy(
                    phase = GraphTaskPhase.Running,
                    attempt = run.attempt + 1,
                    queued = null,
                    resolution = null,
                    attempts = run.attempts + listOfNotNull(
                        run.execution?.let {
                            GraphTaskAttempt(it, run.attempt, run.hostTask, run.process, run.result, run.resolution)
                        },
                    ),
                    previousExecution = if (run.phase == GraphTaskPhase.Recovering) {
                        if (run.hasStarted) run.execution else run.previousExecution
                    } else {
                        null
                    },
                    execution = intent.execution,
                    hasStarted = false,
                    process = null,
                    result = null,
                    isRecoveryNotified = false,
                )
            ),
        )
    }
}

private fun TaskGraph.canClaim(task: GraphTask, run: GraphTaskRun): Boolean = run.phase == GraphTaskPhase.Recovering ||
    (run.phase == GraphTaskPhase.Pending && readiness(task) == TaskReadiness.Ready)

private fun TaskGraphState.Ready.feedback(intent: TaskGraphIntent.Internal.Feedback): TaskGraphState.Ready? =
    when (intent) {
        is TaskGraphIntent.Internal.RecoveryNotified -> editRun(intent.graph, intent.task, intent.execution) {
            it.takeIf { run -> run.phase == GraphTaskPhase.RecoveryRequired && !run.isRecoveryNotified }
                ?.copy(isRecoveryNotified = true)
        }

        is TaskGraphIntent.Internal.RecoveryChecked -> editRun(intent.graph, intent.task, intent.execution) {
            it.takeIf { run ->
                run.phase == GraphTaskPhase.RecoveryRequired &&
                    run.hasNativeWork
            }
                ?.copy(hasStarted = false, process = null, previousExecution = null)
        }

        is TaskGraphIntent.Internal.CompletionNotified -> editGraph(intent.graph) {
            it.takeIf { graph -> graph.isFinished && !graph.isCompletionNotified }?.copy(isCompletionNotified = true)
        }

        is TaskGraphIntent.Internal.Deferred -> editRun(intent.graph, intent.task, intent.execution) {
            it.takeIf { run -> run.phase == GraphTaskPhase.Running }?.copy(
                phase = if (it.previousExecution != null) GraphTaskPhase.Recovering else GraphTaskPhase.Pending,
                hasStarted = false,
                process = null,
            )
        }

        is TaskGraphIntent.Internal.Saved -> takeIf { intent.revision > persisted }?.copy(
            persisted = intent.revision,
            isStorageFailed = false,
        )

        TaskGraphIntent.Internal.SaveFailed -> copy(isStorageFailed = true)

        TaskGraphIntent.Internal.RetrySave -> takeIf { isStorageFailed }

        TaskGraphIntent.Internal.Start, TaskGraphIntent.Internal.LoadFailed, is TaskGraphIntent.Internal.Loaded -> null
    }
