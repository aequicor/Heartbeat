package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Immutable work approved together with its graph. Commands are never interpolated with dependency output. */
@Serializable
public sealed interface GraphAction {
    /** A shell command in the owner's project, with the same limits as a standalone background command. */
    @Serializable
    @SerialName("command")
    public data class Command(val command: String, val timeoutSeconds: Long = 1_800) : GraphAction {
        override fun toString(): String = "Command(timeoutSeconds=$timeoutSeconds)"
    }

    /** One agent assignment in a dedicated chat; recovery continues that same chat. */
    @Serializable
    @SerialName("agent")
    public data class Agent(val prompt: String, val title: String = "Scheduled task") : GraphAction {
        override fun toString(): String = "Agent"
    }
}

/** Required terminal outcome of one predecessor. Interrupted and blocked work satisfies neither condition. */
@Serializable
public enum class DependencyOutcome {
    @SerialName("succeeded")
    Succeeded,

    @SerialName("finished")
    Finished,
}

/** A predecessor in this graph and the outcome required from it. */
@Serializable
public data class TaskDependency(val task: String, val outcome: DependencyOutcome = DependencyOutcome.Succeeded)

/** How the edges entering a task are combined. An empty AllOf describes an independent root. */
@Serializable
public sealed interface TaskDependencies {
    /** Every edge must be satisfied. */
    @Serializable
    @SerialName("all")
    public data class AllOf(val tasks: List<TaskDependency> = emptyList()) : TaskDependencies

    /** The first satisfied edge releases the task; other branches continue running. */
    @Serializable
    @SerialName("any")
    public data class AnyOf(val tasks: List<TaskDependency>) : TaskDependencies
}

/** All direct predecessors, in declaration order. */
public val TaskDependencies.edges: List<TaskDependency>
    get() = when (this) {
        is TaskDependencies.AllOf -> tasks
        is TaskDependencies.AnyOf -> tasks
    }

/** One immutable node. IDs are local to the graph and use letters, digits, underscores or hyphens. */
@Serializable
public data class GraphTask(
    val id: String,
    val action: GraphAction,
    val dependencies: TaskDependencies = TaskDependencies.AllOf(),
)

/** A validated graph has 1–64 nodes, unique IDs, existing predecessors and no cycles. */
@Serializable
public data class TaskGraphDefinition(val tasks: List<GraphTask>) {
    override fun toString(): String = "TaskGraphDefinition(tasks=${tasks.size})"
}

/** Execution state of a node. Recovering and RecoveryRequired are nonterminal. */
@Serializable
public enum class GraphTaskPhase {
    Pending,
    Running,
    Recovering,
    RecoveryRequired,
    Cancelling,
    Succeeded,
    Failed,
    TimedOut,
    Cancelled,
    Blocked,
}

/** Whether scheduling for this node is over. Blocked nodes never actually ran. */
public val GraphTaskPhase.isTerminal: Boolean
    get() = this in setOf(
        GraphTaskPhase.Succeeded,
        GraphTaskPhase.Failed,
        GraphTaskPhase.TimedOut,
        GraphTaskPhase.Cancelled,
        GraphTaskPhase.Blocked,
    )

/**
 * Persisted identity of a local process; start time prevents confusing a reused PID with the original process.
 * [descendants] retains observed POSIX children even after they leave the original process group.
 */
@Serializable
public data class TaskProcess(
    val pid: Long,
    val startedAt: String,
    val group: Long? = null,
    val isJobContained: Boolean = false,
    val job: String? = null,
    val descendants: List<TaskProcess> = emptyList(),
)

/** Bounded output and authoritative outcome; free text is never interpreted as a status. */
@Serializable
public data class GraphTaskResult(val phase: GraphTaskPhase, val text: String, val exitCode: Int? = null) {
    init {
        require(phase.isTerminal || phase == GraphTaskPhase.RecoveryRequired)
        require(text.length <= SchedulerLimits.MAX_PAYLOAD)
    }

    override fun toString(): String = "GraphTaskResult(phase=$phase, exitCode=${exitCode ?: "unavailable"})"
}

/** Durable attempt. [execution] also identifies native submissions and recovery decisions. */
@Serializable
public data class GraphTaskRun(
    val phase: GraphTaskPhase = GraphTaskPhase.Pending,
    val attempt: Int = 0,
    val execution: String? = null,
    val previousExecution: String? = null,
    val hostTask: String? = null,
    val hasStarted: Boolean = false,
    val process: TaskProcess? = null,
    val result: GraphTaskResult? = null,
    val isRecoveryNotified: Boolean = false,
    val queued: Long? = null,
    val resolution: String? = null,
    val attempts: List<GraphTaskAttempt> = emptyList(),
)

/** Either this attempt or a previous submission may still own native work. */
public val GraphTaskRun.hasNativeWork: Boolean get() = hasStarted || previousExecution != null

/** Completed attempt metadata retained when a task is retried or observed after restart. */
@Serializable
public data class GraphTaskAttempt(
    val execution: String,
    val number: Int,
    val hostTask: String?,
    val process: TaskProcess?,
    val result: GraphTaskResult?,
    val resolution: String?,
)

