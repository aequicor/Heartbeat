package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Heartbeat-owned identity of a logical conversation. Never contains native paths, accounts or credentials. */
@Serializable
public data class ConversationId(val value: String) {
    init {
        require(value.matches(SAFE_ID) && !value.startsWith("__")) { "Invalid ConversationId" }
    }
}

/** Caller-generated correlation of one transfer attempt. Never contains native paths, accounts or credentials. */
@Serializable
public data class TransferId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid TransferId" }
    }
}

/**
 * Delivery of the handoff prompt into the target session. Pending is persisted before submission: found outside
 * a running transfer it means the process died mid-send and must be treated as [Unknown]. Neither Pending nor
 * Unknown permits an automatic resend, since request ids do not imply native deduplication.
 */
@Serializable
public enum class HandoffStatus { Pending, Accepted, Unknown }

/** Seeding of a segment with the transcript of the previous one; the source native session is never modified. */
@Serializable
public data class Handoff(val from: SessionRef, val request: RequestId, val status: HandoffStatus)

/**
 * One native session of a logical conversation. The first segment has no [target] and [handoff]: it is the session
 * the conversation started from, with a credential route known only to its engine history.
 */
@Serializable
public data class ConversationSegment(
    val ref: SessionRef,
    val startedAt: Instant,
    val target: EngineTarget? = null,
    val workspace: WorkspaceRef? = null,
    val handoff: Handoff? = null,
) {
    init {
        require((target == null) == (handoff == null)) { "Only transferred segments carry a target and a handoff" }
        require(target == null || target.engine == ref.engine) { "Segment target belongs to another engine" }
    }
}

/**
 * Ordered chain of native segments forming one Heartbeat conversation. Native references are never rewritten:
 * a transfer only appends. The last segment is the one new turns go to.
 */
@Serializable
public data class LogicalConversation(val id: ConversationId, val segments: List<ConversationSegment>) {
    init {
        require(segments.isNotEmpty()) { "Empty conversation" }
        require(segments.map { it.ref }.distinct().size == segments.size) { "Duplicate conversation segment" }
    }

    /** Segment receiving new turns. */
    public val current: ConversationSegment get() = segments.last()

    /** Appends a new tail segment. */
    public operator fun plus(segment: ConversationSegment): LogicalConversation = copy(segments = segments + segment)
}

/**
 * Explicit transfer of [source] into a new session through [target]. A null [conversation] starts a new logical
 * conversation with [source] as its first segment; otherwise [source] must be its current segment.
 * No fallback to another binding or model is ever made.
 */
@Serializable
public data class TransferRequest(
    val transfer: TransferId,
    val source: SessionRef,
    val target: EngineTarget,
    val conversation: ConversationId? = null,
    val workspace: WorkspaceRef? = null,
)

/** Why a transfer did not produce a new segment. */
@Serializable
public sealed interface TransferFailure {
    /** The AI engines or session transfer toggle is off. */
    @Serializable
    public data object Disabled : TransferFailure

    /** The referenced logical conversation does not exist in this profile. */
    @Serializable
    public data object ConversationNotFound : TransferFailure

    /** The source is not the current segment; only the tail of a conversation can be transferred. */
    @Serializable
    public data object NotLatestSegment : TransferFailure

    /** Engine, history or delivery failure reported by the facade. */
    @Serializable
    public data class Engine(val failure: EngineFailure) : TransferFailure

    /** Unclassified failure; details stay in sanitized logs. */
    @Serializable
    public data object Unknown : TransferFailure
}

/** Terminal outcome of one transfer, retained by the machine for late subscribers. */
@Serializable
public sealed interface TransferResult {
    /** Correlation of the finished transfer. */
    public val transfer: TransferId

    /** The segment was appended; [segment] handoff status tells whether the transcript reached the engine. */
    @Serializable
    public data class Completed(
        override val transfer: TransferId,
        val conversation: ConversationId,
        val segment: ConversationSegment,
    ) : TransferResult {
        init {
            require(segment.handoff != null && segment.handoff.status != HandoffStatus.Pending) {
                "A completed transfer has a settled handoff"
            }
        }
    }

    /**
     * No segment was appended. A target session created before a rejected handoff may remain in the catalog;
     * if the journal could not be rolled back, its Pending segment remains and reads as an unknown delivery.
     */
    @Serializable
    public data class Failed(override val transfer: TransferId, val failure: TransferFailure) : TransferResult

    /** Cancelled before a target session was created. */
    @Serializable
    public data class Cancelled(override val transfer: TransferId) : TransferResult
}

/** Transfer of AI sessions between engines. Requires [io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines]. */
public val SessionEngineTransfer: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.session_transfer",
    "Перенос сессии ИИ на другой движок",
    default = false,
)

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
