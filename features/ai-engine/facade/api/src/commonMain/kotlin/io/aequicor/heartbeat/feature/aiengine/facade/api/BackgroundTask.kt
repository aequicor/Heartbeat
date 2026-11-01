package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** One durable background execution attempt; a polling call never allocates another identity. */
@Serializable
public data class BackgroundTaskRef(val id: String, val attempt: RequestId) {
    init {
        require(id.isNotBlank())
    }

    override fun toString(): String = "BackgroundTaskRef"
}

/** Execution state reported by its owner, separate from the tool's short launch acknowledgement. */
@Serializable
public enum class BackgroundTaskPhase {
    Queued,
    Running,
    Waiting,
    Stopping,
    StopUnconfirmed,
    Succeeded,
    Failed,
    Cancelled,
    Unknown,
    ;

    /** Only an authoritative outcome is terminal; lost observation and stop acknowledgement are not. */
    public val isTerminal: Boolean get() = this == Succeeded || this == Failed || this == Cancelled
}

/** The owner's declared execution lifetime; finishing a model response does not override this contract. */
@Serializable
public enum class BackgroundTaskLifetime { Turn, Session, OwnerWork }

/** Missing output is distinct from a complete empty stream. */
@Serializable
public enum class BackgroundOutputCoverage { Complete, Partial, Unavailable, Unsupported }

/** Delivery mode of output to observers; it does not promise automatic delivery into the model context. */
@Serializable
public enum class BackgroundOutputMode { Snapshot, Stream }

/** Available ordinary tool operations; declarations never replace authorization or termination proof. */
@Serializable
public enum class BackgroundTaskAction { ReadOutput, Input, Stop }

/**
 * An owner-assembled output snapshot. [startOffset] counts omitted UTF-16 code units from the original output;
 * partial coverage remains explicit even when the available suffix is empty. No output content is logged.
 */
@Serializable
public data class BackgroundTaskOutput(
    val text: String = "",
    val startOffset: Long = 0,
    val coverage: BackgroundOutputCoverage = BackgroundOutputCoverage.Complete,
    val mode: BackgroundOutputMode = BackgroundOutputMode.Snapshot,
) {
    init {
        require(startOffset >= 0)
        require(startOffset == 0L || coverage == BackgroundOutputCoverage.Partial)
        require(
            text.isEmpty() || coverage == BackgroundOutputCoverage.Complete ||
                coverage == BackgroundOutputCoverage.Partial,
        )
    }

    override fun toString(): String = "BackgroundTaskOutput(coverage=$coverage, mode=$mode)"
}

/**
 * Normalized observation of the task represented by a tool call. The adapter or hosted owner assembles output
 * and publishes a newer [SessionItem.ToolCall] revision; launch receipts do not alter [phase]. [revision] orders
 * task observations independently of the transcript item's revision. The execution owner retains cancellation,
 * recovery and capacity; this value only projects its confirmed facts.
 */
@Serializable
public data class BackgroundTaskSnapshot(
    val ref: BackgroundTaskRef,
    val phase: BackgroundTaskPhase,
    val lifetime: BackgroundTaskLifetime,
    val revision: Long = 0,
    val output: BackgroundTaskOutput = BackgroundTaskOutput(coverage = BackgroundOutputCoverage.Unavailable),
    val actions: Set<BackgroundTaskAction> = emptySet(),
) {
    init {
        require(revision >= 0)
    }

    /**
     * Accepts a newer observation of this exact attempt. Replayed output cannot duplicate the stream and late
     * progress cannot revive a terminal execution. A newer terminal observation may enrich its output without
     * replacing its confirmed outcome. A new attempt belongs to a different snapshot and must be selected by ID.
     */
    public fun observed(next: BackgroundTaskSnapshot): BackgroundTaskSnapshot = when {
        next.ref != ref || next.revision <= revision -> this
        phase.isTerminal && next.phase != phase -> this
        isStopping && next.phase in LIVE_PHASES -> next.copy(phase = phase)
        else -> next
    }

    private val isStopping: Boolean get() = phase == BackgroundTaskPhase.Stopping ||
        phase == BackgroundTaskPhase.StopUnconfirmed

    override fun toString(): String = "BackgroundTaskSnapshot(phase=$phase, revision=$revision)"
}

private val LIVE_PHASES = setOf(
    BackgroundTaskPhase.Queued,
    BackgroundTaskPhase.Running,
    BackgroundTaskPhase.Waiting,
    BackgroundTaskPhase.Unknown,
)
