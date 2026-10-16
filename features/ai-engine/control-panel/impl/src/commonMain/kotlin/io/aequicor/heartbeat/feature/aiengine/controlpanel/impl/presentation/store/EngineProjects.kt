package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Project requests of the panel of [engineId]: local edits of the project draft and operations the machine runs. */
internal class EngineProjects(
    private val machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    private val engineId: EngineId,
) {
    private val log = Log.tag("EngineProjects")

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    suspend fun PanelContext.handle(intent: ProjectIntent) {
        when (intent) {
            is ProjectIntent.Open -> open(intent.project)

            ProjectIntent.Close -> closeProject()

            is ProjectIntent.OpenFile -> updateState { copy(projectFile = intent.file) }

            ProjectIntent.CloseFile -> updateState { closeFile() }

            is ProjectIntent.EditField -> edit { config, project, draft ->
                config.editProject(project, draft, intent.field, intent.input)
            }

            is ProjectIntent.EditFile -> edit { config, project, draft ->
                config.editProjectFile(project, draft, intent.file, intent.text)
            }

            ProjectIntent.Save -> save()

            ProjectIntent.Discard -> updateState { copy(projectDraft = null).reflect(engineId, machine.state.value) }

            ProjectIntent.Reload -> reloadProject()

            is ProjectIntent.SetTrust -> screen().openProject?.let { workspace ->
                apply(ControlOperation.SetTrust(engineId, WorkspaceRef(workspace), intent.trust.toTrust()))
            }
        }
    }

    /** Back to the project from one of its files; a file that parses shows its document again. */
    private fun EngineControlPanelState.closeFile(): EngineControlPanelState {
        val schema = machine.state.value.configOf(engineId)?.schema
        val settled = projectDraft?.let { draft -> schema?.let { draft.copy(draft = it.settleFiles(draft.draft)) } }
        return copy(projectFile = null, projectDraft = settled ?: projectDraft).reflect(engineId, machine.state.value)
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.closeProject() {
        sendTo(machine, EngineControlIntent.Public.CloseProject)
        updateState { copy(openProject = null, projectFile = null, projectDraft = null, project = null) }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.reloadProject() {
        updateState { copy(projectDraft = null) }
        screen().openProject?.let { open(it) }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.open(project: String) {
        updateState {
            copy(
                openProject = project,
                projectFile = null,
                projectDraft = projectDraft?.takeIf { it.workspace.value == project },
            )
        }
        if (apply(ControlOperation.OpenProject(engineId, WorkspaceRef(project))) != SendResult.Accepted) {
            updateState { copy(openProject = null) }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.edit(change: (EngineConfigState, ProjectConfig, ProjectDraft?) -> ProjectDraft?) {
        val state = machine.state.value
        val config = state.configOf(engineId) ?: return
        val project = (state as? EngineControlState.Active)?.project ?: return
        updateState {
            copy(
                projectDraft = change(config, project, projectDraft),
            ).reflect(engineId, machine.state.value)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.save() {
        val screen = screen()
        val draft = screen.projectDraft
        val config = machine.state.value.configOf(engineId)
        if (draft == null || config == null || screen.project?.form?.canSave != true) {
            log.w { "ignore a project save without savable changes" }
            return
        }
        val project = (machine.state.value as? EngineControlState.Active)?.project ?: return
        val edit = config.projectSaveOf(project, draft)
        val save = ControlOperation.SaveProject(engineId, draft.workspace, edit, draft.revision)
        // The draft stays until the files are read anew, so a failed save keeps the edits to fix.
        if (apply(save) == SendResult.Accepted) {
            updateState {
                copy(projectDraft = projectDraft?.let { it.copy(draft = it.draft.copy(isSubmitted = true)) })
                    .reflect(engineId, machine.state.value)
            }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.screen(): EngineControlPanelState {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        return screen
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.apply(operation: ControlOperation): SendResult {
        val result = sendTo(machine, EngineControlIntent.Public.Apply(operation))
        if (result != SendResult.Accepted) log.w { "${operation::class.simpleName.orEmpty()} not accepted: $result" }
        return result
    }
}
