package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerCaretRequest
import io.aequicor.heartbeat.ds.components.HbComposerLayout
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbComposerMenuStyle
import io.aequicor.heartbeat.ds.components.HbComposerSuggestion
import io.aequicor.heartbeat.ds.components.HbComposerSuggestions
import io.aequicor.heartbeat.ds.components.HbComposerToggle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPasteImageButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.components.rememberHbPasteImageAction
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_ask
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_auto
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_edits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_safety
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_page
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_paste
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_commands
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_effort_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_placeholder
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_suggestions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_suggestions_close
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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_menu_section
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_mode_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.research_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sandbox_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security_ask
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security_helper_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security_settings_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security_trust
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security_unsupported
import io.aequicor.heartbeat.feature.aistudio.impl.resources.suggestions_section_commands
import io.aequicor.heartbeat.feature.aistudio.impl.resources.suggestions_section_engine_commands
import io.aequicor.heartbeat.feature.aistudio.impl.resources.suggestions_section_engine_skills
import io.aequicor.heartbeat.feature.aistudio.impl.resources.suggestions_section_files
import io.aequicor.heartbeat.feature.aistudio.impl.resources.suggestions_section_skills
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_remember
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_mode
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EffortUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withRememberCommand
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/**
 * Composer of one pane: prompt templates, approval mode and model preferences around the shared editor. Native drop
 * and clipboard capture belongs to the screen root, which observes the gestures with any focus inside the studio.
 * [onContextWidthChange] reports the width in pixels needed by the context controls on one line, including the
 * composer's inner context padding. The width is independent of the current wrapping; absent controls report zero.
 */
@Composable
internal fun StudioComposer(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    onOpenResearch: ((String) -> Unit)? = null,
    onContextWidthChange: ((Int) -> Unit)? = null,
) {
    HbBoxWithConstraints(modifier) {
        val presentation = when {
            maxWidth < HbTheme.dimensions.composerToolbarBreakpoint -> ComposerPresentation.Compact

            maxWidth < HbTheme.dimensions.compactBreakpoint || !HbTheme.dimensions.isDesktop ->
                ComposerPresentation.Medium

            else -> ComposerPresentation.Wide
        }
        StudioComposerInput(content, onIntent, isCompact, presentation, onOpenResearch, onContextWidthChange)
    }
}

/** Density follows the pane width, independently of the host's touch target preset. */
private enum class ComposerPresentation { Compact, Medium, Wide }

