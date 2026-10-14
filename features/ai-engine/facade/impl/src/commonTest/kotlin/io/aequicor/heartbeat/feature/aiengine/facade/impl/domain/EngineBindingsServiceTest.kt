package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineBindingsServiceTest {
    private val clock = FixedClock()
    private val factory = FakeEngineFactory()
    private val toggles = FakeEngineToggles()
    private val store = FakeBindingStore()
    private val sources = FakeAuthSources()
    private val checks = FakeAuthChecks(clock)
    private val released = mutableListOf<EngineBinding>()
    private var releaseFailure: Exception? = null
    private var releaseGate: CompletableDeferred<Unit>? = null

    private fun TestScope.service(): EngineBindingsService {
        val registry = EngineRegistry(listOf(registration(factory)), EnginePlatform.DesktopMacOs)
        return EngineBindingsService(
            EngineGate(registry, toggles),
            store,
            sources,
            checks,
            {
                assertTrue(store.bindings.value.none { saved -> saved.id == it.id })
                released += it
                releaseGate?.await()
                releaseFailure?.let { failure -> throw failure }
            },
            facadeContext(clock),
        )
    }

    @Test
    fun `connecting refreshes the source revision reported by the adapter before binding`() = runTest {
        val service = service()
        val source = sources.add(cliLogin())
        factory.revision = { AuthRevision.Known("login-2") }

        val binding = service.connect(TestEngine, source.info.id)

        assertEquals(AuthRevision.Known("login-2"), sources.get(source.info.id)?.info?.revision)
        assertEquals(AuthRevision.Known("login-2"), factory.routes.getValue(binding.id).info.revision)
    }

    @Test
    fun `two source ids cannot alias one native CLI account location`() = runTest {
        val service = service()
        val first = sources.add(cliLogin("one"))
        val second = sources.add(cliLogin("two"))
        val binding = service.connect(TestEngine, first.info.id)
        assertFailsWith<EngineException> { service.connect(TestEngine, second.info.id) }
        assertEquals(listOf(binding), store.bindings.value)
    }

    @Test
    fun `connecting the same engine and source updates one binding`() = runTest {
        val service = service()
        val source = sources.add(managedKey())

        val first = service.connect(TestEngine, source.info.id)
        val second = service.connect(TestEngine, source.info.id, priority = 5)

        assertEquals(first.id, second.id)
        assertEquals(5, second.priority)
        assertEquals(listOf(second), store.bindings.value)
        assertEquals(mapOf(first.id to source), factory.routes)
    }

    @Test
    fun `foreign CLI logins and adapter-rejected sources are refused`() = runTest {
        val service = service()
        val foreign = sources.add(cliLogin(owner = AuthOwnerId("other-cli")))
        val mismatch = assertFailsWith<EngineException> { service.connect(TestEngine, foreign.info.id) }
        assertEquals(authFailure(AuthFailureReason.AuthMismatch, foreign.info.id), mismatch.failure)

        factory.accepts = { _, _ -> false }
        val own = sources.add(cliLogin(id = "src_own"))
        assertFailsWith<EngineException> { service.connect(TestEngine, own.info.id) }
        assertTrue(store.bindings.value.isEmpty())
        assertTrue(factory.routes.isEmpty())
    }

    @Test
    fun `missing sources and disabled engines never create bindings`() = runTest {
        val service = service()
        val missing = assertFailsWith<EngineException> { service.connect(TestEngine, AuthSourceId("src_gone")) }
        assertEquals(authFailure(AuthFailureReason.SourceUnavailable, AuthSourceId("src_gone")), missing.failure)

        toggles.disabled.value = setOf(TestEngine)
        val source = sources.add(managedKey())
        assertEquals(
            EngineUnavailable,
            assertFailsWith<EngineException> { service.connect(TestEngine, source.info.id) }.failure,
        )
        assertTrue(store.bindings.value.isEmpty())
    }

    @Test
    fun `disabling keeps the binding while disconnect removes it and releases execution`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)

        service.setEnabled(binding.id, false)
        assertFalse(store.bindings.value.single().isEnabled)

        assertTrue(binding.id in factory.routes)
        service.disconnect(binding.id)
        assertTrue(store.bindings.value.isEmpty())
        assertTrue(factory.routes.isEmpty())
        assertEquals(listOf(binding.copy(isEnabled = false)), released)
        assertIs<EngineException>(assertFailsWith<EngineException> { service.setEnabled(binding.id, true) })
    }

    @Test
    fun `failed runtime shutdown can be retried after the binding was removed`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)
        releaseFailure = IllegalStateException("shutdown failed")

        assertFailsWith<IllegalStateException> { service.disconnect(binding.id) }
        assertTrue(store.bindings.value.isEmpty())
        assertTrue(factory.unbinds.isEmpty())

        releaseFailure = null
        service.disconnect(binding.id)
        service.disconnect(binding.id)

        assertEquals(listOf(binding, binding), released)
        assertEquals(listOf(binding.id), factory.unbinds)
    }

    @Test
    fun `runtime shutdown does not block route reads and survives caller cancellation`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)
        releaseGate = CompletableDeferred()
        val removal = launch { service.disconnect(binding.id) }
        runCurrent()

        assertTrue(service.saved().isEmpty())
        removal.cancel()
        releaseGate?.complete(Unit)
        removal.join()

        assertEquals(listOf(binding.id), factory.unbinds)
        assertTrue(factory.routes.isEmpty())
    }

    @Test
    fun `failed removal does not stop execution or unbind the adapter`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)
        store.saveFailure = IllegalStateException("disk")

        assertFailsWith<IllegalStateException> { service.disconnect(binding.id) }

        assertEquals(listOf(binding), store.bindings.value)
        assertTrue(released.isEmpty())
        assertTrue(factory.unbinds.isEmpty())
    }

    @Test
    fun `check runs the engine authenticators in the engine-derived context`() = runTest {
        val service = service()
        val source = sources.add(managedKey())
        val binding = service.connect(TestEngine, source.info.id)

        val result = service.check(EngineTarget(TestEngine, binding.id, ModelId("m1")))

        assertEquals(binding.id, result.binding)
        assertEquals(AuthVerdict.Authenticated, result.auth.verdict)
        assertEquals(
            Triple(source.info.id, AuthContextKey("ctx.test"), setOf(AuthenticatorId("test.cli"))),
            checks.calls.single(),
        )
    }

    @Test
    fun `a failed bind saves no binding`() = runTest {
        val service = service()
        val source = sources.add(managedKey())
        factory.bindFailure = IllegalStateException("adapter bind")

        assertFailsWith<IllegalStateException> { service.connect(TestEngine, source.info.id) }

        assertTrue(store.bindings.value.isEmpty())
        assertTrue(factory.routes.isEmpty())
    }

    @Test
    fun `a failed save of a new binding unbinds the adapter route`() = runTest {
        val service = service()
        val source = sources.add(managedKey())
        store.saveFailure = IllegalStateException("disk")
        factory.unbindFailure = IllegalArgumentException("unbind")

        val error = assertFailsWith<IllegalStateException> { service.connect(TestEngine, source.info.id) }

        assertEquals("disk", error.message)
        assertIs<IllegalArgumentException>(error.suppressedExceptions.single())
        assertEquals(1, factory.unbinds.size)
        assertTrue(store.bindings.value.isEmpty())
    }

    @Test
    fun `disconnect succeeds when the adapter unbind fails`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)
        factory.unbindFailure = IllegalStateException("unbind")

        service.disconnect(binding.id)

        assertTrue(store.bindings.value.isEmpty())
        assertEquals(listOf(binding.id), factory.unbinds)
    }
}
