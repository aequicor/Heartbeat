package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerLayout
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbComposerMenuStyle
import io.aequicor.heartbeat.ds.components.HbComposerToggle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EffortUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioModelOptions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_ask
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_auto
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_edits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_effort_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_placeholder
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.connect_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_high
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_low
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_medium
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_very_high
import io.aequicor.heartbeat.feature.aistudio.impl.resources.environment_cloud
import io.aequicor.heartbeat.feature.aistudio.impl.resources.environment_local
import io.aequicor.heartbeat.feature.aistudio.impl.resources.model_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.model_not_selected
import io.aequicor.heartbeat.feature.aistudio.impl.resources.no_project
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.research_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_this_computer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Composer of one pane: prompt templates, approval mode and model preferences around the shared editor. */
@Composable
internal fun StudioComposer(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    onOpenResearch: ((String) -> Unit)? = null,
) {
    val pane = content.pane
    val session = content.session
    val draft = content.draft
    val settings = content.settings
    val hasRunPreferences = content.supportsRunPreferences()
    val focus = remember { FocusRequester() }
    var previousPhase by remember(session?.id) { mutableStateOf(content.worktree?.phase) }
    SideEffect {
        if (previousPhase == WorktreePhaseUi.AwaitingDecision && content.worktree?.phase == WorktreePhaseUi.Idle) {
            focus.requestFocus()
        }
        previousPhase = content.worktree?.phase
    }
    HbChatComposer(
        value = draft,
        onValueChange = { onIntent(AiStudioScreenIntent.DraftChanged(pane.id, it)) },
        onSend = { onIntent(AiStudioScreenIntent.Submit(pane.id)) },
        onStop = { session?.let { onIntent(AiStudioScreenIntent.Stop(it.id)) } },
        sendLabel = stringResource(Res.string.composer_send),
        stopLabel = stringResource(Res.string.composer_stop),
        modifier = modifier.testTag("composer-${pane.id}"),
        layout = HbComposerLayout.Panel,
        inputMaxHeight = if (isCompact && !HbTheme.dimensions.isDesktop) {
            HbTheme.dimensions.composerMaxHeight
        } else {
            HbTheme.dimensions.editorMaxHeight
        },
        placeholder = stringResource(Res.string.composer_placeholder),
        isStreaming = session?.isRunning == true,
        enabled = content.isComposerEnabled(),
        inputModifier = Modifier.focusRequester(focus),
        contextContent = { StudioComposerContext(content, onIntent) },
        leadingContent = {
            StudioComposerLeading(content, hasRunPreferences, onIntent, onOpenResearch)
        },
        trailingContent = {
            ComposerEffort(content, hasRunPreferences, onIntent)
            StudioUsage(content, onIntent)
            ModelMenu(
                settings.modelId,
                content.models,
                onIntent,
                canSelect = !content.isSettingPending,
                paneId = pane.id,
            )
        },
    )
}

@Composable
private fun StudioComposerLeading(
    content: PaneContent,
    hasRunPreferences: Boolean,
    onIntent: (AiStudioScreenIntent) -> Unit,
    onOpenResearch: ((String) -> Unit)?,
) {
    TemplatesMenu(
        draft = content.draft,
        approval = content.settings.approval.takeIf { hasRunPreferences || content.isTrustSupported() },
        onDraft = { onIntent(AiStudioScreenIntent.DraftChanged(content.pane.id, it)) },
        onApproval = { onIntent(AiStudioScreenIntent.SelectApproval(it, content.pane.id)) },
        approvalEnabled = !content.isSettingPending,
    )
    if (content.isResearchAvailable && onOpenResearch != null) {
        HbComposerToggle(
            label = stringResource(Res.string.research_mode),
            isChecked = false,
            onCheckedChange = { if (it) onOpenResearch(content.settings.modelId) },
            modifier = Modifier.testTag("research-mode"),
            icon = HbIcons.Library,
        )
    }
}

