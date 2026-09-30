package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
    private val processes = mutableListOf<Process>()

    /** One app process: its graph and the root created in it; [save] + a new process = process death. */
    private inner class Process(
        disk: PersistedProfile,
        saved: SerializableContainer? = null,
        localProfile: ProfileId? = null,
    ) {
        val graph: TestAppGraph = createGraphFactory<TestAppGraph.Factory>().create(disk)
        private val stateKeeper = StateKeeperDispatcher(saved)
        private val lifecycle = LifecycleRegistry().apply { resume() }
        private var isClosed = false
        val root = HeartbeatRoot(
            context = DefaultComponentContext(lifecycle, stateKeeper = stateKeeper),
            graph = graph,
            start = start,
            localProfile = localProfile,
        )

        init {
            processes += this
        }

        /** Serialized like the platform does it: only bytes survive process death. */
        fun save(): SerializableContainer = Json.decodeFromString(
            SerializableContainer.serializer(),
            Json.encodeToString(SerializableContainer.serializer(), stateKeeper.save()),
        )

        /** Ends a simulated process before its replacement opens the same app-owned Preferences files. */
        suspend fun close() {
            if (!isClosed) {
                isClosed = true
                lifecycle.destroy()
                (graph.appScope as OwnedScope).close()
            }
            graph.appScope.coroutineScope.coroutineContext[Job]?.join()
        }
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
        try {
            body()
        } finally {
            withContext(NonCancellable) {
                processes.asReversed().forEach { it.close() }
                processes.clear()
                // Root's lifecycle scope is independent of the app job; finish its queued cancellation too.
                advanceUntilIdle()
            }
        }
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
    fun `ordinary startup opens a stable local profile when no profile is restored`() = runRootTest {
        val disk = PersistedProfile()
        val first = Process(disk, localProfile = ProfileId("local"))
        advanceUntilIdle()
        assertEquals(ProfileId("local"), assertIs<RootChild.Profile>(first.root.child).id)

        first.close()
        val reopened = Process(disk, localProfile = ProfileId("local"))
        advanceUntilIdle()
        assertEquals(ProfileId("local"), assertIs<RootChild.Profile>(reopened.root.child).id)
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
        val process = Process(PersistedProfile(isOperationSuspensionEnabled = false))
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
        val process = Process(PersistedProfile(isOperationSuspensionEnabled = false))
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

        before.close()
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

        val saved = before.save()
        before.close()
        val after = Process(disk, saved)
        advanceUntilIdle()
        after.graph.profileSessions.open(ProfileId("p1"))
        advanceUntilIdle()

        assertEquals(listOf<Route>(FeedRoute), after.root.host?.routes)
    }
}
