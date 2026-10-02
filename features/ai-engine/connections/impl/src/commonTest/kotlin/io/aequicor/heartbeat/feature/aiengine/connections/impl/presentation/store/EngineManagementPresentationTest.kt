package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
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

/** [settingsSnapshot] with engine management on, listing Koog (on) and [codex]. */
internal fun managedSnapshot(codex: ManagedEngine = managedCodex()): ConnectionsSnapshot {
    val base = settingsSnapshot()
    val koog = base.engines.single()
    val managedKoog = ManagedEngine(koog.descriptor, ManagementSpec(), EngineEnablement(), koog.availability)
    return base.copy(
        management = EngineManagementState(true, EnginePlatform.DesktopMacOs, listOf(managedKoog, codex)),
    )
}

class EngineManagementPresentationTest {
    @Test
    fun `with engine management off the space is exactly as before`() {
        val disabled = managedCodex(EngineEnablement(reasons = setOf(DisabledReason.EngineFlagOff)))
        val off = managedSnapshot(disabled).let { it.copy(management = it.management.copy(isEnabled = false)) }

        val before = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(settingsSnapshot()))
        val now = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(off))

        assertEquals(before, now)
        assertNull(now.panel)
        assertEquals(listOf(KoogId.value), now.engines.map { it.id })
    }

    @Test
    fun `every engine is listed and a switched-off one has a panel but no connections`() {
        val disabled = managedCodex(EngineEnablement(reasons = setOf(DisabledReason.EngineFlagOff)))
        val loaded = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(managedSnapshot(disabled)))
        assertEquals(listOf(true, false), loaded.engines.map { it.isEnabled })
        assertEquals(KoogId.value, loaded.panel?.id)

        val codex = loaded.copy(
            selectedEngine = CodexId.value,
        ).reflect(EngineConnectionsState.Active(managedSnapshot(disabled)))

        assertEquals(
            EngineRowUi("codex", "Codex", AvailabilityUi.Available, 2, false, isEnabled = false),
            codex.engines[1],
        )
        assertTrue(codex.connections.isEmpty())
        assertNull(codex.models)
        val panel = codex.panel!!
        assertEquals(listOf(DisabledReasonUi(DisabledReasonUi.Kind.EngineFlagOff, "ai.codex")), panel.reasons)
        assertTrue(panel.isSwitchable)
        assertEquals(setOf(EngineActionKindUi.Configure), panel.actions)
    }

    @Test
    fun `the panel shows the installation, the login and a running download`() {
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
        val rejected = selected(managedCodex()).copy(launchDraft = secret)
            .reflect(EngineConnectionsState.Active(managedSnapshot()))
        val launch = rejected.panel!!.launch!!
        assertEquals(
            listOf(LaunchProblemUi(LaunchOptionUi.Environment, LaunchProblemReasonUi.Secret, 0)),
            launch.errors,
        )
        assertNull(rejected.commandFor(EngineConnectionsScreenIntent.SaveLaunch))

        val proxy = LaunchDraftUi(
            homeDirectory = " /Users/me/.codex-work ",
            environment = persistentListOf(KeyValueUi("HTTPS_PROXY", "http://p"), KeyValueUi()),
        )
        val valid = rejected.copy(launchDraft = proxy).reflect(EngineConnectionsState.Active(managedSnapshot()))
        assertEquals(
            EngineCommand.Configure(
                LaunchSettings(
                    homeDirectory = "/Users/me/.codex-work",
                    environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://p")),
                ),
            ),
            valid.commandFor(EngineConnectionsScreenIntent.SaveLaunch),
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
                EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Login, LoginMethodUi.DeviceCode),
            ),
        )
        assertEquals(
            EngineCommand.AnswerLogin(LoginCode("abc")),
            panel.copy(loginCode = " abc ").commandFor(EngineConnectionsScreenIntent.SubmitLoginCode),
        )
        assertNull(panel.commandFor(EngineConnectionsScreenIntent.SubmitLoginCode))
        assertEquals(
            EngineCommand.Start(EngineAction.Uninstall),
            panel.copy(
                confirmAction = EngineActionUi.Uninstall,
            ).commandFor(EngineConnectionsScreenIntent.ConfirmEngineAction),
        )
        assertNull(EngineConnectionsScreenState().commandFor(EngineConnectionsScreenIntent.InspectEngine))
        assertTrue(panel.needsConfirmation(EngineActionUi.Uninstall))
        assertTrue(panel.needsConfirmation(EngineActionUi.Logout))
        assertFalse(panel.needsConfirmation(EngineActionUi.Update))
        val bundled = panel.copy(panel = panel.panel!!.copy(installation = InstallationUi(InstallSupportUi.Bundled)))
        assertTrue(bundled.needsConfirmation(EngineActionUi.Update))
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
        assertEquals(DisabledReason.entries.size, DisabledReasonUi.Kind.entries.size)
    }

    private fun selected(codex: ManagedEngine): EngineConnectionsScreenState =
        EngineConnectionsScreenState(selectedEngine = CodexId.value)
            .reflect(EngineConnectionsState.Active(managedSnapshot(codex)))
}
