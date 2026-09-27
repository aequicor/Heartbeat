package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchiveFilter
import io.aequicor.heartbeat.feature.aiengine.facade.api.DiscoveryStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SourceDiscovery
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SessionCatalog] over the persistent [SessionIndex]. Pages are read from the index only; native stores are
 * enumerated on [refresh], one source at a time, and a failed source keeps its previous entries.
 * Only engines enabled by toggles take part. [openStored] wraps an adapter session with facade capabilities.
 */
class SessionCatalogService(
    private val registry: EngineRegistry,
    private val enabled: EnabledEngines,
    private val index: SessionIndex,
    private val cursors: SessionCursors,
    private val context: FacadeContext,
    private val openStored: (EngineRegistration, EngineSession) -> EngineSession,
) : SessionCatalog {
    private val log = Log.tag("SessionCatalog")
    private val refreshing = Mutex()

    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage {
        log.d { "page cursor=${request.cursor != null} limit=${request.limit}" }
        val scoped = query.enabledOnly() ?: return SessionPage(emptyList(), null, emptyList())
        // Cursors are bound to the effective query: a toggle change between pages invalidates them.
        val after = request.cursor?.let { decode(it, scoped) }
        val revision = after?.revision ?: index.revision()
        val rows = index.page(scoped, after?.position, request.limit + 1)
        val items = rows.take(request.limit)
        val next = if (rows.size > request.limit) {
            cursors.encode(scoped, revision, query.order.position(items.last()))
        } else {
            null
        }
        return SessionPage(items, next, coverage(scoped, index.coverage()))
    }

    override suspend fun get(ref: SessionRef): EngineSession {
        log.i { "open stored session engine=${ref.engine.value} source=${ref.source.value}" }
        val registration = registry.require(ref.engine)
        if (ref.engine !in enabled.state.value) fail(EngineUnavailable)
        val source = registry.source(ref) ?: fail(EngineFailure.Session(SessionFailureReason.NotFound))
        return openStored(registration, adapterCall("get") { source.get(ref) })
    }

    override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = refreshing.withLock {
        log.i { "refresh sessions" }
        val scoped = query.enabledOnly() ?: return@withLock SessionDiscoveryReport(emptyList())
        val targets = scoped.sources()
        val generation = if (targets.any { it.second.discovery != null }) index.advanceRevision() else index.revision()
        val report = targets.map { (registration, source) ->
            val status = discover(registration, source, scoped, generation)
            SourceDiscovery(source.source, status, Observation(context.clock.now(), isStale = false))
                .also { index.saveCoverage(it) }
        }
        // Pages read while discovery was reordering rows got the intermediate revision; retire it as well.
        if (targets.any { it.second.discovery != null }) index.advanceRevision()
        SessionDiscoveryReport(report)
    }

    /** Records a session created or resumed through Heartbeat, independently of native discovery. */
    suspend fun record(summary: SessionSummary) {
        log.i { "record session engine=${summary.ref.engine.value} origin=${summary.origin}" }
        index.record(summary.copy(observation = Observation(context.clock.now(), isStale = false)))
    }

    /** Indexed metadata of [ref], or null. */
    suspend fun indexed(ref: SessionRef): SessionSummary? = index.find(ref)

    private suspend fun discover(
        registration: EngineRegistration,
        source: EngineSessionSource,
        query: SessionQuery,
        generation: Long,
    ): DiscoveryStatus {
        val discovery = source.discovery ?: return DiscoveryStatus.Unsupported
        val engine = registration.descriptor.id
        val sourceQuery = query.copy(engines = setOf(engine), sources = setOf(source.source.id))
        return try {
            var cursor: SessionCursor? = null
            var pages = 0
            do {
                val page = withContext(context.io) { discovery.page(sourceQuery, PageRequest(cursor, DISCOVERY_PAGE)) }
                val items = page.items.filter { it.ref.engine == engine && it.ref.source == source.source.id }
                if (items.size != page.items.size) log.w { "dropped foreign entries source=${source.source.id.value}" }
                val now = Observation(context.clock.now(), isStale = false)
                index.upsertDiscovered(items.map { it.copy(observation = now) }, generation)
                cursor = page.next
                pages++
            } while (cursor != null && pages < MAX_DISCOVERY_PAGES)
            if (cursor == null && query.isComplete()) index.removeMissing(engine, source.source.id, generation)
            log.i { "discovered source=${source.source.id.value} pages=$pages complete=${cursor == null}" }
            if (cursor == null) DiscoveryStatus.Complete else DiscoveryStatus.InProgress
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "discovery failed source=${source.source.id.value} failure=${e.failure.code}" }
            DiscoveryStatus.Unavailable(e.failure)
        } catch (e: Exception) {
            log.e(e) { "discovery crashed source=${source.source.id.value}" }
            DiscoveryStatus.Unavailable(EngineFailure.Unknown())
        }
    }

    /** Cursor of this profile and query in the current snapshot; a reordered index invalidates it. */
    private suspend fun decode(cursor: SessionCursor, query: SessionQuery): DecodedCursor {
        val decoded = cursors.decode(cursor, query) ?: fail(InvalidRequest)
        if (decoded.revision != index.revision()) {
            log.w { "stale session cursor revision=${decoded.revision}" }
            fail(EngineFailure.Session(SessionFailureReason.Changed))
        }
        return decoded
    }

    private suspend fun <T> adapterCall(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "adapter $operation failed failure=${e.failure.code}" }
        throw e
    } catch (e: Exception) {
        log.e(e) { "adapter $operation crashed" }
        fail(EngineFailure.Unknown())
    }

    /** Narrows the query to enabled engines; null when nothing is left to show. */
    private suspend fun SessionQuery.enabledOnly(): SessionQuery? {
        val on = enabled.current()
        val engines = if (engines.isEmpty()) on else engines.intersect(on)
        return if (engines.isEmpty()) null else copy(engines = engines)
    }

    private fun SessionQuery.sources(): List<Pair<EngineRegistration, EngineSessionSource>> = registry.all
        .asSequence()
        .filter { it.descriptor.id in engines }
        .flatMap { registration -> registration.sessionSources.map { registration to it } }
        .filter { (_, source) -> sources.isEmpty() || source.source.id in sources }
        .toList()

    private fun coverage(query: SessionQuery, stored: List<SourceDiscovery>): List<SourceDiscovery> =
        query.sources().map { (_, source) ->
            when {
                source.discovery == null -> SourceDiscovery(source.source, DiscoveryStatus.Unsupported, Observation())

                else -> stored.firstOrNull { it.source.id == source.source.id }?.copy(source = source.source)
                    ?: SourceDiscovery(source.source, DiscoveryStatus.InProgress, Observation())
            }
        }

    private companion object {
        const val DISCOVERY_PAGE = 200
        const val MAX_DISCOVERY_PAGES = 1_000
    }
}

/** A complete enumeration of a source: no filter could have hidden entries. */
private fun SessionQuery.isComplete(): Boolean = workspace == null && search == null && archive == ArchiveFilter.All
