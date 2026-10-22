package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal val CodexId = EngineId("codex")
internal val CodexFlag = FeatureToggle.Flag("ai.codex", "Codex")

/** Codex as engine management lists it: a managed CLI with a browser or device sign-in and launch settings. */
internal fun managedCodex(
    enablement: EngineEnablement = EngineEnablement(),
    installation: InstallationState = InstallationState(),
    login: LoginState = LoginState.SignedOut,
    job: EngineJob? = null,
) = ManagedEngine(
    EngineDescriptor(CodexId, "Codex", EngineFamily.Vendor, setOf(EnginePlatform.DesktopMacOs), CodexFlag),
    ManagementSpec(
        InstallSupport.Managed,
        LoginSupport.CliWithDeviceCode,
        LaunchSpec(
            LaunchOption.entries.toSet(),
            homeVariable = "CODEX_HOME",
            reservedEnvironment = setOf("CODEX_HOME"),
        ),
    ),
    enablement,
    EngineAvailability.Available,
    connections = 2,
    installation = installation,
    login = login,
    job = job,
)

/** The panel machine observing engine management with [codex] (configuration off). */
internal fun managedSnapshot(codex: ManagedEngine = managedCodex(), isManaged: Boolean = true): EngineControlState =
    EngineControlState.Active(
        ControlSnapshot(
            EngineManagementState(isManaged, EnginePlatform.DesktopMacOs, listOf(codex)),
            EngineConfigurationState.Off,
        ),
    )

class EngineManagementPresentationTest {
    @Test
    fun `sign-in codes disappear when the prompt ends and sensitive UI data is redacted`() {
        val awaiting = managedSnapshot(
            managedCodex(
                job = EngineJob(
                    EngineAction.Login(LoginMethod.Browser),
                    JobPhase.AwaitingCode("https://provider.example/secret"),
                    Instant.fromEpochSeconds(1),
                ),
            ),
        )
        val screen = EngineControlPanelState(CodexId.value, loginCode = "secret")
            .observe(awaiting)
        assertEquals("secret", screen.loginCode)
        val completed = managedSnapshot(
            managedCodex(
                job = EngineJob(
                    EngineAction.Login(LoginMethod.Browser),
                    JobPhase.Succeeded,
                    Instant.fromEpochSeconds(1),
                ),
            ),
        )
        assertEquals("", screen.observe(completed).loginCode)
        assertEquals("", screen.reflect(CodexId, EngineControlState.Active()).loginCode)
        assertEquals("", screen.reflect(CodexId, EngineControlState.Idle).loginCode)
        listOf(
            screen,
            LoginUi.SignedIn("secret"),
            JobPhaseUi.AwaitingBrowser("secret", "secret"),
            JobPhaseUi.AwaitingCode("secret"),
            KeyValueUi("key", "secret"),
            EngineManagementIntent.EditLoginCode("secret"),
        ).forEach {
            assertFalse("secret" in it.toString())
        }
    }

    @Test
    fun `validation errors keep their visible row index after blank rows`() {
        val draft = LaunchDraftUi(
            environment = kotlinx.collections.immutable.persistentListOf(
                KeyValueUi("", ""),
                KeyValueUi("OPENAI_API_KEY", "secret"),
            ),
        )
        val panel = managedCodex().toPanel(draft, EnginePlatform.DesktopMacOs)
        assertEquals(1, panel.launch?.errors?.single()?.index)
    }

    @Test
    fun `with engine management off the panel has no management block`() {
        val now = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(isManaged = false))

