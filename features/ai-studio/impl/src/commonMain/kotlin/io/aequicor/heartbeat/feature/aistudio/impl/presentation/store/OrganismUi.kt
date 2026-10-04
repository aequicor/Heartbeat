package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Trial
import io.aequicor.heartbeat.feature.organicai.api.zygote
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** The sub-session an organism chat shows until another is chosen: its zygote. */
internal val PrimarySubSession: String = CellId.ZYGOTE.value

/** An organism chat: the organism's state, every session that grew from it and the decisions it awaits. */
@Immutable
data class OrganismUi(
    val status: OrganismStatusUi,
    val subSessions: ImmutableList<SubSessionUi>,
    val permissions: ImmutableList<OrganismPermissionUi> = persistentListOf(),
)

/** Where the organism is in its life; a stalled zygote waits for an explicit resume. */
enum class OrganismStatusUi { Developing, Stalled, Completed, Aborted }

/** What a sub-session is: the zygote, a divided cell or a judge of a complaint or a dispute. */
enum class SubSessionKindUi { Zygote, Cell, Complaint, Dispute }

/** State of a sub-session; judges end with their ruling. */
enum class SubSessionStateUi {
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
@Immutable
data class SubSessionUi(
    val key: String,
    val kind: SubSessionKindUi,
    val name: String,
    val state: SubSessionStateUi,
    val subject: String? = null,
    val isViewable: Boolean = false,
)

/** A permission request of a cell's turn, answered by the user through the organism. */
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
enum class OrganismActionUi { Abort, Resume }

internal fun Organism.toUi(): OrganismUi = OrganismUi(
    status = when {
        status is OrganismStatus.Completed -> OrganismStatusUi.Completed
        status == OrganismStatus.Aborted -> OrganismStatusUi.Aborted
        zygote.phase is CellPhase.Stalled -> OrganismStatusUi.Stalled
        else -> OrganismStatusUi.Developing
    },
    subSessions = (cells.map { it.toSubSession() } + trials.map { it.toSubSession() }).toImmutableList(),
    permissions = cells.flatMap { cell ->
        (cell.phase as? CellPhase.Working)?.awaiting.orEmpty().map { it.toUi(cell) }
    }.toImmutableList(),
)

private fun Cell.toSubSession() = SubSessionUi(
    key = id.value,
    kind = if (parent == null) SubSessionKindUi.Zygote else SubSessionKindUi.Cell,
    name = name,
    state = when (val phase = phase) {
        is CellPhase.Working -> when {
            session == null -> SubSessionStateUi.Germinating
            phase.awaiting.isNotEmpty() -> SubSessionStateUi.AwaitingUser
            else -> SubSessionStateUi.Working
        }

        CellPhase.Resting -> SubSessionStateUi.Resting

        is CellPhase.Stalled -> SubSessionStateUi.Stalled

        is CellPhase.Completed -> SubSessionStateUi.Completed

        is CellPhase.Dead -> if (phase.cause is DeathCause.Lysed) SubSessionStateUi.Killed else SubSessionStateUi.Died
    },
    isViewable = session != null,
)

private fun Trial.toSubSession() = SubSessionUi(
    key = case.id.value,
    kind = if (case is ImmuneCase.Complaint) SubSessionKindUi.Complaint else SubSessionKindUi.Dispute,
    name = case.id.value,
    state = when (ruling) {
        null -> SubSessionStateUi.Judging
        is Ruling.Kill -> SubSessionStateUi.Sentenced
        is Ruling.Spare -> SubSessionStateUi.Spared
        is Ruling.Answer -> SubSessionStateUi.Answered
        is Ruling.None -> SubSessionStateUi.Undecided
    },
    subject = (case as? ImmuneCase.Complaint)?.accused?.value,
    isViewable = judge != null,
)

private fun PermissionRequest.toUi(cell: Cell) = OrganismPermissionUi(
    cell = cell.id.value,
    cellName = cell.name,
    turn = turn.value,
    requestId = id.value,
    title = title,
    options = options.filter { input == null || it.isSkip }
        .map { PermissionOptionUi(it.id.value, it.title) }
        .toImmutableList(),
    description = description,
)
