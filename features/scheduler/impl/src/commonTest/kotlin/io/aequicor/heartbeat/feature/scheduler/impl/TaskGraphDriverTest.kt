package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.DependencyOutcome
import io.aequicor.heartbeat.feature.scheduler.api.GraphAction
import io.aequicor.heartbeat.feature.scheduler.api.GraphTask
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskRun
import io.aequicor.heartbeat.feature.scheduler.api.TaskDependencies
import io.aequicor.heartbeat.feature.scheduler.api.TaskDependency
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphOutput
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.api.TaskProcess
import io.aequicor.heartbeat.feature.scheduler.api.TaskRecoveryDecision
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActionSlots
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphDriver
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskGraphDriverTest {
    @Test
    fun `profile cancellation during storage retry cannot keep pumping cancelled jobs`() = runTest {
        val profile = SupervisorJob(backgroundScope.coroutineContext[Job])
        val source = MutableStateFlow<TaskGraphState>(TaskGraphState.Ready(isStorageFailed = true))
        var reads = 0
        val machine = object : TaskGraphMachine {
            override val name = "cancelling graph storage"
            override val outputs = emptyFlow<TaskGraphOutput>()
            override val state = object : StateFlow<TaskGraphState> by source {
                override val value: TaskGraphState
                    get() {
                        // Bound a regression instead of hanging the test worker in an endless completion loop.
                        check(++reads <= 2) { "Cancelled driver continued polling storage" }
                        profile.cancel()
                        return source.value
                    }
            }
            override suspend fun send(intent: TaskGraphIntent) = SendResult.Accepted
        }
        val driver = TaskGraphDriver(
            machine,
            BackgroundActionSlots(),
            GraphCommands(),
            Projects(),
            lazyOf(emptySet()),
            Toggles(),
            VirtualClock(testScheduler),
            TestScopeHandle(CoroutineScope(backgroundScope.coroutineContext + profile)),
        )
        driver.start()
        runCurrent()
        assertTrue(profile.isCompleted)
        assertEquals(1, reads)
    }

    @Test
    fun `missing host during recovery cannot release or forget the previous native execution`() = runTest {
        val f = GraphFixture(this)
        val graph = f.graph(listOf(GraphTask("A", GraphAction.Agent("task")))).copy(
            runs = mapOf(
                "A" to GraphTaskRun(
                    GraphTaskPhase.Running,
                    attempt = 1,
                    execution = "old",
                    hostTask = "existing",
                    hasStarted = true,
                ),
            ),
        )
        f.machine.state.value = TaskGraphState.Loading
        f.machine.send(TaskGraphIntent.Internal.Loaded(listOf(graph)))
        f.host.isAvailable = false
        f.driver.start()
        runCurrent()
        val run = f.machine.graph().runs.getValue("A")
        assertEquals("old", run.previousExecution)
        assertEquals(1, f.slots.state.value.size)
        assertFalse(
            f.driver.resolveInterrupted(
                TaskGraphIntent.Public.Resolve(
                    "graph",
                    "A",
                    checkNotNull(run.execution),
                    SESSION,
                    TaskRecoveryDecision.Retry,
                    "try again",
                ),
            ),
        )
        f.machine.send(TaskGraphIntent.Public.Cancel("graph", SESSION))
        runCurrent()
        assertFalse(f.machine.graph().isFinished)
        assertTrue(f.host.requests.isEmpty())
    }

    @Test
    fun `successors wait until the result is durable and saved results survive restart`() = runTest {
        val f = GraphFixture(this)
        val dependent = f.command("B").copy(dependencies = TaskDependencies.AllOf(listOf(TaskDependency("A"))))
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A"), dependent))))
        f.driver.start()
        runCurrent()
        f.machine.savesAutomatically = false
        f.commands.finish("A")
        runCurrent()
        assertEquals(listOf("A"), f.commands.started)
        val snapshot = f.machine.graph()
        f.machine.savesAutomatically = true
        f.machine.flush()
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        val restored = GraphFixture(this, GraphMachine(TaskGraphState.Loading))
        restored.machine.send(TaskGraphIntent.Internal.Loaded(listOf(snapshot)))
        restored.driver.start()
        runCurrent()
        assertEquals(listOf("B"), restored.commands.started)
    }

    @Test
    fun `uncertain native work retains shared slots after its observer returns`() = runTest {
        val f = GraphFixture(this)
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(GraphTask("A", GraphAction.Agent("task"))))))
        f.driver.start()
        runCurrent()
        f.host.result.complete(GraphTaskResult(GraphTaskPhase.RecoveryRequired, "unknown"))
        runCurrent()
        assertEquals(1, f.slots.state.value.size)
        assertEquals(GraphTaskPhase.RecoveryRequired, f.machine.graph().runs.getValue("A").phase)
    }

    @Test
    fun `disable during command preparation never releases command handshake`() = runTest {
        val f = GraphFixture(this)
        f.commands.beforeStart = { f.toggles.isEnabled.value = false }
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A")))))
        f.driver.start()
        runCurrent()
        assertTrue(f.commands.released.isEmpty())
        assertEquals(GraphTaskPhase.Pending, f.machine.graph().runs.getValue("A").phase)
    }

    @Test
    fun `cancellation between starting and process creation finishes without a process id`() = runTest {
        val f = GraphFixture(this)
        f.commands.beforeStart = { kotlinx.coroutines.awaitCancellation() }
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A")))))
        f.driver.start()
        runCurrent()
        f.machine.send(TaskGraphIntent.Public.Cancel("graph", SESSION))
        runCurrent()
        assertTrue(f.machine.graph().isFinished)
        assertTrue(f.commands.started.isEmpty())
    }

    @Test
    fun `newly satisfied dependency waits behind previously queued roots`() = runTest {
        val f = GraphFixture(this)
        repeat(2) { f.slots.reserve(ActionId("legacy$it"), SESSION) }
        val task = f.command("C").copy(dependencies = TaskDependencies.AllOf(listOf(TaskDependency("A"))))
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A"), task, f.command("B")))))
        f.driver.start()
        runCurrent()
        f.commands.finish("A")
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        f.commands.finish("B")
        runCurrent()
        assertEquals(listOf("A", "B", "C"), f.commands.started)
    }

    @Test
    fun `profile limit covers multiple owners and helpers receive durable predecessor results`() = runTest {
        val f = GraphFixture(this)
        repeat(7) { f.slots.reserve(ActionId("other$it"), SESSION.copy(nativeId = "owner$it")) }
        val task = GraphTask(
            "helper",
            GraphAction.Agent("inspect"),
            TaskDependencies.AllOf(listOf(TaskDependency("A"))),
        )
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A"), task, f.command("B")))))
        f.driver.start()
        runCurrent()
        assertEquals(listOf("A"), f.commands.started)
        f.commands.finish("A")
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        f.commands.finish("B")
        runCurrent()
        assertTrue("result:A" in f.host.requests.single().second.prompt.directive)
        assertEquals(8, f.slots.state.value.size)
    }

    @Test
    fun `roots run concurrently and E starts once while other branch continues`() = runTest {
        val f = GraphFixture(this)
        val both = TaskDependencies.AllOf(listOf(TaskDependency("A"), TaskDependency("B")))
        val either = TaskDependencies.AnyOf(
            listOf(
                TaskDependency("C", DependencyOutcome.Finished),
                TaskDependency("D", DependencyOutcome.Finished),
            ),
        )
        val graph = f.graph(
            listOf(
                f.command("A"),
                f.command("B"),
                f.command("C").copy(dependencies = both),
                f.command("D").copy(dependencies = both),
                f.command("E").copy(dependencies = either),
            ),
        )
        f.machine.send(TaskGraphIntent.Public.Create(graph))
        f.driver.start()
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        f.commands.finish("B")
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        f.commands.finish("A")
        runCurrent()
        assertEquals(listOf("A", "B", "C", "D"), f.commands.started)
        f.commands.finish("C", 1)
        runCurrent()
        assertEquals(listOf("A", "B", "C", "D", "E"), f.commands.started)
        assertEquals(GraphTaskPhase.Running, f.machine.graph().runs.getValue("D").phase)
        f.commands.finish("D")
        f.commands.finish("E")
        runCurrent()
        assertTrue(f.machine.graph().isFinished)
        assertEquals(1, f.commands.started.count { it == "E" })
        assertEquals(1, f.host.woken.size)
    }

    @Test
    fun `shared slots queue graph work behind standalone actions`() = runTest {
        val f = GraphFixture(this)
        repeat(2) { f.slots.reserve(ActionId("legacy$it"), SESSION) }
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A"), f.command("B")))))
        f.driver.start()
        runCurrent()
        assertEquals(listOf("A"), f.commands.started)
        f.slots.release(ActionId("legacy0"))
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
    }

    @Test
    fun `unpersisted graph cannot launch and disabling only pauses admission`() = runTest {
        val f = GraphFixture(this)
        f.machine.savesAutomatically = false
        f.machine.send(TaskGraphIntent.Public.Create(f.graph(listOf(f.command("A"), f.command("B")))))
        f.driver.start()
        runCurrent()
        assertTrue(f.commands.started.isEmpty())
        f.toggles.isEnabled.value = false
        f.machine.savesAutomatically = true
        f.machine.flush()
        runCurrent()
        assertTrue(f.commands.started.isEmpty())
        f.toggles.isEnabled.value = true
        runCurrent()
        assertEquals(listOf("A", "B"), f.commands.started)
        f.toggles.isEnabled.value = false
        f.commands.finish("A")
        runCurrent()
        assertEquals(GraphTaskPhase.Succeeded, f.machine.graph().runs.getValue("A").phase)
    }

    @Test
    fun `interrupted command wakes coordinator and never repeats until an exact safe decision`() = runTest {
        val f = GraphFixture(this)
        val graph = f.graph(listOf(f.command("A"))).copy(
            runs = mapOf(
                "A" to GraphTaskRun(
                    phase = GraphTaskPhase.Running,
                    attempt = 1,
                    execution = "old",
                    hasStarted = true,
                    process = TaskProcess(42, "previous"),
                ),
            ),
        )
        f.machine.state.value = TaskGraphState.Loading
        f.machine.send(TaskGraphIntent.Internal.Loaded(listOf(graph)))
        f.driver.start()
        runCurrent()
        assertTrue(f.commands.started.isEmpty())
        assertEquals(1, f.host.woken.size)
        f.commands.processes += TaskProcess(42, "previous")
        assertFalse(f.driver.checkRecovery(f.machine.graph(), "A"))
        f.commands.processes.clear()
        assertTrue(f.driver.checkRecovery(f.machine.graph(), "A"))
        val decision = TaskGraphIntent.Public.Resolve(
            "graph",
            "A",
            "old",
            SESSION,
            TaskRecoveryDecision.Retry,
            "not done",
        )
        f.machine.send(decision)
        runCurrent()
        f.machine.send(decision)
        runCurrent()
        assertEquals(listOf("A"), f.commands.started)
    }

    @Test
    fun `agent recovery reuses its chat and passes the previous submission`() = runTest {
        val f = GraphFixture(this)
        val graph = f.graph(listOf(GraphTask("A", GraphAction.Agent("task")))).copy(
            runs = mapOf(
                "A" to GraphTaskRun(
                    GraphTaskPhase.Running,
                    attempt = 1,
                    execution = "old",
                    hostTask = "existing",
                    hasStarted = true,
                ),
            ),
        )
        f.machine.state.value = TaskGraphState.Loading
        f.machine.send(TaskGraphIntent.Internal.Loaded(listOf(graph)))
        f.driver.start()
        runCurrent()
        assertTrue(f.host.prepared.isEmpty())
        assertEquals("existing", f.host.requests.single().first)
        assertEquals("old", f.host.requests.single().third)
        f.host.result.complete(GraphTaskResult(GraphTaskPhase.Succeeded, "done"))
        runCurrent()
        assertEquals(GraphTaskPhase.Succeeded, f.machine.graph().runs.getValue("A").phase)
    }

    @Test
    fun `cancelling graph stops running commands without launching queued work`() = runTest {
        val f = GraphFixture(this)
        f.machine.send(TaskGraphIntent.Public.Create(f.graph((1..5).map { f.command("t$it") })))
        f.driver.start()
        runCurrent()
        assertEquals(3, f.commands.started.size)
        f.machine.send(TaskGraphIntent.Public.Cancel("graph", SESSION))
        runCurrent()
        assertTrue(f.machine.graph().isFinished)
        assertTrue(f.commands.processes.isEmpty())
        assertEquals(3, f.commands.started.size)
    }
}