@Composable
private fun StudioComposerInput(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    isCompact: Boolean,
    presentation: ComposerPresentation,
    onOpenResearch: ((String) -> Unit)?,
    onContextWidthChange: ((Int) -> Unit)?,
) {
    val pane = content.pane
    val session = content.session
    val draft = content.draft
    val settings = content.settings
    val hasRunPreferences = content.supportsRunPreferences()
    val support = content.models.firstOrNull { it.id == settings.modelId }?.inputSupport
    val isAddingEnabled = content.canAddAttachments()
    val (focus, requestInputFocus) = rememberComposerFocus(content)
    val hasContext = content.hasComposerContext()
    SideEffect(hasContext, onContextWidthChange) {
        if (!hasContext) onContextWidthChange?.invoke(0)
    }
    HbChatComposer(
        value = draft,
        onValueChange = { onIntent(StudioIntent.Suggestions.DraftChanged(pane.id, it)) },
        onSend = { onIntent(StudioIntent.Submit(pane.id)) },
        onStop = { session?.let { onIntent(StudioIntent.Stop(it.id)) } },
        sendLabel = stringResource(Res.string.composer_send),
        stopLabel = stringResource(Res.string.composer_stop),
        modifier = Modifier.testTag("composer-${pane.id}"),
        hasAttachments = content.attachments.isNotEmpty(),
        canSend = content.isHarnessSelectionReady && !content.isAttachmentPending && (
            support?.accepts(
                content.attachments,
            ) ?: content.attachments.isEmpty()
        ),
        layout = HbComposerLayout.CompactPanel,
        inputMaxHeight = if (isCompact && !HbTheme.dimensions.isDesktop) {
            HbTheme.dimensions.composerMaxHeight
        } else {
            HbTheme.dimensions.editorMaxHeight
        },
        placeholder = stringResource(Res.string.composer_placeholder),
        isStreaming = session?.isRunning == true,
        enabled = content.isComposerEnabled(),
        inputModifier = Modifier.focusRequester(focus),
        suggestions = composerSuggestions(content),
        suggestionsLabel = stringResource(Res.string.composer_suggestions),
        suggestionsCloseLabel = stringResource(Res.string.composer_suggestions_close),
        onSuggestionMove = { delta -> onIntent(StudioIntent.Suggestions.MoveSuggestion(pane.id, delta)) },
        onSuggestionAccept = { index -> onIntent(StudioIntent.Suggestions.AcceptSuggestion(pane.id, index)) },
        onSuggestionsDismiss = { onIntent(StudioIntent.Suggestions.DismissSuggestions(pane.id)) },
        onCaretChange = { caret -> onIntent(StudioIntent.Suggestions.CaretMoved(pane.id, caret)) },
        caretRequest = content.caretRequest?.let { HbComposerCaretRequest(it.id, it.text, it.caret) },
        onCaretRequestApply = { id -> onIntent(StudioIntent.Suggestions.CaretRequestApplied(pane.id, id)) },
        contextContent = if (hasContext) {
            { StudioComposerContext(content, onIntent, presentation, onContextWidthChange) }
        } else {
            null
        },
        leadingContent = {
            StudioComposerLeading(content, hasRunPreferences, presentation, onIntent, onOpenResearch) {
                requestInputFocus()
            }
            if (isAddingEnabled && presentation != ComposerPresentation.Compact) {
                AttachmentActions(content, onIntent, presentation)
            }
        },
        trailingContent = {
            ModelMenu(
                settings.modelId,
                content.models,
                onIntent,
                canSelect = !content.isSettingPending && content.organism == null,
                paneId = pane.id,
                modifier = Modifier.weight(1f, fill = false),
                isCompact = presentation == ComposerPresentation.Compact,
            )
            ComposerEffort(
                content,
                hasRunPreferences,
                onIntent,
                Modifier.widthIn(
                    max = HbTheme.dimensions.composerLabelMaxWidth *
                        if (presentation == ComposerPresentation.Compact) 0.7f else 1f,
                ),
            )
        },
    )
}

/** Menus and inserted diagram prompts restore focus after their transient surface has closed. */
@Composable
private fun rememberComposerFocus(content: PaneContent): Pair<FocusRequester, () -> Unit> {
    val focus = remember { FocusRequester() }
    var inputFocusRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(inputFocusRequests) {
        if (inputFocusRequests == 0) return@LaunchedEffect
        // The menu returns focus to its button when it closes; the input takes it back on the next frame.
        withFrameNanos { }
        focus.requestFocus()
    }
    LaunchedEffect(content.diagram.focusRequest) {
        if (content.diagram.focusRequest > 0L) {
            withFrameNanos { }
            focus.requestFocus()
        }
    }
    var previousPhase by remember(content.session?.id) { mutableStateOf(content.worktree?.phase) }
    SideEffect {
        if (previousPhase == WorktreePhaseUi.AwaitingDecision && content.worktree?.phase == WorktreePhaseUi.Idle) {
            focus.requestFocus()
        }
        previousPhase = content.worktree?.phase
    }
    return focus to { inputFocusRequests++ }
}

/** Available harnesses also need the context row in chats without a project. */
private fun PaneContent.hasComposerContext(): Boolean {
    val hasWorktree = worktree != null || session?.isWorktree == true
    return pane.sessionId == null || project != null || hasWorktree || harnesses.isNotEmpty() || app != null ||
        contextUsage != null || providerUsage?.isVisible == true
}

