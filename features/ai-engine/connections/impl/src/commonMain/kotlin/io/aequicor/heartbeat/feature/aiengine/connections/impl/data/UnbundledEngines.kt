package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.NewAuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in used while the application bundle provides no engine runtime: an empty catalog without bindings.
 * Every write fails with a domain error instead of pretending to succeed. Holds no mutable state.
 */
internal class UnbundledEngineFacade : EngineFacade {
    override val engines: EngineCatalog = object : EngineCatalog {
        override val state: StateFlow<List<EngineInfo>> = MutableStateFlow(emptyList<EngineInfo>()).asStateFlow()

        override suspend fun refresh(engine: EngineId): EngineInfo = unavailable()

        override fun features(engine: EngineId): EngineFeatures = NoFeatures
    }

    override val bindings: EngineBindings = object : EngineBindings {
        override val state: StateFlow<List<EngineBinding>> = MutableStateFlow(emptyList<EngineBinding>()).asStateFlow()

        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): EngineBinding =
            unavailable()

        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean): Unit = unavailable()

        override suspend fun disconnect(binding: EngineBindingId): Unit = unavailable()

        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = unavailable()
    }

    override val models: ModelCatalog = object : ModelCatalog {
        override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot> =
            MutableStateFlow(ModelCatalogSnapshot(emptyList(), Observation())).asStateFlow()

        override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ModelCatalogSnapshot = unavailable()
    }

    override val sessions: SessionCatalog = object : SessionCatalog {
        override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = unavailable()

        override suspend fun get(ref: SessionRef): EngineSession = unavailable()

        override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = unavailable()
    }

    private object NoFeatures : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = FeatureAccess.Unsupported
    }
}

/** Registry stand-in matching [UnbundledEngineFacade]: no sources, and creation fails with a domain error. */
internal class UnbundledAuthSources : AuthSources {
    override val state: StateFlow<List<AuthSource>> = MutableStateFlow(emptyList<AuthSource>()).asStateFlow()

    override suspend fun create(request: NewAuthSource): AuthSource = unavailable()

    override suspend fun forget(source: AuthSourceId): Unit = unavailable()
}

private fun unavailable(): Nothing = throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
