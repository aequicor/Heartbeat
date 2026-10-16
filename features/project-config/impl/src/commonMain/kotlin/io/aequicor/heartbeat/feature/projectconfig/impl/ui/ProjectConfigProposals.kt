package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ContentKindUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.FailureUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProblemCodeUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProblemUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenIntent
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProposalKindUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProposalUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ReviewUi
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.Res
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_applying
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_cancel
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_instruction
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_interface
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_lesson
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_line
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_script
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_skill
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_template
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_tools
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_workflow
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_dismiss
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_conflict
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_duplicate
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_failed
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_invalid
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_limit
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_parent
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_failure_unavailable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_has_code
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_has_code_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_keep_app
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_conflict
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_conflict_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_create
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_create_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_remove
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_remove_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_update
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_kind_update_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_compile
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_limit
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_missing
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_name
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_parent
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_syntax
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_problem_unsupported
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_proposals_empty
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_not_needed
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_pending
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_risk
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_safe
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_unavailable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_unavailable_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_review_uncertain
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_risk_confirm
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_risk_text
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_risk_title
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_proposals
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_proposals_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_source_bundle
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_source_project
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_trust
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Changes from project folders and the open bundle, each with its review and the user's choices. */
@Composable
internal fun ProposalsSection(
    proposals: ImmutableList<ProposalUi>,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.pc_section_proposals),
        modifier.testTag("project-config-proposals"),
        description = stringResource(Res.string.pc_section_proposals_hint),
    ) {
        if (proposals.isEmpty()) {
            HbText(
                stringResource(Res.string.pc_proposals_empty),
                Modifier.padding(HbTheme.spacing.m),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        proposals.forEachIndexed { index, proposal ->
            key(proposal.id) {
                if (index > 0) HbDivider()
                ProposalRow(proposal, onIntent)
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: ProposalUi,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("project-config-proposal-${proposal.id}"),
        gap = HbTheme.spacing.xs,
    ) {
        HbText(proposal.title, style = HbTheme.typography.label)
        ProposalDetails(proposal)
        proposal.failure?.let { Caption(stringResource(it.message())) }
        ProposalActions(proposal, onIntent)
    }
}

/** Badges, origin, contents, review and problems of [proposal]: everything the trust decision rests on. */
@Composable
private fun ProposalDetails(proposal: ProposalUi) {
    HbFlowRow {
        HbBadge(stringResource(proposal.section.title()))
        HbBadge(stringResource(proposal.kind.title()), tone = proposal.kind.tone())
        ReviewBadge(proposal.review)
        if (proposal.hasCode) HbBadge(stringResource(Res.string.pc_has_code), tone = HbTone.Warning)
    }
    Caption(sourceText(proposal))
    Caption(stringResource(proposal.kind.hint()))
    proposal.contents.forEach { content ->
        Caption(stringResource(Res.string.pc_content_line, stringResource(content.kind.title()), content.name))
    }
    if (proposal.hasCode) Caption(stringResource(Res.string.pc_has_code_hint))
    when (val review = proposal.review) {
        is ReviewUi.Risky -> Caption(review.reason)
        ReviewUi.Unavailable -> Caption(stringResource(Res.string.pc_review_unavailable_hint))
        ReviewUi.NotNeeded, ReviewUi.Pending, is ReviewUi.Safe -> Unit
    }
    proposal.problems.forEach { Caption(problemText(it)) }
}

@Composable
private fun ProposalActions(proposal: ProposalUi, onIntent: (ProjectConfigScreenIntent) -> Unit) {
    HbFlowRow {
        if (proposal.isApplying) {
            Caption(stringResource(Res.string.pc_applying))
            return@HbFlowRow
        }
        if (proposal.isTrustOffered) {
            HbButton(
                stringResource(Res.string.pc_trust),
                { onIntent(ProjectConfigScreenIntent.Trust(proposal.version)) },
                Modifier.testTag("project-config-trust-${proposal.id}"),
                size = HbButtonSize.Small,
            )
        }
        if (proposal.isKeepAppOffered) {
            HbButton(
                stringResource(Res.string.pc_keep_app),
                { onIntent(ProjectConfigScreenIntent.KeepApp(proposal.version)) },
                Modifier.testTag("project-config-keep-${proposal.id}"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
        HbButton(
            stringResource(Res.string.pc_dismiss),
            { onIntent(ProjectConfigScreenIntent.Dismiss(proposal.version)) },
            Modifier.testTag("project-config-dismiss-${proposal.id}"),
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun ReviewBadge(review: ReviewUi) {
    val (title, tone) = when (review) {
        ReviewUi.NotNeeded -> Res.string.pc_review_not_needed to HbTone.Neutral

        ReviewUi.Pending -> Res.string.pc_review_pending to HbTone.Neutral

        ReviewUi.Unavailable -> Res.string.pc_review_unavailable to HbTone.Warning

        is ReviewUi.Safe -> Res.string.pc_review_safe to HbTone.Success

        is ReviewUi.Risky -> (if (review.isUncertain) Res.string.pc_review_uncertain else Res.string.pc_review_risk) to
            HbTone.Danger
    }
    HbBadge(stringResource(title), tone = tone)
}

/** Confirmation of a proposal the automatic review did not confirm as safe. */
@Composable
internal fun RiskDialog(proposal: ProposalUi, onIntent: (ProjectConfigScreenIntent) -> Unit) {
    HbDialog(
        stringResource(Res.string.pc_risk_title),
        { onIntent(ProjectConfigScreenIntent.CancelRisk) },
        Modifier.testTag("project-config-risk"),
        actions = {
            HbButton(
                stringResource(Res.string.pc_cancel),
                { onIntent(ProjectConfigScreenIntent.CancelRisk) },
                style = HbButtonStyle.Secondary,
            )
            HbButton(
                stringResource(Res.string.pc_risk_confirm),
                { onIntent(ProjectConfigScreenIntent.ConfirmRisk) },
                Modifier.testTag("project-config-risk-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.pc_risk_text))
        HbText(proposal.title, style = HbTheme.typography.label)
        ProposalDetails(proposal)
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    HbText(text, modifier, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

@Composable
private fun problemText(problem: ProblemUi): String {
    val what = listOfNotNull(stringResource(problem.code.title()), problem.detail.takeIf { it.isNotBlank() })
        .joinToString(": ")
    return problem.path?.let { stringResource(Res.string.pc_problem, it, what) } ?: what
}

private fun ProposalKindUi.title(): StringResource = when (this) {
    ProposalKindUi.Create -> Res.string.pc_kind_create
    ProposalKindUi.Update -> Res.string.pc_kind_update
    ProposalKindUi.Remove -> Res.string.pc_kind_remove
    ProposalKindUi.Conflict -> Res.string.pc_kind_conflict
}

private fun ProposalKindUi.hint(): StringResource = when (this) {
    ProposalKindUi.Create -> Res.string.pc_kind_create_hint
    ProposalKindUi.Update -> Res.string.pc_kind_update_hint
    ProposalKindUi.Remove -> Res.string.pc_kind_remove_hint
    ProposalKindUi.Conflict -> Res.string.pc_kind_conflict_hint
}

@Composable
private fun sourceText(proposal: ProposalUi): String = stringResource(
    if (proposal.source.isBundle) Res.string.pc_source_bundle else Res.string.pc_source_project,
    proposal.source.name,
)

private fun ProposalKindUi.tone(): HbTone = when (this) {
    ProposalKindUi.Create, ProposalKindUi.Update -> HbTone.Brand
    ProposalKindUi.Remove -> HbTone.Neutral
    ProposalKindUi.Conflict -> HbTone.Warning
}

private fun ContentKindUi.title(): StringResource = when (this) {
    ContentKindUi.Skill -> Res.string.pc_content_skill
    ContentKindUi.Instruction -> Res.string.pc_content_instruction
    ContentKindUi.Template -> Res.string.pc_content_template
    ContentKindUi.Script -> Res.string.pc_content_script
    ContentKindUi.Workflow -> Res.string.pc_content_workflow
    ContentKindUi.ToolPolicy -> Res.string.pc_content_tools
    ContentKindUi.Interface -> Res.string.pc_content_interface
    ContentKindUi.Lesson -> Res.string.pc_content_lesson
}

private fun ProblemCodeUi.title(): StringResource = when (this) {
    ProblemCodeUi.Syntax -> Res.string.pc_problem_syntax
    ProblemCodeUi.Missing -> Res.string.pc_problem_missing
    ProblemCodeUi.Unsupported -> Res.string.pc_problem_unsupported
    ProblemCodeUi.Name -> Res.string.pc_problem_name
    ProblemCodeUi.Limit -> Res.string.pc_problem_limit
    ProblemCodeUi.Compile -> Res.string.pc_problem_compile
    ProblemCodeUi.Parent -> Res.string.pc_problem_parent
}

private fun FailureUi.message(): StringResource = when (this) {
    FailureUi.Unavailable -> Res.string.pc_failure_unavailable
    FailureUi.Conflict -> Res.string.pc_failure_conflict
    FailureUi.Duplicate -> Res.string.pc_failure_duplicate
    FailureUi.Limit -> Res.string.pc_failure_limit
    FailureUi.Invalid -> Res.string.pc_failure_invalid
    FailureUi.MissingParent -> Res.string.pc_failure_parent
    FailureUi.Failed -> Res.string.pc_failure_failed
}
