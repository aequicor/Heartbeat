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
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.releaseSnapshot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.resourceLockKey
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.withFreeResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * App-wide build queue: FIFO per overlapping exclusive resources, weighted by build kind against the machine budget
 * and fair across projects ([admitBuilds]). Reattached workers already run and are counted without admission.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopWorktreeBuildCoordinator(
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    private val capacity: BuildCapacity,
) : WorktreeBuildCoordinator {
    private val log = Log.tag("WorktreeBuildCoordinator")
    private val mutex = Mutex()
    private val waiting = mutableListOf<Job>()
    private val jobs = mutableMapOf<String, Job>()
    private var isRecheckPending = false

    /** Last slot of recent sandbox tasks: rebuilding there keeps their incremental outputs warm. */
    private val affinity = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > MAX_AFFINITY
    }

    init {
        // A raised limit or a disabled queue admits waiting builds without waiting for the next enqueue.
        app.coroutineScope.launch {
            capacity.changes().collect { mutex.withLock { admit() } }
        }
    }

    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation {
        val adopted = if (isReattachment) adoptedSlot(execution) else execution
        if (adopted == null) return execution.operation(WorktreeBuildPhase.Unknown, "WorkerOutcomeUnknown")
        val job = enqueue(adopted, isReattachment, update)
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
            if (isReattachment) launchJob(it) else waiting += it
            admit()
        }
    }

    override suspend fun cancel(id: String): Boolean {
        val job = mutex.withLock {
            val found = jobs[id] ?: return@withLock null
            if (waiting.remove(found)) {
                found.result.complete(found.execution.operation(WorktreeBuildPhase.Cancelled))
                releaseUnstartedSnapshot(found.execution)
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

    private suspend fun admit() {
        val running = jobs.values.filter { it !in waiting }.map { job ->
            val sandbox = job.execution.sandbox
            RunningBuild(
                job.execution.id,
                job.execution.project,
                job.execution.cost,
                job.resources,
                sandbox?.pool,
                sandbox?.slot,
            )
        }
        val budget = capacity.budget()
        val slots = budget.slotsPerRepository
        val external = externalSlots(running, slots)
        val queued = waiting.map { job ->
            val sandbox = job.execution.sandbox
            QueuedBuild(
                job.execution.id,
                job.execution.project,
                job.execution.cost,
                job.resources,
                sandbox?.let { SlotDemand(it.pool, slots, affinity[it.affinity]) },
            )
        }
        val admission = admitBuilds(queued, running + external, budget)
        val eligible = waiting.filter { it.execution.id in admission.admitted }
        waiting.removeAll(eligible.toSet())
        eligible.forEach { job ->
            val slot = admission.admitted[job.execution.id] ?: return@forEach
            job.execution = job.execution.inSlot(slot)
            affinity[checkNotNull(job.execution.sandbox).affinity] = slot
        }
        if (eligible.isNotEmpty()) {
            log.d { "Admit builds count=${eligible.size} waiting=${waiting.size} budgetUnits=${budget.units}" }
        }
        waiting.forEach { job ->
            val position = admission.positions[job.execution.id]
            app.coroutineScope.launch {
                notify(job, job.execution.operation(WorktreeBuildPhase.Queued).copy(queuePosition = position))
            }
        }
        eligible.forEach(::launchJob)
        if (external.isNotEmpty() && waiting.any { it.execution.sandbox != null }) scheduleRecheck()
    }

    /**
     * Slots locked by workers this coordinator does not track: another profile's builds or workers not reattached
     * yet. They count as occupied, so a new build takes a free slot instead of waiting on the lock.
     */
    private suspend fun externalSlots(running: List<RunningBuild>, slots: Int): List<RunningBuild> {
        val pools = waiting.mapNotNull { it.execution.sandbox?.pool }.distinct()
        if (pools.isEmpty()) return emptyList()
        val owned = running.mapNotNull { build -> build.slot?.let { build.slotPool to it } }.toSet()
        return withContext(dispatchers.io) {
            pools.flatMap { pool ->
                (0 until slots).filter { (pool to it) !in owned && isSlotLocked(pool, it) }.map { index ->
                    RunningBuild("external:$index:$pool", EXTERNAL_PROJECT, BuildCost.None, emptySet(), pool, index)
                }
            }
        }
    }

    private fun isSlotLocked(pool: String, index: Int): Boolean = try {
        withFreeResource(slotDirectory(pool, index).toString()) {} == null
    } catch (error: java.io.IOException) {
        log.w(IllegalStateException("SlotProbeFailed (${error::class.simpleName.orEmpty()})")) {
            "Build slot availability is unknown; it stays assignable"
        }
        false
    }

    /** An external holder releases its slot without telling this process, so waiting builds are re-admitted. */
    private fun scheduleRecheck() {
        if (isRecheckPending) return
        isRecheckPending = true
        app.coroutineScope.launch {
            delay(SLOT_RECHECK_MILLIS)
            mutex.withLock {
                isRecheckPending = false
                admit()
            }
        }
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
                releaseUnstartedSnapshot(job.execution)
                if (!job.result.isCompleted) job.result.cancel()
                if (!job.started.isCompleted) job.started.cancel()
                withContext(NonCancellable) {
                    mutex.withLock {
                        jobs.remove(job.execution.id)
                        admit()
                    }
                }
            }
        }
    }

    /** A sandbox build whose worker never started still owns its snapshot ref; the worker releases it otherwise. */
    private fun releaseUnstartedSnapshot(execution: BuildExecution) {
        val sandbox = execution.sandbox ?: return
        if (Files.exists(Path.of(execution.jobDirectory).resolve("state.json"))) return
        app.coroutineScope.launch(dispatchers.io) { releaseSnapshot(sandbox, execution.id) }
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

    /**
     * Reattached sandbox workers keep the slot recorded in their private job file. An unreadable or foreign job
     * file yields null: the outcome is unknown and no other reattachment is affected.
     */
    private suspend fun adoptedSlot(execution: BuildExecution): BuildExecution? = withContext(dispatchers.io) {
        val file = Path.of(execution.jobDirectory).resolve("job.json")
        if (execution.sandbox == null || !Files.isRegularFile(file)) return@withContext execution
        try {
            Json.decodeFromString<BuildExecution>(Files.readString(file)).takeIf {
                it.id == execution.id && it.sandbox?.slot != null
            }
        } catch (error: SerializationException) {
            log.w(IllegalStateException("InvalidWorkerJob (${error::class.simpleName.orEmpty()})")) {
                "Reattached sandbox job file is unreadable"
            }
            null
        } catch (error: java.io.IOException) {
            log.w(IllegalStateException("InvalidWorkerJob (${error::class.simpleName.orEmpty()})")) {
                "Reattached sandbox job file is unreadable"
            }
            null
        }
    }

    private class Job(
        @Volatile var execution: BuildExecution,
        initialUpdate: suspend (WorktreeBuildOperation) -> Unit,
        val isReattachment: Boolean,
    ) {
        val update = java.util.concurrent.atomic.AtomicReference(initialUpdate)
        val resources: Set<String> get() = execution.resources.map(::resourceLockKey).toSet()
        val result = CompletableDeferred<WorktreeBuildOperation>()
        val started = CompletableDeferred<Unit>()
    }

    private companion object {
        const val WORKER_MAIN = "io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt"
        const val POLL_MILLIS = 100L
        const val CANCEL_TIMEOUT = 10_000L

        const val SLOT_RECHECK_MILLIS = 2_000L
        const val EXTERNAL_PROJECT = "external"
        const val MAX_AFFINITY = 256
        val terminal = setOf(
            WorktreeBuildPhase.Completed,
            WorktreeBuildPhase.Failed,
            WorktreeBuildPhase.Cancelled,
        )
        fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

/** Places a sandbox build into slot [index] of its pool; the slot directory becomes an exclusive resource. */
internal fun BuildExecution.inSlot(index: Int): BuildExecution {
    val target = checkNotNull(sandbox) { "SandboxUnavailable" }
    val slot = slotDirectory(target.pool, index)
    return copy(
        directory = slot.resolve(target.relativeDirectory).normalize().toString(),
        resources = (resources + slot.toString()).distinct().sorted(),
        sandbox = target.copy(slot = index),
    )
}

internal fun slotDirectory(pool: String, index: Int): Path = Path.of(pool).resolve("slot-$index")

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
