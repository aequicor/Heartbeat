package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorkerState
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.resourceLockKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** FIFO admission per overlapping resources; non-overlapping projects may run concurrently. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopWorktreeBuildCoordinator(
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val dispatchers: DispatcherProvider,
) : WorktreeBuildCoordinator {
    private val log = Log.tag("WorktreeBuildCoordinator")
    private val mutex = Mutex()
    private val waiting = mutableListOf<Job>()
    private val jobs = mutableMapOf<String, Job>()
    private val ownedResources = mutableSetOf<String>()

    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation {
        val job = enqueue(execution, isReattachment, update)
        // Only the waiter is cancelled; an accepted job belongs to the app/worker until explicit cancellation.
        return job.result.await()
    }

    override suspend fun acquireLease(execution: BuildExecution) {
        check(execution.isHold) { "InvalidResourceLease" }
        enqueue(execution) {}.started.await()
    }

    override suspend fun releaseLease(execution: BuildExecution): Boolean = withContext(dispatchers.io) {
        val directory = Path.of(execution.jobDirectory)
        if (!Files.exists(directory.resolve("state.json"))) return@withContext cancel(execution.id)
        Files.writeString(directory.resolve("cancel.request"), "cancel")
        withTimeoutOrNull(CANCEL_TIMEOUT) {
            while (readState(execution)?.operation?.phase !in terminal) delay(POLL_MILLIS)
            true
        } ?: false
    }

    private suspend fun enqueue(
        execution: BuildExecution,
        isReattachment: Boolean = false,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): Job = mutex.withLock {
        jobs[execution.id]?.also { it.update.set(update) } ?: Job(execution, update, isReattachment).also {
            jobs[execution.id] = it
            waiting += it
            admit()
        }
    }

    override suspend fun cancel(id: String): Boolean {
        val job = mutex.withLock {
            val found = jobs[id] ?: return@withLock null
            if (waiting.remove(found)) {
                found.result.complete(found.execution.operation(WorktreeBuildPhase.Cancelled))
                found.started.completeExceptionally(IllegalStateException("LeaseCancelled"))
                jobs.remove(id)
                admit()
            }
            found
        } ?: return false
        if (!job.result.isCompleted) {
            withContext(dispatchers.io) {
                Files.createDirectories(Path.of(job.execution.jobDirectory))
                Files.writeString(Path.of(job.execution.jobDirectory).resolve("cancel.request"), "cancel")
            }
        }
        return withTimeoutOrNull(CANCEL_TIMEOUT) { job.result.await().phase == WorktreeBuildPhase.Cancelled } ?: false
    }

    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = withContext(dispatchers.io) {
        val state = readState(execution)
        if (state == null) {
            return@withContext execution.operation(WorktreeBuildPhase.Unknown, "WorkerOutcomeUnknown")
        }
        if (state.operation.phase in terminal) return@withContext state.operation
        if (state.liveProcess() != null) {
            state.operation
        } else {
            state.operation.copy(phase = WorktreeBuildPhase.Unknown, failure = "WorkerOutcomeUnknown")
        }
    }

    private fun admit() {
        val preceding = mutableSetOf<String>()
        val eligible = mutableListOf<Job>()
        waiting.forEach { job ->
            if (job.resources.none { it in ownedResources || it in preceding }) {
                ownedResources += job.resources
                eligible += job
            }
            preceding += job.resources
        }
        waiting.removeAll(eligible.toSet())
        waiting.forEachIndexed { index, job ->
            app.coroutineScope.launch {
                notify(job, job.execution.operation(WorktreeBuildPhase.Queued).copy(queuePosition = index + 1))
            }
        }
        eligible.forEach(::launchJob)
    }

    private fun launchJob(job: Job) {
        app.coroutineScope.launch(dispatchers.io) {
            try {
                val result = runWorker(job)
                job.result.complete(result)
                if (!job.started.isCompleted) {
                    job.started.completeExceptionally(
                        IllegalStateException(result.failure ?: "WorkerNotStarted"),
                    )
                }
            } catch (error: CancellationException) {
                // The OS worker retains locks after app shutdown. A later profile reads its durable result.
                throw error
            } catch (error: Exception) {
                log.e(
                    IllegalStateException("BuildCoordinatorFailed (${error::class.simpleName.orEmpty()})"),
                ) { "Worker monitoring failed" }
                job.result.complete(job.execution.operation(WorktreeBuildPhase.Unknown, "WorkerMonitoringFailed"))
                job.started.completeExceptionally(IllegalStateException("WorkerMonitoringFailed"))
            } finally {
                if (!job.result.isCompleted) job.result.cancel()
                if (!job.started.isCompleted) job.started.cancel()
                withContext(NonCancellable) {
                    mutex.withLock {
                        ownedResources.removeAll(job.resources)
                        jobs.remove(job.execution.id)
                        admit()
                    }
                }
            }
        }
    }

    private suspend fun runWorker(job: Job): WorktreeBuildOperation {
        val execution = job.execution
        val previous = readState(execution)
        if (previous != null && previous.operation.phase in terminal && previous.liveProcess() == null) {
            return previous.operation
        }
        if (previous == null && job.isReattachment) {
            return execution.operation(WorktreeBuildPhase.Unknown, "WorkerOutcomeUnknown")
        }
        val process = startOrAdopt(execution, previous)
        return monitorWorker(job, process)
    }

    private fun startOrAdopt(execution: BuildExecution, previous: WorkerState?): ProcessHandle {
        val existing = previous?.liveProcess()
        if (existing != null) return existing
        check(previous == null) { "WorkerOutcomeUnknown" }
        val directory = Files.createDirectories(Path.of(execution.jobDirectory))
        Files.writeString(directory.resolve("job.json"), Json.encodeToString(execution))
        val builder = ProcessBuilder(workerLaunchCommand(directory))
            .redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile())
        builder.environment().keys.filter(::isSecretEnvironmentName).forEach { builder.environment().remove(it) }
        return builder.start().toHandle()
    }

    private suspend fun monitorWorker(job: Job, process: ProcessHandle): WorktreeBuildOperation =
        WorkerProcessMonitor(process).await(
            job.execution,
            observe = { previous -> observeWorkerState(job, previous) },
            forward = { operation -> forwardState(job, operation) },
        )

    private suspend fun observeWorkerState(job: Job, previous: WorktreeBuildOperation?): WorkerState? = try {
        readState(job.execution)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("WorkerStateUnavailable (${error::class.simpleName.orEmpty()})")) {
            "Worker snapshot unavailable; its process and resources remain owned"
        }
        notify(
            job,
            job.execution.operation(
                WorktreeBuildPhase.Unknown,
                "WorkerStateUnavailable",
            ).copy(output = previous?.output.orEmpty()),
        )
        null
    }

    private suspend fun forwardState(job: Job, operation: WorktreeBuildOperation) {
        if (operation.phase == WorktreeBuildPhase.Running) job.started.complete(Unit)
        notify(job, operation)
    }
    private suspend fun notify(job: Job, operation: WorktreeBuildOperation) {
        try {
            job.update.get().invoke(operation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                IllegalStateException("BuildProjectionFailed (${error::class.simpleName.orEmpty()})"),
            ) { "Build projection failed" }
        }
    }

    private fun readState(execution: BuildExecution): WorkerState? {
        val file = Path.of(execution.jobDirectory).resolve("state.json")
        if (!Files.exists(file)) return null
        return Json.decodeFromString<WorkerState>(Files.readString(file)).also {
            check(
                it.operation.id == execution.id && it.operation.command == execution.command.id,
            ) { "InvalidWorkerIdentity" }
            check(it.operation.configurationRevision == execution.configurationRevision) { "InvalidBuildConfiguration" }
        }
    }

    private fun workerLaunchCommand(directory: Path): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        if (Files.isRegularFile(java)) {
            return listOf(java.toString(), "-cp", workerClasspath(), WORKER_MAIN, directory.toString())
        }
        val packaged = System.getProperty("jpackage.app-path")?.let(Path::of)
        check(packaged != null && Files.isRegularFile(packaged)) { "BuildWorkerRuntimeUnavailable" }
        return listOf(packaged.toString(), "--heartbeat-worktree-build-worker", directory.toString())
    }

    private data class Job(
        val execution: BuildExecution,
        val initialUpdate: suspend (WorktreeBuildOperation) -> Unit,
        val isReattachment: Boolean,
    ) {
        val update = java.util.concurrent.atomic.AtomicReference(initialUpdate)
        val resources = execution.resources.map(::resourceLockKey).toSet()
        val result = CompletableDeferred<WorktreeBuildOperation>()
        val started = CompletableDeferred<Unit>()
    }

    private companion object {
        const val WORKER_MAIN = "io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt"
        const val POLL_MILLIS = 100L
        const val CANCEL_TIMEOUT = 10_000L
        val terminal = setOf(
            WorktreeBuildPhase.Completed,
            WorktreeBuildPhase.Failed,
            WorktreeBuildPhase.Cancelled,
        )
        fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

private fun BuildExecution.operation(phase: WorktreeBuildPhase, failure: String? = null): WorktreeBuildOperation =
    WorktreeBuildOperation(id, command.id, phase, failure = failure, configurationRevision = configurationRevision)

private fun WorkerState.liveProcess(): ProcessHandle? = ProcessHandle.of(process).orElse(null)?.takeIf {
    it.isAlive && (startedAt == null || it.info().startInstant().orElse(null)?.toString() == startedAt)
}

/** A packaged launcher and its actual JVM may have distinct lifetimes; both remain owned until exit. */
internal class WorkerProcessMonitor(private val launcher: ProcessHandle) {
    private val workers = mutableSetOf<ProcessHandle>()

    suspend fun await(
        execution: BuildExecution,
        observe: suspend (WorktreeBuildOperation?) -> WorkerState?,
        forward: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation {
        var last: WorktreeBuildOperation? = null
        var missingAfterExit = 0
        while (true) {
            val state = observe(last)
            state?.liveProcess()?.let { workers += it }
            if (state == null) last = null
            if (state != null && state.operation != last) {
                last = state.operation
                forward(state.operation)
            }
            if (state?.operation?.phase in terminal) {
                awaitExit()
                return checkNotNull(state).operation
            }
            missingAfterExit = if (areProcessesAlive()) 0 else missingAfterExit + 1
            if (missingAfterExit >= EXIT_GRACE_POLLS) {
                return execution.operation(WorktreeBuildPhase.Unknown, "WorkerOutcomeUnknown")
            }
            delay(POLL_MILLIS)
        }
    }

    private fun areProcessesAlive(): Boolean = launcher.isAlive || workers.any { it.isAlive }

    private suspend fun awaitExit() {
        while (areProcessesAlive()) delay(POLL_MILLIS)
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val EXIT_GRACE_POLLS = 5
        val terminal = setOf(
            WorktreeBuildPhase.Completed,
            WorktreeBuildPhase.Failed,
            WorktreeBuildPhase.Cancelled,
        )
    }
}

/** Gradle tests install dependencies through a child URLClassLoader rather than java.class.path. */
internal fun workerClasspath(): String {
    val paths = linkedSetOf<String>()
    paths += System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }
    var loader: ClassLoader? = DesktopWorktreeBuildCoordinator::class.java.classLoader
    while (loader != null) {
        if (loader is java.net.URLClassLoader) {
            loader.urLs.filter { it.protocol == "file" }.forEach { paths += Path.of(it.toURI()).toString() }
        }
        loader = loader.parent
    }
    listOf(
        DesktopWorktreeBuildCoordinator::class.java,
        BuildExecution::class.java,
        WorktreeBuildOperation::class.java,
        Json::class.java,
        kotlin.Unit::class.java,
        kotlinx.serialization.KSerializer::class.java,
        Log::class.java,
    ).forEach { type -> type.protectionDomain?.codeSource?.location?.let { paths += Path.of(it.toURI()).toString() } }
    return paths.joinToString(java.io.File.pathSeparator)
}