        assertNull(now.panel)
        assertEquals("Codex", now.title)
    }

    @Test
    fun `a switched-off engine keeps its management block with the reasons`() {
        val disabled = managedCodex(EngineEnablement(reasons = setOf(DisabledReason.EngineFlagOff)))

        val panel = selected(disabled).panel!!

        assertEquals(listOf(DisabledReasonUi(DisabledReasonUi.Kind.EngineFlagOff, "ai.codex")), panel.reasons)
        assertTrue(panel.isSwitchable)
        assertEquals(setOf(EngineActionKindUi.Configure), panel.actions)
    }

    @Test
    fun `the panel shows the installation the login and a running download`() {
        val codex = managedCodex(
            installation = InstallationState(
                Installation(InstallSource.System, "0.1.0", "/bin/codex"),
                latest = "0.2.0",
            ),
            job = EngineJob(EngineAction.Install, JobPhase.Downloading(50, 200), Instant.fromEpochSeconds(1)),
        )

        val panel = selected(codex).panel!!

        assertEquals(InstallSourceUi.System, panel.installation.source)
        assertEquals("0.2.0", panel.installation.latest)
        assertEquals(LoginUi.SignedOut, panel.login)
        assertEquals(listOf(LoginMethodUi.Browser, LoginMethodUi.DeviceCode), panel.loginMethods)
        val job = panel.job!!
        assertTrue(job.isRunning)
        assertEquals(0.25f, (job.phase as JobPhaseUi.Downloading).progress)
        assertTrue(panel.actions.isEmpty())
    }

    @Test
    fun `a launch draft is checked against the engine's launch settings before saving`() {
        val secret = LaunchDraftUi(environment = persistentListOf(KeyValueUi("OPENAI_API_KEY", "sk")))
        val rejected = selected(managedCodex()).copy(launchDraft = secret).observe()
        val launch = rejected.panel!!.launch!!
        assertEquals(
            listOf(LaunchProblemUi(LaunchOptionUi.Environment, LaunchProblemReasonUi.Secret, 0)),
            launch.errors,
        )
        assertNull(rejected.commandFor(EngineManagementIntent.SaveLaunch))

        val proxy = LaunchDraftUi(
            homeDirectory = " /Users/me/.codex-work ",
            environment = persistentListOf(KeyValueUi("HTTPS_PROXY", "http://p"), KeyValueUi()),
        )
        val valid = rejected.copy(launchDraft = proxy).observe()
        assertEquals(
            EngineCommand.Configure(
                LaunchSettings(
                    homeDirectory = "/Users/me/.codex-work",
                    environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://p")),
                ),
            ),
            valid.commandFor(EngineManagementIntent.SaveLaunch),
        )
        val saved = selected(managedCodex().copy(launch = LaunchState(proxy.toSettings())))
        assertFalse(saved.panel!!.launch!!.isDirty)
    }

    @Test
    fun `engine requests become management commands and risky ones ask first`() {
        val panel = selected(managedCodex(installation = InstallationState(latest = "0.2.0")))

        assertEquals(
            EngineCommand.Start(EngineAction.Login(LoginMethod.DeviceCode)),
            panel.commandFor(
                EngineManagementIntent.RequestEngineAction(EngineActionUi.Login, LoginMethodUi.DeviceCode),
            ),
        )
        assertEquals(
            EngineCommand.AnswerLogin(LoginCode("abc")),
            panel.copy(loginCode = " abc ").commandFor(EngineManagementIntent.SubmitLoginCode),
        )
        assertNull(panel.commandFor(EngineManagementIntent.SubmitLoginCode))
        assertEquals(
            EngineCommand.Start(EngineAction.Uninstall),
            panel.copy(
                confirmAction = EngineActionUi.Uninstall,
            ).commandFor(EngineManagementIntent.ConfirmEngineAction),
        )
        assertNull(EngineControlPanelState(CodexId.value).commandFor(EngineManagementIntent.InspectEngine))
        assertTrue(panel.needsConfirmation(EngineActionUi.Uninstall))
        assertTrue(panel.needsConfirmation(EngineActionUi.Logout))
        assertTrue(panel.needsConfirmation(EngineActionUi.SyncPackages))
        assertFalse(panel.needsConfirmation(EngineActionUi.UpdatePackages))
        assertFalse(panel.needsConfirmation(EngineActionUi.Update))
        val bundled = panel.copy(panel = panel.panel!!.copy(installation = InstallationUi(InstallSupportUi.Bundled)))
        assertTrue(bundled.needsConfirmation(EngineActionUi.Update))
    }

    @Test
    fun `a host application shows what the inspection found and can be opened`() {
        val codex = managedCodex(installation = InstallationState(Installation(InstallSource.HostApp, "2.1.304")))
        val hosted = codex.copy(spec = codex.spec.copy(hostApp = HostAppSpec("Claude")))
        assertEquals(HostAppUi("Claude"), selected(hosted).panel!!.hostApp)
        assertNull(selected(codex).panel!!.hostApp)

        val inspected = hosted.copy(
            hostApp = HostAppState(
                isInstalled = true,
                version = "3.1",
                path = "/Applications/Claude.app",
                runtimes = listOf(
                    HostRuntime("2.1.305", "c", isVerified = false, path = "/r/c"),
                    HostRuntime("2.1.304", "b", isVerified = true, path = "/r/b"),
                ),
                configDirectory = "/Users/me/.claude",
            ),
        )
        val state = selected(inspected)
        val hostApp = state.panel!!.hostApp!!

        assertTrue(hostApp.isChecked && hostApp.isInstalled && hostApp.isInUse && hostApp.hasRuntime)
        assertEquals(
            listOf(HostRuntimeUi("2.1.305", "c", false), HostRuntimeUi("2.1.304", "b", true)),
            hostApp.runtimes,
        )
        assertEquals(listOf("2.1.304"), state.panel!!.launch!!.hostVersions)
        assertTrue(EngineActionKindUi.OpenHostApp in state.panel!!.actions)
        assertEquals(EngineCommand.OpenHostApp, state.commandFor(EngineManagementIntent.OpenHostApp))
    }

    @Test
    fun `the runtime choice round-trips and pins a version only for the host runtime`() {
        val codex = managedCodex()
        val spec = codex.spec.copy(
            launch = codex.spec.launch.copy(runtimes = setOf(RuntimePreference.HostApp, RuntimePreference.System)),
            hostApp = HostAppSpec("Claude"),
        )
        val state = selected(codex.copy(spec = spec))
        assertEquals(
            setOf(RuntimePreferenceUi.HostApp, RuntimePreferenceUi.System),
            state.panel!!.launch!!.runtimes,
        )

        val pinned = LaunchDraftUi(runtime = RuntimePreferenceUi.HostApp, hostRuntimeVersion = " 2.1.299 ")
        val settings = LaunchSettings(runtime = RuntimePreference.HostApp, hostRuntimeVersion = "2.1.299")
        assertEquals(settings, pinned.toSettings())
        assertEquals(pinned.copy(hostRuntimeVersion = "2.1.299"), settings.toDraft())
        assertEquals(
            LaunchSettings(runtime = RuntimePreference.System),
            pinned.copy(runtime = RuntimePreferenceUi.System).toSettings(),
        )
        assertEquals(
            EngineCommand.Configure(settings),
            state.copy(launchDraft = pinned).observe(managedSnapshot(codex.copy(spec = spec)))
                .commandFor(EngineManagementIntent.SaveLaunch),
        )
    }

    @Test
    fun `every facade value has a panel counterpart`() {
        InstallFailureReason.entries.forEach { InstallFailureUi.valueOf(it.name) }
        LoginFailureReason.entries.forEach { LoginFailureUi.valueOf(it.name) }
        LaunchProblemReason.entries.forEach { LaunchProblemReasonUi.valueOf(it.name) }
        LaunchOption.entries.forEach { LaunchOptionUi.valueOf(it.name) }
        InstallSource.entries.forEach { InstallSourceUi.valueOf(it.name) }
        InstallSupport.entries.forEach { InstallSupportUi.valueOf(it.name) }
        Compatibility.entries.forEach { CompatibilityUi.valueOf(it.name) }
        EngineActionKind.entries.forEach { EngineActionKindUi.valueOf(it.name) }
        LoginMethod.entries.forEach { LoginMethodUi.valueOf(it.name) }
        RuntimePreference.entries.forEach { RuntimePreferenceUi.valueOf(it.name) }
        assertEquals(DisabledReason.entries.size, DisabledReasonUi.Kind.entries.size)
    }

    private fun selected(codex: ManagedEngine): EngineControlPanelState =
        EngineControlPanelState(CodexId.value).observe(managedSnapshot(codex))

    private fun EngineControlPanelState.observe(snapshot: EngineControlState = managedSnapshot()) =
        reflect(CodexId, snapshot)
}
