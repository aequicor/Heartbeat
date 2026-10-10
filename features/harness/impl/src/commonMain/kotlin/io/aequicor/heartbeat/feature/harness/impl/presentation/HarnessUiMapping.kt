package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import kotlinx.serialization.json.Json

internal fun HarnessState.phase(): PhaseUi = when (this) {
    is HarnessState.Idle, is HarnessState.Loading -> PhaseUi.Loading
    is HarnessState.Failed -> PhaseUi.Failed
    is HarnessState.Ready -> PhaseUi.Ready
}

internal fun HarnessApproval.toUi(): ApprovalUi = when (this) {
    HarnessApproval.Ask -> ApprovalUi.Ask
    HarnessApproval.ByTrust -> ApprovalUi.ByTrust
    HarnessApproval.AcceptAll -> ApprovalUi.AcceptAll
}

internal fun ApprovalUi.toDomain(): HarnessApproval = when (this) {
    ApprovalUi.Ask -> HarnessApproval.Ask
    ApprovalUi.ByTrust -> HarnessApproval.ByTrust
    ApprovalUi.AcceptAll -> HarnessApproval.AcceptAll
}

internal fun HarnessScope.toUi(): ScopeUi = when (this) {
    HarnessScope.Attached -> ScopeUi.Attached
    HarnessScope.Profile -> ScopeUi.Profile
    is HarnessScope.Projects -> ScopeUi.Projects
}

internal fun HarnessEntry.toRow(): HarnessRowUi = HarnessRowUi(
    id = harness.id.value,
    name = harness.name.value,
    title = harness.title,
    scope = harness.scope.toUi(),
    projectCount = (harness.scope as? HarnessScope.Projects)?.projects?.size ?: 0,
    itemCount = harness.items.size,
    isEnabled = harness.isEnabled,
    hasFailures = itemStatus.values.any { it is ItemStatus.Failed },
)

internal fun HarnessItem.kindUi(): ItemKindUi = when (this) {
    is HarnessItem.Instruction -> ItemKindUi.Instruction
    is HarnessItem.Skill -> ItemKindUi.Skill
    is HarnessItem.Template -> ItemKindUi.Template
    is HarnessItem.Script -> ItemKindUi.Script
    is HarnessItem.Workflow -> ItemKindUi.Workflow
}

internal val HarnessItem.isCode: Boolean get() = this is HarnessItem.Script || this is HarnessItem.Workflow

/** Description where the kind has one; instructions are short enough to show their text. */
internal fun HarnessItem.summary(): String = when (this) {
    is HarnessItem.Instruction -> text.lineSequence().first()
    is HarnessItem.Skill -> description
    is HarnessItem.Template -> description
    is HarnessItem.Script -> description
    is HarnessItem.Workflow -> description
}

internal fun HarnessEntry.itemRows(isRuntimeAvailable: Boolean): List<ItemRowUi> =
    harness.items.sortedWith(compareBy({ it.kindUi() }, { it.name.value })).map { item ->
        ItemRowUi(
            id = item.id.value,
            name = item.name.value,
            kind = item.kindUi(),
            description = item.summary(),
            isEnabled = item.isEnabled,
            status = item.status(itemStatus[item.id], harness, isRuntimeAvailable),
        )
    }

private fun HarnessItem.status(status: ItemStatus?, harness: Harness, isRuntimeAvailable: Boolean): ItemStatusUi =
    when {
        !isCode -> ItemStatusUi.None
        !isRuntimeAvailable || status == ItemStatus.Unsupported -> ItemStatusUi.Unsupported
        !isEnabled || !harness.isEnabled || status == ItemStatus.Disabled -> ItemStatusUi.Disabled
        status is ItemStatus.Active -> ItemStatusUi.Active
        status is ItemStatus.Failed -> if (status.isDisabled) ItemStatusUi.FailedDisabled else ItemStatusUi.Failed
        else -> ItemStatusUi.Activating
    }

/** Stable key of a session reference for UI lists and intents; decoded only from keys the store produced. */
internal fun SessionRef.key(): String = Json.encodeToString(this)

internal fun SessionRef.label(): String = "${engine.value} · ${nativeId.takeLast(SESSION_SUFFIX)}"

internal fun WorkflowRun.toUi(workflowName: String): RunUi = RunUi(
    id = id.value,
    workflow = workflowName,
    status = when (val current = status) {
        WorkflowStatus.Running -> if (cancellation != null) RunStatusUi.Cancelling else RunStatusUi.Running

        is WorkflowStatus.Completed -> RunStatusUi.Completed

        is WorkflowStatus.Failed ->
            if (current.reason == WorkflowFailure.Cancelled) RunStatusUi.Cancelled else RunStatusUi.Failed
    },
    steps = steps.count { it.phase == StepPhase.Completed },
    awaitingPermissions = awaiting.values.sumOf { it.size },
)

private const val SESSION_SUFFIX = 8
