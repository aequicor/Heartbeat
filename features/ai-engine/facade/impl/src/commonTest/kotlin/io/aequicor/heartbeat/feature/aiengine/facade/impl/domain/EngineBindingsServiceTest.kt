package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    private val busy = mutableSetOf<EngineBindingId>()

    private fun TestScope.service(): EngineBindingsService {
        val registry = EngineRegistry(listOf(registration(factory)), EnginePlatform.DesktopMacOs)
        return EngineBindingsService(
            EngineGate(registry, toggles),
            store,
            sources,
            checks,
            { it in busy },
            facadeContext(clock),
        )
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
    fun `disabling keeps the binding and disconnect refuses active uses`() = runTest {
        val service = service()
        val binding = service.connect(TestEngine, sources.add(managedKey()).info.id)

        service.setEnabled(binding.id, false)
        assertFalse(store.bindings.value.single().isEnabled)

        busy += binding.id
        val error = assertFailsWith<EngineException> { service.disconnect(binding.id) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)

        busy.clear()
        assertTrue(binding.id in factory.routes)
        service.disconnect(binding.id)
        assertTrue(store.bindings.value.isEmpty())
        assertTrue(factory.routes.isEmpty())
        assertIs<EngineException>(assertFailsWith<EngineException> { service.setEnabled(binding.id, true) })
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
