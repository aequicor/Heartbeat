package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.GraphAction
import io.aequicor.heartbeat.feature.scheduler.api.GraphTask
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraph
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphDefinition
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphEffect
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphOutput
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.api.TaskProcess
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledTaskHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActionSlots
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandRunner
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileBackgroundCapacity
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphDriver
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

internal class GraphMachine(initial: TaskGraphState = TaskGraphState.Ready(persisted = 0)) : TaskGraphMachine {
    override val name = TaskGraphMachineSpec.name
    override val state = MutableStateFlow(initial)
    override val outputs = emptyFlow<TaskGraphOutput>()
    var savesAutomatically = true
    val saves = mutableListOf<TaskGraphEffect.Save>()
    override suspend fun send(intent: TaskGraphIntent): SendResult {
        val resolved = TaskGraphMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolved.to
        for (effect in resolved.effects) {
            if (effect is TaskGraphEffect.Save) {
                saves += effect
                if (savesAutomatically) flush()
            }
        }
        return SendResult.Accepted
    }
    suspend fun flush() {
        val ready = state.value as TaskGraphState.Ready
        send(TaskGraphIntent.Internal.Saved(ready.revision))
    }
    fun graph(id: String = "graph") = (state.value as TaskGraphState.Ready).graphs.first { it.id == id }
}

internal class GraphCommands : CommandRunner {
    override val isAvailable = true
    override val isStartTracked = true
    val started = mutableListOf<String>()
    val completions = mutableMapOf<String, CompletableDeferred<CommandOutcome>>()
    val processes = mutableSetOf<TaskProcess>()
    var beforeStart: suspend () -> Unit = {}
    val released = mutableListOf<String>()
    override suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome =
        runTracked(directory, command, timeout) {}
    override suspend fun runTracked(
        directory: String,
        command: String,
        timeout: Duration,
        onStarted: suspend (TaskProcess) -> Unit,
    ): CommandOutcome {
        beforeStart()
        started += command
        val process = TaskProcess(started.size.toLong(), "start")
        processes += process
        try {
            onStarted(process)
            released += command
            return completions.getOrPut(command) { CompletableDeferred() }.await()
        } finally {
            processes -= process
        }
    }
    override suspend fun isStopped(process: TaskProcess) = process !in processes
    override suspend fun stop(process: TaskProcess): Boolean {
        processes -= process
        return true
    }
    fun finish(command: String, code: Int = 0) {
        completions.getValue(
            command,
        ).complete(CommandOutcome(code, "result:$command"))
    }
}

internal class GraphHost : ScheduledTaskHost {
    override var isWakeAdmissionSupported = true
    override val priority = 100
    var isAvailable = true
    override suspend fun owns(session: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef) = isAvailable
    val woken = mutableListOf<WakePrompt>()
    val prepared = mutableListOf<SpawnRequest>()
    val requests = mutableListOf<Triple<String, SpawnRequest, String?>>()
    val result = CompletableDeferred<GraphTaskResult>()
    override suspend fun wake(request: WakeRequest, prompt: WakePrompt) {
        woken += prompt
    }
    override suspend fun prepareTask(request: SpawnRequest): String {
        prepared += request
        return "chat"
    }
    override suspend fun runTask(
        hostTask: String,
        request: SpawnRequest,
        previousExecution: String?,
        admission: kotlinx.coroutines.flow.Flow<Boolean>,
    ): GraphTaskResult {
        requests += Triple(hostTask, request, previousExecution)
        return result.await()
    }
    override suspend fun stopTask(hostTask: String): Boolean {
        result.complete(GraphTaskResult(GraphTaskPhase.Cancelled, "stopped"))
        return true
    }
}

internal class GraphFixture(scope: TestScope, val machine: GraphMachine = GraphMachine()) {
    val commands = GraphCommands()
    val host = GraphHost()
    val slots = BackgroundActionSlots(
        ProfileBackgroundCapacity(CapacitySpecMachine(), TestScopeHandle(scope.backgroundScope), MemoryJournal()),
    )
    val toggles = Toggles()
    val driver = TaskGraphDriver(
        machine,
        slots,
        commands,
        Projects(),
        lazyOf(setOf(host)),
        toggles,
        VirtualClock(scope.testScheduler),
        TestScopeHandle(scope.backgroundScope),
    )
    fun graph(tasks: List<GraphTask>, id: String = "graph") =
        TaskGraph(id, SESSION, PROJECT, TARGET, TaskGraphDefinition(tasks), "approved")
    fun command(id: String) = GraphTask(id, GraphAction.Command(id))
}
