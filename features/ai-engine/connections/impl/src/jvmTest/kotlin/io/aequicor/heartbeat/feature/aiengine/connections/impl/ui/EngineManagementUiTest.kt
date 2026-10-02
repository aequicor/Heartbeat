package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
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
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.KeyValueUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LaunchProblemReasonUi
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimeSummary
import kotlinx.collections.immutable.persistentListOf
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
        onNode(hasText("4.0", substring = true) or hasText("4,0", substring = true)).assertExists()
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
    fun `a device sign-in opens the provider's page and shows the code to enter`() = runSkikoComposeUiTest {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val phase = JobPhase.AwaitingBrowser("https://auth.openai.com/codex/device", "ABCD-EFGH")
        val codex = managedCodex(
            job = EngineJob(EngineAction.Login(LoginMethod.DeviceCode), phase, Instant.DISTANT_PAST),
        )
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                HbTheme { EngineConnectionsContent(selected(codex), {}, {}, {}) }
            }
        }

        onNodeWithTag("engine-login-user-code").assertTextEquals("ABCD-EFGH")
        onNodeWithTag("engine-login-open").performClick()
        runOnIdle { assertEquals(listOf("https://auth.openai.com/codex/device"), opened) }
    }

    @Test
    fun `a pasted code is sent and a terminal-only CLI leaves its command`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        var state by mutableStateOf(
            selected(
                managedCodex(job = EngineJob(EngineAction.Login(), JobPhase.AwaitingCode(), Instant.DISTANT_PAST)),
            ).copy(loginCode = "abc#def"),
        )
        setContent { HbTheme { EngineConnectionsContent(state, { intents += it }, {}, {}) } }

        onNodeWithTag("engine-login-submit").performClick()
        runOnIdle {
            assertEquals(listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.SubmitLoginCode), intents)
        }

        val terminal = ManagementFailure.Login(LoginFailureReason.RequiresTerminal, "claude auth login")
        state = selected(
            managedCodex(job = EngineJob(EngineAction.Login(), JobPhase.Failed(terminal), Instant.DISTANT_PAST)),
        )
        onNodeWithTag("engine-login-terminal").assertTextEquals("claude auth login")
    }

    @Test
    fun `launch settings are edited and a draft with a secret cannot be saved`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        var state by mutableStateOf(selected(managedCodex()))
        setContent { HbTheme { EngineConnectionsContent(state, { intents += it }, {}, {}) } }

        onNodeWithTag("engine-launch-edit").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.EditLaunch(LaunchDraftUi())),
                intents,
            )
        }

        val secret = LaunchDraftUi(environment = persistentListOf(KeyValueUi("OPENAI_API_KEY", "sk")))
        state = state.copy(launchDraft = secret).reflect(EngineConnectionsState.Active(managedSnapshot()))
        onNodeWithTag("engine-launch-env-key:0").assertExists()
        onNodeWithTag("engine-launch-save").assertIsNotEnabled()
        onNodeWithTag("engine-launch-env-remove:0").performClick()
        runOnIdle {
            assertEquals(EngineConnectionsScreenIntent.EditLaunch(LaunchDraftUi()), intents.last())
        }
    }

    @Test
    fun `every phase and installation failure has a text`() {
        LaunchProblemReasonUi.entries.forEach { ProblemTexts.getValue(it) }
        InstallFailureUi.entries.forEach { InstallFailureTexts.getValue(it) }
        listOf(
            JobPhaseUi.Preparing, JobPhaseUi.Verifying, JobPhaseUi.Unpacking, JobPhaseUi.Checking,
            JobPhaseUi.Activating, JobPhaseUi.Removing, JobPhaseUi.AwaitingBrowser("https://x", null),
            JobPhaseUi.AwaitingCode(null), JobPhaseUi.Succeeded, JobPhaseUi.Cancelled,
        ).forEach { PhaseTexts.getValue(it::class) }
        assertEquals(0 to (512L to 0L), scaledSize(524_288))
        assertEquals(2 to (1L to 5L), scaledSize(1_610_612_736))
    }

    private fun selected(codex: ManagedEngine): EngineConnectionsScreenState =
        EngineConnectionsScreenState(selectedEngine = CodexId.value)
            .reflect(EngineConnectionsState.Active(managedSnapshot(codex)))
}
