package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanProposalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Which plan dialog a pane shows. */
internal enum class PlanDialog { Editor, Implement }

/**
 * What the implementation dialog of a pane may offer: the chat's route with its current [effort], its [approval]
 * unless the profile [securityLevel] replaces that choice, and the [modes]; nothing is implemented while [isBusy].
 */
internal data class PlanImplementationOffer(
    val models: ImmutableList<ModelUi>,
    val modelId: String,
    val approval: ApprovalUi,
    val modes: ImmutableList<PlanImplementationModeUi>,
    val effort: String? = null,
    val securityLevel: SecurityLevelUi? = null,
    val isTrustSupported: Boolean = false,
    val isBusy: Boolean = false,
)

/**
 * Whether a plan card may decide on its plan: only the [isLatest] card of a revision shows the full plan and its
 * decisions, none while planning is not [isEnabled], and implementation waits while the chat [isBusy].
 */
internal data class PlanCardDecisions(
    val isLatest: Boolean = true,
    val isEnabled: Boolean = true,
    val isBusy: Boolean = false,
)

/**
 * The plan card of a `plan_propose` call. The latest proposal of the current revision shows the plan with its
 * decisions; a proposal of an older revision, a repeated one, or one without a known revision only opens the plan.
 */
@Composable
internal fun StudioPlanCard(
    proposal: PlanProposalUi,
    plan: PlanUi?,
    onApprove: (Long) -> Unit,
    onRefine: () -> Unit,
    onDialog: (PlanDialog) -> Unit,
    modifier: Modifier = Modifier,
    decisions: PlanCardDecisions = PlanCardDecisions(),
) {
    val revision = proposal.revision
    val isCurrent = plan != null && revision != null && plan.revision == revision
    val current = plan?.takeIf { isCurrent && decisions.isLatest }
    HbCard(modifier.fillMaxWidth().testTag("plan-card"), contentPadding = HbTheme.spacing.l) {
        HbColumn(gap = HbTheme.spacing.m) {
            HbRow(gap = HbTheme.spacing.m) {
                HbText(
                    if (revision == null) {
                        stringResource(Res.string.plan_editor_title)
                    } else {
                        stringResource(Res.string.plan_card_title, revision)
                    },
                    Modifier.testTag("plan-card-title"),
                    style = HbTheme.typography.label,
                )
                current?.let { HbBadge(planStatusLabel(it.status), tone = it.status.tone()) }
            }
            if (proposal.summary.isNotBlank()) HbText(proposal.summary, color = HbTheme.colors.textSecondary)
            when {
                current != null -> HbMarkdown(current.text)

                plan != null && revision != null && !isCurrent -> HbText(
                    stringResource(Res.string.plan_replaced, plan.revision),
                    Modifier.testTag("plan-replaced"),
                    color = HbTheme.colors.textSecondary,
                )
            }
            PlanActions(
                current?.takeIf { decisions.isEnabled },
                isOpenable = plan != null,
                isBusy = decisions.isBusy,
                onApprove = onApprove,
                onRefine = onRefine,
                onDialog = onDialog,
            )
        }
    }
}

/** Decisions on the [current] revision while the chat plans; any known plan opens in the editor. */
@Composable
private fun PlanActions(
    current: PlanUi?,
    isOpenable: Boolean,
    isBusy: Boolean,
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
            PlanButton(Res.string.plan_refine, "plan-refine", onClick = onRefine)
            PlanButton(Res.string.plan_implement, "plan-implement", enabled = !isBusy) {
                onDialog(PlanDialog.Implement)
            }
        }
        if (isOpenable) PlanButton(Res.string.plan_open, "plan-open") { onDialog(PlanDialog.Editor) }
    }
}

/** The draft that asks the agent to refine the plan. */
@Composable
internal fun planRefinePrompt(): String = stringResource(Res.string.plan_refine_prompt)

/** The composer [draft] led by the refine [prefix]: the typed text is kept, and a draft already led by it stays. */
internal fun planRefineDraft(draft: String, prefix: String): String =
    if (draft.startsWith(prefix)) draft else prefix + draft.trimStart()

/** The id of the reply with the last proposal of each plan revision in [messages]. */
internal fun latestPlanProposals(messages: List<MessageUi>): Map<Long, String> = buildMap {
    messages.forEach { message ->
        val revision = (message as? MessageUi.Reply)?.tools?.lastOrNull { it.plan != null }?.plan?.revision
        if (revision != null) put(revision, message.id)
    }
}

/**
 * The user's editor of the plan text, opened from one revision. Save sends the edit from that revision, and the
 * editor stays open until the plan shows the saved text, so a rejected save keeps the edit. A newer revision replaces
 * an unedited text; with edits the editor shows the conflict, and the user reloads the newer revision or overwrites
 * it. Dismissing outside the dialog keeps unsaved edits; Cancel drops them.
 */
