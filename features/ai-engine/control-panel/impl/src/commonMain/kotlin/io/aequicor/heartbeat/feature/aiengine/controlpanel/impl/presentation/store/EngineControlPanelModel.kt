package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.di.scope.EngineControlScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** The engine a panel graph shows; null in the graph of the engine list. */
data class EngineControlTarget(val engine: EngineId?)

/** A configuration page of the engine. */
@Immutable
data class PageItemUi(val id: String, val title: TextUi)

/**
 * One engine's panel: its management ([panel], null while engine management is off), the local input of the
 * management block and the pages of its configuration. [isConfigurationOff] while engine configuration is off,
 * [isMissing] when the engine is not registered (any more).
 */
@Immutable
data class EngineControlPanelState(
    val engine: String,
    val title: String = "",
    val panel: EnginePanelUi? = null,
    /** Launch settings being edited; null shows the saved ones. */
    val launchDraft: LaunchDraftUi? = null,
    /** A code pasted from a sign-in page, kept until sent or its sign-in prompt ends. Never logged. */
    val loginCode: String = "",
    /** An action waiting for confirmation (uninstall, an unverified update, sign-out). */
    val confirmAction: EngineActionUi? = null,
    val isSaving: Boolean = false,
    /** The failure of the last operation, until dismissed. */
    val failure: FailureUi? = null,
    val pages: ImmutableList<PageItemUi> = persistentListOf(),
    /** The page shown instead of the overview, or null. */
    val openPage: String? = null,
    /** The selected scope's saved resource inventory, never the local form draft. */
    val resources: ResourceInventoryUi? = null,
    val isReadingResources: Boolean = false,
    /** The profile configuration form; null while engine configuration is off or the engine declares no schema. */
    val config: ConfigFormUi? = null,
    /** Unsaved changes of the profile configuration. */
    val configDraft: ConfigDraft? = null,
    /** Saved projects whose files the panel edits. */
    val projects: ImmutableList<ProjectItemUi> = persistentListOf(),
    /** The project shown instead of the overview, and the file of it shown as a whole. */
    val openProject: String? = null,
    val projectFile: String? = null,
    /** The open project's configuration once its files are read. */
    val project: ProjectFormUi? = null,
    /** Unsaved changes of the open project's files. */
    val projectDraft: ProjectDraft? = null,
    /** The engine's own configuration can be imported; an import being chosen, and the report of a finished one. */
    val isImportable: Boolean = false,
    val importing: ImportUi? = null,
    val importReport: ImportReportUi? = null,
    /** Back was asked with unsaved changes: the open project's when one is open, else the profile configuration's. */
    val isLeaving: Boolean = false,
    val isConfigurationOff: Boolean = false,
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val hasLoadFailure: Boolean = false,
) : MVIState {
    override fun toString(): String = "EngineControlPanelState(engine=$engine)"
}

/** User events of the panel. */
sealed interface EngineControlPanelIntent : MVIIntent {
    /** Restarts a failed observation. */
    data object RetryLoad : EngineControlPanelIntent

    /** Re-reads files for the current profile or project. */
    data object RefreshResources : EngineControlPanelIntent

    /** Hides the failure of the last operation. */
    data object DismissError : EngineControlPanelIntent

    /**
     * Steps back: from a file to its project, from a project or a page to the overview, from the overview out of the
     * panel. Unsaved changes of what is left ask first.
     */
    data object Back : EngineControlPanelIntent

    /** Drops the unsaved changes Back asked about and steps back. */
    data object DiscardAndLeave : EngineControlPanelIntent

    /** Stays with the unsaved changes Back asked about. */
    data object KeepEditing : EngineControlPanelIntent
}

/** One-off results of the panel. */
sealed interface EngineControlPanelAction : MVIAction {
    /** The panel is left: its owner closes it. */
    data object Leave : EngineControlPanelAction
}

internal typealias PanelContext =
    PipelineContext<EngineControlPanelState, EngineControlPanelIntent, EngineControlPanelAction>

