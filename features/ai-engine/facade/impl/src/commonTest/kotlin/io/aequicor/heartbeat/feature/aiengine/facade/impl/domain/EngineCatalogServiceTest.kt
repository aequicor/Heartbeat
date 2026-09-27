package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineCatalogServiceTest {
    private val clock = FixedClock()
    private val factory = FakeEngineFactory()
    private val toggles = FakeEngineToggles()
    private val bindings = MutableStateFlow(emptyList<EngineBinding>())

    private fun TestScope.catalog(
        registrations: List<EngineRegistration> = listOf(registration(factory)),
        platform: EnginePlatform? = EnginePlatform.DesktopWindows,
    ): EngineCatalogService {
        val registry = EngineRegistry(registrations, platform)
        return EngineCatalogService(
            registry,
            toggles,
            EngineGate(registry, toggles),
            bindings,
            facadeContext(clock),
        ) { NoEngineFeatures }
    }

    @Test
    fun `observation lists enabled engines without probing them`() = runTest {
        val catalog = catalog()
        bindings.value = listOf(EngineBinding(EngineBindingId("b1"), TestEngine, managedKey().info.id))
        runCurrent()

        val info = catalog.state.value.single()
        assertEquals(EngineAvailability.Unknown, info.availability)
        assertEquals(Observation(), info.observation)
        assertEquals(bindings.value, info.bindings)
        assertEquals(0, factory.probes)
    }

    @Test
    fun `disabled engines disappear while their bindings are retained`() = runTest {
        val catalog = catalog()
        bindings.value = listOf(EngineBinding(EngineBindingId("b1"), TestEngine, managedKey().info.id))
        toggles.disabled.value = setOf(TestEngine)
        runCurrent()

        assertTrue(catalog.state.value.isEmpty())
        assertEquals(1, bindings.value.size)
        val error = assertFailsWith<EngineException> { catalog.refresh(TestEngine) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.Unavailable), error.failure)
        assertIs<FeatureAccess.Unavailable>(catalog.features(TestEngine).resolve(ListsSessions))
    }

    @Test
    fun `explicit refresh probes requirements and records the observation`() = runTest {
        val catalog = catalog()
        runCurrent()

        val info = catalog.refresh(TestEngine)
        runCurrent()

        assertEquals(EngineAvailability.Available, info.availability)
        assertEquals(Observation(clock.now, isStale = false), info.observation)
        assertEquals(info, catalog.state.value.single())
        assertEquals(1, factory.probes)
    }

    @Test
    fun `probe failures become cached unavailability instead of exceptions`() = runTest {
        val catalog = catalog()
        factory.probeFailure = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        assertEquals(
            EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)),
            catalog.refresh(TestEngine).availability,
        )

        factory.probeFailure = IllegalStateException("native crash")
        assertEquals(EngineAvailability.Unavailable(EngineFailure.Unknown()), catalog.refresh(TestEngine).availability)
    }

    @Test
    fun `unsupported platforms are known without starting anything`() = runTest {
        val catalog = catalog(listOf(registration(factory, platforms = setOf(EnginePlatform.Android))))
        runCurrent()

        assertEquals(EngineAvailability.UnsupportedPlatform, catalog.state.value.single().availability)
        assertEquals(EngineAvailability.UnsupportedPlatform, catalog.refresh(TestEngine).availability)
        assertEquals(0, factory.probes)
    }

    @Test
    fun `duplicate registrations are rejected before exposing descriptors`() {
        assertFailsWith<IllegalArgumentException> {
            EngineRegistry(listOf(registration(), registration(id = EngineId("other"))), EnginePlatform.Android)
        }
    }
}