/** Suggestions of the pane's active token with section labels and icons for their kinds and origins. */
@Composable
private fun composerSuggestions(content: PaneContent): HbComposerSuggestions? {
    val list = content.suggestions ?: return null
    val rows = list.items.mapIndexed { index, suggestion ->
        val section = suggestion.sectionKey()
        val previousSection = list.items.getOrNull(index - 1)?.sectionKey()
        HbComposerSuggestion(
            id = suggestion.id,
            label = suggestion.label,
            supportingText = suggestion.description.takeIf(String::isNotEmpty),
            sectionLabel = if (section != previousSection) sectionLabel(suggestion) else null,
            icon = when (suggestion.kind) {
                SuggestionKindUi.Command -> HbIcons.Terminal
                SuggestionKindUi.Skill -> HbIcons.Sparkles
                SuggestionKindUi.File -> HbIcons.FileText
            },
            isEnabled = suggestion.kind != SuggestionKindUi.File || suggestion.isSupported,
            isSelected = index == list.selectedIndex,
        )
    }.toImmutableList()
    return HbComposerSuggestions(rows, list.selectedIndex)
}

private fun SuggestionUi.sectionKey(): String = "${origin.engine.orEmpty()}:${kind.name}"

@Composable
private fun sectionLabel(suggestion: SuggestionUi): String = when (suggestion.kind) {
    SuggestionKindUi.Command -> when (val origin = suggestion.origin.engine) {
        null -> stringResource(Res.string.suggestions_section_commands)
        else -> stringResource(Res.string.suggestions_section_engine_commands, origin)
    }

    SuggestionKindUi.Skill -> when (val origin = suggestion.origin.engine) {
        null -> stringResource(Res.string.suggestions_section_skills)
        else -> stringResource(Res.string.suggestions_section_engine_skills, origin)
    }

    SuggestionKindUi.File -> stringResource(Res.string.suggestions_section_files)
}

@Composable
private fun StudioComposerLeading(
    content: PaneContent,
    hasRunPreferences: Boolean,
    presentation: ComposerPresentation,
    onIntent: (StudioIntent) -> Unit,
    onOpenResearch: ((String) -> Unit)?,
    onFocusInput: () -> Unit,
) {
    val pasteImage = rememberHbPasteImageAction {
        onIntent(StudioIntent.ImportAttachments(content.pane.id, listOf(NativeAttachmentUi.Image(it))))
    }
    val support = content.models.firstOrNull { it.id == content.settings.modelId }?.inputSupport
    TemplatesMenu(
        draft = content.draft,
        isRememberEnabled = content.isRememberEnabled,
        onFocusInput = onFocusInput,
        approval = if (content.organism != null) {
            content.organism.approval
        } else {
            content.settings.approval.takeIf { hasRunPreferences || content.isTrustSupported() }
        },
        onDraft = { onIntent(StudioIntent.Suggestions.DraftChanged(content.pane.id, it)) },
        onApproval = { onIntent(StudioIntent.SelectApproval(it, content.pane.id)) },
        approvalEnabled = !content.isSettingPending && content.organism == null,
        securityLevel = content.securityLevel.takeIf { content.usesProfileSecurity(hasRunPreferences) },
        isTrustSupported = content.isTrustSupported(),
        isHelper = content.session?.isHelper == true,
        organism = content.pane.isOrganism.takeIf { content.isOrganismOffered() },
        onOrganism = { onIntent(StudioIntent.SelectOrganism(content.pane.id, it)) },
        worktree = content.worktreeMenuSelection(),
        onWorktree = { onIntent(StudioIntent.SelectWorktree(content.pane.id, it)) },
        onAttach = { onIntent(StudioIntent.PickAttachments(content.pane.id)) }.takeIf {
            content.canAddAttachments() && content.host.isNativeAttachmentInputAvailable &&
                !content.isAttachmentPending
        },
        onPasteImage = pasteImage.takeIf {
            content.canAddAttachments() && content.host.isNativeAttachmentInputAvailable &&
                !content.isAttachmentPending && presentation == ComposerPresentation.Compact &&
                !support?.imageMediaTypes.isNullOrEmpty()
        },
        onAttachPage = content.host.onAttachPage?.let { attach -> { attach(content.pane.id) } }?.takeIf {
            content.canAddAttachments() && !content.isAttachmentPending &&
                "text/markdown" in support?.mediaTypes.orEmpty()
        },
        onResearch = onOpenResearch?.let { open -> { open(content.settings.modelId) } }
            ?.takeIf { content.isResearchAvailable },
    )
}