/** Panel store of one engine: reflects the panel machine and keeps only local input. */
@SingleIn(EngineControlScope::class)
@Inject
class EngineControlPanelModel(
    private val machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    target: EngineControlTarget,
    @ForScope(EngineControlScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("EngineControlPanelModel")
    private val engineId = requireNotNull(target.engine) { "A panel graph is created for one engine" }
    private val projects = EngineProjects(machine, engineId)
    private val imports = EngineImports(machine, engineId)

    val store = factory.create<EngineControlPanelState, EngineControlPanelIntent, EngineControlPanelAction>(
        "EngineControlPanel",
        EngineControlPanelState(engineId.value).reflect(engineId, machine.state.value),
        onError = { copy(isLoading = false, isSaving = false, failure = FailureUi.Unknown) },
    ) {
        init { sendTo(machine, EngineControlIntent.Public.Start) }
        reflect(machine) { reflect(engineId, it) }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope.coroutineScope)
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.handle(intent: EngineControlPanelIntent) {
        when (intent) {
            EngineControlPanelIntent.RetryLoad -> sendTo(machine, EngineControlIntent.Public.RetryLoad)

            EngineControlPanelIntent.DismissError -> sendTo(machine, EngineControlIntent.Public.DismissError)

            EngineControlPanelIntent.RefreshResources -> refreshResources()

            EngineControlPanelIntent.Back -> back()

            EngineControlPanelIntent.DiscardAndLeave -> leave()

            EngineControlPanelIntent.KeepEditing -> updateState { copy(isLeaving = false) }

            // Only a launch draft changes the panel (its errors); other input is local text or a flag.
            is EngineManagementIntent.EngineInput -> updateState { managementInput(intent) }

            is EngineManagementIntent.EngineRequest -> request(intent)

            is ConfigIntent -> configure(intent)

            is ProjectIntent -> with(projects) { handle(intent) }

            is ImportIntent -> with(imports) { handle(intent) }
        }
    }

    private fun EngineControlPanelState.managementInput(
        intent: EngineManagementIntent.EngineInput,
    ): EngineControlPanelState =
        if (intent.isLaunchEdit) edit(intent).reflect(engineId, machine.state.value) else edit(intent)

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.back() {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        val form = if (screen.openProject != null) screen.project?.form else screen.config
        val hasChanges = form?.isDirty == true
        when {
            screen.projectFile != null -> with(projects) { handle(ProjectIntent.CloseFile) }
            screen.openPage != null -> configure(ConfigIntent.ClosePage)
            hasChanges -> updateState { copy(isLeaving = true) }
            else -> leave()
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.leave() {
        var openProject: String? = null
        withState { openProject = this.openProject }
        updateState { copy(isLeaving = false) }
        if (openProject != null) {
            with(projects) { handle(ProjectIntent.Close) }
        } else {
            log.i { "leave the engine panel" }
            updateState { copy(configDraft = null).reflect(engineId, machine.state.value) }
            action(EngineControlPanelAction.Leave)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.configure(intent: ConfigIntent) {
        when (intent) {
            is ConfigIntent.OpenPage -> {
                updateState { copy(openPage = intent.page.takeIf { config?.hasPage(it) == true }) }
                val page = machine.state.value.configOf(engineId)?.schema?.pages?.firstOrNull { it.id == intent.page }
                if (page?.hasResources == true) refreshResources()
            }

            ConfigIntent.ClosePage -> updateState {
                val schema = machine.state.value.configOf(engineId)?.schema
                copy(openPage = null, configDraft = configDraft?.let { draft -> schema?.settleFiles(draft) ?: draft })
                    .reflect(engineId, machine.state.value)
            }

            is ConfigIntent.EditFile -> updateState {
                val config = machine.state.value.configOf(engineId) ?: return@updateState this
                copy(configDraft = config.editFile(configDraft, intent.file, intent.text))
                    .reflect(engineId, machine.state.value)
            }

            is ConfigIntent.EditField -> updateState {
                val config = machine.state.value.configOf(engineId) ?: return@updateState this
                copy(configDraft = config.edit(configDraft, intent.field, intent.input))
                    .reflect(engineId, machine.state.value)
            }

            ConfigIntent.DiscardConfig -> updateState {
                copy(configDraft = null).reflect(engineId, machine.state.value)
            }

            ConfigIntent.SaveConfig -> save()
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.refreshResources() {
        var workspace: WorkspaceRef? = null
        withState { workspace = openProject?.let(::WorkspaceRef) }
        sendTo(machine, EngineControlIntent.Public.Apply(ControlOperation.ListResources(engineId, workspace)))
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.save() {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        val draft = screen.configDraft
        val config = machine.state.value.configOf(engineId)
        if (draft == null || config == null || screen.config?.isSaveable != true) {
            log.w { "ignore a configuration save without savable changes" }
            return
        }
        val edit = config.schema.saveOf(draft)
        val save = ControlOperation.SaveProfile(engineId, edit, draft.base ?: config.revision)
        var isAccepted = false
        try {
            isAccepted = sendTo(machine, EngineControlIntent.Public.Apply(save)) == SendResult.Accepted
        } finally {
            // A save that was not accepted never reaches the effect that closes its secrets.
            if (!isAccepted) edit.secrets.values.forEach { it?.close() }
        }
        // The draft stays until the saved revision changes, so a failed save keeps the edits to fix.
        if (isAccepted) {
            updateState {
                copy(configDraft = configDraft?.copy(isSubmitted = true)).reflect(engineId, machine.state.value)
            }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.request(intent: EngineManagementIntent.EngineRequest) {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        if (intent is EngineManagementIntent.RequestEngineAction && screen.needsConfirmation(intent.action)) {
            updateState { copy(confirmAction = intent.action) }
            return
        }
        val command = screen.commandFor(intent)
        if (command == null) {
            log.w { "ignore ${intent::class.simpleName.orEmpty()} without an applicable engine command" }
            return
        }
        val result = sendTo(machine, EngineControlIntent.Public.Apply(ControlOperation.Manage(engineId, command)))
        // Local input is cleared only once the machine took it; a rejected one stays to be sent again.
        if (result == SendResult.Accepted) updateState { sent(intent) }
    }
}

/** The panel of [engine] with the machine [state], keeping local input while it still applies. */
internal fun EngineControlPanelState.reflect(engine: EngineId, state: EngineControlState): EngineControlPanelState {
    val active = state as? EngineControlState.Active ?: return copy(isLoading = true, loginCode = "")
    val snapshot = active.snapshot
    val base = copy(
        isLoading = snapshot == null && active.loadFailure == null,
        hasLoadFailure = active.loadFailure != null,
        isSaving = active.pending != null,
        isReadingResources = active.pending is ControlOperation.ListResources,
        failure = active.failed?.failure?.toUi(),
    )
    if (snapshot == null) return base.copy(panel = null, loginCode = "", confirmAction = null)
    val managed = snapshot.management.engines.firstOrNull { it.descriptor.id == engine }
    val config = snapshot.configuration.engine(engine)?.takeIf { snapshot.configuration.isEnabled }
    val draft = configDraft.settle(config, active)
    val form = config?.form(draft)
    val project = active.project?.takeIf { it.engine == engine && it.workspace.value == openProject }
    val projectDraft = projectDraft.settle(project, active)
    val title = snapshot.projects.firstOrNull { it.ref.value == openProject }?.name ?: openProject.orEmpty()
    val panel = managed?.takeIf { snapshot.management.isEnabled }
        ?.toPanel(launchDraft, snapshot.management.platform)
    return base.copy(
        title = managed?.descriptor?.title.orEmpty(),
        panel = panel,
        launchDraft = launchDraft.takeIf { panel != null },
        loginCode = loginCode.takeIf { panel?.job?.phase is JobPhaseUi.AwaitingCode }.orEmpty(),
        confirmAction = confirmAction.takeIf { panel != null && panel.job?.isRunning != true },
        pages = form?.pages.orEmpty().map { PageItemUi(it.id, it.title) }.toImmutableList(),
        openPage = openPage?.takeIf { form?.hasPage(it) == true },
        config = form,
        resources = active.resources?.takeIf {
            config != null && it.engine == engine && it.workspace?.value == openProject
        }?.toUi(),
        configDraft = draft,
        projects = config?.projectItems(snapshot.projects).orEmpty().toImmutableList(),
        openProject = openProject.takeIf { config != null },
        project = project?.let { config?.projectForm(it, projectDraft, title) },
        projectDraft = projectDraft,
        isImportable = config != null && ConfigCapability.ImportNative in config.schema.capabilities,
        importing = active.importPreview?.toUi(importing),
        importReport = active.importReport?.let { report -> importReport ?: report.toUi(importing) },
        isConfigurationOff = !snapshot.configuration.isEnabled,
        isMissing = managed == null,
    )
}

/** The panel's failure text of an operation failure. */
internal fun ControlFailure.toUi(): FailureUi = when (this) {
    is ControlFailure.Engine -> failure.toUi()
    is ControlFailure.Config -> failure.toUi()
}

/** The configuration page or file editor [page] exists. */
internal fun ConfigFormUi.hasPage(page: String): Boolean {
    val file = fileOf(page)
    return if (file != null) files.any { it.id == file } else pages.any { it.id == page }
}
