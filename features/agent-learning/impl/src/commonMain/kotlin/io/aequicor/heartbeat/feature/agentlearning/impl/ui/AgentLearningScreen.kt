package io.aequicor.heartbeat.feature.agentlearning.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningModel
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningScreenIntent
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningScreenState
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ApprovalUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.DraftUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.InstructionUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.KindUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.LearningErrorUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ProjectFilterUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ProjectUi
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.Res
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_all
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_all_hint
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_ask
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_ask_hint
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_automatic
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_approval_automatic_hint
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_back
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_cancel
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_collapsed
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_counter
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_delete
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_delete_text
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_delete_title
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_dismiss
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_edit
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_edit_rejected
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_edit_title
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_empty
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_empty_filtered
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_expanded
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_field_content
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_field_description
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_field_title
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_filter
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_filter_all
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_filter_menu
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_filter_none
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_load_failed
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_loading
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_project_removed
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_retry
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_save
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_save_failed
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_section_approval
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_section_approval_hint
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_section_general
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_section_model
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_section_skill
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_show_all
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_title
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_use
import io.aequicor.heartbeat.feature.agentlearning.impl.resources.learning_when
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The registry of learned instructions: approval level, project filter and the instructions by kind. */
@Composable
internal fun AgentLearningScreen(model: AgentLearningModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(AgentLearningScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    AgentLearningContent(state, model.store::intent, onBack, modifier)
}

/** Stateless registry screen; dialogs for an edit or a removal open above the list. */
@Composable
internal fun AgentLearningContent(
    state: AgentLearningScreenState,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val groups = remember(state.instructions, state.filter) {
        state.visible.groupBy { it.kind }.mapValues { it.value.toImmutableList() }
    }
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("agent-learning"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.learning_title),
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.learning_back),
                        onBack,
                        Modifier.testTag("agent-learning-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(
                    max = HbTheme.dimensions.settingsMaxWidth,
                ).fillMaxSize().testTag("agent-learning-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                registryItems(state, groups, onIntent)
            }
        }
    }
    state.draft?.let { EditDialog(it, state.error == LearningErrorUi.EditRejected, onIntent) }
    state.instructions.firstOrNull { it.id == state.deleting }?.let { DeleteDialog(it, onIntent) }
}

/** Approval level, a load or save problem, then the filter and the instructions grouped by kind. */
private fun LazyListScope.registryItems(
    state: AgentLearningScreenState,
    groups: Map<KindUi, ImmutableList<InstructionUi>>,
    onIntent: (AgentLearningScreenIntent) -> Unit,
) {
    item(key = "approval") { ApprovalSection(state.approval.takeIf { state.isLoaded }, onIntent) }
    state.error?.takeUnless { it == LearningErrorUi.EditRejected && state.draft != null }?.let { error ->
        item(key = "error") { ErrorBanner(error, onIntent) }
    }
    if (!state.isLoaded) {
        if (state.error != LearningErrorUi.LoadFailed) {
            item(key = "loading") { HbLoadingState(stringResource(Res.string.learning_loading)) }
        }
        return
    }
    item(key = "filter") { FilterRow(state.projects, state.filter, onIntent) }
    if (groups.isEmpty()) {
        item(key = "empty") { EmptyRegistry(state.filter, onIntent) }
    }
    KindUi.entries.forEach { kind ->
        val rows = groups[kind] ?: return@forEach
        item(key = "kind-${kind.name}") {
            InstructionsSection(kind, rows, state.projects, state.expanded, onIntent)
        }
    }
}

