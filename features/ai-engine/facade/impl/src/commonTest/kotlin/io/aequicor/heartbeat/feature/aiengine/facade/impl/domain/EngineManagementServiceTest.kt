package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.MemoryEnginePreferences
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `an inspection records the adapter's installation, login and launch warnings`() = runTest {
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
    fun `a failing or hanging adapter is recorded, never thrown`() = runTest {
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
    fun `launch settings are validated, saved and applied by stopping idle runtimes`() = runTest {
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
    fun `idle runtimes of switched off, stale or exited engines are stopped, busy ones never`() = runTest {
        val service = service()
        runCurrent()
        val stale = RuntimeEntry(cliId, AuthSourceId("s1"), Instant.fromEpochSeconds(1), isClosed = false)
        runtimes.sessions.value = mapOf(cliId to SessionCounts(open = 1, activeTurns = 1))
        runtimes.entries.value = listOf(stale)
        preferences.update { it.copy(launch = mapOf(cliId to LaunchSettings(executable = "/opt/new"))) }
        runCurrent()
        assertTrue(service.state.value.engine(cliId).runtime.isStale)
        assertEquals(emptyList(), runtimes.retired)

        runtimes.sessions.value = mapOf(cliId to SessionCounts(open = 1, activeTurns = 0))
        runCurrent()

        assertEquals(listOf(cliId), runtimes.retired)
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
        facadeContext(),
    )

    private suspend fun assertRefused(block: suspend () -> Unit) {
        val error = assertFailsWith<EngineException> { block() }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), error.failure)
    }

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
    val engines = registry.all.associate { it.descriptor.id to MutableStateFlow(true) }

    override fun management(): Flow<Boolean> = management

    override fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags> =
        engines.getValue(descriptor.id).map { DeveloperFlags(isCatalogOn = true, isEngineOn = it) }
}

private class FakeCatalog : EngineCatalog {
    val refreshes = mutableListOf<EngineId>()
    override val state = MutableStateFlow(emptyList<EngineInfo>())

    override suspend fun refresh(engine: EngineId): EngineInfo {
        refreshes += engine
        throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    override fun features(engine: EngineId): EngineFeatures = error("unused")
}

private class FakeRuntimes : EngineRuntimes {
    val retired = mutableListOf<EngineId>()
    var prunes = 0
    var busy = 0
    override val entries = MutableStateFlow(emptyList<RuntimeEntry>())
    override val sessions = MutableStateFlow(emptyMap<EngineId, SessionCounts>())

    override suspend fun retire(engine: EngineId): RetireOutcome {
        retired += engine
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
    var installation = Installation(InstallSource.Missing)
    var login: LoginState = LoginState.SignedOut
    var warnings = emptyList<LaunchProblem>()
    var failure: EngineException? = null
    var hangs = false
    var releaseFailure: ManagementException? = null
    val inspected = mutableListOf<LaunchContext>()
    val checked = mutableListOf<LaunchSettings>()

    override suspend fun inspect(launch: LaunchContext): Installation {
        inspected += launch
        failure?.let { throw it }
        if (hangs) awaitCancellation()
        return installation
    }

    override suspend fun loginStatus(launch: LaunchContext): LoginState = login

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> = warnings.also { checked += settings }

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
