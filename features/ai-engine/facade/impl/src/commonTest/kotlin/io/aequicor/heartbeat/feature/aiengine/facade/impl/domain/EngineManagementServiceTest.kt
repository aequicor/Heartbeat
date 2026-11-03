package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibilityProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.actions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.MemoryEnginePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EngineManagementServiceTest {
    private val cliId = EngineId("cli")
    private val builtInId = EngineId("builtin")
    private val mobileId = EngineId("mobile")
    private val manager = FakeManager()
    private val cliSpec = ManagementSpec(
        InstallSupport.Managed,
        LoginSupport.Cli,
        LaunchSpec(setOf(LaunchOption.Executable, LaunchOption.Environment), reservedEnvironment = setOf("CLI_HOME")),
        hasPackages = true,
    )
    private val registry = EngineRegistry(
        listOf(
            registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(cliSpec, manager),
            registration(id = builtInId, owner = AuthOwnerId("builtin")),
            registration(id = mobileId, owner = AuthOwnerId("mobile"), platforms = setOf(EnginePlatform.Android)),
        ),
        EnginePlatform.DesktopMacOs,
    )
    private val flags = FakeFlags(registry)
    private val preferences = MemoryEnginePreferences()
    private val catalog = FakeCatalog()
    private val bindings = MutableStateFlow(listOf(EngineBinding(EngineBindingId("b1"), cliId, AuthSourceId("s1"))))
    private val runtimes = FakeRuntimes()
    private val installs = FakeInstalls()
    private var launchContext = LaunchContext()

    private fun observation(
        owner: EngineId = cliId,
        runtime: String = "1.2.0",
        isBaselineConfirmed: Boolean? = true,
    ): EngineCompatibilityObservation {
        val protocol = EngineProtocolIdentity.ProbedSchema("test-jsonl", "schema-1")
        val requirements = EngineCompatibilityRequirements(
            engine = owner,
            heartbeatVersions = setOf("heartbeat-test"),
            connectorVersions = setOf("connector-test"),
            minimumRuntime = "1.0.0",
            protocols = setOf(protocol),
            configurationSchemas = setOf(1),
            historySchemas = setOf(1),
            nativeTargets = setOf("darwin-arm64"),
            baselineCapabilities = setOf("cancel"),
        )
        return EngineCompatibilityObservation(
            requirements,
            EngineCompatibilityEvidence(
                EngineCompatibilityIdentity(
                    owner, "heartbeat-test", "connector-test", runtime, protocol, 1, 1, "darwin-arm64", "digest",
                ),
                isBaselineConfirmed,
                true,
                setOf("cancel"),
            ),
        )
    }

    @Test
    fun `an inspect failure never starts login or full availability probes`() = runTest {
        manager.failure = EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(0, manager.compatibilityChecks)
        assertEquals(0, manager.loginChecks)
        assertTrue(catalog.refreshes.isEmpty())
        assertEquals(LoginState.Unknown, service.state.value.engine(cliId).login)
    }

    @Test
    fun `a launch change waits for its old inspect and never restores the old report afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.observation = observation()
        manager.compatibilityGate = gate
        val service = service()
        runCurrent()
        val inspecting = async { service.execute(cliId, EngineCommand.Inspect) }
        runCurrent()
        assertEquals(1, manager.compatibilityChecks)
        service.execute(builtInId, EngineCommand.Inspect)
        assertEquals(0L, testScheduler.currentTime)
        val configuring = async {
            service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other/cli")))
        }
        runCurrent()
        assertFalse(configuring.isCompleted)
        gate.complete(Unit)
        inspecting.await()
        configuring.await()
        runCurrent()
        val changed = service.state.value.engine(cliId)
        assertEquals("/other/cli", changed.launch.settings.executable)
        assertNull(changed.installation.current)
        assertNull(changed.installation.compatibilityObservation)
        assertEquals(Compatibility.Unknown, changed.installation.compatibility)
        assertEquals(1, manager.compatibilityChecks)
    }

    @Test
    fun `a catalog timeout releases inspect so a queued launch change can finish`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.observation = observation()
        catalog.gate = CompletableDeferred()
        val service = service()
        runCurrent()
        val inspecting = async { service.execute(cliId, EngineCommand.Inspect) }
        runCurrent()
        assertEquals(listOf(cliId), catalog.refreshes)
        val configuring = async {
            service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other/cli")))
        }
        runCurrent()
        assertFalse(configuring.isCompleted)
        advanceTimeBy(15_000)
        runCurrent()
        inspecting.await()
        configuring.await()
        runCurrent()
        assertEquals(15_000L, testScheduler.currentTime)
        val changed = service.state.value.engine(cliId)
        assertEquals("/other/cli", changed.launch.settings.executable)
        assertNull(changed.installation.compatibilityObservation)
    }

    @Test
    fun `a source change also clears a failed structured probe snapshot`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.compatibilityFailure = EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertNotNull(service.state.value.engine(cliId).installation.failure)
        service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other/cli")))
        runCurrent()
        val changed = service.state.value.engine(cliId).installation
        assertNull(changed.current)
        assertNull(changed.failure)
        assertNull(changed.compatibilityObservation)
    }

    @Test
    fun `structured compatibility is probed only by inspect and exposes facts with requirements`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.observation = observation()
        val service = service()
        runCurrent()
        assertEquals(0, manager.compatibilityChecks)
        assertNull(service.state.value.engine(cliId).installation.compatibilityObservation)
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        val result = service.state.value.engine(cliId).installation
        assertEquals(1, manager.compatibilityChecks)
        assertEquals(Compatibility.Unverified, result.compatibility)
        assertEquals(manager.observation, result.compatibilityObservation)
        assertTrue(result.compatibilityObservation!!.decision.isBaselineAllowed)
    }

    @Test
    fun `a failed baseline overrides legacy metadata and a later probe failure removes its old report`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.legacyCompatibility = Compatibility.Verified
        manager.observation = observation(isBaselineConfirmed = false)
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        val incompatible = service.state.value.engine(cliId).installation
        assertEquals(Compatibility.Incompatible, incompatible.compatibility)
        assertEquals(0, manager.loginChecks)
        assertTrue(catalog.refreshes.isEmpty())
        assertTrue(CompatibilityProblem.BaselineRejected in incompatible.compatibilityObservation!!.decision.problems)
        manager.compatibilityFailure = EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        val failed = service.state.value.engine(cliId).installation
        assertEquals(Compatibility.Unknown, failed.compatibility)
        assertNull(failed.compatibilityObservation)
        assertEquals(0, manager.loginChecks)
        assertTrue(catalog.refreshes.isEmpty())
        assertEquals(
            ManagementFailure.Engine(EngineFailure.Transport(TransportFailureReason.Timeout)),
            failed.failure,
        )
    }

    @Test
    fun `foreign engine and changed artifact version reports cannot confirm the inspected installation`() = runTest {
        val service = service()
        runCurrent()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        for (report in listOf(observation(owner = builtInId), observation(runtime = "1.3.0"))) {
            manager.observation = report
            service.execute(cliId, EngineCommand.Inspect)
            runCurrent()
            val failed = service.state.value.engine(cliId).installation
            assertEquals(Compatibility.Unknown, failed.compatibility)
            assertNull(failed.compatibilityObservation)
            assertEquals(
                ManagementFailure.Engine(EngineFailure.Transport(TransportFailureReason.ProtocolViolation)),
                failed.failure,
            )
        }
    }

    @Test
    fun `changing the selected launch source discards its structured report without a hidden probe`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/test/cli")
        manager.observation = observation()
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other/cli")))
        runCurrent()
        val changed = service.state.value.engine(cliId).installation
        assertEquals(Compatibility.Unknown, changed.compatibility)
        assertNull(changed.compatibilityObservation)
        assertNull(changed.current)
        assertEquals(1, manager.compatibilityChecks)
    }

    @Test
    fun `CLI preparing its runtime on login can sign in when installation is missing`() = runTest {
        var logins = 0
        var inspections = 0
        val preparingManager = object : EngineManager by manager {
            override suspend fun inspect(launch: LaunchContext): Installation {
                inspections++
                return Installation(InstallSource.Missing, isRunnable = false)
            }
            override suspend fun loginStatus(launch: LaunchContext): LoginState = LoginState.SignedOut
            override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState {
                logins++
                return LoginState.SignedIn("account")
            }
        }
        val preparingRegistry = EngineRegistry(
            listOf(
                registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(
                    cliSpec.copy(isRuntimePreparedOnLogin = true),
                    preparingManager,
                ),
            ),
            EnginePlatform.DesktopMacOs,
        )
        val service = service(preparingRegistry)
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(InstallSource.Missing, service.state.value.engine(cliId).installation.current?.source)
        service.execute(cliId, EngineCommand.Start(EngineAction.Login()))
        runCurrent()
        assertEquals(1, logins)
        assertEquals(2, inspections)
        assertEquals(
            io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase.Succeeded,
            service.state.value.engine(cliId).job?.phase,
        )
    }

    @Test
    fun `editing other launch fields while MCP rollout is off preserves the saved opt-in`() = runTest {
        val mcpSpec = cliSpec.copy(launch = cliSpec.launch.copy(options = cliSpec.launch.options + LaunchOption.Mcp))
        val mcpRegistry = EngineRegistry(
            listOf(registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(mcpSpec, manager)),
            EnginePlatform.DesktopMacOs,
        )
        val saved = LaunchSettings(executable = "/old/cli", isMcpEnabled = true)
        preferences.update { it.copy(launch = mapOf(cliId to saved)) }
        val service = service(mcpRegistry)
        runCurrent()
        assertFalse(service.state.value.engine(cliId).launch.settings.isMcpEnabled)
        assertFalse(LaunchOption.Mcp in service.state.value.engine(cliId).spec.launch.options)

        val changed = LaunchSettings(executable = "/new/cli")
        service.execute(cliId, EngineCommand.Configure(changed))
        runCurrent()

        assertEquals(changed.copy(isMcpEnabled = true), preferences.current.value.launchOf(cliId))
        assertEquals(changed, service.state.value.engine(cliId).launch.settings)
        assertEquals(listOf(changed), manager.checked)
    }

    @Test
    fun `reconciliation survives an unexpected runtime exception and handles the next state`() = runTest {
        val service = service()
        runCurrent()
        runtimes.failure = IllegalStateException("runtime crashed")
        runtimes.entries.value = listOf(RuntimeEntry(cliId, AuthSourceId("s1"), Instant.fromEpochSeconds(1), true))
        runCurrent()
        assertTrue(service.state.value.engine(cliId).runtime.hasExited)
        runtimes.failure = null
        runtimes.sessions.value = mapOf(cliId to SessionCounts(1, 0))
        runCurrent()
        assertFalse(service.state.value.engine(cliId).runtime.hasExited)
        assertEquals(listOf(cliId, cliId), runtimes.retired)
    }

    @Test
    fun `managed copies wait until management is enabled and unsupported engines cannot restart`() = runTest {
        flags.management.value = false
        val service = service()
        runCurrent()
        assertEquals(0, installs.refreshes)
        flags.management.value = true
        runCurrent()
        assertEquals(1, installs.refreshes)
        assertRefused { service.execute(mobileId, EngineCommand.Restart) }
        flags.engines.getValue(cliId).value = false
        runCurrent()
        assertRefused { service.execute(cliId, EngineCommand.Restart) }
    }

    @Test
    fun `management off is the off state and refuses commands`() = runTest {
        flags.management.value = false
        val service = service()
        runCurrent()

        assertEquals(EngineManagementState.Off, service.state.value)
        assertRefused { service.execute(cliId, EngineCommand.Inspect) }
    }

    @Test
    fun `every registered engine is listed with why it is off`() = runTest {
        flags.engines.getValue(cliId).value = false
        preferences.update { it.copy(disabled = setOf(builtInId)) }
        val service = service()
        runCurrent()

        val state = service.state.value
        assertEquals(EnginePlatform.DesktopMacOs, state.platform)
        assertEquals(listOf(builtInId, cliId, mobileId), state.engines.map { it.descriptor.id })
        assertEquals(setOf(DisabledReason.EngineFlagOff), state.engine(cliId).enablement.reasons)
        assertEquals(setOf(DisabledReason.DisabledByUser), state.engine(builtInId).enablement.reasons)
        assertEquals(setOf(DisabledReason.UnsupportedPlatform), state.engine(mobileId).enablement.reasons)
        assertEquals(EngineAvailability.UnsupportedPlatform, state.engine(mobileId).availability)
        assertEquals(1, state.engine(cliId).connections)
        assertEquals(Installation(InstallSource.BuiltIn), state.engine(builtInId).installation.current)
        assertEquals(LoginState.Unknown, state.engine(cliId).login)
        assertRefused { service.execute(cliId, EngineCommand.Inspect) }
    }

    @Test
    fun `switching an engine off saves the choice and stops its idle runtimes`() = runTest {
        val service = service()
        runCurrent()

        service.execute(cliId, EngineCommand.SetEnabled(false))
        runCurrent()

        assertEquals(setOf(cliId), preferences.current.value.disabled)
        assertEquals(listOf(cliId), runtimes.retired)
        assertEquals(setOf(DisabledReason.DisabledByUser), service.state.value.engine(cliId).enablement.reasons)
        service.execute(cliId, EngineCommand.SetEnabled(true))
        runCurrent()
        assertTrue(service.state.value.engine(cliId).enablement.isEnabled)
    }

    @Test
    fun `an inspection records the adapter's installation login and launch warnings`() = runTest {
        manager.installation = Installation(InstallSource.System, "1.2.0", "/usr/local/bin/cli")
        manager.login = LoginState.SignedIn("me@example.com")
        manager.warnings = listOf(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound))
        launchContext = LaunchContext(LaunchSettings(executable = "/opt/cli"))
        val service = service()
        runCurrent()

        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()

        val engine = service.state.value.engine(cliId)
        assertEquals(manager.installation, engine.installation.current)
        assertEquals(manager.login, engine.login)
        assertEquals(manager.warnings, engine.launch.warnings)
        assertEquals(launchContext, manager.inspected.single())
        assertEquals(listOf(cliId), catalog.refreshes)
        assertEquals(1, runtimes.prunes)
    }

    @Test
    fun `a host application is inspected only for engines that declare one and opens on request`() = runTest {
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(0, manager.hostInspections)
        assertNull(service.state.value.engine(cliId).hostApp)
        assertRefused { service.execute(cliId, EngineCommand.OpenHostApp) }
    }

    @Test
    fun `an inspected host application is shown and can be opened`() = runTest {
        val hosted = FakeManager().apply { hostApp = HostAppState(isInstalled = true, version = "3.1") }
        val spec = cliSpec.copy(hostApp = HostAppSpec("Host"))
        val hostedRegistry = EngineRegistry(
            listOf(registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(spec, hosted)),
            EnginePlatform.DesktopMacOs,
        )
        val service = service(hostedRegistry)
        runCurrent()
        assertRefused { service.execute(cliId, EngineCommand.OpenHostApp) }

        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(hosted.hostApp, service.state.value.engine(cliId).hostApp)

        service.execute(cliId, EngineCommand.OpenHostApp)
        assertEquals(1, hosted.opened)

        hosted.openFailure = IllegalStateException("launcher broke")
        val crashed = assertFailsWith<EngineException> { service.execute(cliId, EngineCommand.OpenHostApp) }
        assertEquals(EngineFailure.Unknown(), crashed.failure)
        val missing = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        hosted.openFailure = missing
        assertEquals(
            missing.failure,
            assertFailsWith<EngineException> { service.execute(cliId, EngineCommand.OpenHostApp) }.failure,
        )
    }

    @Test
    fun `a failing host application inspection is recorded with the installation`() = runTest {
        val hosted = FakeManager().apply {
            hostFailure = EngineException(
                EngineFailure.Engine(EngineFailureReason.Unavailable),
            )
        }
        val spec = cliSpec.copy(hostApp = HostAppSpec("Host"))
        val hostedRegistry = EngineRegistry(
            listOf(registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(spec, hosted)),
            EnginePlatform.DesktopMacOs,
        )
        val service = service(hostedRegistry)
        runCurrent()

        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()

        val hostApp = assertNotNull(service.state.value.engine(cliId).hostApp)
        assertEquals(ManagementFailure.Engine(EngineFailure.Engine(EngineFailureReason.Unavailable)), hostApp.failure)
        assertFalse(hostApp.isInstalled)
    }

    @Test
    fun `a failing or hanging adapter is recorded never thrown`() = runTest {
        manager.failure = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val service = service()
        runCurrent()
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(
            ManagementFailure.Engine(manager.failure!!.failure),
            service.state.value.engine(cliId).installation.failure,
        )

        manager.failure = null
        manager.hangs = true
        service.execute(cliId, EngineCommand.Inspect)
        runCurrent()
        assertEquals(
            ManagementFailure.Engine(EngineFailure.Transport(TransportFailureReason.Timeout)),
            service.state.value.engine(cliId).installation.failure,
        )
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun `an update check records the newest release or why it failed`() = runTest {
        val service = service()
        runCurrent()

        service.execute(cliId, EngineCommand.CheckForUpdates)
        runCurrent()
        assertEquals("2.0.0", service.state.value.engine(cliId).installation.latest)

        manager.releaseFailure = ManagementException(ManagementFailure.Install(InstallFailureReason.RateLimited))
        service.execute(cliId, EngineCommand.CheckForUpdates)
        runCurrent()
        val installation = service.state.value.engine(cliId).installation
        assertEquals("2.0.0", installation.latest)
        assertEquals(ManagementFailure.Install(InstallFailureReason.RateLimited), installation.failure)
        assertRefused { service.execute(builtInId, EngineCommand.CheckForUpdates) }
    }

    @Test
    fun `launch settings are validated saved and applied by stopping idle runtimes`() = runTest {
        val service = service()
        runCurrent()
        val invalid = LaunchSettings(environment = listOf(EnvironmentEntry("CLI_HOME", "/tmp")))

        val error = assertFailsWith<EngineException> { service.execute(cliId, EngineCommand.Configure(invalid)) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), error.failure)

        val settings = LaunchSettings(executable = "/opt/cli")
        service.execute(cliId, EngineCommand.Configure(settings))
        runCurrent()

        assertEquals(settings, preferences.current.value.launchOf(cliId))
        assertEquals(settings, service.state.value.engine(cliId).launch.settings)
        assertEquals(listOf(cliId), runtimes.retired)
        assertEquals(listOf(settings), manager.checked)
    }

    @Test
    fun `restarting with a turn in flight is refused as busy`() = runTest {
        runtimes.busy = 1
        val service = service()
        runCurrent()

        val error = assertFailsWith<EngineException> { service.execute(cliId, EngineCommand.Restart) }

        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
    }

    @Test
    fun `reconciliation delegates per-source busy checks even when the engine has active turns`() = runTest {
        val service = service()
        runCurrent()
        val stale = RuntimeEntry(cliId, AuthSourceId("s1"), Instant.fromEpochSeconds(1), isClosed = false)
        runtimes.sessions.value = mapOf(cliId to SessionCounts(open = 1, activeTurns = 1))
        runtimes.entries.value = listOf(stale)
        preferences.update { it.copy(launch = mapOf(cliId to LaunchSettings(executable = "/opt/new"))) }
        runCurrent()
        assertFalse(service.state.value.engine(cliId).runtime.isStale)
        assertEquals(listOf(cliId), runtimes.retired)

        runtimes.sessions.value = mapOf(cliId to SessionCounts(open = 1, activeTurns = 0))
        runCurrent()

        assertEquals(listOf(cliId), runtimes.retired)
    }

    @Test
    fun `a configuration change makes runtimes stale only while engine configuration is on`() = runTest {
        val service = service()
        runCurrent()
        runtimes.entries.value = listOf(RuntimeEntry(cliId, AuthSourceId("s1"), Instant.fromEpochSeconds(1), false))
        val values = EngineConfigValues(mapOf(ConfigDocumentId("agents") to ConfigDocument.Text("Be brief")))
        preferences.update { it.copy(config = mapOf(cliId to ProfileEngineConfig(values, revision = 1))) }
        runCurrent()
        assertFalse(service.state.value.engine(cliId).runtime.isStale)
        assertEquals(emptyList(), runtimes.retired)

        flags.configuration.value = true
        runCurrent()

        assertEquals(listOf(cliId), runtimes.retired)
        assertEquals(LaunchContext(config = values, isConfigured = true), runtimes.expected.last())
    }

    @Test
    fun `packages are offered only while engine configuration is on`() = runTest {
        val service = service()
        runCurrent()
        assertFalse(service.state.value.engine(cliId).spec.hasPackages)
        assertFalse(EngineActionKind.Packages in service.state.value.engine(cliId).actions())

        flags.configuration.value = true
        runCurrent()

        assertTrue(EngineActionKind.Packages in service.state.value.engine(cliId).actions())
    }

    @Test
    fun `a job starts only where its action applies and shows on the engine`() = runTest {
        val service = service()
        runCurrent()

        assertRefused { service.execute(builtInId, EngineCommand.Start(EngineAction.Install)) }
        assertRefused { service.execute(cliId, EngineCommand.Start(EngineAction.Login(LoginMethod.DeviceCode))) }
        assertRefused { service.execute(cliId, EngineCommand.Dismiss) }

        service.execute(cliId, EngineCommand.Start(EngineAction.Install))
        runCurrent()

        val job = assertNotNull(service.state.value.engine(cliId).job)
        assertEquals(EngineAction.Install, job.action)
        assertTrue(job.phase.isFinished)
    }

    @Test
    fun `the managed copy is read once at start and shown with the installation`() = runTest {
        val copy = ManagedInstall("1.0.0", "/data/cli", Instant.fromEpochSeconds(5))
        installs.loaded = mapOf(cliId to copy)
        val service = service()
        runCurrent()

        assertEquals(1, installs.refreshes)
        assertEquals(copy, service.state.value.engine(cliId).installation.managed)
    }

    private fun TestScope.service(registry: EngineRegistry = this@EngineManagementServiceTest.registry) =
        EngineManagementService(
            registry,
            if (registry === this@EngineManagementServiceTest.registry) flags else FakeFlags(registry),
            preferences,
            catalog,
            bindings,
            runtimes,
            installs,
            EngineLaunchConfig { launchContext },
            UnusedFeeds,
            CliConnections { },
            facadeContext(),
        )

    private suspend fun assertRefused(block: suspend () -> Unit) {
        val error = assertFailsWith<EngineException> { block() }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), error.failure)
    }

    private fun EngineManagementState.engine(id: EngineId): ManagedEngine = engines.single { it.descriptor.id == id }
}

