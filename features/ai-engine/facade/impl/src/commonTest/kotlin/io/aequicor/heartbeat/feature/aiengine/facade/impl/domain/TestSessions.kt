package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTimes
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

internal val LocalSource = SessionSourceId("local")

internal fun sessionRef(nativeId: String, engine: EngineId = TestEngine, source: SessionSourceId = LocalSource) =
    SessionRef(engine, source, nativeId)

internal fun summary(
    nativeId: String,
    updatedAt: Long? = null,
    title: String? = null,
    isArchived: Boolean = false,
    origin: SessionOrigin = SessionOrigin.External,
) = SessionSummary(
    sessionRef(nativeId),
    title = title,
    origin = origin,
    times = SessionTimes(updatedAt = updatedAt?.let { Instant.fromEpochSeconds(it) }),
    isArchived = isArchived,
)

/** Native listing that serves [items] in pages of [pageSize], ignoring the requested limit like a CLI would. */
internal class FakeListing(var items: List<SessionSummary> = emptyList(), private val pageSize: Int = 2) :
    ListsSessions {
    var failure: Exception? = null
    val queries = mutableListOf<SessionQuery>()

    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage {
        failure?.let { throw it }
        queries += query
        val offset = request.cursor?.value?.toInt() ?: 0
        val next = (offset + pageSize).takeIf { it < items.size }?.let { SessionCursor(it.toString()) }
        return SessionPage(items.drop(offset).take(pageSize), next, emptyList())
    }
}

internal class FakeStoredSession(summary: SessionSummary, override val features: EngineFeatures = NoEngineFeatures) :
    EngineSession {
    override val summary = MutableStateFlow(summary)
}

internal class FakeSessionSource(
    id: SessionSourceId = LocalSource,
    engine: EngineId = TestEngine,
    override var discovery: ListsSessions? = FakeListing(),
) : EngineSessionSource {
    override val source = SessionSource(id, engine, "Local ${id.value}")
    val stored = mutableMapOf<SessionRef, EngineSession>()

    override suspend fun get(ref: SessionRef): EngineSession =
        stored[ref] ?: throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
}
