package io.aequicor.heartbeat.feature.aiengine.controlpanel.api

import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The engine control panel's list of engines, a section of the settings window. Registered in the profile tree
 * because engines are profile-owned; [isEmbedded] marks the route shown inside the settings window.
 */
@Serializable
@SerialName("engine_control")
public data class EngineControlRoute(val isEmbedded: Boolean = false) : Route

/** The control panel of one [engine]: management, its configuration pages, projects and import. */
@Serializable
@SerialName("engine_control_panel")
public data class EngineControlPanelRoute(val engine: EngineId) : Route

/**
 * What the panel shows: [management] of every registered engine, the [configuration] of engines that declare a
 * schema (off while engine configuration is off) and the saved [projects] whose files the panel can edit.
 */
public data class ControlSnapshot(
    val management: EngineManagementState,
    val configuration: EngineConfigurationState,
    val projects: List<LocalWorkspace> = emptyList(),
)

/** A change requested in the panel; one runs at a time. Secrets of an edit are closed once it ran. */
public sealed interface ControlOperation {
    /** The engine the operation acts on. */
    public val engine: EngineId

    /** An engine management command; long work becomes a job reported by the next snapshots. */
    public data class Manage(override val engine: EngineId, val command: EngineCommand) : ControlOperation

    /** Saves the profile configuration read at [expected]. */
    public data class SaveProfile(override val engine: EngineId, val edit: ConfigEdit, val expected: ConfigRevision) :
        ControlOperation

    /** Reads the saved resource inventory of the profile and optional project. */
    public data class ListResources(override val engine: EngineId, val workspace: WorkspaceRef? = null) :
        ControlOperation

    /** Reads the configuration files of a project. */
    public data class OpenProject(override val engine: EngineId, val workspace: WorkspaceRef) : ControlOperation

    /** Writes the configuration files of a project read at [expected]. */
    public data class SaveProject(
        override val engine: EngineId,
        val workspace: WorkspaceRef,
        val edit: ProjectEdit,
        val expected: ProjectRevision,
    ) : ControlOperation

    /** Saves the trust decision for a project. */
    public data class SetTrust(override val engine: EngineId, val workspace: WorkspaceRef, val trust: ProjectTrust) :
        ControlOperation

    /** Lists what an import of the engine's own configuration would bring. */
    public data class PreviewImport(override val engine: EngineId) : ControlOperation

    /** Imports the selected parts into the profile configuration read at [expected]. */
    public data class Import(
        override val engine: EngineId,
        val selection: ImportSelection,
        val expected: ConfigRevision,
    ) : ControlOperation
}

/** What an operation produced for the panel to show. */
public sealed interface ControlResult {
    /** Nothing beyond the next snapshot. */
    public data object Done : ControlResult

    /** A project's files as they are now. */
    public data class Project(val project: ProjectConfig) : ControlResult

    /** Resource files selected by the saved configuration. */
    public data class Resources(val inventory: ResourceInventory) : ControlResult

    /** An import preview. */
    public data class Preview(val preview: ImportPreview) : ControlResult

    /** A finished import. */
    public data class Imported(val report: ImportReport) : ControlResult
}

/** Why an operation failed. */
public sealed interface ControlFailure {
    /** The engine or its management refused. */
    public data class Engine(val failure: EngineFailure) : ControlFailure

    /** The configuration service refused. */
    public data class Config(val failure: ConfigFailure) : ControlFailure
}

/** An operation that failed, kept until dismissed; it is never repeated automatically. */
public data class FailedOperation(val operation: ControlOperation, val failure: ControlFailure)

/** Panel state. */
public sealed interface EngineControlState : MachineState {
    /** Not started. */
    public data object Idle : EngineControlState

    /**
     * Observing; [snapshot] is null until the first one arrives and after observation fails with [loadFailure].
     * [project] is the open project's configuration, [importPreview] and [importReport] the import in progress.
     */
    public data class Active(
        val snapshot: ControlSnapshot? = null,
        val pending: ControlOperation? = null,
        val failed: FailedOperation? = null,
        val loadFailure: EngineFailure? = null,
        val project: ProjectConfig? = null,
        val importPreview: ImportPreview? = null,
        val importReport: ImportReport? = null,
        val resources: ResourceInventory? = null,
    ) : EngineControlState
}

/** Panel requests and effect results. */
public sealed interface EngineControlIntent : MachineIntent {
    /** Accepted from the panel's screens. */
    public sealed interface Public : EngineControlIntent {
        /** Starts observing. */
        public data object Start : Public

        /** Restarts a failed observation. */
        public data object RetryLoad : Public

        /** Requests a serialized change. */
        public data class Apply(val operation: ControlOperation) : Public

        /** Dismisses the failure. */
        public data object DismissError : Public

        /** Closes the open project. */
        public data object CloseProject : Public

        /** Closes the import preview or report. */
        public data object DismissImport : Public
    }

    /** Effect results. */
    public sealed interface Internal : EngineControlIntent {
        /** A fresh snapshot. */
        public data class Snapshot(val snapshot: ControlSnapshot) : Internal

        /** The pending change completed with [result]. */
        public data class Applied(val result: ControlResult) : Internal

        /** The pending change failed. */
        public data class ApplyFailed(val failure: ControlFailure) : Internal