class EngineManagementConditionalLaunchTest {
    private val cliId = EngineId("cli")
    private val builtInId = EngineId("builtin")
    private val manager = FakeManager()
    private val cliSpec = ManagementSpec(
        InstallSupport.Managed,
        LoginSupport.Cli,
        LaunchSpec(setOf(LaunchOption.Executable, LaunchOption.Environment), reservedEnvironment = setOf("CLI_HOME")),
        hasPackages = true,
    )
    private val registry = EngineRegistry(
        listOf(
            registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(cliSpec, manager),
            registration(id = builtInId, owner = AuthOwnerId("builtin")),
        ),
        EnginePlatform.DesktopMacOs,
    )
    private val flags = FakeFlags(registry)
    private val preferences = MemoryEnginePreferences()
    private val catalog = FakeCatalog()
    private val bindings = MutableStateFlow(listOf(EngineBinding(EngineBindingId("b1"), cliId, AuthSourceId("s1"))))
    private val runtimes = FakeRuntimes()
    private val installs = FakeInstalls()

    private fun TestScope.service(registry: EngineRegistry = this@EngineManagementConditionalLaunchTest.registry) =
        EngineManagementService(
            registry,
            if (registry === this@EngineManagementConditionalLaunchTest.registry) flags else FakeFlags(registry),
            preferences,
            catalog,
            bindings,
            runtimes,
            installs,
            EngineLaunchConfig { LaunchContext() },
            UnusedFeeds,
            CliConnections { },
            facadeContext(),
        )

