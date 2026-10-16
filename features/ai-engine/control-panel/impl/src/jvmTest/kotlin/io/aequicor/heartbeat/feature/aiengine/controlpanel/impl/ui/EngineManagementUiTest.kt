package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CodexId
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineManagementIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.InstallFailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.KeyValueUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchProblemReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedCodex
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.reflect
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
        val state = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(isManaged = false))
        setContent { Host(state) }
        onNodeWithTag("engine-panel").assertDoesNotExist()
    }

    @Test
    fun `a missing engine is installed from the panel and a switched-off one keeps no connections`() =
        runSkikoComposeUiTest {
            val intents = mutableListOf<EngineManagementIntent>()
            val codex = managedCodex(
                EngineEnablement(isUserEnabled = false, reasons = setOf(DisabledReason.DisabledByUser)),
                installation = InstallationState(Installation(InstallSource.Missing), latest = "0.2.0"),
            )
            setContent { Host(selected(codex)) { intents += it } }

            onNodeWithTag("engine-panel").assertExists()
            onNodeWithTag("engine-install").performClick()
            onNodeWithTag("engine-enabled").performClick()
            runOnIdle {
                assertEquals(
                    listOf<EngineManagementIntent>(
                        EngineManagementIntent.RequestEngineAction(EngineActionUi.Install),
                        EngineManagementIntent.SetEngineEnabled(true),
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `a running download shows its progress and can only be cancelled`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        val codex = managedCodex(
            job = EngineJob(
                EngineAction.Install,
                JobPhase.Downloading(1_048_576, 4_194_304),
                Instant.fromEpochSeconds(1),
            ),
        )
        setContent { Host(selected(codex)) { intents += it } }

        onNodeWithTag("engine-job-progress").assertExists()
        // Units are localized; the sizes are not.
        onNode(hasText("4.0", substring = true) or hasText("4,0", substring = true)).assertExists()
        onNodeWithTag("engine-check-updates").assertIsNotEnabled()
        onNodeWithTag("engine-job-cancel").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineManagementIntent>(EngineManagementIntent.CancelEngineJob),
                intents,
            )
        }
    }

    @Test
    fun `a stale runtime is restarted`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        val codex = managedCodex().copy(runtime = RuntimeSummary(runtimes = 1, isStale = true))
        setContent { Host(selected(codex)) { intents += it } }

        onNodeWithTag("engine-restart").performClick()
        runOnIdle {
            assertEquals(listOf<EngineManagementIntent>(EngineManagementIntent.RestartEngine), intents)
        }
    }

    @Test
    fun `packages of an engine that has them are installed and updated as jobs`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        val codex = managedCodex().let { it.copy(spec = it.spec.copy(hasPackages = true)) }
        setContent { Host(selected(codex)) { intents += it } }

        onNodeWithTag("engine-packages-sync").performClick()
        onNodeWithTag("engine-packages-update").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineManagementIntent>(
                    EngineManagementIntent.RequestEngineAction(EngineActionUi.SyncPackages),
                    EngineManagementIntent.RequestEngineAction(EngineActionUi.UpdatePackages),
                ),
                intents,
            )
        }
    }

    @Test
    fun `a removal is confirmed first`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        val state = selected(managedCodex()).copy(confirmAction = EngineActionUi.Uninstall)
        setContent { Host(state) { intents += it } }

        onNodeWithTag("engine-action-confirm").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineManagementIntent>(EngineManagementIntent.ConfirmEngineAction),
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
                Host(selected(codex))
            }
        }

        onNodeWithTag("engine-login-user-code").assertTextEquals("ABCD-EFGH")
        onNodeWithTag("engine-login-open").performClick()
        runOnIdle { assertEquals(listOf("https://auth.openai.com/codex/device"), opened) }
    }

    @Test
    fun `a pasted code is sent and a terminal-only CLI leaves its command`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        var state by mutableStateOf(
            selected(
                managedCodex(job = EngineJob(EngineAction.Login(), JobPhase.AwaitingCode(), Instant.DISTANT_PAST)),
            ).copy(loginCode = "abc#def"),
        )
        setContent { Host(state) { intents += it } }

        onNodeWithTag("engine-login-submit").performClick()
        runOnIdle {
            assertEquals(listOf<EngineManagementIntent>(EngineManagementIntent.SubmitLoginCode), intents)
        }

        val terminal = ManagementFailure.Login(LoginFailureReason.RequiresTerminal, "claude auth login")
        state = selected(
            managedCodex(job = EngineJob(EngineAction.Login(), JobPhase.Failed(terminal), Instant.DISTANT_PAST)),
        )
        onNodeWithTag("engine-login-terminal").assertTextEquals("claude auth login")
    }

    @Test
    fun `launch settings are edited and a draft with a secret cannot be saved`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineManagementIntent>()
        var state by mutableStateOf(selected(managedCodex()))
        setContent { Host(state) { intents += it } }

        onNodeWithTag("engine-launch-edit").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineManagementIntent>(EngineManagementIntent.EditLaunch(LaunchDraftUi())),
                intents,
            )
        }

        val secret = LaunchDraftUi(environment = persistentListOf(KeyValueUi("OPENAI_API_KEY", "sk")))
        state = state.copy(launchDraft = secret).observe()
        onNodeWithTag("engine-launch-env-key:0").assertExists()
        onNodeWithTag("engine-launch-save").assertIsNotEnabled()
        onNodeWithTag("engine-launch-env-remove:0").performClick()
        runOnIdle {
            assertEquals(EngineManagementIntent.EditLaunch(LaunchDraftUi()), intents.last())
        }
    }

    @Test
    fun `management writes block panel actions before delayed feedback`() =
        runSkikoComposeUiTest(size = Size(1000f, 1600f)) {
            val loaded = selected(managedCodex())
            var state by mutableStateOf(loaded)
            val intents = mutableListOf<EngineManagementIntent>()
            val tags = listOf(
                "engine-enabled",
                "engine-inspect",
                "engine-check-updates",
                "engine-install",
                "engine-launch-edit",
            )
            setContent { Host(state, intents::add) }
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
            mainClock.autoAdvance = false
            runOnIdle { state = loaded.copy(isSaving = true, isBusy = true) }
            mainClock.advanceTimeByFrame()
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
            }
            runOnIdle { assertEquals(emptyList(), intents) }
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            tags.forEach { onNodeWithTag(it).assertIsNotEnabled() }
            runOnIdle { state = loaded }
            mainClock.advanceTimeByFrame()
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
        }

    @Test
    fun `a pending login code submission blocks repeated submit and job cancellation immediately`() =
        runSkikoComposeUiTest(size = Size(1000f, 1600f)) {
            val loaded = selected(
                managedCodex(job = EngineJob(EngineAction.Login(), JobPhase.AwaitingCode(), Instant.DISTANT_PAST)),
            ).copy(loginCode = "code")
            var state by mutableStateOf(loaded)
            val intents = mutableListOf<EngineManagementIntent>()
            setContent { Host(state, intents::add) }
            val tags = listOf("engine-login-submit", "engine-job-cancel")
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
            mainClock.autoAdvance = false
            runOnIdle { state = loaded.copy(isSaving = true, isBusy = true) }
            mainClock.advanceTimeByFrame()
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
            }
            runOnIdle { assertEquals(emptyList(), intents) }
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            tags.forEach { onNodeWithTag(it).assertIsNotEnabled() }
            runOnIdle { state = loaded }
            mainClock.advanceTimeByFrame()
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
        }

    @Test
    fun `saving a launch draft blocks a second save before visual feedback`() =
        runSkikoComposeUiTest(size = Size(1000f, 1600f)) {
            val draft = LaunchDraftUi(environment = persistentListOf(KeyValueUi("MY_SETTING", "value")))
            val loaded = selected(managedCodex()).copy(launchDraft = draft).observe()
            var state by mutableStateOf(loaded)
            val intents = mutableListOf<EngineManagementIntent>()
            setContent { Host(state, intents::add) }
            onNodeWithTag("engine-launch-save").assertIsEnabled()
            mainClock.autoAdvance = false
            runOnIdle { state = loaded.copy(isSaving = true, isBusy = true) }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("engine-launch-save").assertIsNotEnabled().performMouseInput {
                moveTo(center)
                press()
                release()
                exit()
            }
            runOnIdle { assertEquals(emptyList(), intents) }
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            onNodeWithTag("engine-launch-save").assertIsNotEnabled()
            runOnIdle { state = loaded }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("engine-launch-save").assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(listOf<EngineManagementIntent>(EngineManagementIntent.SaveLaunch), intents)
            }
        }

    @Test
    fun `every phase and installation failure has a text`() {
        LaunchProblemReasonUi.entries.forEach { ProblemTexts.getValue(it) }
        InstallFailureUi.entries.forEach { InstallFailureTexts.getValue(it) }
        FailureUi.entries.forEach { FailureMessages.getValue(it) }
        listOf(
            JobPhaseUi.Preparing, JobPhaseUi.Verifying, JobPhaseUi.Unpacking, JobPhaseUi.Checking,
            JobPhaseUi.Activating, JobPhaseUi.Removing, JobPhaseUi.AwaitingBrowser("https://x", null),
            JobPhaseUi.AwaitingCode(null), JobPhaseUi.Succeeded, JobPhaseUi.Cancelled,
        ).forEach { PhaseTexts.getValue(it::class) }
        assertEquals(0 to (512L to 0L), scaledSize(524_288))
        assertEquals(2 to (1L to 5L), scaledSize(1_610_612_736))
    }

    private fun selected(codex: ManagedEngine): EngineControlPanelState =
        EngineControlPanelState(CodexId.value).observe(codex)

    private fun EngineControlPanelState.observe(codex: ManagedEngine = managedCodex()) =
        reflect(CodexId, managedSnapshot(codex))

    /** Shows [state] on its screen, passing on only the management block's intents. */
    @Composable
    private fun Host(state: EngineControlPanelState, onIntent: (EngineManagementIntent) -> Unit = {}) {
        HbTheme { EngineControlPanelContent(state, { if (it is EngineManagementIntent) onIntent(it) }) }
    }
}
