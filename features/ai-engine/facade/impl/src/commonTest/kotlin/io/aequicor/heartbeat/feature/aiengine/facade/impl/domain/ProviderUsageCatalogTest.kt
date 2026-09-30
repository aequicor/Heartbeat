package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderUsageCatalogTest {
    @Test
    fun `observing and disabled usage do not start an engine`() = runTest {
        val fixture = UsageFixture(this)
        val observed = fixture.catalog.observe(TestEngine, fixture.route.binding.id)
        assertEquals(ProviderUsageSnapshot(), observed.first())
        runCurrent()
        assertTrue(fixture.route.factory.createdRuntimes.isEmpty())
        fixture.flags.enabledState.value = false
        assertFailsWith<EngineException> { fixture.refresh() }
        assertTrue(fixture.route.factory.createdRuntimes.isEmpty())
    }

    @Test
    fun `completed route observations leave no jobs in the profile`() = runTest {
        val fixture = UsageFixture(this)
        runCurrent()
        val profile = fixture.route.context.scope.coroutineContext[Job]!!
        val initialJobs = profile.children.count()

        repeat(5) {
            fixture.catalog.observe(TestEngine, fixture.route.binding.id).first()
            runCurrent()
        }

        assertEquals(initialJobs, profile.children.count())
    }

    @Test
    fun `first refresh works without a session or earlier observation`() = runTest {
        val fixture = UsageFixture(this)

        assertEquals(25.0, fixture.refresh().windows.single().usedPercent)
        assertEquals(1, fixture.route.factory.createdRuntimes.size)
        assertEquals(25.0, fixture.observation().windows.single().usedPercent)
    }

    @Test
    fun `first native observation survives delayed saved binding hydration`() = runTest {
        val context = facadeContext()
        val sources = FakeAuthSources()
        val source = sources.add(managedKey())
        val binding = EngineBinding(EngineBindingId("b1"), TestEngine, source.info.id)
        val hydrated = MutableStateFlow(emptyList<EngineBinding>())
        val store = object : BindingStore {
            override fun observe(): Flow<List<EngineBinding>> = hydrated
            override suspend fun load(): List<EngineBinding> = listOf(binding)
            override suspend fun save(bindings: List<EngineBinding>) = error("unused")
        }
        val factory = FakeEngineFactory().apply {
            runtime = { identity -> usageRuntime(identity, UsageTelemetry()) }
        }
        val registry = EngineRegistry(
            listOf(registration(factory, features = setOf(ReportsProviderUsage.id))),
            EnginePlatform.DesktopWindows,
        )
        val toggles = FakeEngineToggles()
        val engineGate = EngineGate(registry, toggles)
        val bindings = EngineBindingsService(
            engineGate,
            store,
            sources,
            FakeAuthChecks(context.clock),
            { false },
            context,
        )
        val catalog = ProviderUsageCatalogService(
            RouteResolver(registry, engineGate, bindings),
            RuntimePool(context, { _, _ -> false }, { _, _ -> }),
            EnabledEngines(registry, toggles, context.scope),
            sources,
            UsageFlags(),
            context,
        )
        val observed = catalog.observe(TestEngine, binding.id)
        var current = ProviderUsageSnapshot()
        backgroundScope.launch { observed.collect { current = it } }
        runCurrent()
        assertEquals(25.0, catalog.refresh(TestEngine, binding.id).windows.single().usedPercent)
        assertEquals(ProviderUsageSnapshot(), current)

        hydrated.value = listOf(binding)
        runCurrent()

        assertEquals(25.0, current.windows.single().usedPercent)
    }

    @Test
    fun `bindings of one account share requests and native updates`() = runTest {
        val fixture = UsageFixture(this)
        val second = fixture.route.binding.copy(id = EngineBindingId("b2"))
        fixture.route.store.bindings.value += second
        runCurrent()

        fixture.refresh()
        fixture.catalog.refresh(TestEngine, second.id)
        runCurrent()
        val telemetry = fixture.telemetry.single()
        assertEquals(1, telemetry.calls)
        assertEquals(1, telemetry.state.subscriptionCount.value)

        telemetry.state.value = usage(70.0)
        assertEquals(70.0, fixture.observation().windows.single().usedPercent)
        assertEquals(70.0, fixture.refresh().windows.single().usedPercent)
        assertEquals(1, telemetry.calls)
        assertEquals(
            70.0,
            fixture.catalog.observe(TestEngine, second.id).first { it.windows.isNotEmpty() }
                .windows.single().usedPercent,
        )
    }

    @Test
    fun `concurrent refreshes await one profile owned request`() = runTest {
        val fixture = UsageFixture(this)
        fixture.requestGate = CompletableDeferred()
        val first = async { fixture.refresh() }
        val second = async { fixture.refresh() }
        runCurrent()
        assertEquals(1, fixture.telemetry.single().calls)

        fixture.requestGate?.complete(Unit)
        assertEquals(first.await(), second.await())
    }

    @Test
    fun `account revision invalidates cached and in flight observations`() = runTest {
        val fixture = UsageFixture(this)
        fixture.refresh()
        val old = fixture.telemetry.single()
        fixture.route.clock.now += 31.seconds
        old.gate = CompletableDeferred()
        val request = async { fixture.refresh() }
        runCurrent()

        fixture.route.sources.remove(fixture.route.source.info.id)
        fixture.route.sources.add(managedKey(revision = AuthRevision.Known("r2")))
        runCurrent()
        assertFailsWith<CancellationException> { request.await() }
        old.state.value = usage(99.0)
        runCurrent()
        assertEquals(ProviderUsageSnapshot(), fixture.catalog.observe(TestEngine, fixture.route.binding.id).first())
        assertEquals(25.0, fixture.refresh().windows.single().usedPercent)
        assertEquals(2, fixture.route.factory.createdRuntimes.size)
    }

    @Test
    fun `failed refresh preserves the last account observation as stale`() = runTest {
        val fixture = UsageFixture(this)
        fixture.refresh()
        fixture.route.clock.now += 31.seconds
        fixture.telemetry.single().failure = EngineException(
            EngineFailure.Transport(TransportFailureReason.NetworkUnavailable),
        )

        assertFailsWith<EngineException> { fixture.refresh() }
        val stale = fixture.observation()
        assertEquals(25.0, stale.windows.single().usedPercent)
        assertTrue(stale.observation.isStale)
    }

    @Test
    fun `removed bindings and disabled flag stop observation and hide cached limits`() = runTest {
        val fixture = UsageFixture(this)
        fixture.refresh()
        val telemetry = fixture.telemetry.single()
        fixture.flags.enabledState.value = false
        runCurrent()
        assertEquals(0, telemetry.state.subscriptionCount.value)
        telemetry.state.value = usage(99.0)
        assertEquals(ProviderUsageSnapshot(), fixture.catalog.observe(TestEngine, fixture.route.binding.id).first())

        fixture.flags.enabledState.value = true
        runCurrent()
        fixture.refresh()
        fixture.route.bindings.disconnect(fixture.route.binding.id)
        runCurrent()
        assertEquals(0, telemetry.state.subscriptionCount.value)
        assertEquals(ProviderUsageSnapshot(), fixture.catalog.observe(TestEngine, fixture.route.binding.id).first())
    }
}

