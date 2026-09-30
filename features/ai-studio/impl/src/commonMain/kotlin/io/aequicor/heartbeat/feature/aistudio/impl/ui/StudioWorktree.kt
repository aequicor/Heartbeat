package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeActionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_action_working
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_cancel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_cancelled
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_completed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_output
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_position
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_running
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_waiting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_create_pr
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_action_delivery
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_action_unverified
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_changed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_moved
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_checkout_dirty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_checkout_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_merge_in_progress
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_native_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_pr_base
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_loading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_retry
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_leave
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_merge
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_merge_target
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_open_pr
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_preparing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_recheck
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_recovery
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_refine
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_result
import org.jetbrains.compose.resources.stringResource

/** Result decisions and build progress stay attached to the same saved checkout. */
@Composable
internal fun StudioWorktree(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier, gap = HbTheme.spacing.s) {
        WorktreeJournalNotice(content, onIntent)
        if (content.pane.isCreating && content.pane.isWorktree) {
            HbLoadingState(stringResource(Res.string.worktree_preparing))
        }
        WorktreeDetails(content, onIntent)
    }
}

@Composable
private fun WorktreeDetails(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val task = content.worktree
    val id = content.session?.id
    if (task == null || id == null) return
    val isOperational = content.worktreeJournal == WorktreeJournalUi.Ready
    val hasNotice = (
        isOperational && task.phase in setOf(
            WorktreePhaseUi.AwaitingDecision,
            WorktreePhaseUi.ActionWorking,
            WorktreePhaseUi.RecoveryRequired,
            WorktreePhaseUi.Failed,
        )
    ) || task.pullRequestUrl != null
    val builds = task.builds.takeLast(3)
    if (!hasNotice && builds.isEmpty()) return
    HbCard(Modifier.testTag("worktree-result-$id"), contentPadding = HbTheme.spacing.m) {
        HbColumn(
            Modifier.fillMaxWidth().heightIn(max = HbTheme.dimensions.toolPayloadMaxHeight)
                .hbVerticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
            gap = HbTheme.spacing.s,
        ) {
            if (isOperational) ResultStatus(id, task, onIntent)
            val links = LocalUriHandler.current
            task.pullRequestUrl?.let { url ->
                HbButton(
                    stringResource(Res.string.worktree_open_pr),
                    onClick = { links.openUri(url) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
            builds.forEach { BuildStatus(id, it, isOperational, onIntent) }
        }
    }
}

@Composable
private fun ResultStatus(id: String, task: WorktreeUi, onIntent: (AiStudioScreenIntent) -> Unit) {
    when (task.phase) {
        WorktreePhaseUi.AwaitingDecision -> {
            HbText(stringResource(Res.string.worktree_result), style = HbTheme.typography.title)
            task.summary?.let { HbText(it) }
            task.sourceBranch?.let { branch ->
                HbText(
                    stringResource(Res.string.worktree_merge_target, branch),
                    modifier = Modifier.testTag("worktree-merge-target"),
                    style = HbTheme.typography.caption,
                )
            }
            HbFlowRow(gap = HbTheme.spacing.s) {
                Decision(id, WorktreeActionUi.CreatePr, stringResource(Res.string.worktree_create_pr), onIntent)
                task.sourceBranch?.let { branch ->
                    Decision(id, WorktreeActionUi.Merge, stringResource(Res.string.worktree_merge, branch), onIntent)
                }
                Decision(id, WorktreeActionUi.Refine, stringResource(Res.string.worktree_refine), onIntent)
                Decision(id, WorktreeActionUi.Leave, stringResource(Res.string.worktree_leave), onIntent)
            }
        }

        WorktreePhaseUi.ActionWorking -> HbText(stringResource(Res.string.worktree_action_working))

        WorktreePhaseUi.RecoveryRequired, WorktreePhaseUi.Failed -> {
            HbText(
                stringResource(
                    if (task.phase == WorktreePhaseUi.Failed) {
                        Res.string.worktree_failed
                    } else {
                        Res.string.worktree_recovery
                    },
                ),
            )
            task.failure?.let { HbText(worktreeFailureLabel(it), style = HbTheme.typography.caption) }
            HbButton(
                stringResource(Res.string.worktree_recheck),
                onClick = { onIntent(AiStudioScreenIntent.RecheckWorktree(id)) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }

        WorktreePhaseUi.Preparing, WorktreePhaseUi.Idle, WorktreePhaseUi.Working,
        WorktreePhaseUi.CompletionSignaled, WorktreePhaseUi.Retained,
        -> Unit
    }
}

@Composable
private fun worktreeFailureLabel(failure: String): String = stringResource(
    when (failure) {
        "OriginalCheckoutDirty" -> Res.string.worktree_failure_checkout_dirty
        "OriginalBranchChanged" -> Res.string.worktree_failure_branch_changed
        "OriginalBranchMoved" -> Res.string.worktree_failure_branch_moved
        "OriginalBranchUnavailable" -> Res.string.worktree_failure_branch_unavailable
        "WorktreeUnavailable", "ProjectUnavailable" -> Res.string.worktree_failure_checkout_unavailable
        "PullRequestBaseUnavailable" -> Res.string.worktree_failure_pr_base
        "NativeOutcomeUnknown", "InterruptedBeforeAcceptance" -> Res.string.worktree_failure_native_unknown
        "ActionDeliveryFailed" -> Res.string.worktree_failure_action_delivery
        "MergeInProgress", "MergeLeaseReleasePending" -> Res.string.worktree_failure_merge_in_progress
        "ActionResultUnverified", "ActionUnverified" -> Res.string.worktree_failure_action_unverified
        else -> Res.string.worktree_failure_unknown
    },
)

@Composable
private fun WorktreeJournalNotice(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    if (content.session?.isWorktree != true && !content.pane.isWorktree && content.worktree == null) return
    when (content.worktreeJournal) {
        WorktreeJournalUi.Error -> HbBanner(
            stringResource(Res.string.worktree_journal_failed),
            Modifier.testTag("worktree-journal-error"),
        ) {
            HbButton(
                stringResource(Res.string.worktree_journal_retry),
                onClick = { onIntent(AiStudioScreenIntent.RetryWorktreeJournal) },
                modifier = Modifier.testTag("worktree-journal-retry"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }

        WorktreeJournalUi.Loading -> HbLoadingState(
            stringResource(Res.string.worktree_journal_loading),
            Modifier.testTag("worktree-journal-loading"),
        )

        WorktreeJournalUi.Ready -> Unit
    }
}

@Composable
private fun BuildStatus(id: String, build: BuildUi, isOperational: Boolean, onIntent: (AiStudioScreenIntent) -> Unit) {
    HbColumn(gap = HbTheme.spacing.xs) {
        HbText("${build.command}: ${buildLabel(build.phase)}", style = HbTheme.typography.caption)
        build.queuePosition?.let { position ->
            HbText(stringResource(Res.string.worktree_build_position, position), style = HbTheme.typography.caption)
        }
        build.failure?.let { HbText(it, style = HbTheme.typography.caption) }
        if (build.phase in setOf(BuildPhaseUi.Failed, BuildPhaseUi.Unknown) && build.output.isNotBlank()) {
            HbText(stringResource(Res.string.worktree_build_output), style = HbTheme.typography.caption)
            HbText(
                build.output.takeLast(BUILD_OUTPUT_CHARS).lines().takeLast(BUILD_OUTPUT_LINES).joinToString("\n"),
                modifier = Modifier.testTag("build-output-${build.id}"),
                style = HbTheme.typography.code,
            )
        }
        if (isOperational && build.phase in setOf(
                BuildPhaseUi.Queued,
                BuildPhaseUi.WaitingForResource,
                BuildPhaseUi.Running,
            )
        ) {
            HbButton(
                stringResource(Res.string.worktree_build_cancel),
                onClick = {
                    onIntent(AiStudioScreenIntent.CancelWorktreeBuild(id, build.id))
                },
                modifier = Modifier.testTag("cancel-build-${build.id}"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun Decision(id: String, action: WorktreeActionUi, label: String, onIntent: (AiStudioScreenIntent) -> Unit) {
    HbButton(
        label,
        onClick = { onIntent(AiStudioScreenIntent.DecideWorktree(id, action)) },
        modifier = Modifier.testTag("worktree-action-${action.name}"),
        style = when (action) {
            WorktreeActionUi.CreatePr -> HbButtonStyle.Primary
            WorktreeActionUi.Leave -> HbButtonStyle.Ghost
            WorktreeActionUi.Merge, WorktreeActionUi.Refine -> HbButtonStyle.Secondary
        },
        size = HbButtonSize.Small,
    )
}

@Composable
private fun buildLabel(phase: BuildPhaseUi): String = stringResource(
    when (phase) {
        BuildPhaseUi.Queued -> Res.string.worktree_build_queued
        BuildPhaseUi.WaitingForResource -> Res.string.worktree_build_waiting
        BuildPhaseUi.Running -> Res.string.worktree_build_running
        BuildPhaseUi.Completed -> Res.string.worktree_build_completed
        BuildPhaseUi.Cancelled -> Res.string.worktree_build_cancelled
        BuildPhaseUi.Failed -> Res.string.worktree_build_failed
        BuildPhaseUi.Unknown -> Res.string.worktree_build_unknown
    },
)

private const val BUILD_OUTPUT_CHARS = 4000
private const val BUILD_OUTPUT_LINES = 40
