package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlResult
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlEffect
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.pi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain.EngineControlEffects
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain.EngineControlServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineControlEffectsTest {
    private val management = FakeManagement(
        EngineManagementState(isEnabled = true, engines = listOf(ControlTestData.managed(pi))),
    )
    private val configuration = FakeConfiguration(ControlTestData.configuration(pi))
    private val workspaces = FakeWorkspaces()
    private val scope = RecordingScope()
    private val effects = EngineControlEffects(EngineControlServices(management, configuration, workspaces))
    private val workspace = WorkspaceRef("project")

    @Test
    fun `observation combines management configuration and projects`() = runTest {
        val job = launch { effects.handle(EngineControlEffect.Observe, scope) }
        runCurrent()
        workspaces.projects.value = listOf(LocalWorkspace(workspace, "Project"))
        runCurrent()

        val snapshots = scope.intents.filterIsInstance<EngineControlIntent.Internal.Snapshot>().map { it.snapshot }
        assertEquals(
            ControlSnapshot(
                management.state.value,
                configuration.state.value,
                listOf(LocalWorkspace(workspace, "Project")),
            ),
            snapshots.last(),
        )
        job.cancel()
    }

    @Test
    fun `projects are empty where local projects are unsupported`() = runTest {
        val unsupported = EngineControlEffects(
            EngineControlServices(management, configuration, FakeWorkspaces(isAvailable = false)),
        )
        val job = launch { unsupported.handle(EngineControlEffect.Observe, scope) }
        runCurrent()
        assertEquals(emptyList(), (scope.intents.last() as EngineControlIntent.Internal.Snapshot).snapshot.projects)
        job.cancel()
    }

    @Test
    fun `operations reach their service and report their result`() = runTest {
        val operations = listOf(
            ControlOperation.Manage(pi, EngineCommand.Restart),
            ControlOperation.OpenProject(pi, workspace),
            ControlOperation.SaveProject(pi, workspace, ProjectEdit(EngineConfigValues()), ProjectRevision()),
            ControlOperation.SetTrust(pi, workspace, ProjectTrust.Trusted),
            ControlOperation.PreviewImport(pi),
            ControlOperation.Import(pi, ImportSelection(setOf("settings")), ConfigRevision(0)),
        )
        operations.forEach { effects.handle(EngineControlEffect.Execute(it), scope) }

        assertEquals<List<Pair<EngineId, EngineCommand>>>(listOf(pi to EngineCommand.Restart), management.commands)
        assertEquals(
            listOf("readProject", "saveProject", "setProjectTrust:Trusted", "previewImport", "importNative"),
            configuration.calls,
        )
        val results = scope.intents.filterIsInstance<EngineControlIntent.Internal.Applied>().map { it.result }
        assertEquals(ControlResult.Done, results[0])
        assertIs<ControlResult.Project>(results[1])
        assertIs<ControlResult.Project>(results[2])
        assertEquals(ControlResult.Done, results[3])
        assertIs<ControlResult.Preview>(results[4])
        assertEquals(listOf("settings"), (results[5] as ControlResult.Imported).report.imported)
    }

    @Test
    fun `a profile save closes its secrets even when it fails`() = runTest {
        val secret = Secret("value".toCharArray())
        configuration.failure = ConfigException(ConfigFailure.Conflict)
        val save = ControlOperation.SaveProfile(
            pi,
            ConfigEdit(EngineConfigValues(), mapOf("SLOT" to secret)),
            ConfigRevision(0),
        )

        assertFailsWith<ConfigException> { effects.handle(EngineControlEffect.Execute(save), scope) }

        assertFailsWith<IllegalStateException> { secret.reveal { it.size } }
        assertTrue(scope.intents.none { it is EngineControlIntent.Internal.Applied })
    }

    private class RecordingScope : EffectScope<EngineControlIntent> {
        val intents = mutableListOf<EngineControlIntent>()

        override suspend fun send(intent: EngineControlIntent): SendResult {
            intents += intent
            return SendResult.Accepted
        }
    }
}
