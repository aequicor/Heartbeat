package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ExistingUnits
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectConfigMachine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.uuid.Uuid

private typealias ConfigPipeline =
    PipelineContext<ProjectConfigScreenState, ProjectConfigScreenIntent, ProjectConfigScreenAction>

/**
 * The screen after task [requestId] ended with [result]: an import of this screen that found existing units waits for
 * the user's choice, any other result is a notice.
 */
private fun ProjectConfigScreenState.finished(requestId: String, result: ConfigTaskResult): ProjectConfigScreenState {
    val pending = importing?.takeIf { it.requestId == requestId }
    return when {
        result is ConfigTaskResult.ImportConflicts && pending != null ->
            copy(importing = pending.copy(existing = result.units.map { it.toString() }.toImmutableList()))

        result is ConfigTaskResult.ImportConflicts -> this

        else -> copy(notice = result.toNotice(), importing = importing.takeIf { pending == null })
    }
}

/**
 * Configuration screen store. It mirrors the profile machine, the names of the user's projects and the entities of
 * the sections; every decision goes to the machine. The store keeps only the open chooser, the confirmation of a risky
 * proposal, an import waiting for the user's choice on existing units and the last notice. File dialogs are opened
 * here, before the machine gets a path.
 */
internal class ProjectConfigModel(
    private val machine: ProjectConfigMachine,
    workspaces: LocalWorkspaces,
    sections: Set<ConfigSection>,
    private val dialogs: ConfigDialogs,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    /** The screen store, retained for the lifetime of this screen. */
    val store = factory.create<ProjectConfigScreenState, ProjectConfigScreenIntent, ProjectConfigScreenAction>(
        "ProjectConfig",
        ProjectConfigScreenState().reflecting(machine.state.value, dialogs.isAvailable),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            updateState {
                when (output) {
                    is ProjectConfigOutput.TaskFinished -> finished(output.requestId, output.result)
                    ProjectConfigOutput.StorageFailed -> copy(notice = NoticeUi.StorageFailed)
                }
            }
        }) { reflecting(it, dialogs.isAvailable) }
        whileSubscribed {
            workspaces.observe().collect { projects ->
                val names = projects.associate { it.ref.value to it.name }.toImmutableMap()
                updateState { copy(projectNames = names).reflecting(machine.state.value, dialogs.isAvailable) }
            }
        }
        whileSubscribed {
            val flows = sections.map { section -> section.entities.map { section.id to it } }
            if (flows.isNotEmpty()) {
                combine(flows) { pairs -> pairs.mapNotNull { (id, list) -> list?.let { id to it } }.toMap() }
                    .collect { entities -> updateState { copy(entities = entitiesOf(entities)) } }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.handle(intent: ProjectConfigScreenIntent) {
        when (intent) {
            is ProjectConfigScreenIntent.Decision -> decide(intent)
            is ProjectConfigScreenIntent.Task -> run(intent)
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.decide(intent: ProjectConfigScreenIntent.Decision) {
        when (intent) {
            is ProjectConfigScreenIntent.Trust -> trust(intent.version)

            ProjectConfigScreenIntent.ConfirmRisk -> confirmRisk()

            ProjectConfigScreenIntent.CancelRisk -> updateState { copy(confirming = null) }

            is ProjectConfigScreenIntent.KeepApp -> with(intent.version) { sendTo(machine, Internal.KeepApp(id, seen)) }

            is ProjectConfigScreenIntent.Dismiss -> with(intent.version) { sendTo(machine, Public.Dismiss(id, seen)) }

            is ProjectConfigScreenIntent.Sync -> sendTo(machine, Public.Sync(WorkspaceRef(intent.projectKey)))

            is ProjectConfigScreenIntent.RemoveFromProject -> updateState {
                val name = projects.firstOrNull { it.key == intent.projectKey }?.name ?: intent.projectKey
                copy(removing = RemovalUi(intent.projectKey, name, intent.unit))
            }

            ProjectConfigScreenIntent.ConfirmRemove -> confirmRemove()

            ProjectConfigScreenIntent.CancelRemove -> updateState { copy(removing = null) }

            ProjectConfigScreenIntent.Reload -> sendTo(machine, Public.Reload)
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.trust(version: ProposalVersionUi) = withState {
        // The files changed since the user saw them: the new version waits for its own decision.
        val proposal = proposals.firstOrNull { it.version == version } ?: return@withState
        when (proposal.review) {
            ReviewUi.NotNeeded, is ReviewUi.Safe -> sendTo(machine, Internal.Trust(version.id, version.seen))
            ReviewUi.Unavailable, is ReviewUi.Risky -> updateState { copy(confirming = version) }
            ReviewUi.Pending -> Unit
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.confirmRemove() = withState {
        val removal = removing ?: return@withState
        updateState { copy(removing = null) }
        val unit = ConfigUnitRef(ConfigSectionId(removal.unit.sectionId), removal.unit.name)
        sendTo(machine, Internal.RemoveFromProject(WorkspaceRef(removal.projectKey), unit))
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.confirmRisk() = withState {
        val version = confirmed?.version ?: return@withState
        updateState { copy(confirming = null) }
        sendTo(machine, Internal.Trust(version.id, version.seen, isRiskAccepted = true))
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.run(intent: ProjectConfigScreenIntent.Task) {
        when (intent) {
            is ProjectConfigScreenIntent.OpenPicker -> updateState { copy(picker = PickerUi(intent.projectKey)) }

            is ProjectConfigScreenIntent.Toggle -> updateState {
                val current = picker ?: return@updateState this
                val selected = if (intent.entityId in current.selected) {
                    current.selected - intent.entityId
                } else {
                    current.selected + intent.entityId
                }
                copy(picker = current.copy(selected = selected.toImmutableSet()))
            }

            ProjectConfigScreenIntent.ConfirmPicker -> confirmPicker()

            ProjectConfigScreenIntent.CancelPicker -> updateState { copy(picker = null) }

            ProjectConfigScreenIntent.OpenBundle -> dialogs.pickBundle()?.let {
                sendTo(machine, Public.OpenBundle(requestId(), it))
            }

            is ProjectConfigScreenIntent.Import -> import(intent)

            ProjectConfigScreenIntent.CloseBundle -> sendTo(machine, Public.CloseBundle)

            ProjectConfigScreenIntent.DismissNotice -> updateState { copy(notice = null) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.confirmPicker() = withState {
        val chosen = picker ?: return@withState
        // A chosen harness takes its interfaces along.
        val ids = chosen.selected + entities.filter { it.parentId in chosen.selected }.map { it.id }
        val refs = ids.map { id ->
            ConfigEntityRef(ConfigSectionId(id.substringBefore('|')), ConfigEntityKey(id.substringAfter('|')))
        }
        updateState { copy(picker = null) }
        if (refs.isEmpty()) return@withState
        val project = chosen.projectKey
        if (project != null) {
            sendTo(machine, Internal.SaveToProject(requestId(), WorkspaceRef(project), refs))
        } else {
            dialogs.pickBundleTarget(BUNDLE_NAME)?.let { sendTo(machine, Internal.Export(requestId(), it, refs)) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.import(intent: ProjectConfigScreenIntent.Import) {
        when (intent) {
            is ProjectConfigScreenIntent.ImportToProject -> dialogs.pickBundle()?.let { path ->
                val id = requestId()
                updateState {
                    val name = projects.firstOrNull { it.key == intent.projectKey }?.name ?: intent.projectKey
                    copy(importing = ImportUi(id, path, intent.projectKey, name))
                }
                sendTo(machine, Internal.ImportToProject(id, path, WorkspaceRef(intent.projectKey)))
            }

            ProjectConfigScreenIntent.OverwriteExisting -> importAgain(ExistingUnits.Overwrite)

            ProjectConfigScreenIntent.SkipExisting -> importAgain(ExistingUnits.Skip)

            ProjectConfigScreenIntent.CancelImport -> updateState { copy(importing = null) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ConfigPipeline.importAgain(existing: ExistingUnits) = withState {
        val pending = importing?.takeIf { it.existing != null } ?: return@withState
        val id = requestId()
        updateState { copy(importing = pending.copy(requestId = id, existing = null)) }
        sendTo(machine, Internal.ImportToProject(id, pending.path, WorkspaceRef(pending.projectKey), existing))
    }

    private fun requestId(): String = Uuid.random().toString()

    private val ProposalVersionUi.seen: ConfigDigest? get() = digest?.let(::ConfigDigest)

    private companion object {
        const val BUNDLE_NAME = "heartbeat-config.${ConfigLayout.BUNDLE_EXTENSION}"
    }
}