@Composable
private fun StudioComposerContext(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val pane = content.pane
    if (pane.sessionId == null) {
        ContextTray(
            pane,
            content.project,
            content.projects,
            onIntent,
            isProjectAddingAvailable = content.isProjectAddingAvailable,
        )
    } else {
        content.project?.let { HbText(it.name, maxLines = 1, style = HbTheme.typography.caption) }
    }
    if (content.project != null) {
        HbIcon(HbIcons.Laptop, contentDescription = null)
        HbText(stringResource(Res.string.worktree_this_computer), style = HbTheme.typography.caption)
    }
    if (content.worktree != null || content.session?.isWorktree == true) {
        HbComposerToggle(
            label = stringResource(Res.string.worktree_mode),
            isChecked = true,
            onCheckedChange = {},
            enabled = false,
            icon = HbIcons.Branch,
            modifier = Modifier.testTag("worktree-pinned-${pane.id}"),
        )
        val branch = content.worktree?.branch ?: stringResource(Res.string.worktree_mode)
        HbTooltip(branch) {
            HbText(
                branch,
                Modifier.testTag("worktree-branch-${pane.id}"),
                maxLines = 1,
                style = HbTheme.typography.caption,
            )
        }
    } else if (pane.sessionId == null && content.isWorktreeAvailable && content.project != null) {
        HbComposerToggle(
            label = stringResource(Res.string.worktree_mode),
            isChecked = pane.isWorktree,
            onCheckedChange = { onIntent(AiStudioScreenIntent.SelectWorktree(pane.id, it)) },
            modifier = Modifier.testTag("worktree-mode-${pane.id}"),
            icon = HbIcons.Branch,
        )
    }
}

@Composable
private fun ComposerEffort(
    content: PaneContent,
    hasDemoPreferences: Boolean,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    val model = content.models.firstOrNull { it.id == content.settings.modelId }
    if (model != null && model.reasoningEfforts.isNotEmpty()) {
        val selected = if (content.configuration != null) {
            content.settings.nativeEffort
        } else {
            content.settings.engineEfforts[model.id]?.takeIf { it in model.reasoningEfforts }
        }
        EngineEffortMenu(model, selected, onIntent, content.pane.id, enabled = !content.isSettingPending)
    } else if (hasDemoPreferences) {
        EffortMenu(content.settings.effort, onIntent, content.pane.id, enabled = !content.isSettingPending)
    }
}

/** Scripted approvals are local demo behavior; native permission policies remain engine-owned. */
private fun PaneContent.supportsRunPreferences(): Boolean = models.any { model ->
    model.id == settings.modelId && StudioModelOptions.any { it.id == model.id }
}

/** Engines that apply trust levels take the composer's approval mode with every prompt. */
private fun PaneContent.isTrustSupported(): Boolean = models.any { it.id == settings.modelId && it.isTrustSupported }

/** Running requests retain cancellation; pending permissions block another prompt. */
private fun PaneContent.isComposerEnabled(): Boolean =
    !pane.isCreating && !isPickingProject && !isStopping && session?.isContinuable != false &&
        (session?.isWorktree != true || worktreeJournal == WorktreeJournalUi.Ready || session.isRunning) &&
        (worktree?.phase != WorktreePhaseUi.ActionWorking || session?.isRunning == true) &&
        (session?.isRunning != true || isStoppable) &&
        (session?.isRunning == true || (permissions.isEmpty() && models.any { it.id == settings.modelId }))

/** Project, environment and branch a new session starts in; the project can be changed. */
@Composable
internal fun ContextTray(
    pane: PaneUi,
    project: ProjectUi?,
    projects: ImmutableList<ProjectUi>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    isProjectAddingAvailable: Boolean = false,
) {
    var isOpen by remember { mutableStateOf(false) }
    val noProject = stringResource(Res.string.no_project)
    val add = if (isProjectAddingAvailable) {
        listOf(HbComposerAction(ADD_PROJECT, stringResource(Res.string.project_add)))
    } else {
        emptyList()
    }
    val items = (
        projects.map { HbComposerAction(it.id, it.name, isSelected = it.id == project?.id) } +
            HbComposerAction(NO_PROJECT, noProject, isSelected = project == null) + add
    ).toImmutableList()
    HbComposerMenuButton(
        label = project?.name ?: noProject,
        actions = items,
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { id ->
            if (id == ADD_PROJECT) {
                onIntent(AiStudioScreenIntent.AddProject(pane.id))
            } else {
                onIntent(AiStudioScreenIntent.SelectProject(pane.id, id.takeIf { it != NO_PROJECT }))
            }
        },
        modifier = modifier.testTag("project-chip-${pane.id}"),
        accessibleLabel = "${stringResource(Res.string.project_menu)}: ${project?.name ?: noProject}",
        headerLabel = project?.let {
            val environment = stringResource(
                if (it.environment == EnvironmentUi.Local) {
                    Res.string.environment_local
                } else {
                    Res.string.environment_cloud
                },
            )
            listOf(it.name, environment, it.branch).filter(String::isNotBlank).joinToString(" · ")
        },
        icon = if (project == null) null else HbIcons.Folder,
        style = HbComposerMenuStyle.Pill,
    )
}

