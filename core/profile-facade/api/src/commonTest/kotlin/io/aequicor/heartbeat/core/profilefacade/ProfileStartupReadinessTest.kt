package io.aequicor.heartbeat.core.profilefacade

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedScopes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileStartupReadinessTest {
    @Test
    fun `strict host awaits every recovery snapshot without starting services again`() = runTest {
        val first = DeferredStartup()
        val second = DeferredStartup()
        val session = ProfileSession(ProfileId("test"), StartupGraph(emptySet()), startups = setOf(first, second))
        val ready = async { session.awaitStartupReady() }
        runCurrent()
        assertFalse(ready.isCompleted)
        first.ready.complete(Unit)
        runCurrent()
        assertFalse(ready.isCompleted)
        second.ready.complete(Unit)
        ready.await()
        assertEquals(0, first.starts + second.starts)
    }

    @Test
    fun `recovery error is propagated and synchronous startup failure cannot become ready`() = runTest {
        val startup = DeferredStartup()
        startup.ready.completeExceptionally(IllegalStateException("Unavailable"))
        assertFailsWith<IllegalStateException> {
            ProfileSession(ProfileId("test"), StartupGraph(emptySet()), startups = setOf(startup)).awaitStartupReady()
        }
        val graph = StartupGraph(emptySet())
        assertFailsWith<IllegalStateException> {
            ProfileSession(ProfileId("test"), graph, startupFailures = 1).awaitStartupReady()
        }
        assertFalse(graph.wasRead)
    }

    @Test
    fun `legacy fire and forget services remain source compatible`() = runTest {
        var started = false
        val startup = ProfileStartup { started = true }
        startup.start()
        startup.awaitReady()
        assertTrue(started)
    }
}

private class DeferredStartup : ProfileStartup {
    val ready = CompletableDeferred<Unit>()
    var starts = 0
    override fun start() {
        starts += 1
    }
    override suspend fun awaitReady() = ready.await()
}

private class StartupGraph(private val startups: Set<ProfileStartup>) :
    ProfileGraph,
    ProfileStartupAccess {
    var wasRead = false
    override val profileStartups: Set<ProfileStartup> get() {
        wasRead = true
        return startups
    }
    override val scope: ScopeHandle get() = error("No scope access during readiness")
    override val sharedScopes: SharedScopes get() = error("No shared scope access during readiness")
}
