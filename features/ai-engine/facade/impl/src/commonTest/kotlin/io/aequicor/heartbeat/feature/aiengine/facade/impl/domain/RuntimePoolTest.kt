package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class RuntimePoolTest {
    private val factory = FakeEngineFactory()
    private val otherFactory = FakeEngineFactory()
    private val runtimes = mutableListOf<FakeRuntime>()
    private val busy = mutableSetOf<AuthSourceId>()
    private val retiredHandles = mutableListOf<Pair<EngineId, AuthSourceId>>()

    private var launch = LaunchContext()

    private fun TestScope.pool(clock: FixedClock = FixedClock()): RuntimePool {
        factory.runtime = { identity -> FakeRuntime(identity).also { runtimes += it } }
        otherFactory.runtime = factory.runtime
        return RuntimePool(
            facadeContext(clock),
            { _, source -> source in busy },
            { engine, source -> retiredHandles += engine to source },
            { launch },
        )
    }

    @Test
    fun `diagnostics observes a runtime that exits without changing the pool`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey()))
        val observer = backgroundScope.launch { pool.entries.collect {} }
        runCurrent()
        runtimes.single().isClosed = true
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(pool.entries.value.single().isClosed)
        observer.cancel()
    }

    @Test
    fun `reconciliation retires stale idle sources while preserving busy and current ones`() = runTest {
        val pool = pool()
        val active = managedKey("src_busy")
        pool.runtime(route(active))
        pool.runtime(route(managedKey("src_idle")))
        busy += active.info.id
        launch = LaunchContext(LaunchSettings(homeDirectory = "/new"))
        pool.runtime(route(managedKey("src_fresh")))
        assertEquals(RetireOutcome(1, 1), pool.retire(TestEngine, launch))
        assertEquals(setOf("src_busy", "src_fresh"), pool.entries.value.map { it.source.value }.toSet())
        busy.clear()
        assertEquals(RetireOutcome(1, 0), pool.retire(TestEngine, launch))
        assertEquals("src_fresh", pool.entries.value.single().source.value)
        assertEquals(0, runtimes.last().closes)
    }

    @Test
    fun `a live runtime is reused for the same identity`() = runTest {
        val pool = pool()
        val key = managedKey()

        assertSame(pool.runtime(route(key)), pool.runtime(route(key)))
        assertEquals(1, runtimes.size)
    }

    @Test
    fun `a runtime that shut itself down is replaced even while its handles report a turn`() = runTest {
        val pool = pool()
        val key = managedKey()
        val first = pool.runtime(route(key))
        runtimes.single().isClosed = true
        busy += key.info.id

        val second = pool.runtime(route(key))

        assertNotSame(first, second)
        assertEquals(1, runtimes.first().closes)
        assertEquals(listOf(TestEngine to key.info.id), retiredHandles)
        assertFalse(pool.entries.value.single().isClosed)
    }

    @Test
    fun `a changed revision is still refused while a turn runs on the live runtime`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey(revision = AuthRevision.Known("r1"))))
        busy += AuthSourceId("src_key")

        val error = assertFailsWith<EngineException> {
            pool.runtime(route(managedKey(revision = AuthRevision.Known("r2"))))
        }

        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        assertEquals(0, runtimes.single().closes)
    }

    @Test
    fun `retiring an engine stops its idle runtimes and keeps the busy ones`() = runTest {
        val pool = pool()
        val idle = managedKey("src_idle")
        val active = managedKey("src_busy")
        pool.runtime(route(idle))
        pool.runtime(route(active))
        busy += active.info.id

        assertEquals(RetireOutcome(retired = 1, busy = 1), pool.retire(TestEngine))

        assertEquals(listOf(1, 0), runtimes.map { it.closes })
        assertEquals(listOf(TestEngine to idle.info.id), retiredHandles)
        assertEquals(listOf(active.info.id), pool.entries.value.map { it.source })
    }

    @Test
    fun `disconnect stops a busy source and preserves other sources and engines`() = runTest {
        val pool = pool()
        val removed = route(managedKey("src_removed"))
        pool.runtime(removed)
        pool.runtime(route(managedKey("src_other")))
        pool.runtime(route(removed.source, OtherEngine))
        busy += removed.source.info.id

        pool.disconnect(removed.binding)

        assertEquals(listOf(1, 0, 0), runtimes.map { it.closes })
        assertEquals(listOf(TestEngine to removed.source.info.id), retiredHandles)
        assertTrue(runtimes.first().isClosed)
        assertEquals(2, pool.entries.value.size)
        assertEquals(
            OperationNotAllowed,
            assertFailsWith<EngineException> { pool.runtime(removed) }.failure,
        )
    }

    @Test
    fun `disconnect closes the runtime before releasing its handles`() = runTest {
        var closedBeforeHandles = false
        val pool = RuntimePool(
            facadeContext(),
            { _, _ -> false },
            { _, _ -> closedBeforeHandles = runtimes.single().isClosed },
        )
        factory.runtime = { FakeRuntime(it).also { runtime -> runtimes += runtime } }
        val removed = route(managedKey())
        pool.runtime(removed)

        pool.disconnect(removed.binding)

        assertTrue(closedBeforeHandles)
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `disconnect releases handles even when no pooled runtime remains`() = runTest {
        val pool = pool()
        val removed = route(managedKey())

        pool.disconnect(removed.binding)

        assertEquals(listOf(TestEngine to removed.source.info.id), retiredHandles)
        assertTrue(runtimes.isEmpty())
        assertFailsWith<EngineException> { pool.runtime(removed) }
    }

    @Test
    fun `disconnect and profile shutdown close their shared runtime only once`() = runTest {
        val pool = pool()
        val removed = route(managedKey())
        pool.runtime(removed)
        val gate = CompletableDeferred<Unit>().also { runtimes.single().closeGate = it }
        val disconnect = launch { pool.disconnect(removed.binding) }
        runCurrent()
        val shutdown = launch { pool.closeAll() }
        runCurrent()

        assertEquals(1, runtimes.single().closes)
        gate.complete(Unit)
        disconnect.join()
        shutdown.join()

        assertEquals(1, runtimes.single().closes)
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `concurrent shutdown callers wait for native termination`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey()))
        val gate = CompletableDeferred<Unit>().also { runtimes.single().closeGate = it }
        val first = async { pool.closeAll() }
        runCurrent()
        val second = async { pool.closeAll() }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, runtimes.single().closes)
        gate.complete(Unit)
        first.await()
        second.await()
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `shutdown waits for a runtime being created and stops it before completion`() = runTest {
        val pool = pool()
        val gate = CompletableDeferred<Unit>().also { factory.createGate = it }
        val creation = async { assertFailsWith<EngineException> { pool.runtime(route(managedKey())) } }
        runCurrent()
        val closing = async { pool.closeAll() }
        runCurrent()

        assertFalse(closing.isCompleted)
        gate.complete(Unit)
        creation.await()
        closing.await()
        assertEquals(1, runtimes.single().closes)
        assertTrue(runtimes.single().isClosed)
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `shutdown retains failed runtimes and still stops other sources`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey("src_failed")))
        pool.runtime(route(managedKey("src_other")))
        runtimes.first().closeFailure = IllegalStateException("stop failed")

        assertFailsWith<IllegalStateException> { pool.closeAll() }

        assertEquals(listOf(1, 1), runtimes.map { it.closes })
        assertEquals(listOf(AuthSourceId("src_failed")), pool.entries.value.map { it.source })
        runtimes.first().closeFailure = null
        pool.closeAll()
        assertEquals(listOf(2, 1), runtimes.map { it.closes })
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `runtime created during shutdown stays owned when termination fails`() = runTest {
        val pool = pool()
        val gate = CompletableDeferred<Unit>().also { factory.createGate = it }
        factory.runtime = { identity ->
            FakeRuntime(identity).also {
                it.closeFailure = IllegalStateException("stop failed")
                runtimes += it
            }
        }
        val creation = async { assertFailsWith<IllegalStateException> { pool.runtime(route(managedKey())) } }
        runCurrent()
        val closing = async { assertFailsWith<IllegalStateException> { pool.closeAll() } }
        runCurrent()
        gate.complete(Unit)

        creation.await()
        closing.await()
        assertEquals(2, runtimes.single().closes)
        assertEquals(1, pool.entries.value.size)
        runtimes.single().closeFailure = null
        pool.closeAll()
        assertEquals(3, runtimes.single().closes)
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `disconnect retries failed shutdown of a runtime that finished starting after removal`() = runTest {
        val pool = pool()
        val removed = route(managedKey())
        val gate = CompletableDeferred<Unit>().also { factory.createGate = it }
        factory.runtime = { identity ->
            FakeRuntime(identity).also {
                it.closeFailure = IllegalStateException("shutdown failed")
                runtimes += it
            }
        }
        val starting = async { assertFailsWith<EngineException> { pool.runtime(removed) } }
        runCurrent()
        val disconnect = async { assertFailsWith<IllegalStateException> { pool.disconnect(removed.binding) } }
        runCurrent()
        gate.complete(Unit)

        assertEquals(OperationNotAllowed, starting.await().failure)
        assertEquals("shutdown failed", disconnect.await().message)
        assertEquals(1, pool.entries.value.size)
        assertEquals(1, runtimes.single().closes)

        // Some adapters mark closed before all shutdown work has succeeded. Ordinary pruning must not lose it.
        runtimes.single().isClosed = true
        val replacement = removed.copy(binding = removed.binding.copy(id = EngineBindingId("reconnected")))
        assertEquals(0, pool.prune())
        assertEquals(RetireOutcome(retired = 0, busy = 1), pool.retire(TestEngine))
        assertFailsWith<EngineException> { pool.runtime(replacement) }
        assertEquals(1, runtimes.size)

        runtimes.single().closeFailure = null
        pool.disconnect(removed.binding)

        assertEquals(2, runtimes.single().closes)
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `retiring an engine disposes its exited runtime even when its handles report a turn`() = runTest {
        val pool = pool()
        val key = managedKey()
        pool.runtime(route(key))
        runtimes.single().isClosed = true
        busy += key.info.id

        assertEquals(RetireOutcome(retired = 1, busy = 0), pool.retire(TestEngine))
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `retiring an engine leaves the runtimes of other engines running`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey()))
        pool.runtime(route(managedKey(), engine = OtherEngine))

        assertEquals(RetireOutcome(retired = 1, busy = 0), pool.retire(OtherEngine))

        assertEquals(listOf(TestEngine), pool.entries.value.map { it.engine })
        assertEquals(listOf(0, 1), runtimes.map { it.closes })
    }

    @Test
    fun `entries list pooled runtimes with their start time`() = runTest {
        val clock = FixedClock(Instant.fromEpochSeconds(42))
        val pool = pool(clock)
        val key = managedKey()

        pool.runtime(route(key))

        assertEquals(listOf(RuntimeEntry(TestEngine, key.info.id, clock.now, isClosed = false)), pool.entries.value)
        assertEquals(LaunchContext(), pool.entries.value.single().launch)
        pool.closeAll()
        assertTrue(pool.entries.value.isEmpty())
    }

    @Test
    fun `entries remember the launch context each runtime started with`() = runTest {
        val pool = pool()
        launch = LaunchContext(LaunchSettings(executable = "/opt/first"))
        pool.runtime(route(managedKey("src_a")))
        launch = LaunchContext(LaunchSettings(executable = "/opt/second"))
        pool.runtime(route(managedKey("src_b")))

        assertEquals(
            listOf("/opt/first", "/opt/second"),
            pool.entries.value.map { it.launch.settings.executable },
        )
    }

    @Test
    fun `pruning disposes only the runtimes that shut themselves down`() = runTest {
        val pool = pool()
        pool.runtime(route(managedKey("src_a")))
        pool.runtime(route(managedKey("src_b")))
        runtimes.first().isClosed = true

        assertEquals(1, pool.prune())

        assertEquals(listOf(AuthSourceId("src_b")), pool.entries.value.map { it.source })
        assertEquals(listOf(1, 0), runtimes.map { it.closes })
        assertEquals(0, pool.prune())
    }

    private fun route(source: AuthSource, engine: EngineId = TestEngine): ResolvedRoute {
        val binding = EngineBinding(EngineBindingId("b_${source.info.id.value}"), engine, source.info.id)
        val registration = if (engine == TestEngine) {
            registration(factory)
        } else {
            registration(otherFactory, id = engine, owner = AuthOwnerId("other-cli"))
        }
        return ResolvedRoute(registration, binding, source, EngineContext(engine, binding.id))
    }

    private companion object {
        val OtherEngine = EngineId("other")
    }
}
