package io.aequicor.heartbeat.feature.adaptivesubagents.impl

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Toggles answering [values], false for any other flag. */
internal class FakeToggles(private val values: Map<FeatureToggle<*>, Any> = emptyMap()) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Tests register flags only.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle))

    @Suppress("UNCHECKED_CAST") // Tests register flags only.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    @Suppress("UNCHECKED_CAST") // Tests register flags only.
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = (values[toggle] ?: false) as T
}

/** A fixed library state. */
internal class FakeLibrary(state: ModelProfilesState = ModelProfilesState.Ready()) : ModelProfilesView {
    override val state: StateFlow<ModelProfilesState> = MutableStateFlow(state)
}

/** Selected models of one or more connections. */
internal class FakeSelections(vararg targets: EngineTarget) : ModelSelections {
    private val selection = ModelSelection(
        targets.groupBy { it.binding }.map { (binding, all) -> BindingModels(binding, all.map { it.model }.toSet()) },
    )

    override fun observe(): Flow<ModelSelection> = flowOf(selection)
    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection = error("unused")
}

/** One engine per distinct engine id of [catalog], each fully capable unless listed in [limited]. */
internal class FakeCatalogFacade(
    private val catalog: List<ModelInfo>,
    private val limited: Set<EngineId> = emptySet(),
) : EngineFacade {
    override val engines: EngineCatalog = object : EngineCatalog {
        override val state = MutableStateFlow(
            catalog.map { it.target.engine }.distinct().map { engine ->
                val features = if (engine in limited) {
                    setOf(CreatesSessions.id)
                } else {
                    setOf(CreatesSessions.id, ResumesSessions.id, AppliesTrustLevels.id)
                }
                EngineInfo(
                    EngineDescriptor(
                        engine,
                        engine.value,
                        EngineFamily.BuiltIn,
                        emptySet(),
                        io.aequicor.heartbeat.core.featuretoggles.FeatureToggle.Flag("test.${engine.value}", "Test"),
                        declaredFeatures = features,
                        isLocalWorkspaceSupported = true,
                    ),
                    EngineAvailability.Available,
                    emptyList(),
                )
            },
        )

        override suspend fun refresh(engine: EngineId): EngineInfo = error("unused")
        override fun features(engine: EngineId): EngineFeatures = NoFeatures
    }

    override val bindings: EngineBindings = object : EngineBindings {
        override val state = MutableStateFlow(
            catalog.map { it.target }.distinctBy { it.binding }
                .map { EngineBinding(it.binding, it.engine, AuthSourceId("auth-${it.binding.value}")) },
        )

        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int) = error("unused")
        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean) = error("unused")
        override suspend fun disconnect(binding: EngineBindingId) = error("unused")
        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = error("unused")
    }

    override val models: ModelCatalog = object : ModelCatalog {
        override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot> =
            MutableStateFlow(ModelCatalogSnapshot(catalog.filter { it.target.binding == binding }, Observation()))

        override suspend fun refresh(engine: EngineId, binding: EngineBindingId) = error("unused")
    }

    override val sessions: SessionCatalog get() = error("unused")
    override val providerUsage: ProviderUsageCatalog get() = error("unused")
}

/** A model route of tests. */
internal fun target(engine: String, binding: String, model: String) =
    EngineTarget(EngineId(engine), EngineBindingId(binding), ModelId(model))

private object NoFeatures : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = FeatureAccess.Unsupported
}
