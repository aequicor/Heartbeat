package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
import io.aequicor.heartbeat.ds.components.HbMarkdown
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_approve
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_cancel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_card_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_editor_conflict
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_editor_label
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_editor_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_fresh
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_approval
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_default
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_model
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_implement_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_mode_chat
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_mode_organism
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_mode_sandbox
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_mode_worktree
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_open
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_overwrite
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_refine
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_refine_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_reload
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_replaced
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_save
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_status_approved
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_status_draft
import io.aequicor.heartbeat.feature.aistudio.impl.resources.plan_status_proposed
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanProposalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Which plan dialog a pane shows. */
internal enum class PlanDialog { Editor, Implement }

/** What the implementation dialog of a pane may offer. */
internal data class PlanImplementationOffer(
    val models: ImmutableList<ModelUi>,
    val modelId: String,
    val approval: ApprovalUi,
    val modes: ImmutableList<PlanImplementationModeUi>,
)

/**
 * The plan card of a `plan_propose` call: the current plan when the proposal still names it, with its decisions;
 * a proposal of an older revision only opens the plan.
 */
@Composable
internal fun StudioPlanCard(
    proposal: PlanProposalUi,
    plan: PlanUi?,
    onApprove: (Long) -> Unit,
    onRefine: () -> Unit,
    onDialog: (PlanDialog) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision = proposal.revision ?: plan?.revision ?: 0
    val current = plan?.takeIf { it.revision == revision }
    HbCard(modifier.fillMaxWidth().testTag("plan-card"), contentPadding = HbTheme.spacing.l) {
        HbColumn(gap = HbTheme.spacing.m) {
            HbRow(gap = HbTheme.spacing.m) {
                HbText(stringResource(Res.string.plan_card_title, revision), style = HbTheme.typography.label)
                current?.let { HbBadge(planStatusLabel(it.status), tone = it.status.tone()) }
            }
            if (proposal.summary.isNotBlank()) HbText(proposal.summary, color = HbTheme.colors.textSecondary)
            when {
                current != null -> HbMarkdown(current.text)

                plan != null -> HbText(
                    stringResource(Res.string.plan_replaced, plan.revision),
                    Modifier.testTag("plan-replaced"),
                    color = HbTheme.colors.textSecondary,
                )
            }
            PlanActions(current, isOpenable = plan != null, onApprove, onRefine, onDialog)
        }
    }
}

/** Decisions on the [current] revision while the chat plans; any known plan opens in the editor. */
@Composable
private fun PlanActions(
    current: PlanUi?,
    isOpenable: Boolean,
    onApprove: (Long) -> Unit,
    onRefine: () -> Unit,
    onDialog: (PlanDialog) -> Unit,
) {
    val planning = current?.takeIf { it.isPlanning }
    HbFlowRow(gap = HbTheme.spacing.s) {
        if (planning != null && planning.status != PlanStatusUi.Approved) {
            HbButton(
                stringResource(Res.string.plan_approve),
                { onApprove(planning.revision) },
                Modifier.testTag("plan-approve"),
                size = HbButtonSize.Small,
            )
        }
        if (planning != null) {
            PlanButton(Res.string.plan_refine, "plan-refine", onRefine)
            PlanButton(Res.string.plan_implement, "plan-implement") { onDialog(PlanDialog.Implement) }
        }
        if (isOpenable) PlanButton(Res.string.plan_open, "plan-open") { onDialog(PlanDialog.Editor) }
    }
}

/** The draft that asks the agent to refine the plan. */
@Composable
internal fun planRefinePrompt(): String = stringResource(Res.string.plan_refine_prompt)

/**
 * The user's editor of the plan text. A change of the plan while editing shows the newer revision: the user reloads
 * it or overwrites it with the edited text.
 */