@Composable
private fun TemplatesMenu(
    draft: String,
    approval: ApprovalUi?,
    onDraft: (String) -> Unit,
    onApproval: (ApprovalUi) -> Unit,
    approvalEnabled: Boolean,
) {
    var isOpen by remember { mutableStateOf(false) }
    val templates = listOf(
        Template("plan", Res.string.template_plan, Res.string.template_plan_prompt),
        Template("tests", Res.string.template_tests, Res.string.template_tests_prompt),
        Template("review", Res.string.template_review, Res.string.template_review_prompt),
    )
    val prompts = templates.associate { it.id to stringResource(it.prompt) }
    val actions = templates.map { HbComposerAction(it.id, stringResource(it.label)) } +
        approvalActions(approval, approvalEnabled)
    HbComposerMenuButton(
        label = stringResource(Res.string.composer_add),
        actions = actions.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { id ->
            if (id.startsWith(APPROVAL_PREFIX)) {
                onApproval(ApprovalUi.valueOf(id.removePrefix(APPROVAL_PREFIX)))
            } else {
                val prompt = prompts[id].orEmpty()
                onDraft(if (draft.isBlank()) prompt else "${draft.trimEnd()}\n$prompt")
            }
        },
        accessibleLabel = stringResource(Res.string.composer_add),
        icon = HbIcons.Plus,
        style = HbComposerMenuStyle.Circle,
    )
}

@Composable
private fun ModelMenu(
    modelId: String,
    models: ImmutableList<ModelUi>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    canSelect: Boolean,
    paneId: Int,
) {
    var isOpen by remember { mutableStateOf(false) }
    val model = models.firstOrNull { it.id == modelId }
    val modelLabel = model?.shortName ?: stringResource(Res.string.model_not_selected)
    val items = models.map {
        HbComposerAction(it.id, it.name, isEnabled = canSelect, isSelected = it.id == modelId)
    }
    HbComposerMenuButton(
        label = modelLabel,
        actions = items.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { onIntent(AiStudioScreenIntent.SelectModel(it, paneId)) },
        modifier = Modifier.testTag("model-chip"),
        accessibleLabel = model?.name ?: stringResource(Res.string.connect_model_hint),
        headerLabel = stringResource(Res.string.model_menu),
        icon = HbIcons.Layers,
        style = HbComposerMenuStyle.Pill,
        enabled = canSelect && models.isNotEmpty(),
    )
}

@Composable
private fun EffortMenu(effort: EffortUi, onIntent: (AiStudioScreenIntent) -> Unit, paneId: Int, enabled: Boolean) {
    var isOpen by remember { mutableStateOf(false) }
    val actions = EffortUi.entries.map {
        HbComposerAction(it.name, effortLabel(it), isSelected = it == effort)
    }.toImmutableList()
    HbComposerMenuButton(
        label = effortLabel(effort),
        actions = actions,
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { onIntent(AiStudioScreenIntent.SelectEffort(EffortUi.valueOf(it), paneId)) },
        modifier = Modifier.testTag("effort-chip"),
        accessibleLabel = stringResource(Res.string.composer_effort_menu),
        headerLabel = stringResource(Res.string.composer_effort_menu),
        icon = HbIcons.Sparkles,
        style = HbComposerMenuStyle.AccentPill,
        enabled = enabled,
    )
}

@Composable
private fun approvalActions(selected: ApprovalUi?, enabled: Boolean): List<HbComposerAction> {
    if (selected == null) return emptyList()
    return ApprovalUi.entries.mapIndexed { index, approval ->
        HbComposerAction(
            id = "$APPROVAL_PREFIX${approval.name}",
            label = approvalLabel(approval),
            sectionLabel = if (index == 0) stringResource(Res.string.approval_menu) else null,
            isSelected = approval == selected,
            isEnabled = enabled,
        )
    }
}

@Composable
private fun approvalLabel(approval: ApprovalUi): String = stringResource(
    when (approval) {
        ApprovalUi.Ask -> Res.string.approval_ask
        ApprovalUi.AutoEdits -> Res.string.approval_edits
        ApprovalUi.AutoApprove -> Res.string.approval_auto
    },
)

@Composable
private fun effortLabel(effort: EffortUi): String = stringResource(
    when (effort) {
        EffortUi.Low -> Res.string.effort_low
        EffortUi.Medium -> Res.string.effort_medium
        EffortUi.High -> Res.string.effort_high
        EffortUi.VeryHigh -> Res.string.effort_very_high
    },
)

private data class Template(val id: String, val label: StringResource, val prompt: StringResource)

private const val NO_PROJECT = "no-project"
private const val ADD_PROJECT = "add-project"
private const val APPROVAL_PREFIX = "approval:"
