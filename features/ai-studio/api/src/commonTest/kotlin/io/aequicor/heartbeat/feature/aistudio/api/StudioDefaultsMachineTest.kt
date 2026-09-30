package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioDefaultsMachineTest {
    private val settings = RunSettings("route", ReasoningEffort.High, ApprovalMode.AutoEdits)
    private val version = StudioSettingsVersion("loaded-screen", 4)
    private val ready = AiStudioState.Ready(listOf(StudioPane(0)), 0, settings, settingsVersion = version)

    @Test
    fun `an explicit start page choice updates memory and schedules a versioned save`() {
        val selected = settings.copy(modelId = "other", approval = ApprovalMode.AutoApprove)
        val next = version.copy(revision = 5)
        AiStudioMachineSpec.assertTransition(
            ready,
            AiStudioIntent.Public.UpdateSettings(selected),
            ready.copy(settings = selected, settingsVersion = next),
            effects = listOf(AiStudioEffect.SaveSettings(selected, next)),
        )
    }

    @Test
    fun `new conversations in another project inherit the start page choices`() {
        val withChat = ready.copy(panes = listOf(StudioPane(0, sessionId = "existing")))
        val project = ready.copy(panes = listOf(StudioPane(0, projectId = "other-project")))
        AiStudioMachineSpec.assertTransition(withChat, AiStudioIntent.Public.NewSession("other-project"), project)
        AiStudioMachineSpec.assertTransition(
            project,
            AiStudioIntent.Public.Submit(0, "hello"),
            project.copy(
                panes = listOf(StudioPane(0, projectId = "other-project", isCreating = true, createRequestId = 0)),
                nextCreateRequestId = 1,
            ),
            effects = listOf(AiStudioEffect.CreateSession(0, "other-project", "hello", settings, requestId = 0)),
        )
    }

    @Test
    fun `failed persistence retains the current settings and leaves the composer usable`() {
        assertEquals(
            AiStudioIntent.Internal.SettingsSaveFailed,
            AiStudioMachineSpec.onEffectFailure(
                AiStudioEffect.SaveSettings(settings, version),
                IllegalStateException(),
            ),
        )
        AiStudioMachineSpec.assertTransition(ready, AiStudioIntent.Internal.SettingsSaveFailed, ready)
    }

    @Test
    fun `loading restores the writer and saved choices without saving initial values`() {
        AiStudioMachineSpec.assertTransition(
            AiStudioState.Loading,
            AiStudioIntent.Internal.Loaded(true, StudioDefaults(null, settings, version)),
            ready,
            effects = listOf(
                AiStudioEffect.ObserveAvailability,
                AiStudioEffect.ObserveRuntime,
                AiStudioEffect.ObserveModels,
                AiStudioEffect.ObserveProjects,
            ),
        )
    }
}
