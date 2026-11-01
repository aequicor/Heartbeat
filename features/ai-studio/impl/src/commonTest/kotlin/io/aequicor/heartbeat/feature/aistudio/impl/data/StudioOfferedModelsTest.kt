package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
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
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioOfferedModelsTest {
    @Test
    fun `catalog loss hides a route without forgetting the user's selection`() = runTest {
        val facade = OfferedFacade()
        val chosen = OfferedSelections()
        val offered = facade.observeStudioModels(chosen, OfferedSources())

        assertEquals(1, offered.first().size)
        facade.catalog.value = ModelCatalogSnapshot(emptyList(), Observation())
        assertEquals(emptyList(), offered.first())
        assertEquals(setOf(OfferedTarget.model), chosen.value.value.enabled(OfferedTarget.binding))

        facade.catalog.value = ModelCatalogSnapshot(
            listOf(ModelInfo(OfferedTarget, "Restored")),
            Observation(),
        )
        assertEquals("Restored", offered.first().single().shortName)
        facade.bindingState.value = listOf(OfferedBinding.copy(isEnabled = false))
        assertEquals(emptyList(), offered.first())
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)
    }

    @Test
    fun `a matching model id on another credential route is never offered`() = runTest {
        val facade = OfferedFacade()
        val chosen = OfferedSelections()
        val other = OfferedTarget.copy(binding = EngineBindingId("other"))
        facade.catalog.value = ModelCatalogSnapshot(listOf(ModelInfo(other, "Other account")), Observation())

        assertEquals(emptyList(), facade.observeStudioModels(chosen, OfferedSources()).first())
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)
    }

    @Test
    fun `unknown startup availability keeps restored route offered without discovery`() = runTest {
        val facade = OfferedFacade(EngineAvailability.Unknown)
        val chosen = OfferedSelections()

        val offered = facade.observeStudioModels(chosen, OfferedSources()).first()

        assertEquals("Model", offered.single().shortName)
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)
    }

    @Test
    fun `confirmed engine failures hide and restore route without changing selection`() = runTest {
        val facade = OfferedFacade()
        val chosen = OfferedSelections()
        val offered = facade.observeStudioModels(chosen, OfferedSources())

        facade.setAvailability(EngineAvailability.UnsupportedPlatform)
        assertEquals(emptyList(), offered.first())
        facade.setAvailability(
            EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.Unavailable)),
        )
        assertEquals(emptyList(), offered.first())
        facade.setAvailability(EngineAvailability.Available)

        assertEquals("Model", offered.first().single().shortName)
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)
    }

    @Test
    fun `missing auth source hides and restores exact route without changing selection`() = runTest {
        val facade = OfferedFacade()
        val chosen = OfferedSelections()
        val sources = OfferedSources()
        val offered = facade.observeStudioModels(chosen, sources)

        sources.state.value = emptyList()
        assertEquals(emptyList(), offered.first())
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)

        sources.state.value = listOf(OfferedSource)
        assertEquals("Engine · Model · Source", offered.first().single().name)
        assertEquals(OfferedTarget, chosen.value.value.defaultTarget)
    }
}

private val OfferedTarget = EngineTarget(EngineId("offered"), EngineBindingId("binding"), ModelId("model"))
private val OfferedBinding = EngineBinding(OfferedTarget.binding, OfferedTarget.engine, AuthSourceId("source"))
private val OfferedSource = AuthSource.NoAuth(
    AuthSourceInfo(OfferedBinding.authSource, "Source", AuthRevision.Known("revision")),
    AuthScope(ProviderId("test"), EndpointOrigin("http://localhost")),
)

private class OfferedSelections : ModelSelections {
    val value = MutableStateFlow(
        ModelSelection(
            listOf(BindingModels(OfferedTarget.binding, setOf(OfferedTarget.model))),
            OfferedTarget,
        ),
    )

    override fun observe() = value

    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection =
        change(value.value).also { value.value = it }
}

private class OfferedFacade(availability: EngineAvailability = EngineAvailability.Available) : EngineFacade {
    val catalog = MutableStateFlow(
        ModelCatalogSnapshot(listOf(ModelInfo(OfferedTarget, "Model")), Observation()),
    )
    val engineState = MutableStateFlow(listOf(offeredEngine(availability)))
    val bindingState = MutableStateFlow(listOf(OfferedBinding))

    override val models = object : ModelCatalog {
        override fun observe(engine: EngineId, binding: EngineBindingId) = catalog

        override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ModelCatalogSnapshot =
            error("No discovery")
    }
    override val engines = object : EngineCatalog {
        override val state = engineState
        override suspend fun refresh(engine: EngineId): EngineInfo = error("No installation probe")
        override fun features(engine: EngineId): EngineFeatures = error("No features")
    }
    override val bindings = object : EngineBindings {
        override val state = bindingState

        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): EngineBinding =
            error("Read only")

        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean): Unit = error("Read only")
        override suspend fun disconnect(binding: EngineBindingId): Unit = error("Read only")
        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?) = error("No authentication probe")
    }
    override val sessions: SessionCatalog get() = error("No sessions")
    override val providerUsage: ProviderUsageCatalog get() = error("No usage probe")

    fun setAvailability(availability: EngineAvailability) {
        engineState.value = engineState.value.map { it.copy(availability = availability) }
    }
}

private fun offeredEngine(availability: EngineAvailability) = EngineInfo(
    EngineDescriptor(
        OfferedTarget.engine,
        "Engine",
        EngineFamily.Vendor,
        emptySet(),
        FeatureToggle.Flag("test.offered", "Test"),
    ),
    availability,
    listOf(OfferedBinding),
)

private class OfferedSources(initial: List<AuthSource> = listOf(OfferedSource)) : AuthSources {
    override val state = MutableStateFlow(initial)

    override suspend fun get(id: AuthSourceId): AuthSource? = error("No source IO")

    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): AuthSource.ManagedKey =
        error("Read only")

    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): AuthSource.ManagedKey = error("Read only")

    override suspend fun register(draft: AuthSourceDraft): AuthSource = error("Read only")

    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): AuthSource = error("Read only")

    override suspend fun forget(id: AuthSourceId): Unit = error("Read only")
}
