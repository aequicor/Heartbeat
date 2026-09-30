package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineStudioUsageTest {
    @Test
    fun `selected account quotas appear before an active session exists`() = runTest {
        val fixture = UsageFixture(this)
        fixture.providers.snapshot.value = quota(42.0)

        fixture.usage.observe(setOf(fixture.modelId))
        runCurrent()

        assertEquals(42.0, fixture.usage.state.value.providers[fixture.modelId]?.windows?.single()?.usedPercent)
        assertEquals(listOf(UsageEngine to UsageBinding.id), fixture.providers.refreshes)
        assertTrue(fixture.usage.state.value.contexts.isEmpty())
    }

    @Test
    fun `models sharing an account observe the same provider updates and removed targets disappear`() = runTest {
        val fixture = UsageFixture(this)
        val second = fixture.target.copy(model = ModelId("second")).encode()
        fixture.usage.observe(setOf(fixture.modelId, second))
        runCurrent()

        fixture.providers.snapshot.value = quota(75.0)
        runCurrent()
        assertEquals(setOf(fixture.modelId, second), fixture.usage.state.value.providers.keys)
        assertTrue(fixture.usage.state.value.providers.values.all { it == quota(75.0) })

        fixture.usage.observe(setOf(second))
        runCurrent()
        assertEquals(setOf(second), fixture.usage.state.value.providers.keys)
    }

    @Test
    fun `detaching the screen stops provider observation and later account refreshes`() = runTest {
        val fixture = UsageFixture(this)
        fixture.usage.observe(setOf(fixture.modelId))
        runCurrent()
        assertEquals(1, fixture.providers.snapshot.subscriptionCount.value)

        fixture.usage.observe(emptySet())
        runCurrent()
        assertEquals(0, fixture.providers.snapshot.subscriptionCount.value)
        assertTrue(fixture.usage.state.value.providers.isEmpty())

        fixture.sources.state.value = listOf(
            UsageSource.copy(info = UsageSource.info.copy(revision = AuthRevision.Known("r2"))),
        )
        runCurrent()
        assertEquals(1, fixture.providers.refreshes.size)
        assertTrue(fixture.usage.state.value.providers.isEmpty())
    }

    @Test
    fun `the selected route refreshes quotas after an account revision changes`() = runTest {
        val fixture = UsageFixture(this)
        fixture.usage.observe(setOf(fixture.modelId))
        runCurrent()
        assertEquals(1, fixture.providers.refreshes.size)

        fixture.sources.state.value = listOf(
            UsageSource.copy(info = UsageSource.info.copy(revision = AuthRevision.Known("r2"))),
        )
        runCurrent()

        assertEquals(2, fixture.providers.refreshes.size)
    }

    @Test
    fun `disabled usage hides both metrics and performs no refresh until enabled`() = runTest {
        val fixture = UsageFixture(this)
        fixture.flags.isEnabled.value = false
        val active = fixture.session(20)
        fixture.usage.observe(setOf(fixture.modelId))
        fixture.usage.attach("chat", active)
        fixture.usage.refresh(fixture.modelId)
        runCurrent()
        assertEquals(StudioUsageState(), fixture.usage.state.value)
        assertTrue(fixture.providers.refreshes.isEmpty())

        fixture.flags.isEnabled.value = true
        runCurrent()
        assertEquals(20, fixture.usage.state.value.contexts["chat"]?.usedTokens?.toInt())
        assertEquals(1, fixture.providers.refreshes.size)

        fixture.flags.isEnabled.value = false
        runCurrent()
        assertEquals(StudioUsageState(), fixture.usage.state.value)
    }

    @Test
    fun `native context updates are isolated by binding engine and source revision`() = runTest {
        val fixture = UsageFixture(this)
        val active = fixture.session(10)
        fixture.usage.attach("chat", active)
        runCurrent()
        active.contextState.value = ContextUsage(80, 100)
        runCurrent()
        assertEquals(80L, fixture.usage.state.value.contexts["chat"]?.usedTokens)

        fixture.bindings.state.value = listOf(UsageBinding.copy(isEnabled = false))
        runCurrent()
        assertNull(fixture.usage.state.value.contexts["chat"])
        fixture.bindings.state.value = listOf(UsageBinding)
        runCurrent()
        assertEquals(80L, fixture.usage.state.value.contexts["chat"]?.usedTokens)

        fixture.engines.state.value = emptyList()
        runCurrent()
        assertNull(fixture.usage.state.value.contexts["chat"])
        fixture.engines.state.value = listOf(usageEngine())
        fixture.sources.state.value = listOf(
            UsageSource.copy(info = UsageSource.info.copy(revision = AuthRevision.Known("r2"))),
        )
        runCurrent()
        active.contextState.value = ContextUsage(99, 100)
        runCurrent()
        assertNull(fixture.usage.state.value.contexts["chat"])
    }

    @Test
    fun `replaced and closed handles cannot overwrite or leave behind context`() = runTest {
        val fixture = UsageFixture(this)
        val old = fixture.session(25)
        val replacement = fixture.session(60)
        fixture.usage.attach("chat", old)
        runCurrent()
        fixture.usage.attach("chat", replacement)
        old.contextState.value = ContextUsage(99, 100)
        old.state.value = ActiveSessionState.Closed
        runCurrent()
        assertEquals(60L, fixture.usage.state.value.contexts["chat"]?.usedTokens)
        assertEquals(0, old.contextState.subscriptionCount.value)

        replacement.state.value = ActiveSessionState.Closed
        runCurrent()
        assertNull(fixture.usage.state.value.contexts["chat"])
        assertEquals(0, replacement.contextState.subscriptionCount.value)
    }

    @Test
    fun `temporarily unavailable context capability recovers with its handle`() = runTest {
        val fixture = UsageFixture(this)
        val active = fixture.session(40)
        active.state.value = ActiveSessionState.Unavailable(UsageUnavailable)
        fixture.usage.attach("chat", active)
        runCurrent()
        assertNull(fixture.usage.state.value.contexts["chat"])

        active.state.value = ActiveSessionState.Ready()
        runCurrent()
        assertEquals(40L, fixture.usage.state.value.contexts["chat"]?.usedTokens)
    }

    @Test
    fun `attaching the same active handle twice does not duplicate observation`() = runTest {
        val fixture = UsageFixture(this)
        val active = fixture.session(40)
        fixture.usage.attach("chat", active)
        fixture.usage.attach("chat", active)
        runCurrent()
        assertEquals(1, active.contextState.subscriptionCount.value)
    }
}

