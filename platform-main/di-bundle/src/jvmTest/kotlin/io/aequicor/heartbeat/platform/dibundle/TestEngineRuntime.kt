package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.NewAuthSource
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stands for the facade runtime that the bundle will contribute to ProfileScope. Its presence must replace the
 * connection screens' optional-dependency stand-ins; it offers an empty catalog.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
class TestEngineFacade : EngineFacade {
    override val engines: EngineCatalog = object : EngineCatalog {
        override val state: StateFlow<List<EngineInfo>> = MutableStateFlow(emptyList())

        override suspend fun refresh(engine: EngineId): EngineInfo = error("unused")

        override fun features(engine: EngineId): EngineFeatures = error("unused")
    }
    override val bindings: EngineBindings get() = error("unused")
    override val models: ModelCatalog get() = error("unused")
    override val sessions: SessionCatalog get() = error("unused")
}

/** Stands for the profile source registry. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
class TestAuthSources : AuthSources {
    override val state: StateFlow<List<AuthSource>> = MutableStateFlow(emptyList())

    override suspend fun create(request: NewAuthSource): AuthSource = error("unused")

    override suspend fun forget(source: AuthSourceId): Unit = error("unused")
}

/** Profile accessor of the services used by the connection screens. */
@ContributesTo(ProfileScope::class)
interface EngineServicesAccessor {
    val engineServices: EngineServices
}