@Composable
internal fun StudioPlanEditor(plan: PlanUi, onSave: (text: String, base: Long) -> Unit, onDismiss: () -> Unit) {
    var base by rememberSaveable { mutableStateOf(plan.revision) }
    var baseText by rememberSaveable { mutableStateOf(plan.text) }
    var text by rememberSaveable { mutableStateOf(plan.text) }
    val isEdited = text != baseText
    val sync = planEditorSync(plan, base, text, isEdited)
    PlanEditorSyncEffect(plan, sync, onClose = onDismiss) {
        text = plan.text
        baseText = plan.text
        base = plan.revision
    }
    val isConflict = sync == PlanEditorSync.Keep && plan.revision != base
    HbDialog(
        title = stringResource(Res.string.plan_editor_title),
        onDismissRequest = { if (!isEdited) onDismiss() },
        width = HbDialogWidth.Wide,
        actions = {
            HbButton(stringResource(Res.string.plan_cancel), onDismiss, style = HbButtonStyle.Ghost)
            HbButton(
                stringResource(if (isConflict) Res.string.plan_overwrite else Res.string.plan_save),
                { onSave(text, if (isConflict) plan.revision else base) },
                Modifier.testTag("plan-save"),
                enabled = isEdited && plan.text != text,
            )
        },
    ) {
        if (isConflict) {
            PlanConflict(plan.revision) {
                text = plan.text
                baseText = plan.text
                base = plan.revision
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

/** How the editor follows a change of the plan it edits. */
private enum class PlanEditorSync {
    /** The same revision, or a newer one that conflicts with the edit. */
    Keep,

    /** The plan shows the edited text: it was saved, or the newer revision says the same. */
    Saved,

    /** A newer revision replaces the unedited text. */
    Follow,
}

private fun planEditorSync(plan: PlanUi, base: Long, text: String, isEdited: Boolean): PlanEditorSync = when {
    plan.revision == base -> PlanEditorSync.Keep
    !isEdited -> PlanEditorSync.Follow
    plan.text == text -> PlanEditorSync.Saved
    else -> PlanEditorSync.Keep
}

/** Applies [sync] once the plan shows a new revision or text: closes the saved editor or follows the plan. */
@Composable
private fun PlanEditorSyncEffect(plan: PlanUi, sync: PlanEditorSync, onClose: () -> Unit, onFollow: () -> Unit) {
    val latestClose by rememberUpdatedState(onClose)
    val latestFollow by rememberUpdatedState(onFollow)
    key(plan.revision, plan.text) {
        SideEffect {
            when (sync) {
                PlanEditorSync.Keep -> Unit
                PlanEditorSync.Saved -> latestClose()
                PlanEditorSync.Follow -> latestFollow()
            }
        }
    }
}

/** The newer [revision] the plan changed to while the user edited it, with the reload of that revision. */
@Composable
private fun PlanConflict(revision: Long, onReload: () -> Unit) {
    HbBanner(stringResource(Res.string.plan_editor_conflict, revision), tone = HbTone.Warning) {
        HbButton(
            stringResource(Res.string.plan_reload),
            onReload,
            Modifier.testTag("plan-reload"),
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

/**
 * The choice of route, approvals and mode the plan is implemented with. Under the profile security level the dialog
 * shows that level and keeps the chat's approval, as the composer does; nothing is sent while the chat is busy.
 */
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
    var effort by rememberSaveable(modelId) { mutableStateOf(initialEffort(offer, model)) }
    val efforts = model?.reasoningEfforts.orEmpty()
    val selectedEffort = effort?.takeIf { it in efforts } ?: model?.defaultReasoningEffort
    var approval by rememberSaveable { mutableStateOf(offer.approval) }
    var mode by rememberSaveable { mutableStateOf(PlanImplementationModeUi.Chat) }
    val selectedMode = mode.takeIf { it in offer.modes } ?: offer.modes.firstOrNull() ?: PlanImplementationModeUi.Chat
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
                        selectedEffort,
                        if (offer.securityLevel != null) offer.approval else approval,
                        selectedMode,
                        isFresh,
                        prompt.ifBlank { defaultPrompt },
                    )
                    onImplement(intent)
                },
                Modifier.testTag("plan-implement-confirm"),
                enabled = model != null && !offer.isBusy,
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
            if (efforts.isNotEmpty()) {
                HbFlowRow(gap = HbTheme.spacing.s) {
                    efforts.forEach { level ->
                        HbChoiceRow(
                            level,
                            level == selectedEffort,
                            isMultiple = false,
                            onClick = { effort = level },
                            modifier = Modifier.testTag("plan-effort-$level"),
                        )
                    }
                }
            }
        }
        PlanSection(Res.string.plan_implement_approval) {
            PlanApprovals(offer, approval) { approval = it }
        }
        PlanSection(Res.string.plan_implement_mode) {
            offer.modes.forEach { option ->
                HbChoiceRow(
                    modeLabel(option),
                    option == selectedMode,
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

/** The approval choice of the implementation, or the inactive profile security level that replaces it. */
@Composable
private fun PlanApprovals(offer: PlanImplementationOffer, approval: ApprovalUi, onApproval: (ApprovalUi) -> Unit) {
    val level = offer.securityLevel
    if (level != null) {
        HbChoiceRow(
            securityLevelLabel(level),
            selected = true,
            isMultiple = false,
            onClick = {},
            modifier = Modifier.testTag("plan-profile-security"),
            enabled = false,
        )
        HbText(
            securityLevelHint(level, offer.isTrustSupported, isHelper = false),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    } else {
        ApprovalUi.entries.forEach { option ->
            HbChoiceRow(
                approvalLabel(option),
                option == approval,
                isMultiple = false,
                onClick = { onApproval(option) },
                modifier = Modifier.testTag("plan-approval-${option.name}"),
            )
        }
    }
}

/** The chat's current effort for its own model, otherwise the default of the chosen [model]. */
private fun initialEffort(offer: PlanImplementationOffer, model: ModelUi?): String? {
    val efforts = model?.reasoningEfforts.orEmpty()
    return offer.effort?.takeIf { model?.id == offer.modelId && it in efforts } ?: model?.defaultReasoningEffort
}

@Composable
private fun PlanSection(title: StringResource, content: @Composable () -> Unit) {
    HbColumn(gap = HbTheme.spacing.s) {
        HbText(stringResource(title), style = HbTheme.typography.label, color = HbTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun PlanButton(label: StringResource, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    HbButton(
        stringResource(label),
        onClick,
        Modifier.testTag(tag),
        style = HbButtonStyle.Secondary,
        enabled = enabled,
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
