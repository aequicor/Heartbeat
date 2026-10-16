package io.aequicor.heartbeat.feature.aiengine.controlpanel.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent.Public
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState.Active
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState.Idle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineControlMachineTest {
    private val engine = EngineId("pi")
    private val workspace = WorkspaceRef("project")
    private val snapshot = ControlSnapshot(EngineManagementState.Off, EngineConfigurationState.Off)
    private val observed = Active(snapshot = snapshot)
    private val restart = ControlOperation.Manage(engine, EngineCommand.Restart)
    private val project = ProjectConfig(engine, workspace, EngineConfigValues(), ProjectRevision())
    private val failure = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)

    @Test
    fun `start observes`() {
        EngineControlMachineSpec.assertTransition(
            from = Idle,
            intent = Public.Start,
            to = Active(),
            effects = listOf(EngineControlEffect.Observe),
        )
    }

    @Test
    fun `a snapshot replaces the previous one and clears a load failure`() {
        EngineControlMachineSpec.assertTransition(
            from = Active(loadFailure = failure),
            intent = Internal.Snapshot(snapshot),
            to = observed,
        )
    }

    @Test
    fun `apply runs one operation at a time and only once observed`() {
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(failed = FailedOperation(restart, ControlFailure.Engine(failure))),
            intent = Public.Apply(restart),
            to = observed.copy(pending = restart),
            effects = listOf(EngineControlEffect.Execute(restart)),
        )
        EngineControlMachineSpec.assertIgnored(observed.copy(pending = restart), Public.Apply(restart))
        EngineControlMachineSpec.assertIgnored(Active(), Public.Apply(restart))
        EngineControlMachineSpec.assertIgnored(Idle, Public.Apply(restart))
    }

    @Test
    fun `results open a project and an import`() {
        val open = ControlOperation.OpenProject(engine, workspace)
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = open),
            intent = Internal.Applied(ControlResult.Project(project)),
            to = observed.copy(project = project),
        )
        val preview = ImportPreview("~/.pi/agent", emptyList())
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = ControlOperation.PreviewImport(engine)),
            intent = Internal.Applied(ControlResult.Preview(preview)),
            to = observed.copy(importPreview = preview),
        )
        val report = ImportReport(listOf("settings"))
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(importPreview = preview, project = project),
            intent = Internal.Applied(ControlResult.Imported(report)),
            to = observed.copy(importReport = report, project = project),
        )
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = restart, project = project),
            intent = Internal.Applied(ControlResult.Done),
            to = observed.copy(project = project),
        )
    }

    @Test
    fun `a failed operation is kept until dismissed and never repeated`() {
        val failed = FailedOperation(restart, ControlFailure.Engine(failure))
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = restart),
            intent = Internal.ApplyFailed(failed.failure),
            to = observed.copy(failed = failed),
        )
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(failed = failed),
            intent = Public.DismissError,
            to = observed,
        )
    }

    @Test
    fun `the project and the import close`() {
        EngineControlMachineSpec.assertTransition(observed.copy(project = project), Public.CloseProject, observed)
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(importReport = ImportReport(emptyList())),
            intent = Public.DismissImport,
            to = observed,
        )
    }

    @Test
    fun `a failed observation can be retried`() {
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = restart),
            intent = Internal.ObserveFailed(failure),
            to = Active(pending = restart, loadFailure = failure),
        )
        EngineControlMachineSpec.assertTransition(
            from = Active(loadFailure = failure),
            intent = Public.RetryLoad,
            to = Active(),
            effects = listOf(EngineControlEffect.Observe),
        )
        EngineControlMachineSpec.assertIgnored(observed, Public.RetryLoad)
    }

    @Test
    fun `resource reads are serialized and their result is retained`() {
        val request = ControlOperation.ListResources(engine)
        val inventory = ResourceInventory(engine, null, emptyList())
        EngineControlMachineSpec.assertTransition(
            from = observed,
            intent = Public.Apply(request),
            to = observed.copy(pending = request),
            effects = listOf(EngineControlEffect.Execute(request)),
        )
        EngineControlMachineSpec.assertIgnored(observed.copy(pending = request), Public.Apply(restart))
        EngineControlMachineSpec.assertTransition(
            from = observed.copy(pending = request),
            intent = Internal.Applied(ControlResult.Resources(inventory)),
            to = observed.copy(resources = inventory),
        )
    }

    @Test
    fun `a successful save refreshes shown resources and a failed read can be retried`() {
        val inventory = ResourceInventory(engine, null, emptyList())
        val schema = EngineConfigSchema(emptyList(), emptyList(), capabilities = setOf(ConfigCapability.Resources))
        val config = EngineConfigState(engine, schema, EngineConfigValues(), ConfigRevision(0))
        val active = observed.copy(
            snapshot = snapshot.copy(configuration = EngineConfigurationState(true, listOf(config))),
            resources = inventory,
        )
        val save = ControlOperation.SaveProfile(engine, ConfigEdit(EngineConfigValues()), ConfigRevision(0))
        val refresh = ControlOperation.ListResources(engine)
        val refreshing = active.copy(pending = refresh, resources = null)
        EngineControlMachineSpec.assertTransition(
            from = active.copy(pending = save),
            intent = Internal.Applied(ControlResult.Done),
            to = refreshing,
            effects = listOf(EngineControlEffect.Execute(refresh)),
        )
        val error = ControlFailure.Config(ConfigFailure.Storage)
        val failed = refreshing.copy(pending = null, failed = FailedOperation(refresh, error))
        EngineControlMachineSpec.assertTransition(refreshing, Internal.ApplyFailed(error), failed)
        EngineControlMachineSpec.assertTransition(
            from = failed,
            intent = Public.Apply(refresh),
            to = refreshing,
            effects = listOf(EngineControlEffect.Execute(refresh)),
        )
    }

    @Test
    fun `project opening saving trust and importing refresh the shown scope`() {
        val schema = EngineConfigSchema(emptyList(), emptyList(), capabilities = setOf(ConfigCapability.Resources))
        val config = EngineConfigState(engine, schema, EngineConfigValues(), ConfigRevision(0))
        val inventory = ResourceInventory(engine, workspace, emptyList())
        val active = observed.copy(
            snapshot = snapshot.copy(configuration = EngineConfigurationState(true, listOf(config))),
            resources = inventory,
            project = project,
        )
        val operations = listOf(
            ControlOperation.OpenProject(engine, workspace) to ControlResult.Project(project),
            ControlOperation.SaveProject(engine, workspace, ProjectEdit(EngineConfigValues()), ProjectRevision()) to
                ControlResult.Project(project),
            ControlOperation.SetTrust(engine, workspace, ProjectTrust.Trusted) to ControlResult.Done,
            ControlOperation.Import(engine, ImportSelection(emptySet()), ConfigRevision(0)) to
                ControlResult.Imported(ImportReport(emptyList())),
        )
        val refresh = ControlOperation.ListResources(engine, workspace)
        operations.forEach { (operation, result) ->
            val expected = active.copy(
                pending = refresh,
                resources = null,
                importReport = (result as? ControlResult.Imported)?.report,
            )
            EngineControlMachineSpec.assertTransition(
                from = active.copy(pending = operation),
                intent = Internal.Applied(result),
                to = expected,
                effects = listOf(EngineControlEffect.Execute(refresh)),
            )
        }
        EngineControlMachineSpec.assertTransition(
            from = active,
            intent = Public.CloseProject,
            to = active.copy(project = null, resources = null),
        )
    }

    @Test
    fun `exceptions map to panel failures`() {
        assertEquals(
            ControlFailure.Config(ConfigFailure.Conflict),
            ConfigException(ConfigFailure.Conflict).toControlFailure(),
        )
        assertEquals(ControlFailure.Engine(failure), EngineException(failure).toControlFailure())
        assertEquals(ControlFailure.Engine(EngineFailure.Unknown()), IllegalStateException("x").toControlFailure())
    }
}
