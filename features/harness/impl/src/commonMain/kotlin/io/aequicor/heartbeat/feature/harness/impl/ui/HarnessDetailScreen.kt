package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.AttachmentUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.DetailErrorUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailAction
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailModel
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailNavigation
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailState
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemRowUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemStatusUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.MetaDraftUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProjectChoiceUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunStatusUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ScopeUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_attachments
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_attachments_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_cancel
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_conflict
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_delete
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_delete_text
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_delete_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_detach
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_dismiss
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_edit
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_enabled
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_field_description
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_field_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_kind_instruction
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_kind_script
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_kind_skill
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_kind_template
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_kind_workflow
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_meta_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_no_projects
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_cancel
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_status_cancelled
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_status_cancelling
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_status_completed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_status_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_status_running
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_steps
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_waiting
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_runs
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_attached
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_attached_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_profile
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_profile_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_projects_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_projects_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_status_activating
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_status_active
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_status_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_status_failed_disabled
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_status_unsupported
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_tools
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_tools_summary
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_use
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** One harness: switch, metadata, scope, connected chats, items, tools and workflow runs. */
@Composable
internal fun HarnessDetailScreen(
    model: HarnessDetailModel,
    navigation: HarnessDetailNavigation,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(navigation)
    val state by produceState(HarnessDetailState(), model) {
        model.store.collect {
            launch {
                actions.collect { action ->
                    when (action) {
                        is HarnessDetailAction.OpenItem -> current.openItem(action.item)
                        HarnessDetailAction.OpenTools -> current.openTools()
                        HarnessDetailAction.Close -> current.close()
                    }
                }
            }
            states.collect { value = it }
        }
    }
    val onBack = navigation.close
    HarnessDetailContent(state, model.store::intent, onBack, modifier)
}

