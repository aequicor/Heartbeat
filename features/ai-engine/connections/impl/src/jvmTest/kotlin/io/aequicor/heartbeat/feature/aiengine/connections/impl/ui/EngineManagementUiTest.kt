package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CodexId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.managedCodex
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.settingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimeSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class EngineManagementUiTest {
    @Test
    fun `without engine management the panel is not shown`() = runSkikoComposeUiTest {
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(settingsSnapshot()))
        setContent { HbTheme { EngineConnectionsContent(state, {}, {}, {}) } }
        onNodeWithTag("engine-panel").assertDoesNotExist()
    }

    @Test
    fun `a missing engine is installed from the panel and a switched-off one keeps no connections`() =
        runSkikoComposeUiTest {
            val intents = mutableListOf<EngineConnectionsScreenIntent>()
            val codex = managedCodex(
                EngineEnablement(isUserEnabled = false, reasons = setOf(DisabledReason.DisabledByUser)),
                installation = InstallationState(Installation(InstallSource.Missing), latest = "0.2.0"),
            )
            setContent { HbTheme { EngineConnectionsContent(selected(codex), { intents += it }, {}, {}) } }

            onNodeWithTag("engine-panel").assertExists()
            onNodeWithTag("settings-connections").assertDoesNotExist()
            onNodeWithTag("engine-install").performClick()
            onNodeWithTag("engine-enabled").performClick()
            runOnIdle {
                assertEquals(
                    listOf<EngineConnectionsScreenIntent>(
                        EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Install),
                        EngineConnectionsScreenIntent.SetEngineEnabled(true),
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `a running download shows its progress and can only be cancelled`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        val codex = managedCodex(
            job = EngineJob(
                EngineAction.Install,
                JobPhase.Downloading(1_048_576, 4_194_304),
                Instant.fromEpochSeconds(1),
            ),
        )
        setContent { HbTheme { EngineConnectionsContent(selected(codex), { intents += it }, {}, {}) } }

        onNodeWithTag("engine-job-progress").assertExists()
        // Units are localized; the sizes are not.
        onNodeWithText("4.0", substring = true).assertExists()
        onNodeWithTag("engine-check-updates").assertIsNotEnabled()
        onNodeWithTag("engine-job-cancel").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.CancelEngineJob),
                intents,
            )
        }
    }

    @Test
    fun `a stale runtime is restarted`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        val codex = managedCodex().copy(runtime = RuntimeSummary(runtimes = 1, isStale = true))
        setContent { HbTheme { EngineConnectionsContent(selected(codex), { intents += it }, {}, {}) } }

        onNodeWithTag("engine-restart").performClick()
        runOnIdle {
            assertEquals(listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.RestartEngine), intents)
        }
    }

    @Test
    fun `a removal is confirmed first`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        val state = selected(managedCodex()).copy(confirmAction = EngineActionUi.Uninstall)
        setContent { HbTheme { EngineConnectionsContent(state, { intents += it }, {}, {}) } }

        onNodeWithTag("engine-action-confirm").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.ConfirmEngineAction),
                intents,
            )
        }
    }

    @Test
    fun `every phase and installation failure has a text`() {
        InstallFailureUi.entries.forEach { InstallFailureTexts.getValue(it) }
        listOf(
            JobPhaseUi.Preparing, JobPhaseUi.Verifying, JobPhaseUi.Unpacking, JobPhaseUi.Checking,
            JobPhaseUi.Activating, JobPhaseUi.Removing, JobPhaseUi.AwaitingBrowser("https://x", null),
            JobPhaseUi.AwaitingCode(null), JobPhaseUi.Succeeded, JobPhaseUi.Cancelled,
        ).forEach { PhaseTexts.getValue(it::class) }
        assertEquals(0 to "512.0", scaledSize(524_288))
        assertEquals(2 to "1.5", scaledSize(1_610_612_736))
    }

    private fun selected(codex: ManagedEngine): EngineConnectionsScreenState =
        EngineConnectionsScreenState(selectedEngine = CodexId.value)
            .reflect(EngineConnectionsState.Active(managedSnapshot(codex)))
}
