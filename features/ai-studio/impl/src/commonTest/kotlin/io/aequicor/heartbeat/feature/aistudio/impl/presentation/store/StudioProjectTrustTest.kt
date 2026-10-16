package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrustStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioProjectTrustTest {
    private val pi = EngineId("pi")
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, projectId = "web")),
        focusedPaneId = 0,
        settings = DefaultRunSettings.copy(
            modelId = EngineTarget(pi, EngineBindingId("b"), ModelId("m")).studioModelId(),
        ),
        defaultProjectId = "web",
    )

    @Test
    fun `an undecided project with gated configuration asks until the user decides`() = runTest {
        val configuration = TrustConfiguration(hasGated = true)
        val screen = Fixture(this, ready, configuration = configuration).subscribe()
        runCurrent()
        assertEquals(ProjectTrustNoticeUi("web", "pi", "web", "pi"), screen.states.value.projectTrust)

        screen.intent(AiStudioScreenIntent.DecideProjectTrust("web", "pi", isTrusted = false))
        runCurrent()
        assertEquals(listOf(ProjectTrust.Content), configuration.decisions)
        assertNull(screen.states.value.projectTrust)
    }

    @Test
    fun `nothing is asked without gated configuration or with engine configuration off`() = runTest {
        val plain = Fixture(this, ready, configuration = TrustConfiguration(hasGated = false)).subscribe()
        runCurrent()
        assertNull(plain.states.value.projectTrust)

        val off = TrustConfiguration(hasGated = true).apply { state.value = EngineConfigurationState.Off }
        val disabled = Fixture(this, ready, configuration = off).subscribe()
        runCurrent()
        assertNull(disabled.states.value.projectTrust)
    }

    /** Pi with project trust: the project is undecided until [setProjectTrust]. */
    private inner class TrustConfiguration(private val hasGated: Boolean) : EngineConfiguration {
        private val schema = EngineConfigSchema(
            emptyList(),
            emptyList(),
            capabilities = setOf(ConfigCapability.ProjectTrust),
        )
        private val engine = EngineConfigState(pi, schema, EngineConfigValues(), ConfigRevision(0))
        override val state = MutableStateFlow(EngineConfigurationState(true, listOf(engine)))
        val decisions = mutableListOf<ProjectTrust>()

        override suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef) = ProjectTrustStatus(
            state.value.engine(engine)?.trust?.get(workspace) ?: ProjectTrust.Undecided,
            hasGated,
        )

        override suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust) {
            decisions += trust
            state.value = state.value.copy(
                engines = state.value.engines.map { it.copy(trust = it.trust + (workspace to trust)) },
            )
        }

        override suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision) = error("Unused")

        override suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): ProjectConfig = error("Unused")

        override suspend fun saveProject(
            engine: EngineId,
            workspace: WorkspaceRef,
            edit: ProjectEdit,
            expected: ProjectRevision,
        ): ProjectConfig = error("Unused")

        override suspend fun previewImport(engine: EngineId): ImportPreview = error("Unused")

        override suspend fun importNative(
            engine: EngineId,
            selection: ImportSelection,
            expected: ConfigRevision,
        ): ImportReport = error("Unused")
    }
}
