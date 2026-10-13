package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismPermissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionObservationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionUi
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Trial
import io.aequicor.heartbeat.feature.organicai.api.zygote
import kotlinx.collections.immutable.toImmutableList

internal fun SessionObservationSnapshot?.toObservationUi(key: String): SubSessionObservationUi =
    SubSessionObservationUi(
        key,
        when (val current = this?.state) {
            null -> null

            is ActiveSessionState.Submitting, is ActiveSessionState.Running,
            is ActiveSessionState.AwaitingUserAction, is ActiveSessionState.Interrupting,
            -> true

            is ActiveSessionState.Unavailable -> current.activeTurn != null

            is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> false
        },
        this?.context?.toUi(),
    )

internal fun Organism.toUi(): OrganismUi = OrganismUi(
    modelId = target?.studioModelId(),
    approval = when (trust) {
        TrustLevel.Ask -> ApprovalUi.Ask
        TrustLevel.AutoEdits -> ApprovalUi.AutoEdits
        TrustLevel.Full -> ApprovalUi.AutoApprove
        null -> null
    },
    status = when {
        status is OrganismStatus.Completed -> OrganismStatusUi.Completed

        status == OrganismStatus.Aborted -> OrganismStatusUi.Aborted

        zygote.phase is CellPhase.Stalled -> OrganismStatusUi.Stalled

        zygote.phase == CellPhase.Resting && cells.any { it.phase == CellPhase.Resting && !it.isAwaitingResults } ->
            OrganismStatusUi.Stalled

        else -> OrganismStatusUi.Developing
    },
    subSessions = (
        cells.map { cell -> cell.toSubSession().copy(depth = depthOf(cell)) } +
            trials.map { it.toSubSession().copy(depth = 1) }
    ).toImmutableList(),
    permissions = cells.flatMap { cell ->
        (cell.phase as? CellPhase.Working)?.awaiting.orEmpty().map { it.toUi(cell) }
    }.toImmutableList(),
)

private fun Organism.depthOf(cell: Cell): Int {
    var parent = cell.parent
    var depth = 0
    val visited = mutableSetOf(cell.id)
    while (parent != null && visited.add(parent)) {
        depth++
        parent = cells.firstOrNull { it.id == parent }?.parent
    }
    return depth
}

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
