package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class RootIntegrationTest {

    private val start = RootStart(guest = listOf(WelcomeRoute), profile = listOf(WelcomeRoute))

    /** One app process: its graph and the root created in it; [save] + a new process = process death. */
    private inner class Process(disk: PersistedProfile, saved: SerializableContainer? = null) {
        val graph: TestAppGraph = createGraphFactory<TestAppGraph.Factory>().create(disk)
        private val stateKeeper = StateKeeperDispatcher(saved)
        val root = HeartbeatRoot(
            context = DefaultComponentContext(LifecycleRegistry().apply { resume() }, stateKeeper = stateKeeper),
            graph = graph,
            start = start,
        )

        /** Serialized like the platform does it: only bytes survive process death. */
        fun save(): SerializableContainer = Json.decodeFromString(
            SerializableContainer.serializer(),
            Json.encodeToString(SerializableContainer.serializer(), stateKeeper.save()),
        )
    }

    private val HeartbeatRoot.child get() = slot.value.child?.instance

    private val HeartbeatRoot.host: RootHost?
        get() = when (val child = child) {
            is RootChild.Guest -> child.host
            is RootChild.Profile -> child.host.value
            null -> null
        }

    private val RootHost.routes: List<Route> get() = stack.value.items.map { it.configuration.route }

    private fun runRootTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        body()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cold start without a profile shows the guest tree after loading`() = runRootTest {
        val process = Process(PersistedProfile())
        assertNull(process.root.child, "loading until the profile restore finishes")

        advanceUntilIdle()

        assertIs<RootChild.Guest>(process.root.child)
        assertEquals(listOf<Route>(WelcomeRoute), process.root.host?.routes)
    }

    @Test
    fun `sign-in and sign-out switch the tree`() = runRootTest {
        val process = Process(PersistedProfile())
        advanceUntilIdle()

        process.graph.profileSessions.open(ProfileId("p1"))
        advanceUntilIdle()
        val profile = assertIs<RootChild.Profile>(process.root.child)
        assertEquals(ProfileId("p1"), profile.id)
        assertEquals(listOf<Route>(WelcomeRoute), process.root.host?.routes)

        process.graph.profileSessions.close()
        advanceUntilIdle()
        assertIs<RootChild.Guest>(process.root.child)
    }

    @Test
    fun `conflated sign-out and sign-in recreates the tree for the same profile`() = runRootTest {
        val process = Process(PersistedProfile(suspendOperations = false))
        advanceUntilIdle()
        val sessions = process.graph.profileSessions
        val first = sessions.open(ProfileId("p1"))
        advanceUntilIdle()
        val oldChild = assertIs<RootChild.Profile>(process.root.child)
        val oldHost = oldChild.host.value
        oldHost?.navigator?.navigate(FeedRoute)

        sessions.close()
        val reopened = sessions.open(first.id)
        // Neither storage operation suspends: the collector sees only the new session, skipping null.
        assertSame(oldChild, process.root.child)
        assertNotSame(first.graph, reopened.graph)
        advanceUntilIdle()

        val newChild = assertIs<RootChild.Profile>(process.root.child)
        assertNotSame(oldChild, newChild)
        assertNotSame(oldHost, newChild.host.value)
        assertEquals(listOf<Route>(WelcomeRoute), process.root.host?.routes)
        process.root.host?.navigator?.navigate(FeedRoute)
        assertEquals(listOf(WelcomeRoute, FeedRoute), process.root.host?.routes)
    }

    @Test
    fun `opening the already active session preserves its navigation tree`() = runRootTest {
        val process = Process(PersistedProfile(suspendOperations = false))
        advanceUntilIdle()
        val sessions = process.graph.profileSessions
        val session = sessions.open(ProfileId("p1"))
        advanceUntilIdle()
        val child = process.root.child
        val host = process.root.host
        host?.navigator?.navigate(FeedRoute)

        assertSame(session, sessions.open(session.id))
        advanceUntilIdle()

        assertSame(child, process.root.child)
        assertSame(host, process.root.host)
        assertEquals(listOf(WelcomeRoute, FeedRoute), host?.routes)
    }

    @Test
    fun `profile deep link waits for sign-in`() = runRootTest {
        val process = Process(PersistedProfile())
        advanceUntilIdle()
        process.graph.profileSessions.open(ProfileId("p1"))
        advanceUntilIdle()
        process.graph.profileSessions.close()
        advanceUntilIdle()

        process.root.handleDeepLink("heartbeat://feed")
        assertIs<RootChild.Guest>(process.root.child)

        process.graph.profileSessions.open(ProfileId("p2"))
        advanceUntilIdle()
        assertEquals(listOf<Route>(FeedRoute), process.root.host?.routes, "the link replaced the start stack")
    }

    @Test
    fun `profile tree and pending link survive process death`() = runRootTest {
        val disk = PersistedProfile()
        val before = Process(disk)
        advanceUntilIdle()
        before.graph.profileSessions.open(ProfileId("p1"))
        advanceUntilIdle()
        before.root.host?.navigator?.navigate(FeedRoute)
        val saved = before.save()

        val after = Process(disk, saved)
        val restoring = assertIs<RootChild.Profile>(after.root.child, "slot restored before the session")
        assertNull(restoring.host.value)
        after.save() // the platform may save again while loading: the profile tree state must not be lost

        advanceUntilIdle()
        assertEquals(listOf(WelcomeRoute, FeedRoute), after.root.host?.routes)
    }

    @Test
    fun `pending guest link survives process death`() = runRootTest {
        val disk = PersistedProfile()
        val before = Process(disk)
        advanceUntilIdle()
        before.root.handleDeepLink("heartbeat://feed")

        val after = Process(disk, before.save())
        advanceUntilIdle()
        after.graph.profileSessions.open(ProfileId("p1"))
        advanceUntilIdle()

        assertEquals(listOf<Route>(FeedRoute), after.root.host?.routes)
    }
}
