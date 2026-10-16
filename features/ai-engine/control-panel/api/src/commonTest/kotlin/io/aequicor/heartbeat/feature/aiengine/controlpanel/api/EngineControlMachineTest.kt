package io.aequicor.heartbeat.feature.aiengine.controlpanel.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent.Public
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState.Active
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState.Idle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
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
    fun `exceptions map to panel failures`() {
        assertEquals(
            ControlFailure.Config(ConfigFailure.Conflict),
            ConfigException(ConfigFailure.Conflict).toControlFailure(),
        )
        assertEquals(ControlFailure.Engine(failure), EngineException(failure).toControlFailure())
        assertEquals(ControlFailure.Engine(EngineFailure.Unknown()), IllegalStateException("x").toControlFailure())
    }
}
