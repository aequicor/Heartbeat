package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.CheckForUpdates
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Configure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Inspect
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Install
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Login
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Logout
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Restart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Uninstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind.Update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EngineManagementTest {
    private val cli = ManagementSpec(
        InstallSupport.Managed,
        LoginSupport.Cli,
        LaunchSpec(options = setOf(LaunchOption.Executable)),
    )

    @Test
    fun `a missing CLI can be installed but not signed in`() {
        val engine = engine(cli, InstallationState(current = Installation(InstallSource.Missing)))

        assertEquals(setOf(Configure, Inspect, CheckForUpdates, Install), engine.actions())
    }

    @Test
    fun `a system CLI can get Heartbeat's own copy and sign in`() {
        val engine = engine(
            cli,
            InstallationState(current = Installation(InstallSource.System, "1.0.0")),
            login = LoginState.SignedOut,
        )

        assertEquals(setOf(Configure, Inspect, CheckForUpdates, Install, Login), engine.actions())
    }

    @Test
    fun `Heartbeat's copy is updated and removed and a signed in CLI signs out`() {
        val managed = ManagedInstall("1.0.0", "/data/codex", Instant.fromEpochSeconds(1))
        val engine = engine(
            cli,
            InstallationState(Installation(InstallSource.Managed, "1.0.0"), managed, latest = "1.1.0"),
            login = LoginState.SignedIn("me@example.com"),
            runtime = RuntimeSummary(runtimes = 1, openSessions = 1),
        )

        assertEquals(setOf(Configure, Inspect, CheckForUpdates, Update, Uninstall, Logout, Restart), engine.actions())
    }

    @Test
    fun `a bundled engine updates past its bundled version but has nothing to remove`() {
        val spec = ManagementSpec(InstallSupport.Bundled, LoginSupport.Connections)
        val engine = engine(spec, InstallationState(bundledVersion = "0.87.1", latest = "0.99.2"))

        assertEquals(setOf(Inspect, CheckForUpdates, Update), engine.actions())
    }

    @Test
    fun `a running job or a running turn blocks the actions it would disturb`() {
        val job = EngineJob(EngineAction.Install, JobPhase.Downloading(10, 100), Instant.fromEpochSeconds(1))
        assertEquals(emptySet(), engine(cli, job = job).actions())
        val busy = engine(cli, runtime = RuntimeSummary(runtimes = 1, openSessions = 1, activeTurns = 1))
        assertFalse(Restart in busy.actions())
        val finished = engine(cli, job = job.copy(phase = JobPhase.Succeeded))
        assertTrue(Inspect in finished.actions())
    }

    @Test
    fun `a flag or the platform leaves only launch settings, the profile switch does not`() {
        val flagOff = engine(cli, reasons = setOf(DisabledReason.EngineFlagOff))
        assertEquals(setOf(Configure), flagOff.actions())
        val unsupported = engine(
            cli,
            reasons = setOf(DisabledReason.UnsupportedPlatform, DisabledReason.DisabledByUser),
        )
        assertEquals(setOf(Configure), unsupported.actions())
        val userOff = engine(cli, reasons = setOf(DisabledReason.DisabledByUser))
        assertTrue(Install in userOff.actions())
        assertTrue(userOff.enablement.isOnlyUserDisabled)
        assertFalse(userOff.enablement.isEnabled)
    }

    @Test
    fun `newer versions compare numerically and releases outrank pre-releases`() {
        assertTrue(isNewerVersion("0.159.3", "0.159.2"))
        assertTrue(isNewerVersion("0.160.0", "0.159.10"))
        assertTrue(isNewerVersion("2.1.286", "2.1.86"))
        assertTrue(isNewerVersion("1.0.0", "1.0.0-beta"))
        assertTrue(isNewerVersion("v0.99.2", "0.87.1"))
        assertFalse(isNewerVersion("0.159.2", "0.159.2"))
        assertFalse(isNewerVersion("1.0.0-beta", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0+build.5", "1.0.0"))
        assertFalse(isNewerVersion("latest", "1.0.0"))
        assertTrue(isNewerVersion("1.0.0", "unknown"))
    }

    @Test
    fun `an update is available against the copy Heartbeat would replace`() {
        assertTrue(InstallationState(bundledVersion = "0.87.1", latest = "0.99.2").isUpdateAvailable)
        assertFalse(InstallationState(bundledVersion = "0.99.2", latest = "0.99.2").isUpdateAvailable)
        val managed = ManagedInstall("0.99.2", "/data/pi", Instant.fromEpochSeconds(1))
        assertFalse(
            InstallationState(managed = managed, bundledVersion = "0.87.1", latest = "0.99.2").isUpdateAvailable,
        )
        assertFalse(InstallationState(current = Installation(InstallSource.System, "1.0.0")).isUpdateAvailable)
    }

    @Test
    fun `sign-in details and paths never print`() {
        val printed = listOf(
            LoginState.SignedIn("me@example.com"),
            JobPhase.AwaitingBrowser("https://auth.example.com/?state=s3cr3t", "ABCD-1234"),
            EngineCommand.AnswerLogin(LoginCode("code-1234")),
            Installation(InstallSource.Custom, "1.0", "/Users/me/bin/codex"),
            ManagementFailure.Login(
                LoginFailureReason.RequiresTerminal,
                "CLAUDE_CONFIG_DIR=/Users/me claude auth login",
            ),
        ).joinToString()

        listOf(
            "me@example.com",
            "s3cr3t",
            "ABCD-1234",
            "code-1234",
            "/Users/me",
        ).forEach { assertFalse(it in printed, it) }
        assertEquals(LoginCode("x"), LoginCode("x"))
    }

    @Test
    fun `unavailable management is off and refuses commands`() = runTest {
        val management = UnavailableEngineManagement()

        assertEquals(EngineManagementState.Off, management.state.value)
        val error = assertFailsWith<EngineException> { management.execute(EngineId("pi"), EngineCommand.Inspect) }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), error.failure)
    }

    private fun engine(
        spec: ManagementSpec,
        installation: InstallationState = InstallationState(),
        login: LoginState = LoginState.NotApplicable,
        runtime: RuntimeSummary = RuntimeSummary(),
        job: EngineJob? = null,
        reasons: Set<DisabledReason> = emptySet(),
    ) = ManagedEngine(
        EngineDescriptor(
            EngineId("cli"),
            "CLI",
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs),
            FeatureToggle.Flag("ai.cli", "CLI"),
        ),
        spec,
        EngineEnablement(isUserEnabled = DisabledReason.DisabledByUser !in reasons, reasons = reasons),
        EngineAvailability.Unknown,
        installation = installation,
        login = login,
        runtime = runtime,
        job = job,
    )
}