private class UsageFixture(scope: TestScope) {
    val target = EngineTarget(UsageEngine, UsageBinding.id, ModelId("model"))
    val modelId = target.encode()
    val flags = UsageFlags()
    val sources = UsageSources()
    val bindings = UsageBindings()
    val engines = UsageEngines()
    val providers = UsageProviders()
    private val facade = object : EngineFacade {
        override val engines: EngineCatalog = this@UsageFixture.engines
        override val bindings: EngineBindings = this@UsageFixture.bindings
        override val providerUsage: ProviderUsageCatalog = providers
        override val models: ModelCatalog get() = error("Usage must not discover models")
        override val sessions: SessionCatalog get() = error("Usage must not create or resume sessions")
    }
    val usage = EngineStudioUsage(facade, flags, sources, UsageProfile(scope.backgroundScope))

    fun session(used: Long): UsageSession = UsageSession(used)
}

private class UsageSession(used: Long) : ActiveSession {
    val contextState = MutableStateFlow<ContextUsage?>(ContextUsage(used, 100))
    private val context = object : SessionContextUsage {
        override val state = contextState
    }
    override val route = ExecutionRoute(UsageEngine, UsageBinding.id, UsageSource.info.id, UsageSource.info.revision)
    override val ref = SessionRef(UsageEngine, SessionSourceId("history"), "native")
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    override val features = object : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
            if (state.value is ActiveSessionState.Unavailable) return FeatureAccess.Unavailable(UsageUnavailable)
            return key.type.safeCast(context)?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
        }
    }

    override suspend fun close() {
        state.value = ActiveSessionState.Closed
    }
}

private class UsageProviders : ProviderUsageCatalog {
    val snapshot = MutableStateFlow(ProviderUsageSnapshot())
    val refreshes = mutableListOf<Pair<EngineId, EngineBindingId>>()
    override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ProviderUsageSnapshot> = snapshot
    override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ProviderUsageSnapshot {
        refreshes += engine to binding
        return snapshot.value
    }
}

private class UsageFlags : FeatureToggles {
    val isEnabled = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // The helper observes only the boolean usage flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = isEnabled.map { it as T }

    @Suppress("UNCHECKED_CAST") // The helper observes only the boolean usage flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled.value as T
}

private class UsageSources : AuthSources {
    override val state = MutableStateFlow<List<AuthSource>>(listOf(UsageSource))
    override suspend fun get(id: AuthSourceId): AuthSource? = state.value.firstOrNull { it.info.id == id }
    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret) = error("unused")
    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret) = error("unused")
    override suspend fun register(draft: AuthSourceDraft) = error("unused")
    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision) = error("unused")
    override suspend fun forget(id: AuthSourceId) = error("unused")
}

private class UsageBindings : EngineBindings {
    override val state = MutableStateFlow(listOf(UsageBinding))
    override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int) = error("unused")
    override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean) = error("unused")
    override suspend fun disconnect(binding: EngineBindingId) = error("unused")
    override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = error("unused")
}

private class UsageEngines : EngineCatalog {
    override val state = MutableStateFlow(listOf(usageEngine()))
    override suspend fun refresh(engine: EngineId) = error("unused")
    override fun features(engine: EngineId): EngineFeatures = error("Usage must not create sessions")
}

private class UsageProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test-profile"
    override val savedState: ScopeSavedState get() = error("unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

private val UsageEngine = EngineId("test")
private val UsageSource = AuthSource.NoAuth(
    AuthSourceInfo(AuthSourceId("source"), "Source", AuthRevision.Known("r1")),
    AuthScope(ProviderId("local"), EndpointOrigin("http://localhost")),
)
private val UsageBinding = EngineBinding(EngineBindingId("binding"), UsageEngine, UsageSource.info.id)
private val UsageUnavailable = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)

private fun usageEngine() = EngineInfo(
    EngineDescriptor(
        UsageEngine,
        "Test",
        EngineFamily.Vendor,
        setOf(EnginePlatform.DesktopMacOs),
        FeatureToggle.Flag("test.engine", "Test"),
    ),
    EngineAvailability.Available,
    listOf(UsageBinding),
)

private fun EngineTarget.encode(): String = Json.encodeToString(EngineTarget.serializer(), this)

private fun quota(percent: Double) = ProviderUsageSnapshot(
    windows = listOf(ProviderUsageWindow("weekly", "Weekly", percent)),
)
