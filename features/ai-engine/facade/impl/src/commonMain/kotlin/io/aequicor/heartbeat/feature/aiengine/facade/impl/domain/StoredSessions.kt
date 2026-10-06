package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter session seen through the facade. History borrows an open handle's stream while available. Closing its
 * owner ends the subscription; resolving again falls back to stored history or borrows a replacement handle.
 * Readers never own or close that handle. Resumption always passes
 * route checks and attaches through the profile runtime. Read-only observation follows the profile registry;
 * other capabilities remain the stored adapter's.
 */
class StoredSession(
    private val stored: EngineSession,
    history: () -> FeatureAccess<SessionHistory>? = { null },
    observation: () -> FeatureAccess<SessionObservation> = { FeatureAccess.Unsupported },
    resume: () -> FeatureAccess<EngineFeature>,
) : EngineSession {
    override val summary: StateFlow<SessionSummary> get() = stored.summary

    override val features: EngineFeatures = FeatureTable(
        mapOf(
            ResumesSessions.id to resume,
            SessionObservation.id to observation,
            SessionHistory.id to { history() ?: stored.features.resolve(SessionHistory) },
        ),
        stored.features,
    )
}

/** Engine-wide native discovery backed by the unified catalog, restricted to one engine. */
class EngineSessionListing(private val engine: EngineId, private val catalog: SessionCatalog) : ListsSessions {
    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage =
        catalog.page(query.copy(engines = setOf(engine)), request)
}
