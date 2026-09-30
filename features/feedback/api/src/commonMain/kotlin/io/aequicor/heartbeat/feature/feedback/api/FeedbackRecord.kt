package io.aequicor.heartbeat.feature.feedback.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Native context preceding an overlay entry; native checkpoints and positions are never synthesized. */
@Serializable
public data class FeedbackAnchor(val session: SessionRef? = null, val after: ItemId? = null, val turn: TurnId? = null)

/** Requested session-local parameter change. Presentation localizes values rather than persisting prose. */
@Serializable
public sealed interface FeedbackChange {
    /** A model route selection, including an engine or binding change that an executor may reject. */
    @Serializable
    public data class Model(val before: EngineTarget, val requested: EngineTarget) : FeedbackChange

    /** A native effort selection; null means the engine default. */
    @Serializable
    public data class Effort(val before: String?, val requested: String?) : FeedbackChange {
        init {
            require(before == null || before.isNotBlank())
            require(requested == null || requested.isNotBlank())
        }
    }

    /** A tool approval policy selection; null identifies an unknown or native default policy. */
    @Serializable
    public data class Trust(val before: TrustLevel?, val requested: TrustLevel?) : FeedbackChange
}

/** Confirmed result, or explicit lack of confirmation, of a configuration operation. */
@Serializable
public sealed interface FeedbackOutcome {
    /** The executor has not acknowledged the requested change yet. */
    @Serializable
    public data object Pending : FeedbackOutcome

    /** Subsequent operations use this authoritative configuration. */
    @Serializable
    public data class Applied(val configuration: SessionConfiguration) : FeedbackOutcome

    /** Safe classification of a failure to confirm or apply the change; no native outcome is inferred. */
    @Serializable
    public data class Failed(val failure: EngineFailure) : FeedbackOutcome

    /** A restart interrupted observation; neither success nor failure can be inferred or replayed. */
    @Serializable
    public data object Unknown : FeedbackOutcome
}

/**
 * Durable feedback belonging to a Heartbeat logical chat [source], independently of its native engine segment.
 * [id] identifies one operation. Higher [revision] updates that operation in its original transcript position;
 * ids and sources contain only safe local identifiers, never native paths, accounts or credentials.
 * [createdAt] is display metadata: publication order, rather than wall-clock time, orders the journal.
 */
@Serializable
public data class FeedbackRecord(
    val id: String,
    val source: String,
    val revision: Long,
    val createdAt: Instant,
    val anchor: FeedbackAnchor = FeedbackAnchor(),
    val change: FeedbackChange,
    val outcome: FeedbackOutcome,
) {
    init {
        require(id.matches(SAFE_ID)) { "Invalid feedback operation id" }
        require(source.matches(SAFE_ID)) { "Invalid feedback source" }
        require(revision >= 0) { "Invalid feedback revision" }
    }

    override fun toString(): String = "FeedbackRecord(id=$id, revision=$revision)"
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
