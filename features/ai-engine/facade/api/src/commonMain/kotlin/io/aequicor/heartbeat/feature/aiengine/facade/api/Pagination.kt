package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Opaque catalog cursor tied to profile, query and index snapshot. Consumers never parse or persist it. */
@Serializable
public data class SessionCursor(val value: String) {
    init {
        require(value.isNotBlank())
    }
    override fun toString(): String = "SessionCursor(***)"
}

/** Opaque page cursor tied to a session and history generation. Consumers never parse or persist it. */
@Serializable
public data class HistoryCursor(val value: String) {
    init {
        require(value.isNotBlank())
    }
    override fun toString(): String = "HistoryCursor(***)"
}

/**
 * Replay boundary returned with a page; invalidated when its retained journal expires. Consumers never
 * parse or persist it.
 */
@Serializable
public data class HistoryCheckpoint(val value: String) {
    init {
        require(value.isNotBlank())
    }
    override fun toString(): String = "HistoryCheckpoint(***)"
}

/** Bounded page request. Null cursor selects the first page of a new snapshot. */
@Serializable
public data class PageRequest(val cursor: SessionCursor? = null, val limit: Int = 50) {
    init {
        require(limit in 1..MAX_PAGE_SIZE)
    }
}

/** First history load starts at the latest window; subsequent cursors encode their direction. */
@Serializable
public data class HistoryPageRequest(val cursor: HistoryCursor? = null, val limit: Int = 50) {
    init {
        require(limit in 1..MAX_PAGE_SIZE)
    }
}

/** Discovery state for one configured source. Failure is not an empty successful result. */
@Serializable
public sealed interface DiscoveryStatus {
    /** Source enumeration completed for the report's query. */
    @Serializable
    public data object Complete : DiscoveryStatus

    /** Not all native pages have been discovered yet. */
    @Serializable
    public data object InProgress : DiscoveryStatus

    /** Adapter cannot enumerate native sessions; known Heartbeat entries may still be shown. */
    @Serializable
    public data object Unsupported : DiscoveryStatus

    /** Last successful entries can remain visible with this failure. */
    @Serializable
    public data class Unavailable(val failure: EngineFailure) : DiscoveryStatus
}

/** Per-source coverage, including sources which produced no entries. */
@Serializable
public data class SourceDiscovery(val source: SessionSource, val status: DiscoveryStatus, val observation: Observation)

/** Explicit refresh result. Auth discovery and helper execution are not implied by session discovery. */
@Serializable
public data class SessionDiscoveryReport(val sources: List<SourceDiscovery>)

/**
 * Page from one catalog snapshot. Null next means the end of this indexed snapshot, not complete discovery.
 * Entries are deduplicated by SessionRef. Consumers inspect sources to determine discovery completeness.
 */
@Serializable
public data class SessionPage(
    val items: List<SessionSummary>,
    val next: SessionCursor?,
    val sources: List<SourceDiscovery>,
)

/** Completeness of the native transcript independently from page boundaries. */
@Serializable
public enum class HistoryCoverage {
    /** The available pages represent the whole native transcript, including all observed item identities. */
    Complete,

    /**
     * Some native content is unavailable. Consumers may retain their earlier saved transcript before this
     * window. Adapters must not reseed earlier content under new item IDs: partial replay must preserve the
     * identity of previously observed items, or omit that replay and expose only newly observed content.
     */
    Partial,

    /** The adapter cannot establish whether native content is missing. */
    Unknown,
}

/**
 * Chronological page. Checkpoint and items are an atomic view; watch(checkpoint) replays later changes.
 * Null older/newer indicates the edge of available history; coverage states whether native data is missing.
 */
@Serializable
public data class HistoryPage(
    val items: List<SessionItem>,
    val older: HistoryCursor?,
    val newer: HistoryCursor?,
    val checkpoint: HistoryCheckpoint,
    val coverage: HistoryCoverage,
) {
    init {
        require(items.map { it.info.id }.distinct().size == items.size) { "Duplicate history items" }
        require(items.zipWithNext().all { (a, b) -> a.info.position < b.info.position }) { "Unordered history page" }
    }
}

private const val MAX_PAGE_SIZE = 500
