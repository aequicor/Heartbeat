package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.GraphAction
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraph
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphDefinition
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.api.TaskRecoveryDecision
import io.aequicor.heartbeat.feature.scheduler.api.edges
import io.aequicor.heartbeat.feature.scheduler.api.validationError
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/** Immutable graph approvals and owner-scoped status, cancellation and interrupted-attempt resolution. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class TaskGraphTools(private val machine: TaskGraphMachine, private val driver: TaskGraphDriver) :
    AgentToolContribution {
    override val isDetachedSupported: Boolean get() = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = when {
        driver.isEnabled() -> TaskGraphToolSpecs.all

        ready()?.graphs?.isNotEmpty() == true -> TaskGraphToolSpecs.all.filter {
            it.name !=
                TaskGraphToolSpecs.CREATE
        }

        else -> emptyList()
    }

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (driver.isEnabled()) {
        "Use scheduler_create_graph to approve and run an entire dependency graph. " +
            "Independent tasks run concurrently; dependencies.kind all waits for every edge, any for one edge. " +
            "An edge outcome is succeeded by default, or finished (also failure, timeout or cancellation). " +
            "Choose a stable graph_id for retries of the same creation. Graphs execute automatically and wake " +
            "their owner when finished or when recovery needs a decision. Do not poll or create sleep timers. " +
            "For interrupted commands inspect saved results and actual effects, then use " +
            "scheduler_resolve_interrupted_task for the exact execution ID. Never repeat them outside the graph."
    } else {
        ""
    }

    override suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean = spec.name == TaskGraphToolSpecs.CREATE

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        if (spec.name == TaskGraphToolSpecs.CREATE) {
            val graph = parseGraph(context, arguments)
            val description = "Граф ${graph.id}\n${graphJson.encodeToString(graph.definition)}\n" +
                "Готовые задачи запускаются автоматически. После прерывания команды агент решает, нужен ли повтор."
            return AgentToolApproval(spec.name, "Запустить граф задач", description, binding(graph))
        }
        val graph = owned(context, arguments) ?: return AgentToolApproval(spec.name, spec.description)
        return AgentToolApproval(
            spec.name,
            spec.description,
            binding =
                "${graph.approval}\n${spec.name}\n$arguments",
        )
    }

    override suspend fun existingAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval? {
        if (spec.name != TaskGraphToolSpecs.RESOLVE && spec.name != TaskGraphToolSpecs.CANCEL) return null
        val graph = owned(context, arguments) ?: return null
        if (graph.approval.isBlank()) return null
        if (spec.name == TaskGraphToolSpecs.RESOLVE) {
            val run = graph.runs[arguments.text("task_id")]
            if (graph.isCancelled || run?.phase != GraphTaskPhase.RecoveryRequired ||
                run.execution != arguments.text("execution")
            ) {
                return null
            }
        }
        return approval(context, spec, arguments)
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!driver.isEnabled() && name == TaskGraphToolSpecs.CREATE) return failure("Task graphs are turned off")
        val spec = TaskGraphToolSpecs.all.firstOrNull { it.name == name } ?: return failure("Unknown graph tool")
        return when (name) {
            TaskGraphToolSpecs.CREATE -> create(context, spec, arguments)
            TaskGraphToolSpecs.LIST -> list(context)
            TaskGraphToolSpecs.GET -> get(context, arguments)
            TaskGraphToolSpecs.CANCEL, TaskGraphToolSpecs.RESOLVE -> mutate(context, spec, arguments)
            else -> failure("Unknown graph tool")
        }
    }

    private fun list(context: AgentToolContext): AgentToolResult {
        val graphs = ready()?.graphs?.filter { it.owner == context.session }
            ?: return failure("Graph storage is not ready")
        return AgentToolResult(graphs.joinToString("\n") { summary(it) }.ifEmpty { "No graphs" })
    }

    private suspend fun mutate(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject): AgentToolResult {
        val graph = owned(context, arguments) ?: return failure("Unknown graph")
        if (context.authorization != approval(
                context,
                spec,
                arguments,
            )
        ) {
            return failure("Graph authorization changed")
        }
        return if (spec.name == TaskGraphToolSpecs.CANCEL) {
            accepted(TaskGraphIntent.Public.Cancel(graph.id, context.session), "Cancellation requested")
        } else {
            resolve(context, graph, arguments)
        }
    }

    private suspend fun get(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val graph = owned(context, arguments) ?: return failure("Unknown graph")
        val task = arguments.text("task_id")
        if (task != null && task !in graph.runs) return failure("Unknown task")
        val view = buildJsonObject {
            put("graph_id", graph.id)
            put("paused", !driver.isEnabled())
            put(
                "waiting_reasons",
                buildJsonObject {
                    graph.definition.tasks.filter { graph.runs.getValue(it.id).phase == GraphTaskPhase.Pending }
                        .forEach { node ->
                            put(
                                node.id,
                                node.dependencies.edges.joinToString {
                                    val phase = graph.runs.getValue(it.task).phase
                                    "${it.task}: requires ${it.outcome}, currently $phase"
                                }.ifEmpty { "Waiting for an execution slot or enabled task graphs" },
                            )
                        }
                },
            )
            put("finished", graph.isFinished)
            put("cancelled", graph.isCancelled)
            put(
                "tasks",
                graphJson.encodeToJsonElement(
                    TaskGraphDefinition.serializer(),
                    TaskGraphDefinition(graph.definition.tasks.filter { task == null || it.id == task }),
                ),
            )
            put(
                "runs",
                graphJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.MapSerializer(
                        kotlinx.serialization.serializer<String>(),
                        io.aequicor.heartbeat.feature.scheduler.api.GraphTaskRun.serializer(),
                    ),
                    graph.runs.filterKeys { task == null || it == task },
                ),
            )
        }
        return AgentToolResult(view.toString())
    }

    private suspend fun create(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject): AgentToolResult {
        val parsed = parseGraph(context, arguments)
        val expected = approval(context, spec, arguments)
        if (context.authorization != expected) return failure("The complete graph must be approved first")
        val graph = parsed.copy(approval = checkNotNull(expected.binding))
        val existing = ready()?.graphs?.find { it.id == graph.id }
        if (existing != null) {
            return if (existing.owner == graph.owner && existing.approval == graph.approval) {
                AgentToolResult("Graph already exists: ${summary(existing)}")
            } else {
                failure("Graph id is already used")
            }
        }
        val error = graph.definition.validationError()
        if (error != null) return failure(error)
        if (graph.definition.tasks.any { it.action is GraphAction.Command } &&
            (graph.workspace == null || !driver.areCommandsAvailable)
        ) {
            return failure("Command tasks require a Desktop project")
        }
        if (driver.taskHost(
                graph,
            ) == null || graph.target == null
        ) {
            return failure("No graph-capable chat host owns this session")
        }
        return accepted(
            TaskGraphIntent.Public.Create(graph),
            "Graph ${graph.id} accepted; ready tasks start automatically",
        )
    }

    private suspend fun resolve(context: AgentToolContext, graph: TaskGraph, arguments: JsonObject): AgentToolResult {
        val task = arguments.text("task_id") ?: return failure("Missing task_id")
        val run = graph.runs[task] ?: return failure("Unknown task")
        val execution = arguments.text("execution") ?: return failure("Missing execution")
        if (run.phase != GraphTaskPhase.RecoveryRequired || run.execution != execution) {
            return failure(
                "Stale recovery decision",
            )
        }
        val decision = when (arguments.text("decision")) {
            "retry" -> TaskRecoveryDecision.Retry
            "succeeded" -> TaskRecoveryDecision.Succeeded
            "failed" -> TaskRecoveryDecision.Failed
            else -> return failure("Decision must be retry, succeeded or failed")
        }
        val explanation = arguments.text("explanation") ?: return failure("Explain the inspected evidence")
        if (explanation.length > SchedulerLimits.MAX_PAYLOAD) return failure("Explanation is too long")
        val intent = TaskGraphIntent.Public.Resolve(graph.id, task, execution, context.session, decision, explanation)
        val isSaved = withTimeoutOrNull(10.seconds) { driver.resolveInterrupted(intent) }
        return if (isSaved == true) {
            AgentToolResult("Recovery decision saved")
        } else {
            failure(
                "Decision not confirmed: the attempt changed, still runs, or storage is unavailable; inspect the graph",
            )
        }
    }

    private suspend fun accepted(intent: TaskGraphIntent, message: String): AgentToolResult {
        val isAccepted = withTimeoutOrNull(10.seconds) { driver.durable(intent) }
        return when (isAccepted) {
            true -> AgentToolResult(message)
            false -> failure("Operation was not accepted; read the current graph")
            null -> failure("Durable acceptance is not confirmed yet; read the graph before retrying")
        }
    }

    private fun ready(): TaskGraphState.Ready? = machine.state.value as? TaskGraphState.Ready

    private fun owned(context: AgentToolContext, arguments: JsonObject): TaskGraph? =
        ready()?.graphs?.find { it.id == arguments.text("graph_id") && it.owner == context.session }

    private fun parseGraph(context: AgentToolContext, arguments: JsonObject): TaskGraph {
        val id = requireNotNull(arguments.text("graph_id")) { "Missing graph_id" }
        require(id.matches(Regex("[a-z0-9_-]{1,32}"))) { "Invalid graph_id" }
        val definition = graphJson.decodeFromJsonElement<TaskGraphDefinition>(
            buildJsonObject {
                put("tasks", requireNotNull(arguments["tasks"]) { "Missing tasks" })
            },
        )
        require(definition.validationError() == null) { definition.validationError().orEmpty() }
        return TaskGraph(id, context.session, context.workspace, context.target, definition, "")
    }

    private fun binding(graph: TaskGraph): String = graphJson.encodeToString(
        graph.copy(approval = "", runs = emptyMap()),
    )
    private fun summary(graph: TaskGraph): String = "${graph.id}: " +
        graph.runs.entries.joinToString { "${it.key}=${it.value.phase}" }
    private fun failure(text: String) = AgentToolResult(text, isError = true)

    private companion object {
        val graphJson = Json {
            classDiscriminator = "kind"
            encodeDefaults = true
        }
    }
}
