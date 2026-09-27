package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

internal class FakeModelCache : ModelCache {
    val entries = MutableStateFlow(emptyList<CachedModels>())

    override fun observe(): Flow<List<CachedModels>> = entries

    override suspend fun load(): List<CachedModels> = entries.value

    override suspend fun save(entries: List<CachedModels>) {
        this.entries.value = entries
    }
}

/** Registry, gate, bindings and route resolver over fakes, with one managed-key binding `b1`. */
internal class RouteFixture(scope: TestScope, val factory: FakeEngineFactory = FakeEngineFactory()) {
    val clock = FixedClock()
    val toggles = FakeEngineToggles()
    val store = FakeBindingStore()
    val sources = FakeAuthSources()
    val context = scope.facadeContext(clock)
    val registry = EngineRegistry(listOf(registration(factory)), EnginePlatform.DesktopWindows)
    val gate = EngineGate(registry, toggles)
    val bindings = EngineBindingsService(gate, store, sources, FakeAuthChecks(clock), { false }, context)
    val routes = RouteResolver(registry, gate, bindings)
    val source = sources.add(managedKey())
    val binding = EngineBinding(EngineBindingId("b1"), TestEngine, source.info.id)
    val target = EngineTarget(TestEngine, binding.id, ModelId("m1"))

    init {
        store.bindings.value = listOf(binding)
    }
}

class RouteAndModelsTest {
    @Test
    fun `route fixes the checked source revision and pools by engine and source`() = runTest {
        val fixture = RouteFixture(this)
        val resolved = fixture.routes.resolve(TestEngine, fixture.binding.id, WorkspaceRef("w1"), ModelId("m1"))

        assertEquals(
            ExecutionRoute(
                TestEngine,
                fixture.binding.id,
                fixture.source.info.id,
                AuthRevision.Known("r1"),
                WorkspaceRef("w1"),
            ),
            resolved.route,
        )
        assertEquals(RuntimeIdentity(TestEngine, fixture.source.info.id, AuthRevision.Known("r1")), resolved.identity)
    }

    @Test
    fun `disabled bindings and foreign engines never fall back to another route`() = runTest {
        val fixture = RouteFixture(this)
        fixture.store.bindings.value = listOf(fixture.binding.copy(isEnabled = false))
        assertEquals(
            OperationNotAllowed,
            assertFailsWith<EngineException> { fixture.routes.resolve(TestEngine, fixture.binding.id) }.failure,
        )
        assertEquals(
            EngineUnavailable,
            assertFailsWith<EngineException> { fixture.routes.resolve(EngineId("other"), fixture.binding.id) }.failure,
        )
    }

    @Test
    fun `recheck rejects a rotated source before the next turn`() = runTest {
        val fixture = RouteFixture(this)
        val route = fixture.routes.resolve(TestEngine, fixture.binding.id).route
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))

        val error = assertFailsWith<EngineException> { fixture.routes.recheck(route, ModelId("m1")) }
        assertEquals(authFailure(AuthFailureReason.SourceChanged, fixture.source.info.id), error.failure)
    }

    @Test
    fun `refresh caches only models of the exact binding`() = runTest {
        val fixture = RouteFixture(this)
        val cache = FakeModelCache()
        val models = ModelCatalogService(cache, fixture.routes, fixture.context)
        val own = ModelInfo(fixture.target, "Model 1")
        val foreign = ModelInfo(fixture.target.copy(binding = EngineBindingId("other")), "Foreign")
        fixture.factory.models = listOf(own, foreign)

        assertEquals(Observation(), models.observe(TestEngine, fixture.binding.id).value.observation)
        val snapshot = models.refresh(TestEngine, fixture.binding.id)

        assertEquals(listOf(own), snapshot.models)
        assertEquals(Observation(fixture.clock.now, isStale = false), snapshot.observation)
        assertEquals(snapshot, models.observe(TestEngine, fixture.binding.id).first { it.models.isNotEmpty() })
    }

    @Test
    fun `failed discovery keeps valid cached data and old entries turn stale`() = runTest {
        val fixture = RouteFixture(this)
        val cache = FakeModelCache()
        val models = ModelCatalogService(cache, fixture.routes, fixture.context)
        fixture.factory.models = listOf(ModelInfo(fixture.target, "Model 1"))
        models.refresh(TestEngine, fixture.binding.id)

        val outage = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)
        fixture.factory.modelsFailure = EngineException(outage)
        assertEquals(
            outage,
            assertFailsWith<EngineException> { models.refresh(TestEngine, fixture.binding.id) }.failure,
        )
        fixture.factory.modelsFailure = IllegalStateException("adapter bug")
        assertEquals(
            EngineFailure.Unknown(),
            assertFailsWith<EngineException> { models.refresh(TestEngine, fixture.binding.id) }.failure,
        )

        fixture.clock.now += 2.days
        val cached = models.observe(TestEngine, fixture.binding.id).first { it.models.isNotEmpty() }
        assertEquals(1, cached.models.size)
        assertTrue(cached.observation.isStale)
    }
}
