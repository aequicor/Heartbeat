package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCleanupOwnerTest {
    private val identity = RuntimeIdentity(TestEngine, AuthSourceId("source"), AuthRevision.Unknown)

    @Test
    fun `failed runtime close stays owned and another source cannot clear it`() = runTest {
        val owner = RuntimeCleanupOwner(facadeContext())
        val runtime = FakeRuntime(identity).also { it.closeFailure = IllegalStateException("test") }
        assertFailsWith<IllegalStateException> { owner.close(runtime, runtime.identity) }
        assertEquals(1, owner.pendingCount)
        owner.retryFor(TestEngine, AuthSourceId("another"))
        owner.retryFor(EngineId("another"), identity.source)
        assertEquals(1, runtime.closes)
        runtime.closeFailure = null
        owner.retryFor(TestEngine, identity.source)
        assertEquals(2, runtime.closes)
        assertEquals(0, owner.pendingCount)
    }

    @Test
    fun `initial close and overlapping shutdown retry wait for one native close`() = runTest {
        val owner = RuntimeCleanupOwner(facadeContext())
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(identity).also { it.closeGate = gate }
        val first = launch { owner.close(runtime, runtime.identity) }
        runCurrent()
        val same = launch { owner.close(runtime, runtime.identity) }
        val shutdown = launch { owner.retryAll() }
        runCurrent()
        assertEquals(1, runtime.closes)
        assertFalse(first.isCompleted)
        assertFalse(same.isCompleted)
        assertFalse(shutdown.isCompleted)
        gate.complete(Unit)
        first.join()
        same.join()
        shutdown.join()
        assertEquals(1, runtime.closes)
        assertEquals(0, owner.pendingCount)
    }

    @Test
    fun `cancelling an unconfirmed close leaves the resource for a later retry`() = runTest {
        val owner = RuntimeCleanupOwner(facadeContext())
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(identity).also { it.closeGate = gate }
        val first = launch { owner.close(runtime, runtime.identity) }
        runCurrent()
        first.cancel()
        first.join()
        assertEquals(1, owner.pendingCount)
        gate.complete(Unit)
        owner.retryAll()
        assertEquals(2, runtime.closes)
        assertEquals(0, owner.pendingCount)
    }

    @Test
    fun `equal clients remain separately owned and all are retried after one fails`() = runTest {
        val owner = RuntimeCleanupOwner(facadeContext())
        val first = FakeRuntime(identity).also { it.closeFailure = IllegalStateException("first") }
        val second = FakeRuntime(identity).also { it.closeFailure = IllegalStateException("second") }
        val a = EqualRuntime(first)
        val b = EqualRuntime(second)
        assertEquals(a, b)
        assertFailsWith<IllegalStateException> { owner.close(a, identity) }
        assertFailsWith<IllegalStateException> { owner.close(b, identity) }
        assertEquals(2, owner.pendingCount)
        second.closeFailure = null
        assertFailsWith<IllegalStateException> { owner.retryAll() }
        assertEquals(2, second.closes)
        assertEquals(1, owner.pendingCount)
        first.closeFailure = null
        owner.retryAll()
        assertEquals(3, first.closes)
        assertEquals(0, owner.pendingCount)
    }

    @Test
    fun `a stalled source does not prevent independent retained resources from closing`() = runTest {
        val owner = RuntimeCleanupOwner(facadeContext())
        val first = FakeRuntime(identity).also { it.closeFailure = IllegalStateException("first") }
        val other = FakeRuntime(identity.copy(source = AuthSourceId("other"))).also {
            it.closeFailure = IllegalStateException("other")
        }
        assertFailsWith<IllegalStateException> { owner.close(first, first.identity) }
        assertFailsWith<IllegalStateException> { owner.close(other, other.identity) }
        first.closeFailure = null
        other.closeFailure = null
        val gate = CompletableDeferred<Unit>().also { first.closeGate = it }
        val shutdown = async { owner.retryAll() }
        runCurrent()
        assertEquals(2, first.closes)
        assertEquals(2, other.closes)
        assertEquals(1, owner.pendingCount)
        assertFalse(shutdown.isCompleted)
        gate.complete(Unit)
        shutdown.await()
        assertEquals(0, owner.pendingCount)
    }

    private class EqualRuntime(runtime: FakeRuntime) : EngineRuntime by runtime {
        override fun equals(other: Any?): Boolean = other is EqualRuntime
        override fun hashCode(): Int = 1
    }
}