@Composable
private fun ApprovalSection(
    approval: ApprovalUi?,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.learning_section_approval),
        modifier.testTag("agent-learning-approval"),
        description = stringResource(Res.string.learning_section_approval_hint),
    ) {
        ApprovalUi.entries.forEachIndexed { index, level ->
            if (index > 0) HbDivider()
            val isSelected = level == approval
            HbSettingsRow(
                stringResource(level.title()),
                Modifier.fillMaxWidth().semantics { selected = isSelected }
                    .testTag("agent-learning-approval-${level.name}"),
                description = stringResource(level.hint()),
                onClick = { onIntent(AgentLearningScreenIntent.SelectApproval(level)) },
                enabled = approval != null,
                isSelected = isSelected,
            ) {
                if (isSelected) HbIcon(HbIcons.Check, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: LearningErrorUi,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbBanner(stringResource(error.message()), modifier.testTag("agent-learning-error")) {
        val (label, intent) = if (error == LearningErrorUi.LoadFailed) {
            stringResource(Res.string.learning_retry) to AgentLearningScreenIntent.Reload
        } else {
            stringResource(Res.string.learning_dismiss) to AgentLearningScreenIntent.DismissError
        }
        HbButton(
            label,
            { onIntent(intent) },
            Modifier.testTag("agent-learning-error-action"),
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun EmptyRegistry(
    filter: ProjectFilterUi,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filter == ProjectFilterUi.All) {
        HbEmptyState(stringResource(Res.string.learning_empty), modifier.testTag("agent-learning-empty"))
    } else {
        HbEmptyState(
            stringResource(Res.string.learning_empty_filtered),
            modifier.testTag("agent-learning-empty"),
            action = {
                HbButton(
                    stringResource(Res.string.learning_show_all),
                    { onIntent(AgentLearningScreenIntent.SelectFilter(ProjectFilterUi.All)) },
                    Modifier.testTag("agent-learning-show-all"),
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            },
        )
    }
}

@Composable
private fun FilterRow(
    projects: ImmutableList<ProjectUi>,
    filter: ProjectFilterUi,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val all = stringResource(Res.string.learning_filter_all)
    val none = stringResource(Res.string.learning_filter_none)
    val removed = stringResource(Res.string.learning_project_removed)
    val options = remember(projects, all, none) {
        listOf(ALL_ID to (ProjectFilterUi.All to all), NONE_ID to (ProjectFilterUi.WithoutProject to none)) +
            projects.map { PROJECT_ID + it.key to (ProjectFilterUi.Project(it.key) to it.name) }
    }
    // A filtered project that disappeared stays selected and is named as removed.
    val current = options.firstOrNull { it.second.first == filter }?.second?.second ?: removed
    val label = stringResource(Res.string.learning_filter)
    HbSettingsRow(label, modifier.fillMaxWidth().testTag("agent-learning-filter")) {
        Box {
            HbButton(
                current,
                { isExpanded = true },
                Modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth).testTag("agent-learning-filter-button"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbMenu(
                options.map { (id, option) -> HbMenuItem(id, option.second, isChecked = option.first == filter) }
                    .toImmutableList(),
                isExpanded,
                { isExpanded = false },
                { id ->
                    isExpanded = false
                    options.firstOrNull { it.first == id }?.let {
                        onIntent(AgentLearningScreenIntent.SelectFilter(it.second.first))
                    }
                },
                stringResource(Res.string.learning_filter_menu),
            )
        }
    }
}

@Composable
private fun InstructionsSection(
    kind: KindUi,
    rows: ImmutableList<InstructionUi>,
    projects: ImmutableList<ProjectUi>,
    expanded: String?,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(kind.title()), modifier.testTag("agent-learning-kind-${kind.name}")) {
        val removed = stringResource(Res.string.learning_project_removed)
        val none = stringResource(Res.string.learning_filter_none)
        rows.forEachIndexed { index, row ->
            key(row.id) {
                if (index > 0) HbDivider()
                val project = row.projectKey?.let { key -> projects.firstOrNull { it.key == key }?.name ?: removed }
                    ?: none
                InstructionRow(
                    row,
                    listOfNotNull(project, row.model).joinToString(" · "),
                    isExpanded = expanded == row.id,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@Composable
private fun InstructionRow(
    row: InstructionUi,
    scope: String,
    isExpanded: Boolean,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expansion = stringResource(if (isExpanded) Res.string.learning_expanded else Res.string.learning_collapsed)
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.none) {
        HbSettingsRow(
            row.title,
            Modifier.fillMaxWidth().semantics { stateDescription = expansion }
                .testTag("agent-learning-row-${row.id}"),
            description = scope,
            onClick = { onIntent(AgentLearningScreenIntent.ToggleExpanded(row.id)) },
            isSelected = isExpanded,
            leadingContent = {
                HbIcon(if (isExpanded) HbIcons.ChevronDown else HbIcons.ChevronRight, contentDescription = null)
            },
        ) {
            HbSwitch(
                row.isEnabled,
                { onIntent(AgentLearningScreenIntent.SetEnabled(row.id, it)) },
                stringResource(Res.string.learning_use, row.title),
                Modifier.testTag("agent-learning-enabled-${row.id}"),
            )
        }
        if (isExpanded) InstructionDetails(row, onIntent)
    }
}

@Composable
private fun InstructionDetails(
    row: InstructionUi,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("agent-learning-details-${row.id}"),
        gap = HbTheme.spacing.s,
    ) {
        // The row title is cut to one line; the details repeat it in full.
        HbText(row.title, style = HbTheme.typography.label)
        if (row.description.isNotBlank()) {
            HbText(
                stringResource(Res.string.learning_when, row.description),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        HbText(row.content)
        HbRow(gap = HbTheme.spacing.s) {
            HbButton(
                stringResource(Res.string.learning_edit),
                { onIntent(AgentLearningScreenIntent.Edit(row.id)) },
                Modifier.testTag("agent-learning-edit-${row.id}"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.learning_delete),
                { onIntent(AgentLearningScreenIntent.Delete(row.id)) },
                Modifier.testTag("agent-learning-delete-${row.id}"),
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun EditDialog(
    draft: DraftUi,
    isRejected: Boolean,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.learning_edit_title),
        { onIntent(AgentLearningScreenIntent.CancelDraft) },
        modifier.testTag("agent-learning-editor"),
        actions = {
            HbButton(
                stringResource(Res.string.learning_cancel),
                { onIntent(AgentLearningScreenIntent.CancelDraft) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.learning_save),
                { onIntent(AgentLearningScreenIntent.SaveDraft) },
                Modifier.testTag("agent-learning-editor-save"),
                enabled = draft.isValid,
            )
        },
    ) {
        if (isRejected) {
            HbBanner(stringResource(Res.string.learning_edit_rejected), Modifier.testTag("agent-learning-editor-error"))
        }
        DraftField(
            Res.string.learning_field_title,
            draft.title,
            draft.titleLimit,
            { onIntent(AgentLearningScreenIntent.ChangeDraft(it, draft.description, draft.content)) },
            Modifier.testTag("agent-learning-editor-title"),
        )
        if (draft.kind == KindUi.Skill) {
            DraftField(
                Res.string.learning_field_description,
                draft.description,
                draft.descriptionLimit,
                { onIntent(AgentLearningScreenIntent.ChangeDraft(draft.title, it, draft.content)) },
                Modifier.testTag("agent-learning-editor-description"),
            )
        }
        DraftField(
            Res.string.learning_field_content,
            draft.content,
            draft.contentLimit,
            { onIntent(AgentLearningScreenIntent.ChangeDraft(draft.title, draft.description, it)) },
            Modifier.testTag("agent-learning-editor-content"),
            isMultiline = true,
        )
    }
}

/** A visible label with the length counter above an edit field; a multiline field grows up to a bound. */
@Composable
private fun DraftField(
    label: StringResource,
    value: String,
    limit: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isMultiline: Boolean = false,
) {
    val name = stringResource(label)
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(
            stringResource(Res.string.learning_counter, name, value.length, limit),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbTextField(
            value,
            onValueChange,
            if (isMultiline) {
                Modifier.fillMaxWidth().heightIn(
                    min = HbTheme.dimensions.composerMaxHeight,
                    max = HbTheme.dimensions.toolPayloadMaxHeight,
                )
            } else {
                Modifier.fillMaxWidth()
            },
            placeholder = name,
            singleLine = !isMultiline,
        )
    }
}

@Composable
private fun DeleteDialog(
    row: InstructionUi,
    onIntent: (AgentLearningScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.learning_delete_title),
        { onIntent(AgentLearningScreenIntent.CancelDelete) },
        modifier.testTag("agent-learning-delete-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.learning_cancel),
                { onIntent(AgentLearningScreenIntent.CancelDelete) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.learning_delete),
                { onIntent(AgentLearningScreenIntent.ConfirmDelete) },
                Modifier.testTag("agent-learning-delete-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.learning_delete_text, row.title))
    }
}

private fun ApprovalUi.title(): StringResource = when (this) {
    ApprovalUi.Ask -> Res.string.learning_approval_ask
    ApprovalUi.Automatic -> Res.string.learning_approval_automatic
    ApprovalUi.AcceptAll -> Res.string.learning_approval_all
}

private fun ApprovalUi.hint(): StringResource = when (this) {
    ApprovalUi.Ask -> Res.string.learning_approval_ask_hint
    ApprovalUi.Automatic -> Res.string.learning_approval_automatic_hint
    ApprovalUi.AcceptAll -> Res.string.learning_approval_all_hint
}

private fun KindUi.title(): StringResource = when (this) {
    KindUi.General -> Res.string.learning_section_general
    KindUi.Model -> Res.string.learning_section_model
    KindUi.Skill -> Res.string.learning_section_skill
}

private fun LearningErrorUi.message(): StringResource = when (this) {
    LearningErrorUi.LoadFailed -> Res.string.learning_load_failed
    LearningErrorUi.SaveFailed -> Res.string.learning_save_failed
    LearningErrorUi.EditRejected -> Res.string.learning_edit_rejected
}

private const val ALL_ID = "all"
private const val NONE_ID = "none"
private const val PROJECT_ID = "project:"

private val previewState = AgentLearningScreenState(
    isLoaded = true,
    instructions = persistentListOf(
        InstructionUi("1", KindUi.General, "Console encoding", "", "Run chcp 65001 before scripts.", true, "p", null),
        InstructionUi("2", KindUi.Model, "Short answers", "", "Answer in two sentences.", true, null, "claude"),
        InstructionUi("3", KindUi.Skill, "Release", "When releasing", "1. Bump the version.", false, "p", null),
    ),
    projects = persistentListOf(ProjectUi("p", "Heartbeat")),
    expanded = "1",
)

@Preview
@Composable
private fun AgentLearningLightPreview() {
    HbTheme(darkTheme = false) { AgentLearningContent(previewState, {}, null) }
}

@Preview
@Composable
private fun AgentLearningDarkPreview() {
    HbTheme(darkTheme = true) { AgentLearningContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun AgentLearningEmptyPreview() {
    HbTheme(darkTheme = false) { AgentLearningContent(AgentLearningScreenState(isLoaded = true), {}, null) }
}

@Preview
@Composable
private fun AgentLearningFailedPreview() {
    HbTheme(darkTheme = true) {
        AgentLearningContent(AgentLearningScreenState(error = LearningErrorUi.LoadFailed), {}, null)
    }
}
