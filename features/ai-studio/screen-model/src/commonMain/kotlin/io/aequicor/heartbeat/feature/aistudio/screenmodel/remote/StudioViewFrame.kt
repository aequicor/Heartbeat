package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One update of the remote studio view (app → browser side panel). The first frame of a view, and the first after
 * a resync, is a [Snapshot]; later frames are [Delta]s against the revision [Delta.base]. [ack] is the sequence
 * number of the last panel intent the app's store has applied, so the panel can drop its local predictions.
 *
 * The screen state travels as a shell (every property except the message feeds, as JSON) plus feeds: chat
 * transcripts under `t:<session>` and organism sub-session transcripts under `s:<sub-session>`.
 */
@Serializable
sealed interface StudioViewFrame {
    val rev: Long
    val ack: Long

    /** The whole screen. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        override val rev: Long,
        override val ack: Long,
        val shell: JsonObject,
        val feeds: Map<String, List<MessageUi>>,
    ) : StudioViewFrame

    /** What changed since revision [base]: shell properties replaced as a whole and feed patches. */
    @Serializable
    @SerialName("delta")
    data class Delta(
        override val rev: Long,
        override val ack: Long,
        val base: Long,
        val shell: Map<String, JsonElement> = emptyMap(),
        val feeds: Map<String, FeedPatch> = emptyMap(),
    ) : StudioViewFrame
}

/** Changes of one message feed. */
@Serializable
sealed interface FeedPatch {
    /** Messages replaced or added by id, ids removed and, when the order changed, the full new order of ids. */
    @Serializable
    @SerialName("edit")
    data class Edit(
        val upserts: List<MessageUi> = emptyList(),
        val removed: List<String> = emptyList(),
        val order: List<String>? = null,
    ) : FeedPatch

    /** A feed that appeared, with all its messages. */
    @Serializable
    @SerialName("reset")
    data class Reset(val messages: List<MessageUi>) : FeedPatch

    /** The feed is gone. */
    @Serializable
    @SerialName("gone")
    data object Gone : FeedPatch
}
