package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import androidx.room.RoomRawQuery
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchiveFilter
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrder
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SourceDiscovery
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.IndexPosition
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionIndex
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.indexKey
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.sortKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [SessionIndex] in the profile Room database. Rows that cannot be decoded are skipped and logged. */
class RoomSessionIndex(private val database: SessionIndexDatabase) : SessionIndex {
    private val log = Log.tag("RoomSessionIndex")
    private val dao = database.sessions()

    override suspend fun revision(): Long {
        log.d { "read revision" }
        return dao.counter(REVISION) ?: 0L
    }

    override suspend fun advanceRevision(): Long {
        log.d { "advance revision" }
        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val next = (dao.counter(REVISION) ?: 0L) + 1
                dao.setCounter(IndexMetaEntity(REVISION, next))
                next
            }
        }
    }

    override suspend fun page(query: SessionQuery, after: IndexPosition?, limit: Int): List<SessionSummary> {
        log.d { "page after=${after != null} limit=$limit order=${query.order}" }
        return dao.page(pageQuery(query, after, limit)).mapNotNull { it.decode() }
    }

    override suspend fun find(ref: SessionRef): SessionSummary? {
        log.d { "find engine=${ref.engine.value} source=${ref.source.value}" }
        return dao.find(ref.indexKey())?.decode()
    }

    override suspend fun upsertDiscovered(entries: List<SessionSummary>, generation: Long) {
        log.d { "upsert discovered count=${entries.size} generation=$generation" }
        if (entries.isEmpty()) return
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val existing = dao.findAll(entries.map { it.ref.indexKey() }).associateBy { it.refKey }
                dao.upsert(
                    entries.map { entry ->
                        val known = existing[entry.ref.indexKey()]
                        val previous = known?.decode()
                        val isHeartbeat = known?.isHeartbeat == true
                        val merged = entry.copy(
                            title = entry.title ?: previous?.title,
                            workspace = entry.workspace ?: previous?.workspace,
                            origin = when {
                                isHeartbeat -> SessionOrigin.Heartbeat
                                entry.origin == SessionOrigin.Heartbeat -> SessionOrigin.Unknown
                                else -> entry.origin
                            },
                            lastRoute = entry.lastRoute ?: previous?.lastRoute,
                        )
                        merged.toEntity(generation, isHeartbeat)
                    },
                )
            }
        }
    }

    override suspend fun record(entry: SessionSummary) {
        log.d { "record engine=${entry.ref.engine.value} source=${entry.ref.source.value}" }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val known = dao.find(entry.ref.indexKey())
                val isHeartbeat = known?.isHeartbeat == true || entry.origin == SessionOrigin.Heartbeat
                val merged = entry.copy(
                    origin = if (isHeartbeat) SessionOrigin.Heartbeat else entry.origin,
                    title = entry.title ?: known?.title,
                )
                dao.upsert(listOf(merged.toEntity(known?.seenGeneration, isHeartbeat)))
            }
        }
    }

    override suspend fun removeMissing(engine: EngineId, source: SessionSourceId, generation: Long) {
        log.d { "remove missing engine=${engine.value} source=${source.value} generation=$generation" }
        val removed = dao.deleteMissing(engine.value, source.value, generation)
        if (removed > 0) log.i { "removed sessions missing from source=${source.value} count=$removed" }
    }

    override suspend fun coverage(): List<SourceDiscovery> {
        log.d { "read coverage" }
        return dao.coverage().mapNotNull { row ->
            try {
                IndexJson.decodeFromString(SourceDiscovery.serializer(), row.discovery)
            } catch (e: SerializationException) {
                log.w(e) { "undecodable coverage row skipped" }
                null
            }
        }
    }

    override suspend fun saveCoverage(discovery: SourceDiscovery) {
        log.d { "save coverage source=${discovery.source.id.value}" }
        val key = "${discovery.source.engine.value.length}:${discovery.source.engine.value}${discovery.source.id.value}"
        dao.upsertCoverage(CoverageEntity(key, IndexJson.encodeToString(SourceDiscovery.serializer(), discovery)))
    }

    private fun SessionEntity.decode(): SessionSummary? = try {
        IndexJson.decodeFromString(SessionSummary.serializer(), summary)
    } catch (e: SerializationException) {
        log.w(e) { "undecodable session row skipped" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "invalid session row skipped" }
        null
    }

    private companion object {
        const val REVISION = "revision"
    }
}

private val IndexJson = Json { ignoreUnknownKeys = true }

/** [isHeartbeat] is provenance recorded by Heartbeat itself; an adapter's claim never exempts a row from cleanup. */
private fun SessionSummary.toEntity(generation: Long?, isHeartbeat: Boolean) = SessionEntity(
    refKey = ref.indexKey(),
    engine = ref.engine.value,
    source = ref.source.value,
    title = title,
    workspace = workspace?.value,
    isHeartbeat = isHeartbeat,
    isArchived = isArchived,
    sortUpdated = SessionOrder.RecentlyUpdated.sortKey(this),
    sortCreated = SessionOrder.RecentlyCreated.sortKey(this),
    seenGeneration = generation,
    summary = IndexJson.encodeToString(SessionSummary.serializer(), this),
)

/** Builds the keyset query; column names come from a fixed whitelist, every value is a bound argument. */
private fun pageQuery(query: SessionQuery, after: IndexPosition?, limit: Int): RoomRawQuery {
    val sortColumn = when (query.order) {
        SessionOrder.RecentlyUpdated -> "sort_updated"
        SessionOrder.RecentlyCreated -> "sort_created"
    }
    val clauses = mutableListOf<String>()
    val args = mutableListOf<Any>()
    if (query.engines.isNotEmpty()) {
        clauses += "engine IN (${query.engines.joinToString { "?" }})"
        args.addAll(query.engines.map { it.value })
    }
    if (query.sources.isNotEmpty()) {
        clauses += "source IN (${query.sources.joinToString { "?" }})"
        args.addAll(query.sources.map { it.value })
    }
    query.workspace?.let {
        clauses += "workspace = ?"
        args += it.value
    }
    query.search?.takeIf { it.isNotBlank() }?.let {
        clauses += "title LIKE ? ESCAPE '\\'"
        args += "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
    when (query.archive) {
        ArchiveFilter.All -> Unit
        ArchiveFilter.Active -> clauses += "is_archived = 0"
        ArchiveFilter.Archived -> clauses += "is_archived = 1"
    }
    after?.let {
        clauses += "($sortColumn < ? OR ($sortColumn = ? AND ref_key > ?))"
        args.addAll(listOf(it.sortKey, it.sortKey, it.refKey))
    }
    val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
    val sql = "SELECT * FROM sessions $where ORDER BY $sortColumn DESC, ref_key ASC LIMIT ?"
    args += limit.toLong()
    return RoomRawQuery(sql) { statement ->
        args.forEachIndexed { index, value ->
            when (value) {
                is Long -> statement.bindLong(index + 1, value)
                else -> statement.bindText(index + 1, value.toString())
            }
        }
    }
}
