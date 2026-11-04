package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteUnavailableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.routeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Instant

class ExactRouteReadinessServiceTest {
    @Test
    fun `unknown is never available and an explicit refresh confirms the exact route`() = runTest {
        val fixture = fixture(EngineAvailability.Unknown) { engines ->
            engines.value = listOf(engine(EngineAvailability.Available))
        }

        assertEquals(RouteAvailability.Unknown, fixture.service.availability(target))
        assertEquals(RouteAvailability.Unknown, fixture.service.routes.value[target.routeKey()])

        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
        assertEquals(1, fixture.probes)
        assertEquals(RouteAvailability.Available, fixture.service.routes.value[target.routeKey()])
        assertEquals(1, fixture.probes)
    }

    @Test
    fun `a removed binding is unavailable and a restored binding is unknown until refreshed`() = runTest {
        val fixture = fixture(EngineAvailability.Available)
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))

        fixture.bindings.value = emptyList()
        advanceUntilIdle()
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.BindingMissing),
            fixture.service.availability(target),
        )

        fixture.bindings.value = listOf(binding)
        advanceUntilIdle()
        assertEquals(RouteAvailability.Unknown, fixture.service.availability(target))
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
    }

    @Test
    fun `a changed source revision retires the route and refresh confirms the new revision`() = runTest {
        val fixture = fixture(EngineAvailability.Available)
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))

        fixture.sources.value = listOf(source("r2"))
        advanceUntilIdle()
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.SourceRevisionChanged),
            fixture.service.availability(target),
        )

        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
        assertEquals(RouteAvailability.Available, fixture.service.availability(target))
    }

    @Test
    fun `a closed runtime is typed and a replaced runtime is unknown until refreshed`() = runTest {
        val fixture = fixture(EngineAvailability.Available)
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))

        fixture.runtimes.value = listOf(RuntimeEntry(engineId, sourceId, Instant.fromEpochSeconds(1), isClosed = true))
        advanceUntilIdle()
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.RuntimeClosed),
            fixture.service.availability(target),
        )

        fixture.runtimes.value = emptyList()
        advanceUntilIdle()
        assertEquals(RouteAvailability.Unknown, fixture.service.availability(target))
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
    }

    @Test
    fun `a refused admission is typed and an explicit refresh restores the route`() = runTest {
        val fixture = fixture(EngineAvailability.Available)
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))

        fixture.service.recordFailure(target, EngineFailure.Engine(EngineFailureReason.Incompatible))
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.AdmissionRefused),
            fixture.service.availability(target),
        )

        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
    }

    @Test
    fun `a refusal recorded while a probe is in flight survives its confirmation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var isBlocked = false
        val fixture = fixture(EngineAvailability.Available) {
            if (isBlocked) gate.await()
        }
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
        isBlocked = true

        val refresh = async { fixture.service.refresh(target) }
        runCurrent()
        fixture.service.recordFailure(target, EngineFailure.Engine(EngineFailureReason.Incompatible))
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.AdmissionRefused),
            fixture.service.availability(target),
        )
        gate.complete(Unit)

        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.AdmissionRefused),
            refresh.await(),
        )
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.AdmissionRefused),
            fixture.service.availability(target),
        )
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.AdmissionRefused),
            fixture.service.routes.value[target.routeKey()],
        )
        assertEquals(2, fixture.probes)
    }

    @Test
    fun `cancelling a refresh leaves the previous readiness unchanged`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val fixture = fixture(EngineAvailability.Available) {
            calls++
            if (calls > 1) gate.await()
        }
        assertEquals(RouteAvailability.Available, fixture.service.refresh(target))
        fixture.sources.value = listOf(source("r2"))
        advanceUntilIdle()
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.SourceRevisionChanged),
            fixture.service.availability(target),
        )

        val refresh = launch { fixture.service.refresh(target) }
        runCurrent()
        assertFalse(refresh.isCompleted)
        refresh.cancelAndJoin()

        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.SourceRevisionChanged),
            fixture.service.availability(target),
        )
    }

    @Test
    fun `an owner refresh restores a route after its binding returns`() = runTest {
        val fixture = fixture(EngineAvailability.Available, auto = true)
        advanceUntilIdle()
        assertEquals(RouteAvailability.Available, fixture.service.availability(target))

        fixture.bindings.value = emptyList()
        advanceUntilIdle()
        assertEquals(
            RouteAvailability.Unavailable(RouteUnavailableReason.BindingMissing),
            fixture.service.availability(target),
        )

        fixture.bindings.value = listOf(binding)
        advanceUntilIdle()
        assertEquals(RouteAvailability.Available, fixture.service.availability(target))
    }

    @Test
    fun `cached availability and routes read never probe`() = runTest {
        val fixture = fixture(EngineAvailability.Unknown)

        repeat(5) { fixture.service.availability(target) }
        fixture.service.routes.value

        assertEquals(0, fixture.probes)
        assertEquals(RouteAvailability.Unknown, fixture.service.availability(target))
    }

    private fun TestScope.fixture(
        availability: EngineAvailability,
        auto: Boolean = false,
        onProbe: suspend (MutableStateFlow<List<EngineInfo>>) -> Unit = {},
    ): Fixture {
        val engines = MutableStateFlow(listOf(engine(availability)))
        val fixture = Fixture(
            engines,
            MutableStateFlow(listOf(binding)),
            MutableStateFlow(listOf(source("r1"))),
            MutableStateFlow(emptyList()),
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        fixture.service = ExactRouteReadinessService(
            fixture.bindings,
            fixture.sources,
            fixture.engines,
            fixture.runtimes,
            FacadeContext(scope, Clock.System, UnconfinedTestDispatcher(testScheduler)) { "t" },
            { engineId ->
                fixture.probes++
                onProbe(engines)
            },
            auto,
        )
        return fixture
    }

    private class Fixture(
        val engines: MutableStateFlow<List<EngineInfo>>,
        val bindings: MutableStateFlow<List<EngineBinding>>,
        val sources: MutableStateFlow<List<AuthSource>>,
        val runtimes: MutableStateFlow<List<RuntimeEntry>>,
    ) {
        lateinit var service: ExactRouteReadinessService
        var probes = 0
    }

    private companion object {
        val engineId = EngineId("engine")
        val bindingId = EngineBindingId("binding")
        val sourceId = AuthSourceId("source")
        val target = EngineTarget(engineId, bindingId, ModelId("model"))
        val binding = EngineBinding(bindingId, engineId, sourceId)

        fun source(revision: String): AuthSource = AuthSource.NoAuth(
            AuthSourceInfo(sourceId, "Source", AuthRevision.Known(revision)),
            AuthScope(ProviderId("provider"), EndpointOrigin("https://localhost")),
        )

        fun engine(availability: EngineAvailability): EngineInfo = EngineInfo(
            EngineDescriptor(
                engineId,
                "Engine",
                EngineFamily.Vendor,
                emptySet(),
                FeatureToggle.Flag("test.route", "Test"),
            ),
            availability,
            listOf(binding),
        )
    }
}
