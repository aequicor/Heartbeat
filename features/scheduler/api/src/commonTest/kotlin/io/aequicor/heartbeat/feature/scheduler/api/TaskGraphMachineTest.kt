package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskGraphMachineTest {
    private val owner = SessionRef(EngineId("pi"), SessionSourceId("local"), "owner")
    private val command = GraphAction.Command("test")
    private val both = TaskDependencies.AllOf(listOf(TaskDependency("A"), TaskDependency("B")))
    private val either = TaskDependencies.AnyOf(
        listOf(
            TaskDependency("C", DependencyOutcome.Finished),
            TaskDependency("D", DependencyOutcome.Finished),
        ),
    )
    private val definition = TaskGraphDefinition(
        listOf(
            GraphTask("A", command),
            GraphTask("B", command),
            GraphTask("C", command, both),
            GraphTask("D", command, both),
            GraphTask("E", command, either),
        ),
    )

    private fun graph(definition: TaskGraphDefinition = this.definition) =
        TaskGraph("g1", owner, null, null, definition, "approved")

    private fun TaskGraphState.step(intent: TaskGraphIntent): TaskGraphState.Ready {
        val resolution = assertNotNull(TaskGraphMachineSpec.resolve(this, intent))
        val next = resolution.to as TaskGraphState.Ready
        if (intent !is TaskGraphIntent.Internal.Saved) {
            assertEquals(listOf(TaskGraphEffect.Save(next.graphs, next.revision)), resolution.effects)
        }
        return next
    }

    private fun TaskGraphState.finish(task: String, phase: GraphTaskPhase = GraphTaskPhase.Succeeded) = step(
        TaskGraphIntent.Internal.Finished("g1", task, task, GraphTaskResult(phase, "done")),
    )

    @Test
    fun `accepted recovery and cancellation causes persist atomically and survive restart`() {
        val initial = RequestInitiator(owner, RequestId("initial"))
        val retry = RequestInitiator(owner, RequestId("retry"))
        val cancel = RequestInitiator(owner, RequestId("cancel"))
        val run = GraphTaskRun(GraphTaskPhase.RecoveryRequired, execution = "A")
        val graph = graph().copy(initiator = initial, runs = graph().runs + ("A" to run))
        val before = TaskGraphState.Ready(listOf(graph), revision = 1, persisted = 1)
        val resolution = TaskGraphIntent.Public.Resolve(
            "g1",
            "A",
            "A",
            owner,
            TaskRecoveryDecision.Retry,
            "checked",
            retry,
        )
        val after = before.step(resolution)
        assertEquals(setOf(retry), after.graphs.single().causes)
        assertEquals(initial, after.graphs.single().initiator)
        TaskGraphMachineSpec.assertTransition(
            before,
            resolution,
            after,
            effects = listOf(TaskGraphEffect.Save(after.graphs, after.revision)),
        )
        TaskGraphMachineSpec.assertIgnored(after, resolution.copy(cause = cancel))
        val cancelled = after.step(TaskGraphIntent.Public.Cancel("g1", owner, cancel))
        assertEquals(setOf(retry, cancel), cancelled.graphs.single().causes)
        TaskGraphMachineSpec.assertIgnored(cancelled, TaskGraphIntent.Public.Cancel("g1", owner, initial))
        val restarted = TaskGraphState.Loading.step(TaskGraphIntent.Internal.Loaded(cancelled.graphs))
        assertEquals(initial, restarted.graphs.single().initiator)
        assertEquals(setOf(retry, cancel), restarted.graphs.single().causes)
    }

    @Test
    fun `legacy graph snapshot without causal fields decodes without guessed ancestry`() {
        val encoded = Json.encodeToJsonElement(TaskGraph.serializer(), graph()).jsonObject
        val legacy = JsonObject(encoded - setOf("initiator", "causes"))
        val restored = Json.decodeFromString<TaskGraph>(legacy.toString())
        assertNull(restored.initiator)
        assertTrue(restored.causes.isEmpty())
    }

    @Test
    fun `five node graph waits for both roots and releases E exactly once in every finish order`() {
        for (roots in listOf(listOf("A", "B"), listOf("B", "A"))) {
            for (branches in listOf(listOf("C", "D"), listOf("D", "C"))) {
                var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph()))
                for (id in roots) state = state.step(TaskGraphIntent.Internal.Claim("g1", id, id))
                state = state.finish(roots.first())
                TaskGraphMachineSpec.assertIgnored(state, TaskGraphIntent.Internal.Claim("g1", "C", "C"))
                state = state.finish(roots.last())
                for (id in branches) state = state.step(TaskGraphIntent.Internal.Claim("g1", id, id))
                state = state.finish(branches.first(), GraphTaskPhase.Failed)
                state = state.step(TaskGraphIntent.Internal.Claim("g1", "E", "E"))
                assertEquals(GraphTaskPhase.Running, state.graphs.single().runs.getValue(branches.last()).phase)
                state = state.finish(branches.last())
                TaskGraphMachineSpec.assertIgnored(state, TaskGraphIntent.Internal.Claim("g1", "E", "again"))
                state = state.finish("E")
                assertTrue(state.graphs.single().isFinished)
                TaskGraphMachineSpec.assertIgnored(
                    state,
                    TaskGraphIntent.Internal.Finished(
                        "g1",
                        "E",
                        "E",
                        GraphTaskResult(GraphTaskPhase.Succeeded, "duplicate"),
                    ),
                )
            }
        }
    }

    @Test
    fun `failed required root blocks descendants but does not stop independent work`() {
        var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph()))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "A")).finish("A", GraphTaskPhase.TimedOut)
        val runs = state.graphs.single().runs
        assertEquals(GraphTaskPhase.Blocked, runs.getValue("C").phase)
        assertEquals(GraphTaskPhase.Blocked, runs.getValue("D").phase)
        assertEquals(GraphTaskPhase.Blocked, runs.getValue("E").phase)
        state.step(TaskGraphIntent.Internal.Claim("g1", "B", "B"))
    }

    @Test
    fun `any success keeps waiting while another predecessor can succeed`() {
        val def = TaskGraphDefinition(
            listOf(
                GraphTask("A", command),
                GraphTask("B", command),
                GraphTask("C", command, TaskDependencies.AnyOf(listOf(TaskDependency("A"), TaskDependency("B")))),
            ),
        )
        var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph(def)))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "A")).finish("A", GraphTaskPhase.Failed)
        assertEquals(GraphTaskPhase.Pending, state.graphs.single().runs.getValue("C").phase)
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "B", "B")).finish("B")
        state.step(TaskGraphIntent.Internal.Claim("g1", "C", "C"))
    }

    @Test
    fun `invalid graphs are rejected without a partial create`() {
        val invalid = listOf(
            TaskGraphDefinition(emptyList()),
            TaskGraphDefinition(listOf(GraphTask("A", command), GraphTask("A", command))),
            TaskGraphDefinition(listOf(GraphTask("A", command, TaskDependencies.AllOf(listOf(TaskDependency("B")))))),
            TaskGraphDefinition(listOf(GraphTask("A", command, TaskDependencies.AnyOf(emptyList())))),
            TaskGraphDefinition(
                listOf(
                    GraphTask("A", command, TaskDependencies.AllOf(listOf(TaskDependency("B")))),
                    GraphTask("B", command, TaskDependencies.AllOf(listOf(TaskDependency("A")))),
                ),
            ),
        )
        for (definition in invalid) {
            assertNotNull(definition.validationError())
            TaskGraphMachineSpec.assertIgnored(TaskGraphState.Ready(), TaskGraphIntent.Public.Create(graph(definition)))
        }
        assertNull(definition.validationError())
    }

    @Test
    fun `recovery preserves completion and requires an exact checked attempt for commands`() {
        var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph()))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "A")).finish("A")
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "B", "B"))
        state = state.step(TaskGraphIntent.Internal.Starting("g1", "B", "B"))
        state = TaskGraphState.Loading.step(TaskGraphIntent.Internal.Loaded(state.graphs))
        assertEquals(GraphTaskPhase.Succeeded, state.graphs.single().runs.getValue("A").phase)
        assertEquals(GraphTaskPhase.RecoveryRequired, state.graphs.single().runs.getValue("B").phase)
        val decision = TaskGraphIntent.Public.Resolve(
            "g1",
            "B",
            "B",
            owner,
            TaskRecoveryDecision.Retry,
            "checked output",
        )
        TaskGraphMachineSpec.assertIgnored(state, decision)
        state = state.step(TaskGraphIntent.Internal.RecoveryChecked("g1", "B", "B"))
        TaskGraphMachineSpec.assertIgnored(state, decision.copy(execution = "stale"))
        TaskGraphMachineSpec.assertIgnored(state, decision.copy(owner = owner.copy(nativeId = "other")))
        state = state.step(decision)
        TaskGraphMachineSpec.assertIgnored(state, decision)
        assertEquals(GraphTaskPhase.Pending, state.graphs.single().runs.getValue("B").phase)
    }

    @Test
    fun `repeated crashes before host journaling retain the native recovery lineage`() {
        val def = TaskGraphDefinition(listOf(GraphTask("A", GraphAction.Agent("helper", "assignment"))))
        var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph(def)))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "native"))
        state = state.step(TaskGraphIntent.Internal.Starting("g1", "A", "native"))
        repeat(3) { index ->
            state = TaskGraphState.Loading.step(TaskGraphIntent.Internal.Loaded(state.graphs))
            state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "observer-$index"))
            state = state.step(TaskGraphIntent.Internal.Starting("g1", "A", "observer-$index"))
            assertEquals("native", state.graphs.single().runs.getValue("A").previousExecution)
        }
        val current = state.graphs.single().runs.getValue("A").execution!!
        state = state.step(
            TaskGraphIntent.Internal.Finished(
                "g1",
                "A",
                current,
                GraphTaskResult(GraphTaskPhase.RecoveryRequired, "unknown"),
            ),
        )
        state = state.step(TaskGraphIntent.Internal.RecoveryChecked("g1", "A", current))
        state = state.step(
            TaskGraphIntent.Public.Resolve("g1", "A", current, owner, TaskRecoveryDecision.Retry, "verified stopped"),
        )
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "retry"))
        assertNull(state.graphs.single().runs.getValue("A").previousExecution)
        state = state.step(TaskGraphIntent.Internal.Starting("g1", "A", "retry"))
        state = TaskGraphState.Loading.step(TaskGraphIntent.Internal.Loaded(state.graphs))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "retry-observer"))
        assertEquals("retry", state.graphs.single().runs.getValue("A").previousExecution)
    }

    @Test
    fun `cancel prevents launches and waits for running tasks to stop`() {
        var state = TaskGraphState.Ready().step(TaskGraphIntent.Public.Create(graph()))
        state = state.step(TaskGraphIntent.Internal.Claim("g1", "A", "A"))
        state = state.step(TaskGraphIntent.Public.Cancel("g1", owner))
        assertEquals(GraphTaskPhase.Cancelling, state.graphs.single().runs.getValue("A").phase)
        assertEquals(GraphTaskPhase.Cancelled, state.graphs.single().runs.getValue("B").phase)
        TaskGraphMachineSpec.assertIgnored(state, TaskGraphIntent.Internal.Claim("g1", "B", "B"))
        state.finish("A", GraphTaskPhase.Cancelled)
    }
}