/** An approved immutable graph owned by one session. Approval does not raise the helper's tool trust. */
@Serializable
public data class TaskGraph(
    val id: String,
    val owner: SessionRef,
    val workspace: WorkspaceRef?,
    val target: EngineTarget?,
    val definition: TaskGraphDefinition,
    val approval: String,
    val runs: Map<String, GraphTaskRun> = definition.tasks.associate { it.id to GraphTaskRun() },
    val isCancelled: Boolean = false,
    val isCompletionNotified: Boolean = false,
) {
    /** All branches settled, including those not needed by an AnyOf successor. */
    public val isFinished: Boolean get() = runs.values.all { it.phase.isTerminal }

    override fun toString(): String = "TaskGraph(id=$id, tasks=${runs.size}, isCancelled=$isCancelled)"
}

/** The coordinator's decision after inspecting the actual effects of an interrupted task. */
@Serializable
public enum class TaskRecoveryDecision { Retry, Succeeded, Failed }

/** Graph validation independent of platform availability and authorization. */
public fun TaskGraphDefinition.validationError(): String? {
    val ids = tasks.map { it.id }
    val error = when {
        tasks.isEmpty() || tasks.size > MAX_TASKS -> "A graph needs 1 to 64 tasks"
        ids.any { !it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } -> "Invalid task id"
        ids.toSet().size != ids.size -> "Duplicate task id"
        else -> tasks.firstNotNullOfOrNull { it.validationError(ids) }
    }
    return error ?: "Cyclic dependencies".takeIf { hasCycles() }
}

private fun GraphTask.validationError(ids: List<String>): String? {
    val edges = dependencies.edges
    return when {
        dependencies is TaskDependencies.AnyOf && edges.isEmpty() -> "AnyOf needs a predecessor"
        edges.map { it.task }.distinct().size != edges.size -> "Duplicate dependency"
        edges.any { it.task !in ids || it.task == id } -> "Unknown or self dependency"
        else -> action.validationError()
    }
}

private fun GraphAction.validationError(): String? = when (this) {
    is GraphAction.Command -> "Invalid command or timeout".takeIf {
        !command.validText(MAX_COMMAND) || timeoutSeconds !in 1..MAX_TIMEOUT
    }

    is GraphAction.Agent -> "Invalid agent assignment".takeIf {
        !prompt.validText(MAX_PROMPT) || !title.validText(MAX_TITLE)
    }
}

private fun String.validText(limit: Int): Boolean = isNotBlank() && length <= limit

private fun TaskGraphDefinition.hasCycles(): Boolean {
    val visited = mutableSetOf<String>()
    repeat(tasks.size) {
        tasks.filter { task ->
            task.dependencies.edges.all { it.task in visited }
        }.forEach { task -> visited += task.id }
    }
    return visited.size != tasks.size
}

private const val MAX_TASKS = 64
private const val MAX_COMMAND = 4_000
private const val MAX_PROMPT = 8_000
private const val MAX_TITLE = 60
private const val MAX_TIMEOUT = 21_600L

/** Pure readiness verdict used by the machine and its driver. */
public enum class TaskReadiness { Waiting, Ready, Impossible }

/** Evaluates all/any against retained results, including events which arrived before the successor existed. */
public fun TaskGraph.readiness(task: GraphTask): TaskReadiness {
    val edges = task.dependencies.edges
    fun impossible(edge: TaskDependency): Boolean = !satisfied(edge) && runs.getValue(edge.task).phase.isTerminal
    return when (task.dependencies) {
        is TaskDependencies.AllOf -> when {
            edges.all { satisfied(it) } -> TaskReadiness.Ready
            edges.any(::impossible) -> TaskReadiness.Impossible
            else -> TaskReadiness.Waiting
        }

        is TaskDependencies.AnyOf -> when {
            edges.any { satisfied(it) } -> TaskReadiness.Ready
            edges.all(::impossible) -> TaskReadiness.Impossible
            else -> TaskReadiness.Waiting
        }
    }
}

/** Propagates impossible dependencies until stable; independent branches remain executable. */
public fun TaskGraph.settleBlocked(): TaskGraph {
    var graph = this
    repeat(definition.tasks.size) {
        for (task in definition.tasks) {
            val run = graph.runs.getValue(task.id)
            if (run.phase == GraphTaskPhase.Pending && graph.readiness(task) == TaskReadiness.Impossible) {
                graph = graph.copy(
                    runs = graph.runs + (
                        task.id to run.copy(
                            phase = GraphTaskPhase.Blocked,
                            result = GraphTaskResult(
                                GraphTaskPhase.Blocked,
                                "A required predecessor cannot satisfy its dependency",
                            ),
                        )
                    ),
                )
            }
        }
    }
    return graph
}

private fun TaskGraph.satisfied(edge: TaskDependency): Boolean {
    val phase = runs.getValue(edge.task).phase
    return when (edge.outcome) {
        DependencyOutcome.Succeeded -> phase == GraphTaskPhase.Succeeded
        DependencyOutcome.Finished -> phase.isTerminal && phase != GraphTaskPhase.Blocked
    }
}
