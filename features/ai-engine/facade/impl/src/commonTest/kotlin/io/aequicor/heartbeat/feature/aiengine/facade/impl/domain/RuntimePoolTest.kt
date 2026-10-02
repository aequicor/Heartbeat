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
import kotlinx.coroutines.test.TestScope
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
