package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
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