    @Test
    fun `conditional launch change and rollback preserve hidden MCP opt in`() = runTest {
        val mcpSpec = cliSpec.copy(launch = cliSpec.launch.copy(options = cliSpec.launch.options + LaunchOption.Mcp))
        val mcpRegistry = EngineRegistry(
            listOf(registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(mcpSpec, manager)),
            EnginePlatform.DesktopMacOs,
        )
        val original = LaunchSettings(executable = "/old/cli", isMcpEnabled = true)
        preferences.update { it.copy(launch = mapOf(cliId to original)) }
        val service = service(mcpRegistry)
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        var receipt: LaunchSettingsSnapshot? = null
        service.configureIfUnchanged(cliId, snapshot, original.copy(executable = "/new/cli")) { receipt = it }
        assertEquals(original.copy(executable = "/new/cli"), preferences.load().launchOf(cliId))
        service.configureIfUnchanged(cliId, assertNotNull(receipt), original) { }
        assertEquals(original, preferences.load().launchOf(cliId))
    }

    @Test
    fun `conditional writes reject stale snapshots even after an identical configure and ABA`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        service.execute(cliId, EngineCommand.Configure(snapshot.settings))
        val error = assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { }
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), error.failure)

        val second = assertNotNull(service.inspect(cliId).savedLaunch)
        service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other")))
        service.execute(cliId, EngineCommand.Configure(second.settings))
        assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, second, LaunchSettings(executable = "/new")) { }
        }
        assertEquals(second.settings, preferences.load().launchOf(cliId))
    }

    @Test
    fun `an unchanged conditional write confirms the choice without retiring a runtime`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        manager.checked.clear()
        var receipt: LaunchSettingsSnapshot? = null
        service.configureIfUnchanged(cliId, snapshot, snapshot.settings) { receipt = it }
        assertNotNull(receipt)
        assertTrue(runtimes.retired.isEmpty())
        assertTrue(manager.checked.isEmpty())
        assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, snapshot, snapshot.settings) { }
        }
    }

    @Test
    fun `only one of two concurrent snapshots can commit`() = runTest {
        val service = service()
        runCurrent()
        val first = assertNotNull(service.inspect(cliId).savedLaunch)
        val second = assertNotNull(service.inspect(cliId).savedLaunch)
        val gate = CompletableDeferred<Unit>()
        preferences.beforeSave = { gate.await() }
        val winner = async {
            service.configureIfUnchanged(cliId, first, LaunchSettings(executable = "/winner")) { }
        }
        runCurrent()
        val loser = async {
            assertFailsWith<EngineException> {
                service.configureIfUnchanged(cliId, second, LaunchSettings(executable = "/loser")) { }
            }
        }
        runCurrent()
        gate.complete(Unit)
        winner.await()
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), loser.await().failure)
        assertEquals("/winner", preferences.load().launchOf(cliId).executable)
    }

    @Test
    fun `receipt permits rollback only until another configure and preserves neighboring preferences`() = runTest {
        val service = service()
        runCurrent()
        val original = assertNotNull(service.inspect(cliId).savedLaunch)
        preferences.update { it.copy(disabled = setOf(builtInId)) }
        var receipt: LaunchSettingsSnapshot? = null
        service.configureIfUnchanged(cliId, original, LaunchSettings(executable = "/new")) { receipt = it }
        val committed = assertNotNull(receipt)
        assertEquals(setOf(builtInId), preferences.load().disabled)
        service.configureIfUnchanged(cliId, committed, original.settings) { receipt = it }
        assertEquals(original.settings, preferences.load().launchOf(cliId))
        service.execute(cliId, EngineCommand.Configure(LaunchSettings(executable = "/other")))
        assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, assertNotNull(receipt), original.settings) { }
        }
        assertEquals("/other", preferences.load().launchOf(cliId).executable)
    }

    @Test
    fun `conditional write checks stored settings inside the preferences transaction`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        val other = LaunchSettings(executable = "/external")
        preferences.update { it.copy(launch = it.launch + (cliId to other)) }
        var wasSaved = false
        assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { wasSaved = true }
        }
        assertFalse(wasSaved)
        assertEquals(other, preferences.load().launchOf(cliId))
    }

    @Test
    fun `a token cannot be paired with another saved value or another owner`() = runTest {
        val first = service()
        val second = service()
        runCurrent()
        val snapshot = assertNotNull(first.inspect(cliId).savedLaunch)
        val forged = LaunchSettingsSnapshot(LaunchSettings(executable = "/forged"), snapshot.token)
        assertFailsWith<EngineException> {
            first.configureIfUnchanged(cliId, forged, LaunchSettings(executable = "/new")) { }
        }
        assertFailsWith<EngineException> {
            second.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { }
        }
        assertEquals(snapshot.settings, preferences.load().launchOf(cliId))
    }

    @Test
    fun `modifying a returned snapshot cannot change the token or stored preferences`() = runTest {
        val original = LaunchSettings(environment = listOf(EnvironmentEntry("ONE", "1"), EnvironmentEntry("TWO", "2")))
        preferences.update { it.copy(launch = mapOf(cliId to original)) }
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        (snapshot.settings.environment as MutableList<EnvironmentEntry>).clear()
        assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, snapshot, snapshot.settings) { }
        }
        assertEquals(original, preferences.load().launchOf(cliId))
    }

    @Test
    fun `successful persistence reports receipt before retirement failure`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        runtimes.failure = IllegalStateException("retire failed")
        var receipt: LaunchSettingsSnapshot? = null
        assertFailsWith<IllegalStateException> {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { receipt = it }
        }
        assertEquals("/new", assertNotNull(receipt).settings.executable)
        assertEquals("/new", preferences.load().launchOf(cliId).executable)
    }

    @Test
    fun `cancellation during persistence still delivers the confirmed receipt`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        val gate = CompletableDeferred<Unit>()
        preferences.beforeSave = { gate.await() }
        var receipt: LaunchSettingsSnapshot? = null
        val job = launch {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { receipt = it }
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals("/new", assertNotNull(receipt).settings.executable)
        assertEquals("/new", preferences.load().launchOf(cliId).executable)
    }

    @Test
    fun `cancellation while waiting for the engine lock does not save`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        val gate = CompletableDeferred<Unit>()
        manager.inspectionGate = gate
        val inspection = launch { service.inspect(cliId) }
        runCurrent()
        var wasSaved = false
        val job = launch {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { wasSaved = true }
        }
        runCurrent()
        job.cancelAndJoin()
        gate.complete(Unit)
        inspection.join()
        assertFalse(wasSaved)
        assertEquals(snapshot.settings, preferences.load().launchOf(cliId))
    }

    @Test
    fun `cancellation after persistence does not lose the receipt`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        val gate = CompletableDeferred<Unit>()
        manager.checkGate = gate
        var receipt: LaunchSettingsSnapshot? = null
        val job = launch {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { receipt = it }
        }
        runCurrent()
        assertEquals("/new", assertNotNull(receipt).settings.executable)
        job.cancelAndJoin()
        assertEquals("/new", preferences.load().launchOf(cliId).executable)
        manager.checkGate = null
        service.configureIfUnchanged(cliId, assertNotNull(receipt), snapshot.settings) { }
        assertEquals(snapshot.settings, preferences.load().launchOf(cliId))
    }

    @Test
    fun `ambiguous storage failure invalidates the snapshot without inventing a receipt`() = runTest {
        val service = service()
        runCurrent()
        val snapshot = assertNotNull(service.inspect(cliId).savedLaunch)
        preferences.afterSave = { throw IllegalStateException("ambiguous storage outcome") }
        var receipt: LaunchSettingsSnapshot? = null
        assertFailsWith<IllegalStateException> {
            service.configureIfUnchanged(cliId, snapshot, LaunchSettings(executable = "/new")) { receipt = it }
        }
        assertNull(receipt)
        assertEquals("/new", preferences.load().launchOf(cliId).executable)
        preferences.afterSave = {}
        val conflict = assertFailsWith<EngineException> {
            service.configureIfUnchanged(cliId, snapshot, snapshot.settings) { }
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), conflict.failure)
        assertEquals("/new", preferences.load().launchOf(cliId).executable)
    }
}

