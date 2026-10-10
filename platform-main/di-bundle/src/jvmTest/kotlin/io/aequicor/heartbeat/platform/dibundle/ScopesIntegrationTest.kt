package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.di.ext.retainedShared
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScopesIntegrationTest {

    private val processes = mutableListOf<TestAppGraph>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        // Cancellation may still resume IO continuations on Main; join them before resetting the test dispatcher.
        processes.forEach { (it.appScope as OwnedScope).close() }
        processes.forEach { it.appScope.coroutineScope.coroutineContext[Job]?.join() }
        processes.forEach { it.storageMaintenance.awaitClosed() }
        Dispatchers.resetMain()
    }

    private suspend fun newProcess(persisted: PersistedProfile): TestAppGraph {
        // Process death releases app-owned DataStore files without signing out the persisted profile.
        processes.forEach { (it.appScope as OwnedScope).close() }
        processes.forEach { it.appScope.coroutineScope.coroutineContext[Job]?.join() }
        processes.forEach { it.storageMaintenance.awaitClosed() }
        return createGraphFactory<TestAppGraph.Factory>().create(persisted).also { processes += it }
    }

    private val ProfileSession.accessors get() = graph as TestProfileAccessors

    private fun TestComponent.feature(session: ProfileSession): TestFeatureGraph =
        retainedGraph(session.accessors.scopes, session.graph.scope, name = "feature") { scope ->
            session.accessors.featureGraphs.create(scope)
        }

    @Test
    fun `closing the profile closes feature scopes and cancels their coroutines`() = runTest {
        val app = newProcess(PersistedProfile())
        val session = app.profileSessions.open(ProfileId("p1"))
        val events = mutableListOf<String>()
        session.graph.scope.onClose { events += "profile resource" }
        val feature = app.scopes.child(session.graph.scope, "feature")
        feature.onClose { events += "feature resource" }
        val work = feature.coroutineScope.launch { awaitCancellation() }

        app.profileSessions.close()

        assertEquals("app/profile/feature", feature.name)
        assertEquals(listOf("feature resource", "profile resource"), events)
        assertTrue(feature.isClosed)
        assertTrue(work.isCancelled)
        assertNull(app.profileSessions.active.value)
        assertFalse(app.appScope.isClosed)
    }

    @Test
    fun `switching profile closes the previous session, reopening the same id is a no-op`() = runTest {
        val app = newProcess(PersistedProfile())
        val first = app.profileSessions.open(ProfileId("p1"))

        assertSame(first, app.profileSessions.open(ProfileId("p1")))
        val second = app.profileSessions.open(ProfileId("p2"))

        assertTrue(first.graph.scope.isClosed)
        assertFalse(second.graph.scope.isClosed)
        assertSame(second, app.profileSessions.active.value)
    }

    @Test
    fun `active profile is restored in a new process, and not after sign-out`() = runTest {
        val disk = PersistedProfile()
        val before = newProcess(disk).profileSessions.open(ProfileId("p1"))

        val restored = newProcess(disk).profileSessions.restore()

        assertEquals(ProfileId("p1"), restored?.id)
        assertNotSame(before.graph, restored?.graph)

        newProcess(disk).profileSessions.run {
            restore()
            close()
        }
        assertNull(newProcess(disk).profileSessions.restore())
    }

    @Test
    fun `retained feature graph survives configuration change and is restored after process death`() = runTest {
        val disk = PersistedProfile()
        val session = newProcess(disk).profileSessions.open(ProfileId("p1"))
        val keeper = InstanceKeeperDispatcher()
        val firstState = StateKeeperDispatcher()
        val original = TestComponent(keeper, firstState).feature(session)
        original.draft.text = "hello"

        // configuration change: the component and its StateKeeper are new, the InstanceKeeper is retained
        val secondState = StateKeeperDispatcher(firstState.save())
        val afterRotation = TestComponent(keeper, secondState).feature(session)
        assertSame(original, afterRotation)
        // edited after the rotation: must reach the new StateKeeper, not only the pre-rotation snapshot
        afterRotation.draft.text = "edited after rotation"

        // process death: only serialized state and "disk" survive
        val savedBytes = Json.encodeToString(SerializableContainer.serializer(), secondState.save())
        val restoredSession = requireNotNull(newProcess(disk).profileSessions.restore())
        val newKeeper = InstanceKeeperDispatcher()
        val restoredState = StateKeeperDispatcher(Json.decodeFromString(SerializableContainer.serializer(), savedBytes))
        val restored = TestComponent(newKeeper, restoredState).feature(restoredSession)

        assertNotSame(original, restored)
        assertEquals("edited after rotation", restored.draft.text)

        // component destroyed for good → its scope is closed
        newKeeper.destroy()
        assertTrue(restored.scope.isClosed)
    }

    @Test
    fun `shared object lives while any component holds it`() = runTest {
        val session = newProcess(PersistedProfile()).profileSessions.open(ProfileId("p1"))
        val firstKeeper = InstanceKeeperDispatcher()
        val secondKeeper = InstanceKeeperDispatcher()
        val shared = session.graph.sharedScopes

        val a = TestComponent(firstKeeper, StateKeeperDispatcher()).retainedShared(shared, CounterKey)
        val b = TestComponent(secondKeeper, StateKeeperDispatcher()).retainedShared(shared, CounterKey)
        assertSame(a, b)
        assertEquals("app/profile/shared:counter", a.scope.name)

        firstKeeper.destroy()
        assertFalse(a.scope.isClosed)
        secondKeeper.destroy()
        assertTrue(a.scope.isClosed)

        val c = TestComponent(InstanceKeeperDispatcher(), StateKeeperDispatcher()).retainedShared(shared, CounterKey)
        assertNotSame(a, c)
    }

    @Test
    fun `sign-out from a coroutine of the profile itself completes and is persisted`() = runTest {
        val disk = PersistedProfile()
        val app = newProcess(disk)
        val session = app.profileSessions.open(ProfileId("p1"))

        session.graph.scope.coroutineScope.launch { app.profileSessions.close() }.join()

        assertNull(app.profileSessions.active.value)
        assertNull(disk.id)
        assertNull(newProcess(disk).profileSessions.restore())
    }

    @Test
    fun `profile switch from a coroutine of the profile itself never exposes an empty session`() = runTest {
        val disk = PersistedProfile()
        val app = newProcess(disk)
        val first = app.profileSessions.open(ProfileId("p1"))
        val seen = mutableListOf<ProfileId?>()
        // unconfined collector observes every value, so an intermediate `null` would be caught
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            app.profileSessions.active.collect { seen += it?.id }
        }

        first.graph.scope.coroutineScope.launch { app.profileSessions.open(ProfileId("p2")) }.join()

        assertEquals(ProfileId("p2"), app.profileSessions.active.value?.id)
        assertEquals(ProfileId("p2"), disk.id)
        assertEquals(listOf<ProfileId?>(ProfileId("p1"), ProfileId("p2")), seen)
        observer.cancel()
    }

    @Test
    fun `failing shared factory does not leak its scope and a lease is released once`() = runTest {
        val session = newProcess(PersistedProfile()).profileSessions.open(ProfileId("p1"))
        val shared = session.graph.sharedScopes
        val broken = (session.graph as TestSharedAccessors).sharedFactories.getValue("broken") as BrokenFactory

        assertFailsWith<IllegalStateException> { shared.acquire(BrokenKey) }
        assertTrue(requireNotNull(broken.lastScope).isClosed)

        val first = shared.acquire(CounterKey)
        val second = shared.acquire(CounterKey)
        first.close()
        first.close() // idempotent: must not release the second holder's reference
        assertFalse(second.value.scope.isClosed)
        second.close()
        assertTrue(second.value.scope.isClosed)
    }
}
