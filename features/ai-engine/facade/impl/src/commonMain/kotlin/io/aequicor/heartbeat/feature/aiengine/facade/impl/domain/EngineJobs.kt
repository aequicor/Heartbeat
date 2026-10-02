package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.CancellationException
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
 * staged copy (it must start and report the release's version) before activating it; nothing half-installed ever
 * becomes active. Signing in shows the CLI's prompts and forwards pasted codes; it ends after [loginTimeoutMillis].
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
    suspend fun start(engine: EngineId, action: EngineAction) = guard.withLock {
        if (running[engine]?.isActive == true) {
            log.w { "job refused, another one runs engine=${engine.value}" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
        log.i { "job started engine=${engine.value} action=${action::class.simpleName.orEmpty()}" }
        published.update { it + (engine to EngineJob(action, JobPhase.Preparing, context.clock.now())) }
        if (action is EngineAction.Login) codes[engine] = Channel(Channel.CONFLATED)
        running[engine] = context.scope.launch { run(engine, action) }
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

    /** Passes [code] to the sign-in of [engine] that waits for it. */
    suspend fun answer(engine: EngineId, code: LoginCode) {
        val channel =
            guard.withLock { codes[engine] }?.takeIf { published.value[engine]?.phase is JobPhase.AwaitingCode }
                ?: refuse("no sign-in waits for a code engine=${engine.value}")
        log.i { "sign-in code passed engine=${engine.value}" }
        channel.trySend(code)
    }

    private suspend fun run(engine: EngineId, action: EngineAction) {
        // Stays Cancelled unless the job completes; the outcome is published even when the job is cancelled.
        var outcome: JobPhase = JobPhase.Cancelled
        try {
            outcome = perform(engine, action)
        } finally {
            withContext(NonCancellable) {
                finish(engine, outcome)
                inspectAfterJob(engine)
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

    private suspend fun perform(engine: EngineId, action: EngineAction): JobPhase = try {
        when (action) {
            EngineAction.Install, EngineAction.Update -> install(engine, action)
            EngineAction.Uninstall -> uninstall(engine)
            is EngineAction.Login -> login(engine, action)
            EngineAction.Logout -> logout(engine)
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
        val settings = launch.context(engine).settings
        if (action == EngineAction.Update && installs.state.value[engine]?.version == plan.version) {
            log.i { "managed copy is already the newest engine=${engine.value} version=${plan.version}" }
            return
        }
        val staged = installs.stage(engine, plan) { step -> phase(engine, step.toPhase()) }
        var isActivated = false
        try {
            phase(engine, JobPhase.Checking)
            // The copy runs once before activation; a custom executable must not shadow the candidate.
            val candidate = LaunchContext(settings.copy(executable = null), staged.candidate)
            val installation = withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
                withContext(context.io) { manager.inspect(candidate) }
            }
            if (installation?.source != InstallSource.Managed || !isSameVersion(installation.version, plan.version)) {
                log.w { "staged copy does not report the release engine=${engine.value} expected=${plan.version}" }
                throw ManagementException(ManagementFailure.Install(InstallFailureReason.VersionMismatch))
            }
            phase(engine, JobPhase.Activating)
            // Idle runtimes release the copy they run (Windows locks it); a busy one keeps it until its turn ends.
            runtimes.retire(engine)
            // Commit the file switch and the published state together, even if Cancel arrives meanwhile.
            withContext(NonCancellable) {
                installs.activate(staged)
                isActivated = true
            }
        } finally {
            if (!isActivated) withContext(NonCancellable) { installs.discard(staged) }
        }
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

    private suspend fun login(engine: EngineId, action: EngineAction.Login) {
        val manager = manager(engine)
        val session = object : LoginSession {
            override fun prompt(prompt: LoginPrompt) {
                phase(engine, prompt.toPhase())
            }

            override suspend fun awaitCode(): LoginCode {
                val channel = guard.withLock { codes.getValue(engine) }
                return channel.receive()
            }
        }
        val signedIn = withTimeoutOrNull(loginTimeoutMillis) {
            manager.login(launch.context(engine), action.method, session)
        }
        guard.withLock { codes.remove(engine) }
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
            codes.remove(engine)
            progress.remove(engine)
        }
        published.update { all -> all[engine]?.let { all + (engine to it.copy(phase = phase)) } ?: all }
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