class EngineManagementExactInspectionTest {
    private val cliId = EngineId("cli")
    private val manager = FakeManager()
    private val registry = EngineRegistry(
        listOf(
            registration(id = cliId, owner = AuthOwnerId("cli")).withManagement(
                ManagementSpec(InstallSupport.Managed, LoginSupport.Cli),
                manager,
            ),
        ),
        EnginePlatform.DesktopMacOs,
    )
    private val flags = FakeFlags(registry)
    private val preferences = MemoryEnginePreferences()
    private val catalog = FakeCatalog()
    private val bindings = MutableStateFlow<List<EngineBinding>>(emptyList())
    private val runtimes = FakeRuntimes()
    private val installs = FakeInstalls()
    private var launchContext = LaunchContext()

    @Test
    fun `exact inspection returns its own result even before published state catches up`() = runTest {
        val service = service()
        runCurrent()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/first")
        val first = service.inspect(cliId)
        manager.installation = Installation(InstallSource.Missing)
        val second = service.inspect(cliId)

        assertEquals(InstallSource.System, first.installation.current?.source)
        assertEquals(InstallSource.Missing, second.installation.current?.source)
        assertEquals(first.installation.checkedAt, second.installation.checkedAt)
    }

    @Test
    fun `an inspection refuses a launch context changed during the adapter check`() = runTest {
        val gate = CompletableDeferred<Unit>()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/first")
        manager.inspectionGate = gate
        val service = service()
        runCurrent()
        val pending = async { assertFailsWith<EngineException> { service.inspect(cliId) } }
        runCurrent()
        launchContext = LaunchContext(LaunchSettings(executable = "/new"))
        gate.complete(Unit)
        val error = pending.await()
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), error.failure)
        runCurrent()
        assertNull(service.state.value.engine(cliId).installation.current)
    }

    @Test
    fun `an exact inspection rechecks the engine flag after waiting for its lock`() = runTest {
        val gate = CompletableDeferred<Unit>()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/first")
        manager.inspectionGate = gate
        val service = service()
        runCurrent()
        val first = async { service.inspect(cliId) }
        runCurrent()
        val second = async { assertFailsWith<EngineException> { service.inspect(cliId) } }
        runCurrent()
        flags.engines.getValue(cliId).value = false
        runCurrent()
        gate.complete(Unit)
        first.await()
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), second.await().failure)
        assertEquals(1, manager.inspected.size)
    }

    @Test
    fun `cancelling an exact inspection waiting for the lock starts no second adapter call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        manager.installation = Installation(InstallSource.System, "1.2.0", "/first")
        manager.inspectionGate = gate
        val service = service()
        runCurrent()
        val first = async { service.inspect(cliId) }
        runCurrent()
        val second = launch { service.inspect(cliId) }
        runCurrent()
        second.cancelAndJoin()
        gate.complete(Unit)
        first.await()
        assertEquals(1, manager.inspected.size)
    }

    private fun TestScope.service() = EngineManagementService(
        registry,
        flags,
        preferences,
        catalog,
        bindings,
        runtimes,
        installs,
        EngineLaunchConfig { launchContext },
        UnusedFeeds,
        CliConnections { },
        facadeContext(),
    )

    private fun EngineManagementState.engine(id: EngineId): ManagedEngine = engines.single { it.descriptor.id == id }
}

private fun EngineRegistration.withManagement(spec: ManagementSpec, manager: EngineManager) = EngineRegistration(
    descriptor,
    authOwner,
    factory,
    sessionSources,
    authenticators,
    modelCatalogRevision,
    spec,
    lazyOf(manager),
)

private class FakeFlags(registry: EngineRegistry) : EngineFlags {
    val management = MutableStateFlow(true)
    val configuration = MutableStateFlow(false)
    val engines = registry.all.associate { it.descriptor.id to MutableStateFlow(true) }

    override fun management(): Flow<Boolean> = management

    override fun configuration(): Flow<Boolean> = configuration

    override fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags> =
        engines.getValue(descriptor.id).map { DeveloperFlags(isCatalogOn = true, isEngineOn = it) }
}

private class FakeCatalog : EngineCatalog {
    var gate: CompletableDeferred<Unit>? = null
    val refreshes = mutableListOf<EngineId>()
    override val state = MutableStateFlow(emptyList<EngineInfo>())

    override suspend fun refresh(engine: EngineId): EngineInfo {
        refreshes += engine
        gate?.await()
        throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    override fun features(engine: EngineId): EngineFeatures = error("unused")
}

private class FakeRuntimes : EngineRuntimes {
    var failure: Exception? = null
    val retired = mutableListOf<EngineId>()
    val expected = mutableListOf<LaunchContext?>()
    var prunes = 0
    var busy = 0
    override val entries = MutableStateFlow(emptyList<RuntimeEntry>())
    override val sessions = MutableStateFlow(emptyMap<EngineId, SessionCounts>())

    override suspend fun retire(engine: EngineId, expected: LaunchContext?): RetireOutcome {
        retired += engine
        this.expected += expected
        failure?.let { throw it }
        entries.value = entries.value.filterNot { it.engine == engine }
        return RetireOutcome(retired = 0, busy = busy)
    }

    override suspend fun prune(): Int = 0.also { prunes++ }
}

private class FakeInstalls : ManagedInstallStore {
    var refreshes = 0
    var loaded = emptyMap<EngineId, ManagedInstall>()
    override val state = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())

    override suspend fun refresh() {
        refreshes++
        state.value = loaded
    }

    override suspend fun stage(engine: EngineId, plan: InstallPlan, progress: suspend (InstallStep) -> Unit) =
        error("unused")

    override suspend fun activate(staged: StagedInstall) = error("unused")
    override suspend fun discard(staged: StagedInstall) = error("unused")
    override suspend fun uninstall(engine: EngineId) = error("unused")
}

private class FakeManager : EngineManager {
    var observation: EngineCompatibilityObservation? = null
    var compatibilityFailure: EngineException? = null
    var legacyCompatibility = Compatibility.Unknown
    var compatibilityChecks = 0
    var loginChecks = 0
    var compatibilityGate: CompletableDeferred<Unit>? = null
    var inspectionGate: CompletableDeferred<Unit>? = null
    var checkGate: CompletableDeferred<Unit>? = null

    override fun compatibility(version: String): Compatibility = legacyCompatibility

    override suspend fun inspectCompatibility(
        installation: Installation,
        launch: LaunchContext,
    ): EngineCompatibilityObservation? {
        compatibilityChecks++
        compatibilityGate?.await()
        compatibilityFailure?.let { throw it }
        return observation
    }

    var installation = Installation(InstallSource.Missing)
    var login: LoginState = LoginState.SignedOut
    var warnings = emptyList<LaunchProblem>()
    var failure: EngineException? = null
    var hangs = false
    var releaseFailure: ManagementException? = null
    val inspected = mutableListOf<LaunchContext>()
    val checked = mutableListOf<LaunchSettings>()
    var hostApp = HostAppState()
    var hostInspections = 0
    var opened = 0

    var hostFailure: EngineException? = null
    var openFailure: Exception? = null

    override suspend fun inspectHostApp(): HostAppState {
        hostInspections++
        hostFailure?.let { throw it }
        return hostApp
    }

    override suspend fun openHostApp() {
        openFailure?.let { throw it }
        opened++
    }

    override suspend fun inspect(launch: LaunchContext): Installation {
        inspected += launch
        inspectionGate?.await()
        failure?.let { throw it }
        if (hangs) awaitCancellation()
        return installation
    }

    override suspend fun loginStatus(launch: LaunchContext): LoginState {
        loginChecks++
        return login
    }

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> {
        checked += settings
        checkGate?.await()
        return warnings
    }

    override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan {
        releaseFailure?.let { throw it }
        return InstallPlan(
            "2.0.0",
            "https://github.com/x/cli/releases/download/v2.0.0/cli.tar.gz",
            "a".repeat(64),
            null,
            ArchiveKind.TarGz(),
            "cli",
            GitHubDownloadHosts,
        )
    }
}

private object UnusedFeeds : ReleaseFeeds {
    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("unused")
    override suspend fun document(url: String, allowedHosts: Set<String>): String = error("unused")
}
