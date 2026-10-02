package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class EngineJobsTest {
    private val engine = EngineId("cli")
    private val manager = JobManager()
    private val registry = EngineRegistry(
        listOf(
            registration(id = engine, owner = AuthOwnerId("cli")).let {
                EngineRegistration(
                    it.descriptor,
                    it.authOwner,
                    it.factory,
                    management = ManagementSpec(InstallSupport.Managed, LoginSupport.CliWithDeviceCode),
                    manager = lazyOf(manager),
                )
            },
        ),
        EnginePlatform.DesktopMacOs,
    )
    private val installs = JobInstalls()
    private val runtimes = JobRuntimes()
    private val hooks = RecordingHooks()
    private val settings = LaunchSettings(executable = "/custom/cli", homeDirectory = "/home/cli")

    @Test
    fun `a sign-out that leaves the account signed in fails without retiring runtimes`() = runTest {
        manager.logoutResult = LoginState.SignedIn("account")
        val jobs = jobs()
        jobs.start(engine, EngineAction.Logout)
        runCurrent()
        assertEquals(
            JobPhase.Failed(ManagementFailure.Login(LoginFailureReason.Rejected)),
            jobs.jobs.value.getValue(engine).phase,
        )
        assertTrue(runtimes.retired.isEmpty())
    }

    @Test
    fun `activation finishes consistently when Cancel arrives during its commit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        installs.onActivate = {
            entered.complete(Unit)
            finish.await()
        }
        val jobs = jobs()
        jobs.start(engine, EngineAction.Install)
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(listOf(engine), runtimes.retired)
        jobs.cancel(engine)
        runCurrent()
        finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("2.0.0"), installs.activated)
        assertEquals(0, installs.discarded)
        assertEquals(listOf(engine), hooks.changed)
        assertEquals("2.0.0", installs.state.value.getValue(engine).version)
    }

    @Test
    fun `an inspection hook failure does not lose the completed job or prevent another job`() = runTest {
        hooks.failure = IllegalStateException("inspection crashed")
        val jobs = jobs()
        jobs.start(engine, EngineAction.Install)
        runCurrent()
        assertEquals(JobPhase.Succeeded, jobs.jobs.value.getValue(engine).phase)
        jobs.dismiss(engine)
        hooks.failure = null
        jobs.start(engine, EngineAction.Install)
        runCurrent()
        assertEquals(JobPhase.Succeeded, jobs.jobs.value.getValue(engine).phase)
    }

    @Test
    fun `an install checks the staged copy before activating it`() = runTest {
        val jobs = jobs()

        jobs.start(engine, EngineAction.Install)
        runCurrent()

        assertEquals(JobPhase.Succeeded, jobs.jobs.value.getValue(engine).phase)
        assertEquals(listOf("2.0.0"), installs.activated)
        val checked = manager.inspected.single()
        assertEquals(null, checked.settings.executable)
        assertEquals("/home/cli", checked.settings.homeDirectory)
        assertEquals("/staged/cli", checked.managed?.executable)
        assertEquals(listOf(engine), runtimes.retired)
        assertEquals(listOf(engine), hooks.changed)
    }

    @Test
    fun `a staged copy reporting another version is discarded`() = runTest {
        manager.reportedVersion = "1.9.9"
        val jobs = jobs()

        jobs.start(engine, EngineAction.Install)
        runCurrent()

        assertEquals(
            JobPhase.Failed(ManagementFailure.Install(InstallFailureReason.VersionMismatch)),
            jobs.jobs.value.getValue(engine).phase,
        )
        assertEquals(emptyList(), installs.activated)
        assertEquals(1, installs.discarded)
    }

    @Test
    fun `an update to the version already installed changes nothing`() = runTest {
        installs.state.value = mapOf(engine to ManagedInstall("2.0.0", "/data/cli", Instant.fromEpochSeconds(1)))
        val jobs = jobs()

        jobs.start(engine, EngineAction.Update)
        runCurrent()

        assertEquals(JobPhase.Succeeded, jobs.jobs.value.getValue(engine).phase)
        assertEquals(0, installs.staged)
    }

    @Test
    fun `an uninstall waits for running turns instead of breaking them`() = runTest {
        runtimes.busy = 1
        val jobs = jobs()

        jobs.start(engine, EngineAction.Uninstall)
        runCurrent()

        assertEquals(
            JobPhase.Failed(ManagementFailure.Engine(EngineFailure.Session(SessionFailureReason.Busy))),
            jobs.jobs.value.getValue(engine).phase,
        )
        assertEquals(0, installs.uninstalled)
    }

    @Test
    fun `a sign-in shows the page, takes the pasted code and refreshes CLI connections`() = runTest {
        val jobs = jobs()

        jobs.start(engine, EngineAction.Login(LoginMethod.DeviceCode))
        runCurrent()
        assertEquals(
            JobPhase.AwaitingCode("https://claude.ai/oauth"),
            jobs.jobs.value.getValue(engine).phase,
        )
        assertEquals(LoginMethod.DeviceCode, manager.method)
        assertEquals(listOf<LoginPrompt>(LoginPrompt.OpenUrl("https://claude.ai/oauth", "ABCD")), manager.shown.take(1))

        jobs.answer(engine, LoginCode("code-1"))
        runCurrent()

        assertEquals(LoginCode("code-1"), manager.received)
        assertEquals(JobPhase.Succeeded, jobs.jobs.value.getValue(engine).phase)
        assertEquals(listOf(engine), hooks.signedIn)
        assertFailsWith<EngineException> { jobs.answer(engine, LoginCode("late")) }
    }

    @Test
    fun `a sign-in nobody completes times out`() = runTest {
        manager.isLoginHanging = true
        val jobs = jobs(loginTimeout = 1_000)

        jobs.start(engine, EngineAction.Login())
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(
            JobPhase.Failed(ManagementFailure.Login(LoginFailureReason.TimedOut)),
            jobs.jobs.value.getValue(engine).phase,
        )
        assertTrue(manager.wasLoginCancelled.isCompleted)
    }

    @Test
    fun `a cancelled install discards its staged copy and a second job waits for the first`() = runTest {
        manager.isInspectHanging = true
        val jobs = jobs()

        jobs.start(engine, EngineAction.Install)
        runCurrent()
        assertEquals(JobPhase.Checking, jobs.jobs.value.getValue(engine).phase)
        val busy = assertFailsWith<EngineException> { jobs.start(engine, EngineAction.Uninstall) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), busy.failure)
        assertFailsWith<EngineException> { jobs.dismiss(engine) }

        jobs.cancel(engine)
        runCurrent()

        assertEquals(JobPhase.Cancelled, jobs.jobs.value.getValue(engine).phase)
        assertEquals(1, installs.discarded)
        assertEquals(listOf(engine), hooks.changed)
        assertEquals(emptyList(), installs.activated)
        jobs.dismiss(engine)
        assertEquals(emptyMap(), jobs.jobs.value)
    }

    private fun TestScope.jobs(loginTimeout: Long = 60_000) = EngineJobs(
        registry,
        installs,
        EngineLaunchConfig { LaunchContext(settings, installs.state.value[it]) },
        NoFeeds,
        runtimes,
        facadeContext(),
        hooks,
        loginTimeout,
    )
}

private class JobManager : EngineManager {
    var logoutResult: LoginState = LoginState.SignedOut
    override suspend fun logout(launch: LaunchContext): LoginState = logoutResult
    var reportedVersion = "2.0.0"
    var isInspectHanging = false
    var isLoginHanging = false
    val inspected = mutableListOf<LaunchContext>()
    val shown = mutableListOf<LoginPrompt>()
    var method: LoginMethod? = null
    var received: LoginCode? = null
    val wasLoginCancelled = CompletableDeferred<Unit>()

    override suspend fun inspect(launch: LaunchContext): Installation {
        inspected += launch
        if (isInspectHanging) awaitCancellation()
        return Installation(InstallSource.Managed, reportedVersion, launch.managed?.executable)
    }

    override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan = InstallPlan(
        "2.0.0",
        "https://github.com/x/cli/releases/download/v2.0.0/cli.tar.gz",
        "b".repeat(64),
        null,
        ArchiveKind.TarGz(),
        "cli",
        GitHubDownloadHosts,
    )

    override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState {
        this.method = method
        try {
            if (isLoginHanging) awaitCancellation()
            LoginPrompt.OpenUrl("https://claude.ai/oauth", "ABCD").also {
                shown += it
                session.prompt(it)
            }
            LoginPrompt.PasteCode("https://claude.ai/oauth").also {
                shown += it
                session.prompt(it)
            }
            received = session.awaitCode()
            return LoginState.SignedIn("me@example.com")
        } finally {
            wasLoginCancelled.complete(Unit)
        }
    }
}

private class JobInstalls : ManagedInstallStore {
    val activated = mutableListOf<String>()
    var staged = 0
    var discarded = 0
    var uninstalled = 0
    var onActivate: suspend () -> Unit = {}
    override val state = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())

    override suspend fun refresh() = Unit

    override suspend fun stage(
        engine: EngineId,
        plan: InstallPlan,
        progress: suspend (InstallStep) -> Unit,
    ): StagedInstall {
        staged++
        progress(InstallStep.Downloading(10, 20))
        progress(InstallStep.Downloading(20, 20))
        progress(InstallStep.Verified)
        progress(InstallStep.Unpacking)
        return StagedInstall(
            engine,
            ManagedInstall(plan.version, "/staged/cli", Instant.fromEpochSeconds(2)),
            "t",
            plan.sha256,
        )
    }

    override suspend fun activate(staged: StagedInstall): ManagedInstall {
        onActivate()
        activated += staged.candidate.version
        state.value = state.value + (staged.engine to staged.candidate)
        return staged.candidate
    }

    override suspend fun discard(staged: StagedInstall) {
        discarded++
    }

    override suspend fun uninstall(engine: EngineId) {
        uninstalled++
    }
}

private class JobRuntimes : EngineRuntimes {
    val retired = mutableListOf<EngineId>()
    var busy = 0
    override val entries = MutableStateFlow(emptyList<RuntimeEntry>())
    override val sessions = emptyFlow<Map<EngineId, SessionCounts>>()

    override suspend fun retire(engine: EngineId, expected: LaunchContext?): RetireOutcome = RetireOutcome(
        0,
        busy,
    ).also { retired += engine }

    override suspend fun prune(): Int = 0
}

private class RecordingHooks : JobHooks {
    var failure: Exception? = null
    val changed = mutableListOf<EngineId>()
    val signedIn = mutableListOf<EngineId>()

    override suspend fun changed(engine: EngineId) {
        changed += engine
        failure?.let { throw it }
    }

    override suspend fun signedIn(engine: EngineId) {
        signedIn += engine
    }
}

private object NoFeeds : ReleaseFeeds {
    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("unused")
    override suspend fun document(url: String, allowedHosts: Set<String>): String = error("unused")
}
