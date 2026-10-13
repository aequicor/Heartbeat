@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionOptionUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** The sub-session an organism chat shows until another is chosen: its zygote (`CellId.ZYGOTE`). */
const val PRIMARY_SUB_SESSION: String = "zygote"

/** An organism chat: the organism's state, every session that grew from it and the decisions it awaits. */
@Serializable
@SerialName("OrganismUi")
@Immutable
data class OrganismUi(
    val status: OrganismStatusUi,
    val subSessions: ImmutableList<SubSessionUi>,
    val permissions: ImmutableList<OrganismPermissionUi> = persistentListOf(),
    /** The organism's fixed execution route and trust, independent of new-chat defaults. */
    val modelId: String? = null,
    val approval: ApprovalUi? = null,
)

/** The selected session's live execution and context; [key] prevents stale data after selection changes. */
@Serializable
@SerialName("SubSessionObservationUi")
@Immutable
data class SubSessionObservationUi(val key: String, val isRunning: Boolean?, val context: ContextUsageUi?)

/** Where the organism is in its life; a stalled zygote waits for an explicit resume. */
@Serializable
@SerialName("OrganismStatusUi")
enum class OrganismStatusUi { Developing, Stalled, Completed, Aborted }

/** What a sub-session is: the zygote, a divided cell or a judge of a complaint or a dispute. */
@Serializable
@SerialName("SubSessionKindUi")
enum class SubSessionKindUi { Zygote, Cell, Complaint, Dispute, Root, Agent }

/** State of a sub-session; judges end with their ruling. */
@Serializable
@SerialName("SubSessionStateUi")
enum class SubSessionStateUi {
    Unknown,
    Queued,
    Cancelled,
    Failed,
    Germinating,
    Working,
    AwaitingUser,
    Resting,
    Stalled,
    Completed,
    Died,
    Killed,
    Judging,
    Sentenced,
    Spared,
    Answered,
    Undecided,
}

/**
 * One session of an organism. [key] is the cell id or the case id; [name] is the cell's name; [subject] is the
 * accused cell of a complaint. Only a sub-session with a native session can be shown ([isViewable]).
 */
@Serializable
@SerialName("SubSessionUi")
@Immutable
data class SubSessionUi(
    val key: String,
    val kind: SubSessionKindUi,
    val name: String,
    val state: SubSessionStateUi,
    val subject: String? = null,
    val isViewable: Boolean = false,
    val depth: Int = 0,
)

/** A permission request of a cell's turn, answered by the user through the organism. */
@Serializable
@SerialName("OrganismPermissionUi")
@Immutable
data class OrganismPermissionUi(
    val cell: String,
    val cellName: String,
    val turn: String,
    val requestId: String,
    val title: String,
    val options: ImmutableList<PermissionOptionUi>,
    val description: String? = null,
)

/** What a pane does with an organism. */
@Serializable
@SerialName("OrganismActionUi")
enum class OrganismActionUi { Abort, Resume }

/** Cells and judges of the organism still at work; none once it ended. */
fun OrganismUi.activeDescendants(): Int = if (
    status == OrganismStatusUi.Aborted || status == OrganismStatusUi.Completed
) {
    0
} else {
    subSessions.distinctBy { it.key }.count {
        it.kind != SubSessionKindUi.Zygote && it.state in setOf(
            SubSessionStateUi.Germinating,
            SubSessionStateUi.Working,
            SubSessionStateUi.AwaitingUser,
            SubSessionStateUi.Judging,
        )
    }
}
