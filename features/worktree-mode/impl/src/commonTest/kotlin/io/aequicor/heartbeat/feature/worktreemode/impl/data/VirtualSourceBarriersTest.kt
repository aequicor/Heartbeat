package io.aequicor.heartbeat.feature.worktreemode.impl.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualSourceBarriersTest {
    @Test
    fun `all profiles stop before any resume and closed participants are detached`() = runTest {
        val barriers = VirtualSourceBarriers()
        val events = mutableListOf<String>()
        val first = Participant("first", events)
        val second = Participant("second", events)
        barriers.refresh("project", first, first, explicit = false, resolve = false)
        first.paused = true
        second.stopped = false
        barriers.refresh("project", second, second, explicit = false, resolve = false)
        assertFalse(events.any { it.endsWith("resume") })
        events.clear()
        second.stopped = true
        barriers.refresh("project", first, first, explicit = false, resolve = false)
        assertEquals(
            listOf(
                "first inspect",
                "second inspect",
                "first pause",
                "second pause",
                "first stop",
                "second stop",
                "first resume",
                "second resume",
            ),
            events,
        )
        barriers.detach(first)
        events.clear()
        second.paused = true
        barriers.refresh("project", second, second, explicit = false, resolve = false)
        assertTrue(events.none { it.startsWith("first") })
    }

    @Test
    fun `independent queues atomically share application capacity`() = runTest {
        val arbiter = ProjectBuildArbiter()
        val one = QueuedBuild("one", "go", BuildCost(2, 100), emptySet())
        val two = QueuedBuild("two", "rust", BuildCost(2, 100), emptySet())
        assertTrue("one" in arbiter.admit(listOf(one), emptyList(), BuildBudget.parallel(1)).admitted)
        assertTrue(arbiter.admit(listOf(two), emptyList(), BuildBudget.parallel(1)).admitted.isEmpty())
        arbiter.release("one")
        assertTrue("two" in arbiter.admit(listOf(two), emptyList(), BuildBudget.parallel(1)).admitted)
    }

    private class Participant(val name: String, val events: MutableList<String>) : VirtualSourceParticipant {
        var paused = false
        var stopped = true
        override suspend fun inspect(): Boolean {
            events += "$name inspect"
            return paused
        }
        override suspend fun pause(all: Boolean) {
            events += "$name pause"
        }
        override suspend fun stop(): Boolean {
            events += "$name stop"
            return stopped
        }
        override suspend fun resume(explicit: Boolean, resolve: Boolean) {
            events += "$name resume"
            paused = false
        }
    }
}