        /** Observation failed. */
        public data class ObserveFailed(val failure: EngineFailure) : Internal
    }
}

/** IO of the panel. */
public sealed interface EngineControlEffect : MachineEffect {
    /** Observes management, configuration and saved projects until the state is left. */
    public data object Observe : EngineControlEffect

    /** Executes one change. */
    public data class Execute(val operation: ControlOperation) : EngineControlEffect
}

/** The panel emits no one-off results. */
public sealed interface EngineControlOutput : MachineOutput

/** Address of the control panel machine. */
public object EngineControlMachineKey :
    MachineKey<
        EngineControlState,
        EngineControlIntent,
        EngineControlIntent.Public,
        EngineControlEffect,
        EngineControlOutput,
    > {
    override val name: String = "engine_control"
}

/**
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Idle | Start | | Active | Observe |
 * | Active | Snapshot | | stay (load failure cleared) | |
 * | Active | Apply | observed, nothing pending | stay (pending, failure cleared) | Execute |
 * | Active | Applied | | stay (result, refresh resources after saved changes) | Execute ListResources if needed |
 * | Active | ApplyFailed | | stay (failed) | |
 * | Active | DismissError | | stay | |
 * | Active | CloseProject | | stay (no project) | |
 * | Active | DismissImport | | stay (no preview or report) | |
 * | Active | ObserveFailed | | stay (snapshot dropped, load failure) | |
 * | Active | RetryLoad | has load failure | stay | Observe |
 *
 * Changes and observation failures use `stay`, so neither cancels the other while it runs. A failed change is never
 * replayed: secrets of a configuration edit are closed once it ran, and a save may have partially completed.
 */
public val EngineControlMachineSpec:
    MachineSpec<EngineControlState, EngineControlIntent, EngineControlEffect, EngineControlOutput> =
    machineSpec(EngineControlMachineKey, EngineControlState.Idle) {
        state<EngineControlState.Idle> {
            on<EngineControlIntent.Public.Start> {
                goto<EngineControlState.Active> { EngineControlState.Active() }
                effect { EngineControlEffect.Observe }
            }
        }
        state<EngineControlState.Active> {
            on<EngineControlIntent.Internal.Snapshot> {
                stay {
                    state.copy(
                        snapshot = intent.snapshot,
                        loadFailure = null,
                        resources = state.resources.takeIf { intent.snapshot.configuration.isEnabled },
                    )
                }
            }
            on<EngineControlIntent.Public.Apply>(guard = { state.pending == null && state.snapshot != null }) {
                stay {
                    state.copy(
                        pending = intent.operation,
                        failed = null,
                        resources = state.resources.takeUnless { intent.operation is ControlOperation.ListResources },
                    )
                }
                effect { EngineControlEffect.Execute(intent.operation) }
            }
            on<EngineControlIntent.Internal.Applied> {
                stay {
                    val refresh = state.resourceRefresh(intent.result)
                    state.applied(intent.result).copy(
                        pending = refresh,
                        resources = if (refresh != null) null else state.applied(intent.result).resources,
                    )
                }
                effect { state.resourceRefresh(intent.result)?.let(EngineControlEffect::Execute) }
            }
            on<EngineControlIntent.Internal.ApplyFailed> {
                stay { state.copy(pending = null, failed = state.pending?.let { FailedOperation(it, intent.failure) }) }
            }
            on<EngineControlIntent.Public.DismissError> { stay { state.copy(failed = null) } }
            on<EngineControlIntent.Public.CloseProject> {
                stay {
                    state.copy(
                        project = null,
                        resources = state.resources.takeIf { it?.workspace == null },
                    )
                }
            }
            on<EngineControlIntent.Public.DismissImport> {
                stay { state.copy(importPreview = null, importReport = null) }
            }
            on<EngineControlIntent.Internal.ObserveFailed> {
                stay { state.copy(snapshot = null, loadFailure = intent.failure) }
            }
            on<EngineControlIntent.Public.RetryLoad>(guard = { state.loadFailure != null }) {
                stay { state.copy(loadFailure = null) }
                effect { EngineControlEffect.Observe }
            }
        }
        onEffectFailure { effect, error ->
            when (effect) {
                EngineControlEffect.Observe -> EngineControlIntent.Internal.ObserveFailed(
                    (error as? EngineException)?.failure ?: EngineFailure.Unknown(),
                )

                is EngineControlEffect.Execute -> EngineControlIntent.Internal.ApplyFailed(error.toControlFailure())
            }
        }
    }

private fun EngineControlState.Active.applied(result: ControlResult): EngineControlState.Active = when (result) {
    ControlResult.Done -> copy(pending = null)
    is ControlResult.Resources -> copy(pending = null, resources = result.inventory)
    is ControlResult.Project -> copy(pending = null, project = result.project)
    is ControlResult.Preview -> copy(pending = null, importPreview = result.preview, importReport = null)
    is ControlResult.Imported -> copy(pending = null, importPreview = null, importReport = result.report)
}

/** The panel's failure for an exception an operation threw. */
public fun Throwable.toControlFailure(): ControlFailure = when (this) {
    is ConfigException -> ControlFailure.Config(failure)
    is EngineException -> ControlFailure.Engine(failure)
    else -> ControlFailure.Engine(EngineFailure.Unknown())
}
