package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineOwnedJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.PackageOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long management operations of the profile, one job per engine, running in the profile scope so they outlive the
 * screen that started them. Progress and the outcome are published in [jobs] until dismissed. Installing checks the
 * staged copy with a restricted, verified compatibility probe before activating it; unknown or incompatible
 * candidates never replace an installation. Signing in shows the CLI's prompts and forwards pasted codes; it ends
 * after [loginTimeoutMillis].
 * Package jobs run the engine configurator's package command for the configured packages.
 */
@Suppress("LongParameterList") // A job spans the engine subsystems; each one is a separate port.
class EngineJobs(
    private val registry: EngineRegistry,
    private val installs: ManagedInstallStore,
    private val launch: EngineLaunchConfig,
    private val feeds: ReleaseFeeds,
    private val runtimes: EngineRuntimes,
    private val context: FacadeContext,
    private val hooks: JobHooks,
    private val loginTimeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
) {
    private val log = Log.tag("EngineJobs")
    private val published = MutableStateFlow(emptyMap<EngineId, EngineJob>())
    private val guard = Mutex()
    private val running = mutableMapOf<EngineId, Job>()
    private val codes = mutableMapOf<EngineId, Channel<LoginCode>>()
    private val progress = mutableMapOf<EngineId, Pair<Long, Long>>()

    /** Running jobs and the outcomes of finished ones. */
    val jobs: StateFlow<Map<EngineId, EngineJob>> = published.asStateFlow()

    /** Starts [action] for [engine]; Busy while another job of the engine runs. */
    suspend fun start(
        engine: EngineId,
        action: EngineAction,
        loginLaunch: LaunchContext? = null,
        onStarted: (EngineOwnedJob) -> Unit = {},
    ) = guard.withLock {
        require(loginLaunch == null || action is EngineAction.Login)
        if (running.containsKey(engine)) {
            log.w { "job refused, another one runs engine=${engine.value}" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
        log.i { "job started engine=${engine.value} action=${action::class.simpleName.orEmpty()}" }
        if (action is EngineAction.Login) codes[engine] = Channel(Channel.CONFLATED)
        val start = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<JobPhase>()
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            run(engine, action, start, outcome, loginLaunch)
        }
        running[engine] = job
        val handle = OwnedJob(job, outcome) { owner -> dismissOwned(engine, owner) }
        published.update { it + (engine to EngineJob(action, JobPhase.Preparing, context.clock.now(), handle)) }
        var isDelivered = false
        try {
            onStarted(handle)
            isDelivered = true
        } finally {
            if (!isDelivered) job.cancel()
            start.complete(Unit)
        }
        Unit
    }

    /** Cancels the running job of [engine]; its cleanup still runs. */
    suspend fun cancel(engine: EngineId) {
        val job = guard.withLock { running[engine] } ?: refuse("no job to cancel engine=${engine.value}")
        log.i { "job cancel requested engine=${engine.value}" }
        job.cancel()
    }

    /** Clears the outcome of the finished job of [engine]. */
    suspend fun dismiss(engine: EngineId) = guard.withLock {
        if (published.value[engine]?.phase?.isFinished != true) refuse("no finished job engine=${engine.value}")
        published.update { it - engine }
        log.i { "job outcome dismissed engine=${engine.value}" }
    }

    private suspend fun dismissOwned(engine: EngineId, owner: EngineOwnedJob): Boolean = guard.withLock {
        val current = published.value[engine]
        if (current?.handle !== owner || !current.phase.isFinished) return@withLock false
        published.update { it - engine }
        log.i { "owned job outcome dismissed engine=${engine.value}" }
        true
    }

    /** Passes [code] to the sign-in of [engine] that waits for it. */
    suspend fun answer(engine: EngineId, code: LoginCode) = guard.withLock {
        val channel = codes[engine]?.takeIf { published.value[engine]?.phase is JobPhase.AwaitingCode }
            ?: refuse("no sign-in waits for a code engine=${engine.value}")
        log.i { "sign-in code passed engine=${engine.value}" }
        if (channel.trySend(code).isFailure) refuse("sign-in stopped waiting for a code engine=${engine.value}")
    }

    private suspend fun run(
        engine: EngineId,
        action: EngineAction,
        start: CompletableDeferred<Unit>,
        completion: CompletableDeferred<JobPhase>,
        loginLaunch: LaunchContext?,
    ) {
        // Stays Cancelled unless the job completes; the outcome is published even when the job is cancelled.
        var outcome: JobPhase = JobPhase.Cancelled
        try {
            start.await()
            outcome = perform(engine, action, loginLaunch)
        } finally {
            withContext(NonCancellable) {
                try {
                    finish(engine, outcome)
                    inspectAfterJob(engine)
                } finally {
                    completion.complete(outcome)
                }
            }
        }
    }

    private suspend fun inspectAfterJob(engine: EngineId) {
        try {
            hooks.changed(engine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "engine could not be inspected after the job engine=${engine.value}" }
        }
    }

    private suspend fun perform(engine: EngineId, action: EngineAction, loginLaunch: LaunchContext?): JobPhase = try {
        when (action) {
            EngineAction.Install, EngineAction.Update -> install(engine, action)
            EngineAction.Uninstall -> uninstall(engine)
            is EngineAction.Login -> login(engine, action, loginLaunch)
            EngineAction.Logout -> logout(engine)
            EngineAction.SyncPackages -> packages(engine, PackageOperation.Sync)
            EngineAction.UpdatePackages -> packages(engine, PackageOperation.Update)
        }
        JobPhase.Succeeded
    } catch (e: CancellationException) {
        throw e
    } catch (e: ManagementException) {
        log.w(e) { "job failed engine=${engine.value}" }
        JobPhase.Failed(e.failure)
    } catch (e: EngineException) {
        log.w(e) { "job failed engine=${engine.value}" }
        JobPhase.Failed(ManagementFailure.Engine(e.failure))
    } catch (e: Exception) {
        log.e(e) { "job crashed engine=${engine.value}" }
        JobPhase.Failed(ManagementFailure.Engine(EngineFailure.Unknown()))
    }

    private suspend fun install(engine: EngineId, action: EngineAction) {
        val manager = manager(engine)
        val plan = manager.resolveRelease(feeds)
        if (action == EngineAction.Update && installs.state.value[engine]?.version == plan.version) {
            log.i { "managed copy is already the newest engine=${engine.value} version=${plan.version}" }
            return
        }
        val staged = installs.stage(engine, plan) { step -> phase(engine, step.toPhase()) }
        var isActivated = false
        try {
            phase(engine, JobPhase.Checking)
            if (!isSameVersion(staged.candidate.version, plan.version)) {
                throw ManagementException(ManagementFailure.Install(InstallFailureReason.VersionMismatch))
            }
            verifyCandidate(engine, manager, staged.candidate)
            phase(engine, JobPhase.Activating)
            // A busy runtime still owns the old copy; leave that installation untouched until it can retire.
            if (runtimes.retire(engine).busy > 0) {
                throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
            }
            // Commit the file switch and the published state together, even if Cancel arrives meanwhile.
            withContext(NonCancellable) {
                installs.activate(staged)
                isActivated = true
            }
        } finally {
            if (!isActivated) withContext(NonCancellable) { installs.discard(staged) }
        }
    }

    private suspend fun verifyCandidate(engine: EngineId, manager: EngineManager, candidate: ManagedInstall) {
        val launch = LaunchContext(managed = candidate, isInstallCandidate = true)
        val installation = Installation(InstallSource.Managed, candidate.version, candidate.executable)
        val report = withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
            withContext(context.io) { manager.inspectCompatibility(installation, launch) }
        }
        // The report is input, not the authority defining which runtime contracts are accepted.
        val policy = registry.require(engine).factory.value.runtimeRequirements
        val decision = if (policy != null && report != null) {
            assessEngineCompatibility(policy, report.evidence)
        } else {
            null
        }
        val identity = report?.evidence?.identity
        val failure = when {
            policy?.engine != engine || identity?.engine != engine || identity.runtime == null ->
                EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))

            !isSameVersion(identity.runtime, candidate.version) ->
                ManagementException(ManagementFailure.Install(InstallFailureReason.VersionMismatch))

            decision?.status == Compatibility.Incompatible ->
                EngineException(EngineFailure.Engine(EngineFailureReason.Incompatible))

            decision?.status != Compatibility.Verified ->
                EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))

            else -> null
        }
        if (failure != null) throw failure
    }

    private suspend fun uninstall(engine: EngineId) {
        phase(engine, JobPhase.Removing)
        val outcome = runtimes.retire(engine)
        if (outcome.busy > 0) {
            log.w { "managed copy is used by a running turn engine=${engine.value}" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
        withContext(NonCancellable) { installs.uninstall(engine) }
    }

    private suspend fun login(engine: EngineId, action: EngineAction.Login, loginLaunch: LaunchContext?) {
        val manager = manager(engine)
        val owner = requireNotNull(published.value[engine]?.handle)
        val codeChannel = guard.withLock { codes.getValue(engine) }
        val session = object : LoginSession {
            override fun prompt(prompt: LoginPrompt) {
                published.update { all ->
                    val current = all[engine]
                    if (current?.handle === owner && !current.phase.isFinished) {
                        all + (engine to current.copy(phase = prompt.toPhase()))
                    } else {
                        all
                    }
                }
            }

            override suspend fun awaitCode(): LoginCode = codeChannel.receive()
        }
        val signedIn = withTimeoutOrNull(loginTimeoutMillis) {
            manager.login(loginLaunch ?: launch.context(engine), action.method, session)
        }
        guard.withLock { codes.remove(engine)?.cancel() }
        if (signedIn == null) {
            log.w { "sign-in timed out engine=${engine.value}" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.TimedOut))
        }
        if (signedIn !is LoginState.SignedIn) {
            log.w { "sign-in did not establish an account engine=${engine.value}" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
        }
        hooks.signedIn(engine)
        runtimes.retire(engine)
    }

    private suspend fun logout(engine: EngineId) {
        phase(engine, JobPhase.Preparing)
        val state = manager(engine).logout(launch.context(engine))
        if (state != LoginState.SignedOut) {
            log.w { "sign-out did not clear the account engine=${engine.value}" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
        }
        runtimes.retire(engine)
    }

    /** Runs a package command of the engine's configurator; idle runtimes restart with the packages it left. */
    private suspend fun packages(engine: EngineId, operation: PackageOperation) {
        val configurator = registry.require(engine).configurator?.value
            ?: refuse("engine has no configurator engine=${engine.value}")
        val started = launch.context(engine)
        // Without configuration the list is empty, and a sync would remove every installed package.
        if (!started.isConfigured) refuse("package jobs need engine configuration engine=${engine.value}")
        val config = started.config
        withContext(context.io) {
            configurator.packages(operation, config) { done, total -> phase(engine, JobPhase.Installing(done, total)) }
        }
        runtimes.retire(engine)
    }

    private fun manager(engine: EngineId): EngineManager =
        registry.require(engine).manager?.value ?: refuse("engine has no manager engine=${engine.value}")

    /** Publishes [phase]; download progress at most every [PROGRESS_INTERVAL_MILLIS] or per percent. */
    private fun phase(engine: EngineId, phase: JobPhase) {
        if (phase is JobPhase.Downloading && !isProgressDue(engine, phase)) return
        published.update { all -> all[engine]?.let { all + (engine to it.copy(phase = phase)) } ?: all }
        if (phase !is JobPhase.Downloading) log.i { "job phase engine=${engine.value} phase=$phase" }
    }

    private fun isProgressDue(engine: EngineId, phase: JobPhase.Downloading): Boolean {
        val now = context.clock.now().toEpochMilliseconds()
        val percent = phase.total?.takeIf { it > 0 }?.let { phase.bytes * PERCENT / it } ?: -1L
        val (lastAt, lastPercent) = progress[engine] ?: (Long.MIN_VALUE to Long.MIN_VALUE)
        val isDue = phase.bytes == phase.total || now - lastAt >= PROGRESS_INTERVAL_MILLIS || percent > lastPercent
        if (isDue) progress[engine] = now to percent
        return isDue
    }

    private suspend fun finish(engine: EngineId, phase: JobPhase) {
        guard.withLock {
            running.remove(engine)
            codes.remove(engine)?.cancel()
            progress.remove(engine)
            published.update { all -> all[engine]?.let { all + (engine to it.copy(phase = phase)) } ?: all }
        }
        log.i { "job finished engine=${engine.value} outcome=${phase::class.simpleName.orEmpty()}" }
    }

    private fun refuse(reason: String): Nothing {
        log.w { "job command refused: $reason" }
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }

    private companion object {
        const val LOGIN_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val CHECK_TIMEOUT_MILLIS = 30_000L
        const val PROGRESS_INTERVAL_MILLIS = 250L
        const val PERCENT = 100L
    }
}

private class OwnedJob(
    private val job: Job,
    private val completion: CompletableDeferred<JobPhase>,
    private val dismissOwned: suspend (EngineOwnedJob) -> Boolean,
) : EngineOwnedJob {
    override suspend fun await(): JobPhase = completion.await()

    override suspend fun cancel(): JobPhase = withContext(NonCancellable) {
        job.cancel()
        completion.await()
    }

    override suspend fun dismiss(): Boolean = dismissOwned(this)
}

/** What engine management does after a job: re-inspect the engine and refresh signed-in CLI connections. */
interface JobHooks {
    /** The engine's installation or login changed. */
    suspend fun changed(engine: EngineId)

    /** The engine's CLI signed in; connections using its login refresh their source revision. */
    suspend fun signedIn(engine: EngineId)
}

private fun InstallStep.toPhase(): JobPhase = when (this) {
    is InstallStep.Downloading -> JobPhase.Downloading(bytes, total)
    InstallStep.Verified -> JobPhase.Verifying
    InstallStep.Unpacking -> JobPhase.Unpacking
}

private fun LoginPrompt.toPhase(): JobPhase = when (this) {
    is LoginPrompt.OpenUrl -> JobPhase.AwaitingBrowser(url, userCode)
    is LoginPrompt.PasteCode -> JobPhase.AwaitingCode(url)
}

private fun isSameVersion(reported: String?, expected: String): Boolean =
    reported != null && reported.removePrefix("v") == expected.removePrefix("v")