private class UsageFixture(scope: TestScope) {
    private val factory = FakeEngineFactory()
    val route = RouteFixture(scope, factory, registration(factory, features = setOf(ReportsProviderUsage.id)))
    val flags = UsageFlags()
    val telemetry = mutableListOf<UsageTelemetry>()
    var requestGate: CompletableDeferred<Unit>? = null
    private val enabled = EnabledEngines(route.registry, route.toggles, route.context.scope)
    private val pool = RuntimePool(route.context, { _, _ -> false }, { _, _ -> })
    val catalog = ProviderUsageCatalogService(route.routes, pool, enabled, route.sources, flags, route.context)

    init {
        factory.runtime = { identity ->
            val reporter = UsageTelemetry().also {
                it.gate = requestGate
                telemetry += it
            }
            usageRuntime(identity, reporter)
        }
    }

    suspend fun refresh(): ProviderUsageSnapshot = catalog.refresh(TestEngine, route.binding.id)

    suspend fun observation(): ProviderUsageSnapshot =
        catalog.observe(TestEngine, route.binding.id).first { it.windows.isNotEmpty() }
}

private class UsageTelemetry : ReportsProviderUsage {
    override val state = MutableStateFlow(usage(25.0))
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    var failure: EngineException? = null

    override suspend fun refresh(): ProviderUsageSnapshot {
        calls++
        gate?.await()
        failure?.let { throw it }
        return state.value
    }
}

private class UsageFlags : EngineUsageGate {
    val enabledState = MutableStateFlow(true)

    override fun observe(): Flow<Boolean> = enabledState

    override suspend fun isEnabled(): Boolean = enabledState.value
}

private fun usage(percent: Double) = ProviderUsageSnapshot(
    windows = listOf(ProviderUsageWindow("weekly", "Weekly", percent)),
    observation = Observation(Instant.fromEpochSeconds(1_000), isStale = false),
)

private fun usageRuntime(identity: RuntimeIdentity, reporter: ReportsProviderUsage) = object : EngineRuntime {
    override val identity: RuntimeIdentity = identity
    override val features = FeatureTable(mapOf(ReportsProviderUsage.id to available(reporter)))
    override suspend fun close() = Unit
}
