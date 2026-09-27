package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/**
 * Replayable journal event. Checkpoints advance monotonically within one session history generation.
 * Delivery may repeat events after reconnect; apply item revisions idempotently. On Invalidated, reload pages.
 */
@Serializable
public sealed interface SessionEvent {
    /** Boundary after applying this event, or the replacement generation for invalidation. */
    public val checkpoint: HistoryCheckpoint

    /** Insert or replace the item only if this revision is newer. */
    @Serializable
    public data class ItemUpserted(override val checkpoint: HistoryCheckpoint, val item: SessionItem) : SessionEvent

    /** Tombstone revision prevents delayed upserts from resurrecting a deleted item. */
    @Serializable
    public data class ItemRemoved(override val checkpoint: HistoryCheckpoint, val item: ItemId, val revision: Long) :
        SessionEvent {
        init {
            require(revision >= 0)
        }
    }

    /** A request has been accepted for execution. */
    @Serializable
    public data class TurnStarted(override val checkpoint: HistoryCheckpoint, val turn: Turn) : SessionEvent

    /** Exactly one terminal outcome is recorded per accepted turn. */
    @Serializable
    public data class TurnFinished(
        override val checkpoint: HistoryCheckpoint,
        val turn: TurnId,
        val outcome: TurnOutcome,
    ) : SessionEvent

    /** Informational replay; active pending requests are read from ActiveSession.state. */
    @Serializable
    public data class PermissionRequested(override val checkpoint: HistoryCheckpoint, val request: PermissionRequest) :
        SessionEvent

    /** Reconciliation cannot continue from the old checkpoint; the consumer must reload. */
    @Serializable
    public data class HistoryInvalidated(
        override val checkpoint: HistoryCheckpoint,
        val reason: HistoryFailureReason,
    ) : SessionEvent
}
