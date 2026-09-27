package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Capability set resolving exactly the registered features; everything else is Unsupported. */
internal class FakeFeatures(private val features: Map<EngineFeatureKey<*>, FeatureAccess<*>>) : EngineFeatures {
    @Suppress("UNCHECKED_CAST") // test fake: keys and accesses are registered in matching pairs
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        features[key] as FeatureAccess<F>? ?: FeatureAccess.Unsupported
}

/** Facade exposing only the catalog capabilities and stored sessions used by transfers. */
internal class FakeEngineFacade(
    engineFeatures: Map<EngineId, EngineFeatures> = emptyMap(),
    storedSessions: Map<SessionRef, EngineFeatures> = emptyMap(),
) : EngineFacade {
    override val engines: EngineCatalog = object : EngineCatalog {
        override val state: StateFlow<List<EngineInfo>> = MutableStateFlow(emptyList())

        override suspend fun refresh(engine: EngineId): EngineInfo = error("unused")

        override fun features(engine: EngineId): EngineFeatures = engineFeatures.getValue(engine)
    }
    override val bindings: EngineBindings = object : EngineBindings {
        override val state: StateFlow<List<EngineBinding>> = MutableStateFlow(emptyList())

        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int) = error("unused")

        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean) = error("unused")

        override suspend fun disconnect(binding: EngineBindingId) = error("unused")

        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = error("unused")
    }
    override val models: ModelCatalog get() = error("unused")
    override val sessions: SessionCatalog = object : SessionCatalog {
        override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = error("unused")

        override suspend fun get(ref: SessionRef): EngineSession = object : EngineSession {
            override val summary: StateFlow<SessionSummary> = MutableStateFlow(SessionSummary(ref))
            override val features: EngineFeatures = storedSessions.getValue(ref)
        }

        override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = error("unused")
    }
}

/** Active handle recording its release. */
internal class FakeActiveSession(override val ref: SessionRef, override val features: EngineFeatures) : ActiveSession {
    var closes = 0

    override val route: ExecutionRoute get() = error("unused")
    override val state: StateFlow<ActiveSessionState> = MutableStateFlow(ActiveSessionState.Ready())

    override suspend fun close() {
        closes++
    }
}
