package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StateMachineIntegrationTest {

    private val processes = mutableListOf<TestAppGraph>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        processes.forEach { (it.appScope as OwnedScope).close() }
        // close cancels without waiting; IO continuations must finish before replacing Dispatchers.Main.
        processes.forEach { it.appScope.coroutineScope.coroutineContext[Job]?.join() }
        processes.forEach { it.storageMaintenance.awaitClosed() }
        Dispatchers.resetMain()
    }

    private fun newProcess(persisted: PersistedProfile): TestAppGraph =
        createGraphFactory<TestAppGraph.Factory>().create(persisted).also { processes += it }

    private fun TestComponent.feature(session: ProfileSession): TestFeatureGraph {
        val accessors = session.graph as TestProfileAccessors
        return retainedGraph(accessors.scopes, session.graph.scope, name = "counter") { scope ->
            accessors.featureGraphs.create(scope)
        }
    }

    @Test
    fun `feature machine is reachable through the registry while the feature is open`() = runTest {
        val app = newProcess(PersistedProfile())
        val session = app.profileSessions.open(ProfileId("p1"))
        assertNull(app.machines.find(CounterMachineKey))

        val machine = TestComponent(InstanceKeeperDispatcher(), StateKeeperDispatcher()).feature(session).machine

        assertSame(machine.state, app.machines.find(CounterMachineKey)?.state)
        assertEquals(SendResult.Accepted, app.machines.send(CounterMachineKey, CounterIntent.Public.Increment))
        // the effect echoed its result back through the machine (main dispatcher is unconfined)
        assertEquals(CounterState.Counting(count = 1, echoed = 1), machine.state.value)
    }

    @Test
    fun `closing the profile stops feature machines`() = runTest {
        val app = newProcess(PersistedProfile())
        val session = app.profileSessions.open(ProfileId("p1"))
        val machine = TestComponent(InstanceKeeperDispatcher(), StateKeeperDispatcher()).feature(session).machine

        app.profileSessions.close()

        assertNull(app.machines.find(CounterMachineKey))
        assertEquals(SendResult.NotRunning, app.machines.send(CounterMachineKey, CounterIntent.Public.Increment))
        assertEquals(SendResult.NotRunning, machine.send(CounterIntent.Public.Increment))
    }

    @Test
    fun `persistent machine state survives process death`() = runTest {
        val disk = PersistedProfile()
        val session = newProcess(disk).profileSessions.open(ProfileId("p1"))
        val state = StateKeeperDispatcher()
        val machine = TestComponent(InstanceKeeperDispatcher(), state).feature(session).machine
        machine.send(CounterIntent.Public.Increment)
        machine.send(CounterIntent.Public.Increment)

        val savedBytes = Json.encodeToString(SerializableContainer.serializer(), state.save())
        val restoredApp = newProcess(disk)
        val restoredSession = requireNotNull(restoredApp.profileSessions.restore())
        val restoredState = StateKeeperDispatcher(Json.decodeFromString(SerializableContainer.serializer(), savedBytes))
        val restored = TestComponent(InstanceKeeperDispatcher(), restoredState).feature(restoredSession).machine

        assertEquals(CounterState.Counting(count = 2, echoed = 2), restored.state.value)
        assertSame(restored.state, restoredApp.machines.find(CounterMachineKey)?.state)
    }
}
