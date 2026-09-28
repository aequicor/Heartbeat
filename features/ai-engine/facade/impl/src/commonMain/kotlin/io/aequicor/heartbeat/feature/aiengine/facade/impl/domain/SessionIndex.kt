package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrder
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SourceDiscovery

/** Keyset position in one ordering: newest first, [refKey] ascending as the tie-breaker. */
data class IndexPosition(val sortKey: Long, val refKey: String)

/**
 * Persistent profile index of sessions from every source. The [revision] advances whenever discovery may have
 * reordered entries, which invalidates cursors of older snapshots. Failures propagate.
 */
interface SessionIndex {
    /** Current snapshot revision. */
    suspend fun revision(): Long

    /** Starts a new snapshot and returns its revision. */
    suspend fun advanceRevision(): Long

    /** Up to [limit] entries matching [query] strictly after [after], in the query's order. */
    suspend fun page(query: SessionQuery, after: IndexPosition?, limit: Int): List<SessionSummary>

    /** The indexed entry of [ref], or null. */
    suspend fun find(ref: SessionRef): SessionSummary?

    /**
     * Stores discovered entries stamped with [generation]. Heartbeat provenance and a known last route of existing
     * entries are kept: discovery cannot prove them.
     */
    suspend fun upsertDiscovered(entries: List<SessionSummary>, generation: Long)

    /** Stores an entry known to Heartbeat directly (created or resumed here). */
    suspend fun record(entry: SessionSummary)

    /** Removes externally discovered entries of [source] that a complete enumeration at [generation] missed. */
    suspend fun removeMissing(engine: EngineId, source: SessionSourceId, generation: Long)

    /** Last recorded coverage per source. */
    suspend fun coverage(): List<SourceDiscovery>

    /** Records the coverage of one source. */
    suspend fun saveCoverage(discovery: SourceDiscovery)
}

/** Decoded cursor. */
data class DecodedCursor(val revision: Long, val position: IndexPosition)

/** Opaque cursor encoding bound to the profile and query. */
interface SessionCursors {
    /** Cursor after [position] in the snapshot [revision] of [query]. */
    fun encode(query: SessionQuery, revision: Long, position: IndexPosition): SessionCursor

    /** Position of [cursor], or null when it is malformed or belongs to another profile or query. */
    fun decode(cursor: SessionCursor, query: SessionQuery): DecodedCursor?
}

/** Injective index key of a native reference. */
fun SessionRef.indexKey(): String =
    "${engine.value.length}:${engine.value}${source.value.length}:${source.value}$nativeId"

/** Sort key of [summary] in [order]; unknown times sort last and are never fabricated. */
fun SessionOrder.sortKey(summary: SessionSummary): Long = when (this) {
    SessionOrder.RecentlyUpdated -> summary.times.updatedAt
    SessionOrder.RecentlyCreated -> summary.times.createdAt
}?.toEpochMilliseconds() ?: UNKNOWN_TIME

/** Keyset position of [summary] in [order]. */
fun SessionOrder.position(summary: SessionSummary): IndexPosition = IndexPosition(
    sortKey(summary),
    summary.ref.indexKey(),
)

private const val UNKNOWN_TIME = Long.MIN_VALUE