/** Ordinary native chats show the profile choice; demo preferences and organism decisions keep their own modes. */
private fun PaneContent.usesProfileSecurity(hasRunPreferences: Boolean): Boolean =
    !hasRunPreferences && organism == null && session?.isOrganism != true &&
        !pane.isOrganism && models.any { it.id == settings.modelId }

@Composable
private fun StudioComposerContext(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    presentation: ComposerPresentation,
    onContextWidthChange: ((Int) -> Unit)?,
) {
    val contextPadding = HbTheme.spacing.m * 2
    val measureWidth = if (onContextWidthChange == null) {
        Modifier
    } else {
        Modifier.layout { measurable, constraints ->
            val singleLineWidth = measurable.maxIntrinsicWidth(Constraints.Infinity) + contextPadding.roundToPx()
            onContextWidthChange(singleLineWidth)
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }
    HbFlowRow(Modifier.fillMaxWidth().then(measureWidth), gap = HbTheme.spacing.s) {
        StudioComposerIdentity(content, onIntent, presentation)
        content.app?.let { StudioAppChip(it, content.pane.id, onIntent) }
        StudioComposerModes(content, onIntent)
    }
}

/** Identity wraps its controls so modes can share the line; long names yield space to the harness touch target. */
@Composable
private fun StudioComposerIdentity(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    presentation: ComposerPresentation,
) {
    val pane = content.pane
    HbRow(gap = HbTheme.spacing.m) {
        if (pane.sessionId == null) {
            ContextTray(
                pane,
                content.project,
                content.projects,
                onIntent,
                modifier = Modifier.weight(1f, fill = false),
                isProjectAddingAvailable = content.isProjectAddingAvailable,
            )
        } else {
            content.project?.let {
                HbRow(
                    Modifier.weight(1f, fill = false)
                        .widthIn(max = HbTheme.dimensions.composerLabelMaxWidth)
                        .testTag("session-project-${pane.id}"),
                    gap = HbTheme.spacing.xs,
                ) {
                    if (presentation != ComposerPresentation.Compact) {
                        HbIcon(HbIcons.Folder, null, tint = HbTheme.colors.textSecondary)
                    }
                    HbText(
                        it.name,
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
            }
        }
        StudioHarnessPicker(
            content,
            onIntent,
            modifier = Modifier.weight(1f, fill = false),
            isCompact = presentation == ComposerPresentation.Compact,
        )
        StudioUsage(content, onIntent)
    }
}

@Composable
private fun StudioComposerModes(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val pane = content.pane
    OrganismModeToggle(content, onIntent)
    if (content.worktree != null || content.session?.isWorktree == true) {
        val isSandbox = content.worktree?.isSandbox == true
        HbComposerToggle(
            label = stringResource(if (isSandbox) Res.string.sandbox_mode else Res.string.worktree_mode),
            isChecked = true,
            onCheckedChange = {},
            enabled = false,
            icon = if (isSandbox) HbIcons.Layers else HbIcons.Branch,
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
        NewSessionIsolationToggle(content, onIntent)
    }
}

@Composable
private fun AttachmentActions(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    presentation: ComposerPresentation,
) {
    val support = content.models.firstOrNull { it.id == content.settings.modelId }?.inputSupport
    if (content.host.isNativeAttachmentInputAvailable && !content.isAttachmentPending &&
        !support?.imageMediaTypes.isNullOrEmpty()
    ) {
        HbPasteImageButton(
            stringResource(Res.string.attachments_paste),
            onImage = {
                onIntent(StudioIntent.ImportAttachments(content.pane.id, listOf(NativeAttachmentUi.Image(it))))
            },
            modifier = Modifier.testTag("attachment-paste-${content.pane.id}"),
            isIconOnly = presentation == ComposerPresentation.Medium,
        )
    }
}

@Composable
private fun ComposerEffort(
    content: PaneContent,
    hasDemoPreferences: Boolean,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Organism turns keep their own session configuration; new-chat effort choices would not apply.
    if (content.organism != null) return
    val model = content.models.firstOrNull { it.id == content.settings.modelId }
    if (model != null && model.reasoningEfforts.isNotEmpty()) {
        val selected = if (content.configuration != null) {
            content.settings.nativeEffort
        } else {
            content.settings.engineEfforts[model.id]?.takeIf { it in model.reasoningEfforts }
        }
        EngineEffortMenu(model, selected, onIntent, modifier, content.pane.id, enabled = !content.isSettingPending)
    } else if (hasDemoPreferences) {
        EffortMenu(content.settings.effort, onIntent, content.pane.id, !content.isSettingPending, modifier)
    }
}

/** Scripted approvals are local demo behavior; native permission policies remain engine-owned. */
private fun PaneContent.supportsRunPreferences(): Boolean = models.any { model ->
    model.id == settings.modelId && model.isScripted
}

/** Engines that apply trust levels take the composer's approval mode with every prompt. */
private fun PaneContent.isTrustSupported(): Boolean = models.any { it.id == settings.modelId && it.isTrustSupported }

/**
 * Whether the shown session takes prompts. A completed zygote takes a follow-up; an organism out of view takes
 * its goal again. Both require organic AI to be on. Descendants and judges remain read-only.
 */
private fun PaneContent.takesPrompts(): Boolean = session?.isContinuable != false || (
    session.isOrganism && isOrganismEnabled && subSession == PRIMARY_SUB_SESSION &&
        (organism == null || organism.status == OrganismStatusUi.Completed)
)

/** Running requests retain cancellation; pending permissions block another prompt. */
private fun PaneContent.isComposerEnabled(): Boolean = !pane.isCreating && !isPickingProject && !isStopping &&
    takesPrompts() &&
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
    onIntent: (StudioIntent) -> Unit,
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
                onIntent(StudioIntent.AddProject(pane.id))
            } else {
                onIntent(StudioIntent.SelectProject(pane.id, id.takeIf { it != NO_PROJECT }))
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
    isRememberEnabled: Boolean,
    onFocusInput: () -> Unit,
    approval: ApprovalUi?,
    onDraft: (String) -> Unit,
    onApproval: (ApprovalUi) -> Unit,
    approvalEnabled: Boolean,
    securityLevel: SecurityLevelUi?,
    isTrustSupported: Boolean,
    isHelper: Boolean,
    organism: Boolean?,
    onOrganism: (Boolean) -> Unit,
    worktree: Boolean?,
    onWorktree: (Boolean) -> Unit,
    onAttach: (() -> Unit)?,
    onPasteImage: (() -> Unit)?,
    onAttachPage: (() -> Unit)?,
    onResearch: (() -> Unit)?,
) {
    var isOpen by remember { mutableStateOf(false) }
    val templates = listOf(
        Template("plan", Res.string.template_plan, Res.string.template_plan_prompt),
        Template("tests", Res.string.template_tests, Res.string.template_tests_prompt),
        Template("review", Res.string.template_review, Res.string.template_review_prompt),
    )
    val prompts = templates.associate { it.id to stringResource(it.prompt) }
    // A command, not a template: it starts its own group of the menu.
    val rememberAction = HbComposerAction(
        REMEMBER_ACTION,
        stringResource(Res.string.template_remember),
        sectionLabel = stringResource(Res.string.composer_commands),
    )
    val modes = composerModeActions(organism, worktree)
    val attachments = listOfNotNull(
        onAttachPage?.let { HbComposerAction(PAGE_ACTION, stringResource(Res.string.attachments_page)) },
        onAttach?.let { HbComposerAction(ATTACH_ACTION, stringResource(Res.string.attachments_add)) },
        onPasteImage?.let { HbComposerAction(PASTE_ACTION, stringResource(Res.string.attachments_paste)) },
        onResearch?.let { HbComposerAction(RESEARCH_ACTION, stringResource(Res.string.research_mode)) },
    )
    val actions = attachments + templates.map { HbComposerAction(it.id, stringResource(it.label)) } +
        listOfNotNull(rememberAction.takeIf { isRememberEnabled }) + modes +
        approvalActions(approval, approvalEnabled, securityLevel, isTrustSupported, isHelper)
    HbComposerMenuButton(
        label = stringResource(Res.string.composer_add),
        actions = actions.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { id ->
            if (id == PAGE_ACTION) {
                onAttachPage?.invoke()
            } else if (id == ATTACH_ACTION) {
                onAttach?.invoke()
            } else if (id == PASTE_ACTION) {
                onPasteImage?.invoke()
            } else if (id == RESEARCH_ACTION) {
                onResearch?.invoke()
            } else if (id.startsWith(APPROVAL_PREFIX)) {
                onApproval(ApprovalUi.valueOf(id.removePrefix(APPROVAL_PREFIX)))
            } else if (id == WORKTREE_ACTION) {
                onWorktree(worktree != true)
            } else if (id == ORGANISM_ACTION) {
                onOrganism(organism != true)
            } else if (id == REMEMBER_ACTION) {
                // The command must lead the prompt: templates append, /remember prefixes the typed text.
                onDraft(withRememberCommand(draft))
                onFocusInput()
            } else {
                val prompt = prompts[id].orEmpty()
                onDraft(if (draft.isBlank()) prompt else "${draft.trimEnd()}\n$prompt")
            }
        },
        modifier = Modifier.size(HbTheme.dimensions.touchTarget),
        accessibleLabel = stringResource(Res.string.composer_add),
        icon = HbIcons.Plus,
        style = HbComposerMenuStyle.Circle,
    )
}

@Composable
private fun ModelMenu(
    modelId: String,
    models: ImmutableList<ModelUi>,
    onIntent: (StudioIntent) -> Unit,
    canSelect: Boolean,
    paneId: Int,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
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
        onAction = { onIntent(StudioIntent.SelectModel(it, paneId)) },
        modifier = modifier.testTag("model-chip"),
        accessibleLabel = model?.name ?: stringResource(Res.string.connect_model_hint),
        headerLabel = stringResource(Res.string.model_menu),
        icon = if (isCompact) null else HbIcons.Layers,
        style = HbComposerMenuStyle.Pill,
        enabled = canSelect && models.isNotEmpty(),
    )
}

@Composable
private fun EffortMenu(
    effort: EffortUi,
    onIntent: (StudioIntent) -> Unit,
    paneId: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var isOpen by remember { mutableStateOf(false) }
    val actions = EffortUi.entries.map {
        HbComposerAction(it.name, effortLabel(it), isSelected = it == effort)
    }.toImmutableList()
    HbComposerMenuButton(
        label = effortLabel(effort),
        actions = actions,
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { onIntent(StudioIntent.SelectEffort(EffortUi.valueOf(it), paneId)) },
        modifier = modifier.testTag("effort-chip"),
        accessibleLabel = stringResource(Res.string.composer_effort_menu),
        headerLabel = stringResource(Res.string.composer_effort_menu),
        icon = HbIcons.Sparkles,
        style = HbComposerMenuStyle.AccentPill,
        enabled = enabled,
    )
}

@Composable
private fun approvalActions(
    selected: ApprovalUi?,
    enabled: Boolean,
    securityLevel: SecurityLevelUi?,
    isTrustSupported: Boolean,
    isHelper: Boolean,
): List<HbComposerAction> {
    if (securityLevel != null) {
        return listOf(
            HbComposerAction(
                id = "profile-security",
                label = stringResource(
                    when (securityLevel) {
                        SecurityLevelUi.Ask -> Res.string.security_ask
                        SecurityLevelUi.Automatic -> Res.string.approval_safety
                        SecurityLevelUi.Trust -> Res.string.security_trust
                    },
                ),
                supportingText = stringResource(
                    when {
                        isHelper -> Res.string.security_helper_hint

                        securityLevel != SecurityLevelUi.Automatic && !isTrustSupported ->
                            Res.string.security_unsupported

                        else -> Res.string.security_settings_hint
                    },
                ),
                sectionLabel = stringResource(Res.string.approval_menu),
                isEnabled = false,
            ),
        )
    }
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
internal fun approvalLabel(approval: ApprovalUi): String = stringResource(
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

private const val ATTACH_ACTION = "attach-file"
private const val PASTE_ACTION = "paste-image"
private const val RESEARCH_ACTION = "research"
private const val REMEMBER_ACTION = "remember"
private const val WORKTREE_ACTION = "worktree-mode"
private const val ORGANISM_ACTION = "organism"

private const val NO_PROJECT = "no-project"
private const val ADD_PROJECT = "add-project"
private const val APPROVAL_PREFIX = "approval:"

private fun PaneContent.canAddAttachments(): Boolean {
    val support = models.firstOrNull { it.id == settings.modelId }?.inputSupport
    return isAttachmentsEnabled && !pane.isCreating && takesPrompts() && session?.isRunning != true &&
        !support?.mediaTypes.isNullOrEmpty()
}

private const val PAGE_ACTION = "attach-page"

/** The default action offers a sandbox until a plain worktree has been explicitly selected. */
@Composable
private fun NewSessionIsolationToggle(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val pane = content.pane
    val isSandbox = content.isSandboxAvailable && (!pane.isWorktree || pane.isSandbox)
    HbComposerToggle(
        label = stringResource(if (isSandbox) Res.string.sandbox_mode else Res.string.worktree_mode),
        isChecked = pane.isWorktree,
        onCheckedChange = { onIntent(StudioIntent.SelectWorktree(pane.id, it, isSandbox)) },
        modifier = Modifier.testTag("worktree-mode-${pane.id}"),
        enabled = !pane.isCreating,
        icon = if (isSandbox) HbIcons.Layers else HbIcons.Branch,
    )
}

/** Modes are selectable only before provisioning; a selected worktree appears as the active chip. */
private fun PaneContent.worktreeMenuSelection(): Boolean? = (pane.isWorktree && !pane.isSandbox).takeIf {
    pane.sessionId == null && !pane.isCreating && isSandboxAvailable
}

@Composable
private fun composerModeActions(organism: Boolean?, worktree: Boolean?): List<HbComposerAction> {
    // A mode of the new chat, offered only before its first message.
    val organismAction = organism?.let {
        HbComposerAction(
            ORGANISM_ACTION,
            stringResource(Res.string.organism_mode),
            supportingText = stringResource(Res.string.organism_mode_hint),
            sectionLabel = stringResource(Res.string.organism_menu_section),
            isSelected = it,
        )
    }
    // Both modes share one section, so only its first item carries the header.
    val worktreeAction = worktree?.let {
        HbComposerAction(
            WORKTREE_ACTION,
            stringResource(Res.string.worktree_mode),
            sectionLabel = stringResource(Res.string.organism_menu_section).takeIf { organismAction == null },
            isSelected = it,
        )
    }
    return listOfNotNull(organismAction, worktreeAction)
}