/** Stateless detail screen; edit and delete dialogs open above the list. */
@Composable
internal fun HarnessDetailContent(
    state: HarnessDetailState,
    onIntent: (HarnessDetailIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(state.items) { state.items.groupBy { it.kind }.mapValues { it.value.toImmutableList() } }
    HarnessPane(state.title.ifEmpty { state.name }, "harness-detail", onBack, modifier) {
        state.error?.let { error ->
            item(key = "error") { ErrorBanner(error, onIntent) }
        }
        if (!phaseItems(state.phase)) return@HarnessPane
        item(key = "header") { HeaderSection(state, onIntent) }
        item(key = "scope") { ScopeSection(state.scope, state.projects, onIntent) }
        if (state.attachments.isNotEmpty()) {
            item(key = "attachments") { AttachmentsSection(state.attachments, onIntent) }
        }
        ItemKindUi.entries.forEach { kind ->
            val rows = groups[kind] ?: return@forEach
            item(key = "kind-${kind.name}") { ItemsSection(kind, rows, onIntent) }
        }
        item(key = "tools") { ToolsSection(state, onIntent) }
        if (state.runs.isNotEmpty()) {
            item(key = "runs") { RunsSection(state.runs, onIntent) }
        }
    }
    state.meta?.let { MetaDialog(it, onIntent) }
    if (state.isDeleting) DeleteDialog(state.title.ifEmpty { state.name }, onIntent)
}

@Composable
private fun ErrorBanner(error: DetailErrorUi, onIntent: (HarnessDetailIntent) -> Unit, modifier: Modifier = Modifier) {
    val message = when (error) {
        DetailErrorUi.SaveFailed -> Res.string.harness_save_failed
        DetailErrorUi.Conflict -> Res.string.harness_conflict
    }
    HbBanner(stringResource(message), modifier.testTag("harness-detail-error")) {
        HbButton(
            stringResource(Res.string.harness_dismiss),
            { onIntent(HarnessDetailIntent.DismissError) },
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun HeaderSection(
    state: HarnessDetailState,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        state.title.ifEmpty { state.name },
        modifier.testTag("harness-detail-header"),
        description = state.description,
    ) {
        HbSettingsRow(stringResource(Res.string.harness_enabled), Modifier.fillMaxWidth(), description = state.name) {
            HbSwitch(
                state.isEnabled,
                { onIntent(HarnessDetailIntent.SetEnabled(it)) },
                stringResource(Res.string.harness_use, state.title.ifEmpty { state.name }),
                Modifier.testTag("harness-detail-enabled"),
            )
        }
        HbRow(gap = HbTheme.spacing.s) {
            HbButton(
                stringResource(Res.string.harness_edit),
                { onIntent(HarnessDetailIntent.EditMeta) },
                Modifier.testTag("harness-detail-edit"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.harness_delete),
                { onIntent(HarnessDetailIntent.Delete) },
                Modifier.testTag("harness-detail-delete"),
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ScopeSection(
    scope: ScopeUi,
    projects: ImmutableList<ProjectChoiceUi>,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.harness_scope), modifier.selectableGroup().testTag("harness-scope")) {
        ScopeUi.entries.forEachIndexed { index, option ->
            if (index > 0) HbDivider()
            val isSelected = option == scope
            HbSettingsRow(
                stringResource(option.title()),
                Modifier.fillMaxWidth().semantics { selected = isSelected }.testTag("harness-scope-${option.name}"),
                description = stringResource(option.hint()),
                onClick = { onIntent(HarnessDetailIntent.SelectScope(option)) },
                enabled = option != ScopeUi.Projects || projects.isNotEmpty(),
                isSelected = isSelected,
                role = Role.RadioButton,
            ) {
                if (isSelected) HbIcon(HbIcons.Check, contentDescription = null)
            }
        }
        if (projects.isEmpty()) {
            HbText(
                stringResource(Res.string.harness_no_projects),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        } else if (scope == ScopeUi.Projects) {
            projects.forEach { project ->
                key(project.key) {
                    HbChoiceRow(
                        project.name,
                        project.isSelected,
                        isMultiple = true,
                        { onIntent(HarnessDetailIntent.ToggleProject(project.key)) },
                        Modifier.fillMaxWidth().testTag("harness-project-${project.key}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: ImmutableList<AttachmentUi>,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.harness_attachments),
        modifier.testTag("harness-attachments"),
        description = stringResource(Res.string.harness_attachments_hint),
    ) {
        attachments.forEachIndexed { index, attachment ->
            key(attachment.key) {
                if (index > 0) HbDivider()
                HbSettingsRow(attachment.label, Modifier.fillMaxWidth()) {
                    HbButton(
                        stringResource(Res.string.harness_detach),
                        { onIntent(HarnessDetailIntent.Detach(attachment.key)) },
                        Modifier.testTag("harness-detach-$index"),
                        style = HbButtonStyle.Ghost,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsSection(
    kind: ItemKindUi,
    rows: ImmutableList<ItemRowUi>,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(kind.title()), modifier.testTag("harness-kind-${kind.name}")) {
        rows.forEachIndexed { index, row ->
            key(row.id) {
                if (index > 0) HbDivider()
                HbSettingsRow(
                    row.name,
                    Modifier.fillMaxWidth().testTag("harness-item-${row.id}"),
                    description = row.description.ifEmpty { null },
                    onClick = { onIntent(HarnessDetailIntent.OpenItem(row.id)) },
                ) {
                    HbRow(gap = HbTheme.spacing.s) {
                        row.status.label()?.let { (text, tone) -> HbBadge(stringResource(text), tone = tone) }
                        HbSwitch(
                            row.isEnabled,
                            { onIntent(HarnessDetailIntent.SetItemEnabled(row.id, it)) },
                            stringResource(Res.string.harness_use, row.name),
                            Modifier.testTag("harness-item-enabled-${row.id}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsSection(
    state: HarnessDetailState,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.harness_tools), modifier) {
        HbSettingsRow(
            stringResource(Res.string.harness_tools),
            Modifier.fillMaxWidth().testTag("harness-detail-tools"),
            description = stringResource(Res.string.harness_tools_summary, state.hostedOff, state.nativeSwitches),
            onClick = { onIntent(HarnessDetailIntent.OpenTools) },
        ) {
            HbIcon(HbIcons.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun RunsSection(
    runs: ImmutableList<RunUi>,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.harness_runs), modifier.testTag("harness-runs")) {
        runs.forEachIndexed { index, run ->
            key(run.id) {
                if (index > 0) HbDivider()
                val details = buildList {
                    add(stringResource(Res.string.harness_run_steps, run.steps))
                    if (run.awaitingPermissions > 0) {
                        add(stringResource(Res.string.harness_run_waiting, run.awaitingPermissions))
                    }
                }.joinToString(" · ")
                HbSettingsRow(run.workflow, Modifier.fillMaxWidth().testTag("harness-run-${run.id}"), details) {
                    HbRow(gap = HbTheme.spacing.s) {
                        val (text, tone) = run.status.label()
                        HbBadge(stringResource(text), tone = tone)
                        if (run.status == RunStatusUi.Running) {
                            HbButton(
                                stringResource(Res.string.harness_run_cancel),
                                { onIntent(HarnessDetailIntent.CancelRun(run.id)) },
                                Modifier.testTag("harness-run-cancel-${run.id}"),
                                style = HbButtonStyle.Ghost,
                                size = HbButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaDialog(draft: MetaDraftUi, onIntent: (HarnessDetailIntent) -> Unit, modifier: Modifier = Modifier) {
    HbDialog(
        stringResource(Res.string.harness_meta_title),
        { onIntent(HarnessDetailIntent.CancelMeta) },
        modifier.testTag("harness-meta-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.harness_cancel),
                { onIntent(HarnessDetailIntent.CancelMeta) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.harness_save),
                { onIntent(HarnessDetailIntent.SaveMeta) },
                Modifier.testTag("harness-meta-save"),
                enabled = draft.isValid,
            )
        },
    ) {
        val title = stringResource(Res.string.harness_field_title)
        val description = stringResource(Res.string.harness_field_description)
        HbTextField(
            draft.title,
            { onIntent(HarnessDetailIntent.ChangeMeta(it, draft.description)) },
            Modifier.fillMaxWidth().testTag("harness-meta-title"),
            placeholder = title,
        )
        HbTextField(
            draft.description,
            { onIntent(HarnessDetailIntent.ChangeMeta(draft.title, it)) },
            Modifier.fillMaxWidth().testTag("harness-meta-description"),
            placeholder = description,
        )
    }
}

@Composable
private fun DeleteDialog(title: String, onIntent: (HarnessDetailIntent) -> Unit, modifier: Modifier = Modifier) {
    HbDialog(
        stringResource(Res.string.harness_delete_title),
        { onIntent(HarnessDetailIntent.CancelDelete) },
        modifier.testTag("harness-delete-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.harness_cancel),
                { onIntent(HarnessDetailIntent.CancelDelete) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.harness_delete),
                { onIntent(HarnessDetailIntent.ConfirmDelete) },
                Modifier.testTag("harness-delete-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.harness_delete_text, title))
    }
}

internal fun ItemKindUi.title(): StringResource = when (this) {
    ItemKindUi.Instruction -> Res.string.harness_kind_instruction
    ItemKindUi.Skill -> Res.string.harness_kind_skill
    ItemKindUi.Template -> Res.string.harness_kind_template
    ItemKindUi.Script -> Res.string.harness_kind_script
    ItemKindUi.Workflow -> Res.string.harness_kind_workflow
}

private fun ScopeUi.title(): StringResource = when (this) {
    ScopeUi.Attached -> Res.string.harness_scope_attached
    ScopeUi.Profile -> Res.string.harness_scope_profile
    ScopeUi.Projects -> Res.string.harness_scope_projects_title
}

private fun ScopeUi.hint(): StringResource = when (this) {
    ScopeUi.Attached -> Res.string.harness_scope_attached_hint
    ScopeUi.Profile -> Res.string.harness_scope_profile_hint
    ScopeUi.Projects -> Res.string.harness_scope_projects_hint
}

private fun ItemStatusUi.label(): Pair<StringResource, HbTone>? = when (this) {
    ItemStatusUi.None, ItemStatusUi.Disabled -> null
    ItemStatusUi.Unsupported -> Res.string.harness_status_unsupported to HbTone.Neutral
    ItemStatusUi.Activating -> Res.string.harness_status_activating to HbTone.Neutral
    ItemStatusUi.Active -> Res.string.harness_status_active to HbTone.Success
    ItemStatusUi.Failed -> Res.string.harness_status_failed to HbTone.Warning
    ItemStatusUi.FailedDisabled -> Res.string.harness_status_failed_disabled to HbTone.Danger
}

private fun RunStatusUi.label(): Pair<StringResource, HbTone> = when (this) {
    RunStatusUi.Running -> Res.string.harness_run_status_running to HbTone.Brand
    RunStatusUi.Cancelling -> Res.string.harness_run_status_cancelling to HbTone.Neutral
    RunStatusUi.Completed -> Res.string.harness_run_status_completed to HbTone.Success
    RunStatusUi.Failed -> Res.string.harness_run_status_failed to HbTone.Danger
    RunStatusUi.Cancelled -> Res.string.harness_run_status_cancelled to HbTone.Neutral
}

private val previewState = HarnessDetailState(
    phase = PhaseUi.Ready,
    name = "compose_ui",
    title = "Compose UI",
    description = "Native Compose screens with the design system",
    isEnabled = true,
    scope = ScopeUi.Projects,
    projects = persistentListOf(ProjectChoiceUi("p", "Heartbeat", isSelected = true)),
    attachments = persistentListOf(AttachmentUi("a", "claude · 7f3a91c2")),
    items = persistentListOf(
        ItemRowUi("1", "tokens", ItemKindUi.Instruction, "Use only tokens", true, ItemStatusUi.None),
        ItemRowUi("2", "verify", ItemKindUi.Script, "Blocks destructive commands", true, ItemStatusUi.Active),
        ItemRowUi("3", "screen", ItemKindUi.Workflow, "Design and implement", true, ItemStatusUi.Failed),
    ),
    runs = persistentListOf(RunUi("wf_1", "screen", RunStatusUi.Running, 1, 2)),
    hostedOff = 1,
    nativeSwitches = 2,
)

@Preview
@Composable
private fun HarnessDetailLightPreview() {
    HbTheme(darkTheme = false) { HarnessDetailContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun HarnessDetailDarkPreview() {
    HbTheme(darkTheme = true) { HarnessDetailContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun HarnessDetailNotFoundPreview() {
    HbTheme(darkTheme = false) { HarnessDetailContent(HarnessDetailState(phase = PhaseUi.NotFound), {}, {}) }
}