@Composable
internal fun StudioPlanEditor(plan: PlanUi, onSave: (text: String, base: Long) -> Unit, onDismiss: () -> Unit) {
    var base by remember { mutableStateOf(plan.revision) }
    var text by rememberSaveable { mutableStateOf(plan.text) }
    val isChanged = plan.revision != base
    HbDialog(
        title = stringResource(Res.string.plan_editor_title),
        onDismissRequest = onDismiss,
        width = HbDialogWidth.Wide,
        actions = {
            HbButton(stringResource(Res.string.plan_cancel), onDismiss, style = HbButtonStyle.Ghost)
            HbButton(
                stringResource(if (isChanged) Res.string.plan_overwrite else Res.string.plan_save),
                { onSave(text, plan.revision) },
                Modifier.testTag("plan-save"),
                enabled = text != plan.text,
            )
        },
    ) {
        if (isChanged) {
            HbBanner(stringResource(Res.string.plan_editor_conflict, plan.revision), tone = HbTone.Warning) {
                HbButton(
                    stringResource(Res.string.plan_reload),
                    {
                        text = plan.text
                        base = plan.revision
                    },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
        HbCodeEditor(
            value = text,
            onValueChange = { text = it },
            accessibleLabel = stringResource(Res.string.plan_editor_label),
            modifier = Modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.toolPayloadMaxHeight)
                .testTag("plan-editor"),
            language = "markdown",
        )
    }
}

/** The choice of route, approvals and mode the plan is implemented with. */
@Composable
internal fun StudioPlanImplementDialog(
    plan: PlanUi,
    offer: PlanImplementationOffer,
    onImplement: (StudioIntent.Plan) -> Unit,
    onDismiss: () -> Unit,
    sessionId: String,
) {
    var modelId by rememberSaveable { mutableStateOf(offer.modelId) }
    val model = offer.models.firstOrNull { it.id == modelId }
    var effort by rememberSaveable(modelId) { mutableStateOf(model?.defaultReasoningEffort) }
    var approval by rememberSaveable { mutableStateOf(offer.approval) }
    var mode by rememberSaveable { mutableStateOf(PlanImplementationModeUi.Chat) }
    var isFresh by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val defaultPrompt = stringResource(Res.string.plan_implement_default)
    HbDialog(
        title = stringResource(Res.string.plan_implement_title),
        onDismissRequest = onDismiss,
        actions = {
            HbButton(stringResource(Res.string.plan_cancel), onDismiss, style = HbButtonStyle.Ghost)
            HbButton(
                stringResource(Res.string.plan_implement),
                {
                    val intent = StudioIntent.ImplementPlan(
                        sessionId,
                        plan.revision,
                        modelId,
                        effort,
                        approval,
                        mode,
                        isFresh,
                        prompt.ifBlank { defaultPrompt },
                    )
                    onImplement(intent)
                },
                Modifier.testTag("plan-implement-confirm"),
                enabled = model != null,
            )
        },
    ) {
        PlanSection(Res.string.plan_implement_model) {
            offer.models.forEach { option ->
                HbChoiceRow(
                    option.name,
                    option.id == modelId,
                    isMultiple = false,
                    onClick = { modelId = option.id },
                    modifier = Modifier.testTag("plan-model-${option.id}"),
                )
            }
            model?.reasoningEfforts?.takeIf { it.isNotEmpty() }?.let { efforts ->
                HbFlowRow(gap = HbTheme.spacing.s) {
                    efforts.forEach { level ->
                        HbButton(
                            level,
                            { effort = level },
                            style = if (level == effort) HbButtonStyle.Primary else HbButtonStyle.Secondary,
                            size = HbButtonSize.Small,
                        )
                    }
                }
            }
        }
        PlanSection(Res.string.plan_implement_approval) {
            ApprovalUi.entries.forEach { option ->
                HbChoiceRow(
                    approvalLabel(option),
                    option == approval,
                    isMultiple = false,
                    onClick = { approval = option },
                )
            }
        }
        PlanSection(Res.string.plan_implement_mode) {
            offer.modes.forEach { option ->
                HbChoiceRow(
                    modeLabel(option),
                    option == mode,
                    isMultiple = false,
                    onClick = { mode = option },
                    modifier = Modifier.testTag("plan-mode-${option.name}"),
                )
            }
        }
        HbSwitch(isFresh, { isFresh = it }, stringResource(Res.string.plan_fresh))
        HbTextField(
            prompt,
            { prompt = it },
            Modifier.fillMaxWidth(),
            placeholder = stringResource(Res.string.plan_implement_prompt),
            singleLine = false,
        )
    }
}

@Composable
private fun PlanSection(title: org.jetbrains.compose.resources.StringResource, content: @Composable () -> Unit) {
    HbColumn(gap = HbTheme.spacing.s) {
        HbText(stringResource(title), style = HbTheme.typography.label, color = HbTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun PlanButton(label: org.jetbrains.compose.resources.StringResource, tag: String, onClick: () -> Unit) {
    HbButton(
        stringResource(label),
        onClick,
        Modifier.testTag(tag),
        style = HbButtonStyle.Secondary,
        size = HbButtonSize.Small,
    )
}

@Composable
private fun planStatusLabel(status: PlanStatusUi): String = stringResource(
    when (status) {
        PlanStatusUi.Draft -> Res.string.plan_status_draft
        PlanStatusUi.Proposed -> Res.string.plan_status_proposed
        PlanStatusUi.Approved -> Res.string.plan_status_approved
    },
)

private fun PlanStatusUi.tone(): HbTone = when (this) {
    PlanStatusUi.Draft -> HbTone.Neutral
    PlanStatusUi.Proposed -> HbTone.Brand
    PlanStatusUi.Approved -> HbTone.Success
}

@Composable
private fun modeLabel(mode: PlanImplementationModeUi): String = stringResource(
    when (mode) {
        PlanImplementationModeUi.Chat -> Res.string.plan_mode_chat
        PlanImplementationModeUi.Worktree -> Res.string.plan_mode_worktree
        PlanImplementationModeUi.Sandbox -> Res.string.plan_mode_sandbox
        PlanImplementationModeUi.Organism -> Res.string.plan_mode_organism
    },
)
