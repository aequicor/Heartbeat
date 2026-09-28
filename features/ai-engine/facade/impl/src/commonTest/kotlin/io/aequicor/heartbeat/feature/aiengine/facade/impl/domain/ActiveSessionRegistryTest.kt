package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActiveSessionRegistryTest {
    @Test
    fun `lock is dropped after exclusive block`() = runTest {
        val registry = ActiveSessionRegistry()
        repeat(3) { index -> registry.exclusive(sessionRef("s$index")) { } }

        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is dropped after failed block`() = runTest {
        val registry = ActiveSessionRegistry()
        assertFailsWith<IllegalStateException> {
            registry.exclusive(sessionRef("s")) { error("boom") }
        }

        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is kept while waiter pending and serializes`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("s")
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch {
            registry.exclusive(ref) {
                order += "first"
                release.await()
                order += "first-done"
            }
        }
        runCurrent()
        val second = launch { registry.exclusive(ref) { order += "second" } }
        runCurrent()

        assertEquals(1, registry.lockCount())
        assertEquals(listOf("first"), order)

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("first", "first-done", "second"), order)
        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is dropped when waiter is cancelled`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("s")
        val release = CompletableDeferred<Unit>()
        val holder = launch { registry.exclusive(ref) { release.await() } }
        runCurrent()
        val waiter = launch { registry.exclusive(ref) { } }
        runCurrent()
        waiter.cancel()
        release.complete(Unit)
        holder.join()
        waiter.join()

        assertEquals(0, registry.lockCount())
    }
}
