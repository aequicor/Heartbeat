package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionJournal
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionResults
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActions
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandRunner
import io.aequicor.heartbeat.feature.scheduler.impl.data.InMemorySchedulerBus
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileBackgroundCapacity
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileHelperAgents
import io.aequicor.heartbeat.feature.scheduler.impl.data.ScheduledHelperActions
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

internal val PROJECT = WorkspaceRef("project")

internal class RecordingCommands(override val isAvailable: Boolean = true) : CommandRunner {
    val runs = mutableListOf<Pair<String, String>>()
    val result = CompletableDeferred<CommandOutcome>()

    override suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome {
        runs += directory to command
        return result.await()
    }
}

internal class Projects(
    private val paths: Map<WorkspaceRef, String> = mapOf(
        PROJECT to "/work/project",
    ),
) : LocalWorkspaces {
    override val isAvailable: Boolean = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(paths.keys.map { LocalWorkspace(it, it.value) })
    override suspend fun register(directory: String): LocalWorkspace = error("unused")
    override suspend fun resolve(ref: WorkspaceRef): String? = paths[ref]
}

internal class MemoryJournal(initial: List<ActionRecord> = emptyList()) : ActionJournal {
    val records = initial.toMutableList()
    var beforeAdd: suspend (ActionRecord) -> Unit = {}

    override suspend fun add(record: ActionRecord) {
        beforeAdd(record)
        records.removeAll { it.id == record.id }
        records += record
    }

    override suspend fun remove(id: ActionId) {
        records.removeAll { it.id == id }
    }

    override suspend fun readAll(): List<ActionRecord> = records.toList()
}

internal class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name: String = "profile"
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed: Boolean = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

internal class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
}

internal class ActionsFixture(
    scope: TestScope,
    val machine: SpecMachine,
    val commands: RecordingCommands = RecordingCommands(),
    val journal: MemoryJournal = MemoryJournal(),
    hosts: Set<ScheduledSessionHost> = emptySet(),
    toggles: Toggles = Toggles(),
    workspaces: LocalWorkspaces = Projects(),
    profile: TestScopeHandle = TestScopeHandle(scope.backgroundScope),
    recovery: Set<HelperCapacityRecoverySource> = emptySet(),
) {
    val clock = VirtualClock(scope.testScheduler)
    val bus = InMemorySchedulerBus(clock)
    val wakeStorage = MemoryWakeStorage((machine.state.value as? SchedulerState.Ready)?.wakes.orEmpty())
    val persistence = SchedulerPersistence(wakeStorage)
    val capacityMachine = CapacitySpecMachine()
    val capacity = ProfileBackgroundCapacity(capacityMachine, profile, journal, helpers = lazyOf(recovery))
    val results = ActionResults(profile, bus, machine, persistence, journal, toggles, clock)
    val helperAgents = ProfileHelperAgents(profile, lazyOf(hosts), capacity, results)
    val helperActions = ScheduledHelperActions(profile, helperAgents, capacity, journal, results, bus, clock)
    val actions = BackgroundActions(
        profile,
        TestDispatchers(StandardTestDispatcher(scope.testScheduler)),
        commands,
        workspaces,
        clock,
        capacity,
        results,
        helperActions,
    )

    init {
        scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { persistence.load() }
    }

    suspend fun persistWakes() {
        val ready = machine.state.value as SchedulerState.Ready
        persistence.persist(SchedulerEffect.Persist(ready.wakes, ready.revision))
    }
}

internal class MemoryWakeStorage(var wakes: List<ScheduledWake>) : WakeStorage {
    override suspend fun load(): List<ScheduledWake> = wakes

    override suspend fun save(wakes: List<ScheduledWake>) {
        this.wakes = wakes
    }
}
