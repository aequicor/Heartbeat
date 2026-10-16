package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.EntityUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.PickerUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenIntent
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.RemovalUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.SectionUi
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.Res
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_bundle_close
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_bundle_open
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_busy
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_cancel
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_content_line
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_export
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_to_project
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_not_selected
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_confirm_export
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_confirm_save
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_empty
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_export
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_lessons_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_picker_save
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_actions
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_actions_named
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_no_folder
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_syncing
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_units
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_project_unreadable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_projects_empty
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_remove_confirm
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_remove_named
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_remove_text
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_remove_title
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_save_to_project
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_harness
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_interface
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_learning
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_other
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_projects
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_projects_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_transfer
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_section_transfer_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_selected
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_sync
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_sync_named
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The user's projects, their folder state and the units bound there. */
@Composable
internal fun ProjectsSection(
    projects: ImmutableList<ProjectUi>,
    isBusy: Boolean,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.pc_section_projects),
        modifier.testTag("project-config-projects"),
        description = stringResource(Res.string.pc_section_projects_hint),
    ) {
        if (projects.isEmpty()) {
            Caption(stringResource(Res.string.pc_projects_empty), Modifier.padding(HbTheme.spacing.m))
        }
        projects.forEachIndexed { index, project ->
            key(project.key) {
                if (index > 0) HbDivider()
                ProjectRow(project, isBusy, onIntent)
                project.units.forEach { unit ->
                    val section = stringResource(unit.section.title())
                    val title = stringResource(Res.string.pc_content_line, section, unit.name)
                    HbSettingsRow(title, Modifier.fillMaxWidth().padding(start = HbTheme.spacing.l)) {
                        HbIconButton(
                            HbIcons.Trash,
                            stringResource(Res.string.pc_remove_named, unit.name),
                            { onIntent(ProjectConfigScreenIntent.RemoveFromProject(project.key, unit)) },
                            Modifier.testTag("project-config-remove-${project.key}-${unit.ref}"),
                            enabled = !isBusy && !project.isSyncing && !unit.isWriting,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectUi, isBusy: Boolean, onIntent: (ProjectConfigScreenIntent) -> Unit) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val status = when {
        project.isSyncing -> stringResource(Res.string.pc_project_syncing)
        project.isUnreadable -> stringResource(Res.string.pc_project_unreadable)
        !project.hasFolder -> stringResource(Res.string.pc_project_no_folder)
        else -> stringResource(Res.string.pc_project_units, project.units.size)
    }
    val save = stringResource(Res.string.pc_save_to_project)
    val import = stringResource(Res.string.pc_import_to_project)
    val items = remember(save, import) { persistentListOf(HbMenuItem(SAVE, save), HbMenuItem(IMPORT, import)) }
    val actions = stringResource(Res.string.pc_project_actions_named, project.name)
    HbSettingsRow(
        project.name,
        Modifier.fillMaxWidth().testTag("project-config-project-${project.key}"),
        description = status,
        leadingContent = { HbIcon(HbIcons.Folder, contentDescription = null) },
    ) {
        HbIconButton(
            HbIcons.Refresh,
            stringResource(Res.string.pc_sync_named, project.name),
            { onIntent(ProjectConfigScreenIntent.Sync(project.key)) },
            enabled = !project.isSyncing,
            tooltipText = stringResource(Res.string.pc_sync),
        )
        Box {
            HbIconButton(
                HbIcons.More,
                actions,
                { isMenuOpen = true },
                enabled = !isBusy,
                tooltipText = stringResource(Res.string.pc_project_actions),
            )
            HbMenu(
                items,
                isMenuOpen,
                { isMenuOpen = false },
                { id ->
                    isMenuOpen = false
                    when (id) {
                        SAVE -> onIntent(ProjectConfigScreenIntent.OpenPicker(project.key))
                        IMPORT -> onIntent(ProjectConfigScreenIntent.ImportToProject(project.key))
                    }
                },
                actions,
            )
        }
    }
}

/** Confirmation of deleting a unit's files from a project folder. */
@Composable
internal fun RemovalDialog(
    removal: RemovalUi,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.pc_remove_title),
        { onIntent(ProjectConfigScreenIntent.CancelRemove) },
        modifier.testTag("project-config-remove-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.pc_cancel),
                { onIntent(ProjectConfigScreenIntent.CancelRemove) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.pc_remove_confirm),
                { onIntent(ProjectConfigScreenIntent.ConfirmRemove) },
                Modifier.testTag("project-config-remove-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.pc_remove_text, removal.unit.name, removal.projectName))
    }
}

/** Export and import of `.hbai` files and the bundle opened for import. */
@Composable
internal fun TransferSection(
    bundle: String?,
    isBusy: Boolean,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.pc_section_transfer),
        modifier.testTag("project-config-transfer"),
        description = stringResource(Res.string.pc_section_transfer_hint),
    ) {
        if (isBusy) HbLoadingState(stringResource(Res.string.pc_busy), Modifier.testTag("project-config-busy"))
        HbSettingsRow(
            stringResource(Res.string.pc_export),
            Modifier.fillMaxWidth().testTag("project-config-export"),
            onClick = { onIntent(ProjectConfigScreenIntent.OpenPicker(null)) },
            enabled = !isBusy,
            leadingContent = { HbIcon(HbIcons.Upload, contentDescription = null) },
        )
        HbDivider()
        HbSettingsRow(
            stringResource(Res.string.pc_import),
            Modifier.fillMaxWidth().testTag("project-config-import"),
            onClick = { onIntent(ProjectConfigScreenIntent.OpenBundle) },
            enabled = !isBusy,
            leadingContent = { HbIcon(HbIcons.Download, contentDescription = null) },
        )
        bundle?.let { name ->
            HbDivider()
            HbSettingsRow(stringResource(Res.string.pc_bundle_open, name), Modifier.fillMaxWidth()) {
                HbButton(
                    stringResource(Res.string.pc_bundle_close),
                    { onIntent(ProjectConfigScreenIntent.CloseBundle) },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

/** Chooser of entities to save into a project or to export; interfaces follow their harness. */
@Composable
internal fun EntityPicker(
    picker: PickerUi,
    entities: ImmutableList<EntityUi>,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
) {
    val isExport = picker.projectKey == null
    val confirm = if (isExport) Res.string.pc_picker_confirm_export else Res.string.pc_picker_confirm_save
    HbDialog(
        stringResource(if (isExport) Res.string.pc_picker_export else Res.string.pc_picker_save),
        { onIntent(ProjectConfigScreenIntent.CancelPicker) },
        Modifier.testTag("project-config-picker"),
        actions = {
            HbButton(
                stringResource(Res.string.pc_cancel),
                { onIntent(ProjectConfigScreenIntent.CancelPicker) },
                style = HbButtonStyle.Secondary,
            )
            HbButton(
                stringResource(confirm),
                { onIntent(ProjectConfigScreenIntent.ConfirmPicker) },
                Modifier.testTag("project-config-picker-confirm"),
                enabled = picker.selected.isNotEmpty(),
            )
        },
    ) {
        PickerRows(picker, entities, onIntent)
    }
}

@Composable
private fun PickerRows(
    picker: PickerUi,
    entities: ImmutableList<EntityUi>,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
) {
    val choosable = remember(entities) { entities.filter { it.parentId == null } }
    val hasChildren = remember(entities) { entities.any { it.parentId != null } }
    val hasLessons = remember(entities) { entities.any { it.section == SectionUi.Learning } }
    if (choosable.isEmpty()) Caption(stringResource(Res.string.pc_picker_empty))
    if (hasChildren) Caption(stringResource(Res.string.pc_picker_hint))
    // A lesson saved into a project applies only there from then on.
    if (hasLessons && picker.projectKey != null) Caption(stringResource(Res.string.pc_picker_lessons_hint))
    choosable.forEach { entity ->
        key(entity.id) { PickerRow(entity, entity.id in picker.selected, onIntent) }
    }
}

@Composable
private fun PickerRow(entity: EntityUi, isSelected: Boolean, onIntent: (ProjectConfigScreenIntent) -> Unit) {
    val state = stringResource(if (isSelected) Res.string.pc_selected else Res.string.pc_not_selected)
    HbSettingsRow(
        entity.title,
        Modifier.fillMaxWidth()
            .semantics { stateDescription = state }
            .testTag("project-config-pick-${entity.id}"),
        description = stringResource(entity.section.title()),
        onClick = { onIntent(ProjectConfigScreenIntent.Toggle(entity.id)) },
        isSelected = isSelected,
        role = Role.Checkbox,
    ) {
        if (isSelected) HbIcon(HbIcons.Check, contentDescription = null)
    }
}

/** Name of a section as rows show it. */
internal fun SectionUi.title(): StringResource = when (this) {
    SectionUi.Harness -> Res.string.pc_section_harness
    SectionUi.Interface -> Res.string.pc_section_interface
    SectionUi.Learning -> Res.string.pc_section_learning
    SectionUi.Other -> Res.string.pc_section_other
}

private const val SAVE = "save"
private const val IMPORT = "import"
