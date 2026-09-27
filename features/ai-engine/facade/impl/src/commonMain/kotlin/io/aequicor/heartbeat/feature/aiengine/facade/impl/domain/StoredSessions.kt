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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter session seen through the facade. Metadata and history capabilities are the adapter's; resumption is
 * always the facade's, because it must pass route checks and attach through the profile runtime.
 */
class StoredSession(private val stored: EngineSession, resume: () -> FeatureAccess<EngineFeature>) : EngineSession {
    override val summary: StateFlow<SessionSummary> get() = stored.summary

    override val features: EngineFeatures = FeatureTable(mapOf(ResumesSessions.id to resume), stored.features)
}

/** Engine-wide native discovery backed by the unified catalog, restricted to one engine. */
class EngineSessionListing(private val engine: EngineId, private val catalog: SessionCatalog) : ListsSessions {
    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage =
        catalog.page(query.copy(engines = setOf(engine)), request)
}
