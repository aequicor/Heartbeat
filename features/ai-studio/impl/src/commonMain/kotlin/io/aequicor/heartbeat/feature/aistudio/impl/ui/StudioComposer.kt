package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_placeholder
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.connect_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.environment_cloud
import io.aequicor.heartbeat.feature.aistudio.impl.resources.environment_local
import io.aequicor.heartbeat.feature.aistudio.impl.resources.model_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.no_project
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests_prompt
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
) {
    val pane = content.pane
    val session = content.session
    val draft = content.draft
    val settings = content.settings
    HbChatComposer(
        value = draft,
        onValueChange = { onIntent(AiStudioScreenIntent.DraftChanged(pane.id, it)) },
        onSend = { onIntent(AiStudioScreenIntent.Submit(pane.id)) },
        onStop = { session?.let { onIntent(AiStudioScreenIntent.Stop(it.id)) } },
        sendLabel = stringResource(Res.string.composer_send),
        stopLabel = stringResource(Res.string.composer_stop),
        modifier = modifier.testTag("composer-${pane.id}"),
        inputMaxHeight = if (isCompact) {
            HbTheme.dimensions.composerMinHeight
        } else {
            HbTheme.dimensions.composerMaxHeight
        },
        placeholder = stringResource(Res.string.composer_placeholder),
        isStreaming = session?.isRunning == true,
        enabled =
            !pane.isCreating && !content.isPickingProject && !content.isStopping && session?.isContinuable != false &&
                (session?.isRunning != true || content.isStoppable) &&
                // A pending permission must be answered before another prompt can be sent.
                (
                    session?.isRunning == true ||
                        (content.permissions.isEmpty() && content.models.any { it.id == settings.modelId })
                ),
        leadingContent = { TemplatesMenu(draft) { onIntent(AiStudioScreenIntent.DraftChanged(pane.id, it)) } },
        trailingContent = {
            ModelMenu(
                settings.modelId,
                content.models,
                onIntent,
                canSelect = session?.modelId == null || (content.project != null && !session.isRunning),
            )
        },
    )
}

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
        listOf(
            HbMenuItem(ADD_PROJECT, stringResource(Res.string.project_add), HbIcons.Plus, isGroupStart = true),
        )
    } else {
        emptyList()
    }
    val items = (
        projects.map { HbMenuItem(it.id, it.name, HbIcons.Folder, isChecked = it.id == project?.id) } +
            HbMenuItem(NO_PROJECT, noProject, HbIcons.Chat, isChecked = project == null, isGroupStart = true) + add
    ).toImmutableList()
    HbFlowRow(modifier.testTag("context-tray-${pane.id}"), gap = HbTheme.spacing.xs) {
        Box {
            HbChip(
                label = project?.name ?: noProject,
                icon = HbIcons.Folder,
                onClick = { isOpen = !isOpen },
                modifier = Modifier.testTag("project-chip-${pane.id}"),
                accessibleLabel = "${stringResource(Res.string.project_menu)}: ${project?.name ?: noProject}",
            )
            HbMenu(
                items = items,
                isExpanded = isOpen,
                onDismiss = { isOpen = false },
                onItem = { id ->
                    if (id == ADD_PROJECT) {
                        onIntent(AiStudioScreenIntent.AddProject(pane.id))
                    } else {
                        onIntent(AiStudioScreenIntent.SelectProject(pane.id, id.takeIf { it != NO_PROJECT }))
                    }
                },
                label = stringResource(Res.string.project_menu),
            )
        }
        project?.let { ProjectDetails(it) }
    }
}

@Composable
private fun ProjectDetails(project: ProjectUi) {
    HbChip(
        label = stringResource(
            when (project.environment) {
                EnvironmentUi.Local -> Res.string.environment_local
                EnvironmentUi.Cloud -> Res.string.environment_cloud
            },
        ),
        icon = if (project.environment == EnvironmentUi.Local) HbIcons.Laptop else HbIcons.Cloud,
    )
    if (project.branch.isNotBlank()) HbChip(label = project.branch, icon = HbIcons.Branch)
}

@Composable
private fun TemplatesMenu(draft: String, onDraft: (String) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    val templates = listOf(
        Template("plan", Res.string.template_plan, Res.string.template_plan_prompt),
        Template("tests", Res.string.template_tests, Res.string.template_tests_prompt),
        Template("review", Res.string.template_review, Res.string.template_review_prompt),
    )
    val prompts = templates.associate { it.id to stringResource(it.prompt) }
    HbComposerMenuButton(
        label = stringResource(Res.string.composer_add),
        actions = templates.map { HbComposerAction(it.id, stringResource(it.label)) }.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = { id ->
            val prompt = prompts[id].orEmpty()
            onDraft(if (draft.isBlank()) prompt else "${draft.trimEnd()}\n$prompt")
        },
        accessibleLabel = stringResource(Res.string.composer_add),
        icon = HbIcons.Plus,
    )
}

@Composable
private fun ModelMenu(
    modelId: String,
    models: ImmutableList<ModelUi>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    canSelect: Boolean,
) {
    var isOpen by remember { mutableStateOf(false) }
    val model = models.firstOrNull { it.id == modelId }
    Box {
        HbChip(
            label = model?.name ?: stringResource(Res.string.connect_model_hint),
            icon = HbIcons.Sparkles,
            onClick = if (canSelect) ({ isOpen = !isOpen }) else null,
            modifier = Modifier.testTag("model-chip"),
        )
        HbMenu(
            items = models.map { HbMenuItem(it.id, it.name, isChecked = it.id == modelId) }.toImmutableList(),
            isExpanded = isOpen,
            onDismiss = { isOpen = false },
            onItem = { onIntent(AiStudioScreenIntent.SelectModel(it)) },
            label = stringResource(Res.string.model_menu),
        )
    }
}

private data class Template(val id: String, val label: StringResource, val prompt: StringResource)

private const val NO_PROJECT = "no-project"
private const val ADD_PROJECT = "add-project"
