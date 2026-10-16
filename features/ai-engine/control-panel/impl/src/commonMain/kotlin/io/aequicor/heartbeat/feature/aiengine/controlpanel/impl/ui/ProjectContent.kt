package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectTrustUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_projects
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_projects_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_project_files
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_project_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_project_trust
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_project_trust_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_trust_content
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_trust_trusted
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_trust_undecided
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The saved projects of the overview, each opening the configuration in its files once no operation runs. */
@Composable
internal fun ProjectsSection(
    projects: ImmutableList<ProjectItemUi>,
    isEnabled: Boolean,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.control_panel_projects),
        modifier.testTag("engine-control-projects"),
        description = stringResource(Res.string.control_panel_projects_description),
    ) {
        projects.forEachIndexed { index, project ->
            if (index > 0) HbDivider()
            HbSettingsRow(
                project.title,
                Modifier.testTag("engine-control-project:${project.id}"),
                onClick = { onIntent(ProjectIntent.Open(project.id)) },
                enabled = isEnabled,
            ) {
                if (project.trust != ProjectTrustUi.Undecided) {
                    HbBadge(
                        stringResource(project.trust.label()),
                        tone = if (project.trust == ProjectTrustUi.Trusted) HbTone.Success else HbTone.Neutral,
                    )
                }
                HbIcon(HbIcons.ChevronRight, contentDescription = null)
            }
        }
    }
}

/**
 * An open project in place of the overview: its trust, the fields of its files with the profile values as defaults
 * and its files; an open file replaces the project. Back returns to the project, then to the overview.
 */
@Composable
internal fun ProjectScreen(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = state.project
    val file = project?.form?.files?.firstOrNull { it.id == state.projectFile }
    val title = file?.fileName ?: project?.title ?: state.openProject.orEmpty()
    val onConfig = { intent: ConfigIntent -> intent.forProject()?.let(onIntent) ?: Unit }
    val form = project?.form?.takeIf { it.isDirty || it.hasConflict }
    ControlColumn(
        title,
        { onIntent(EngineControlPanelIntent.Back) },
        modifier.testTag("engine-control-project"),
        pinned = form?.let { { ConfigSaveBar(it, state.isSaving, onConfig, isBusy = state.isBusy) } },
    ) {
        state.failure?.let { ControlFailureBanner(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
        when {
            project == null -> HbLoadingState(stringResource(Res.string.control_project_loading))

            file != null -> ConfigFileContent(file, project.form, state.isSaving, onConfig)

            else -> {
                ProjectContent(project, state.isSaving, state.isBusy, onIntent, onConfig)
                if (project.form.pages.any { it.hasResources }) {
                    ResourceContent(
                        state.resources,
                        state.isReadingResources,
                        !state.isBusy,
                        { onIntent(EngineControlPanelIntent.RefreshResources) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectContent(
    project: ProjectFormUi,
    isSaving: Boolean,
    isBusy: Boolean,
    onIntent: (EngineControlPanelIntent) -> Unit,
    onConfig: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = project.form
    val isEditable = !isSaving && !form.isSubmitted
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxl) {
        project.trust?.let { TrustRow(it, enabled = !isBusy, { trust -> onIntent(ProjectIntent.SetTrust(trust)) }) }
        form.pages.forEach { page ->
            HbText(page.title.text(), Modifier.semantics { heading() }, style = HbTheme.typography.title)
            ConfigPageSections(page, isEditable, onConfig)
        }
        if (form.files.isNotEmpty()) {
            HbSettingsSection(stringResource(Res.string.control_project_files), Modifier.testTag("project-files")) {
                form.files.forEachIndexed { index, file ->
                    if (index > 0) HbDivider()
                    HbSettingsRow(
                        file.title.text(),
                        Modifier.testTag("project-file:${file.id}"),
                        description = file.fileName,
                        onClick = { onIntent(ProjectIntent.OpenFile(file.id)) },
                    ) {
                        HbIcon(HbIcons.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustRow(
    trust: ProjectTrustUi,
    enabled: Boolean,
    onTrust: (ProjectTrustUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.control_project_trust)
    val titles = ProjectTrustUi.entries.associateWith { stringResource(it.label()) }
    val items = remember(trust, titles) {
        ProjectTrustUi.entries.map {
            HbMenuItem(
                it.name,
                titles.getValue(it),
                isChecked = it == trust,
            )
        }.toImmutableList()
    }
    HbSettingsRow(
        label,
        modifier.testTag("project-trust"),
        description = stringResource(Res.string.control_project_trust_description),
    ) {
        HbMenuButton(
            HbIcons.Shield,
            label,
            items,
            isExpanded,
            { isExpanded = it },
            { id -> ProjectTrustUi.entries.firstOrNull { it.name == id }?.let(onTrust) },
            Modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth).testTag("project-trust-choice"),
            enabled = enabled,
            label = stringResource(trust.label()),
        )
    }
}

private fun ProjectTrustUi.label(): StringResource = when (this) {
    ProjectTrustUi.Undecided -> Res.string.control_trust_undecided
    ProjectTrustUi.Content -> Res.string.control_trust_content
    ProjectTrustUi.Trusted -> Res.string.control_trust_trusted
}

/** The project request of a configuration editor's intent; discarding reads the files anew. */
private fun ConfigIntent.forProject(): ProjectIntent? = when (this) {
    is ConfigIntent.EditField -> ProjectIntent.EditField(field, input)
    is ConfigIntent.EditFile -> ProjectIntent.EditFile(file, text)
    ConfigIntent.SaveConfig -> ProjectIntent.Save
    ConfigIntent.DiscardConfig -> ProjectIntent.Reload
    is ConfigIntent.OpenPage, ConfigIntent.ClosePage -> null
}
