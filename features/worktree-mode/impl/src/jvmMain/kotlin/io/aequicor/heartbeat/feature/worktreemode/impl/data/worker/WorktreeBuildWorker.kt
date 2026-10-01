package io.aequicor.heartbeat.feature.worktreemode.impl.data.worker

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.impl.data.BuildExecution
import io.aequicor.heartbeat.feature.worktreemode.impl.data.isSecretEnvironmentName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Build workers own resource locks until their observed command tree has stopped. Hold leases serialize
 * cooperative live agents; external/native commands require separate recovery after lost observation.
 */
public fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "InvalidWorkerArguments" }
    runBuildWorker(Path.of(arguments.single()))
}

@Serializable
internal data class WorkerState(
    val operation: WorktreeBuildOperation,
    val process: Long,
    val startedAt: String? = null,
) {
    override fun toString(): String = "WorkerState"
}

private val workerLog = Log.tag("WorktreeBuildWorker")
private const val POLL_MILLIS = 50L
internal const val MAX_BUILD_OUTPUT = 32_768

internal fun runBuildWorker(job: Path) {
    val execution = Json.decodeFromString<BuildExecution>(Files.readString(job.resolve("job.json")))
    check(Path.of(execution.jobDirectory).toAbsolutePath().normalize() == job.toAbsolutePath().normalize()) {
        "InvalidWorkerIdentity"
    }
    WorkerRuntime(job, execution).run()
}

private class WorkerRuntime(private val job: Path, private val execution: BuildExecution) {
    private val locks = mutableListOf<Pair<FileChannel, FileLock>>()
    private val descendants = ConcurrentHashMap<Long, ProcessHandle>()
    private val child = java.util.concurrent.atomic.AtomicReference<Process?>()
    private val captured = BoundedBuildOutput()
    private val startedAt = System.nanoTime()
    private val shutdown = Thread({ terminateTree(child.get(), descendants) }, "heartbeat-build-shutdown")

    fun run() {
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            publish(WorktreeBuildPhase.WaitingForResource)
            if (acquireLocks()) {
                if (execution.isHold) holdLease() else runCommand()
            } else {
                publishStopped(checkNotNull(stopped()))
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            workerLog.e(
                IllegalStateException("BuildWorkerFailed (${error::class.simpleName.orEmpty()})"),
            ) { "Build worker failed" }
            terminateTree(child.get(), descendants)
            publish(WorktreeBuildPhase.Failed, failure = "WorkerFailed${error::class.simpleName.orEmpty()}")
        } finally {
            // Process termination precedes publishing a terminal state and releasing the lease.
            terminateTree(child.get(), descendants)
            locks.asReversed().forEach { (channel, lock) ->
                lock.release()
                channel.close()
            }
            Runtime.getRuntime().removeShutdownHook(shutdown)
        }
    }

    private fun acquireLocks(): Boolean {
        val root = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "heartbeat-build-locks"))
        for (key in execution.resources.asSequence().map(::resourceLockKey).distinct().sorted()) {
            val channel = FileChannel.open(
                root.resolve("$key.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            var acquired: FileLock? = null
            try {
                while (acquired == null && stopped() == null) {
                    acquired = channel.tryLock()
                    if (acquired == null) pause()
                }
                if (acquired == null) return false
                locks += channel to acquired
            } finally {
                if (acquired == null) channel.close()
            }
        }
        return stopped() == null
    }

    private fun holdLease() {
        publish(WorktreeBuildPhase.Running)
        while (stopped() == null) pause()
        publishStopped(checkNotNull(stopped()))
    }

    private fun runCommand() {
        val stop = stopped()
        if (stop != null) {
            publishStopped(stop)
            return
        }
        val builder = ProcessBuilder(workerCommand(execution))
            .directory(Path.of(execution.directory).toFile()).redirectErrorStream(true)
        require(execution.command.environment.keys.none(::isSecretEnvironmentName)) { "SecretBuildEnvironment" }
        builder.environment().keys.filter(::isSecretEnvironmentName).forEach { builder.environment().remove(it) }
        builder.environment().putAll(execution.command.environment)
        val process = builder.start()
        child.set(process)
        val reader = outputReader(process)
        publish(WorktreeBuildPhase.Running)
        val reason = monitor(process)
        reader.join()
        val exit = process.exitValue()
        when {
            reason != null -> publishStopped(reason, exit)
            exit == 0 -> publish(WorktreeBuildPhase.Completed, exit)
            else -> publish(WorktreeBuildPhase.Failed, exit, "BuildExitedWithCode$exit")
        }
    }

    private fun monitor(process: Process): String? {
        var reason: String? = null
        var publishAt = System.nanoTime()
        while (process.isAlive || descendants.values.any { it.isAlive }) {
            process.descendants().use { stream -> stream.forEach { descendants[it.pid()] = it } }
            stopped()?.let {
                reason = it
                terminateTree(process, descendants)
            }
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishAt) >= PROGRESS_MILLIS) {
                publish(WorktreeBuildPhase.Running)
                publishAt = System.nanoTime()
            }
            process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)
            if (!process.isAlive && descendants.values.any { it.isAlive }) terminateTree(process, descendants)
        }
        return reason
    }

    private fun outputReader(process: Process): Thread = Thread({
        try {
            process.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(OUTPUT_CHUNK)
                var count = stream.read(buffer)
                while (count >= 0) {
                    captured.append(String(buffer, 0, count))
                    count = stream.read(buffer)
                }
            }
        } catch (error: java.io.IOException) {
            workerLog.w(IllegalStateException("BuildOutputReadFailed (${error::class.simpleName.orEmpty()})")) {
                "Build output stream ended unexpectedly"
            }
        }
    }, "heartbeat-build-output").also { it.start() }

    private fun stopped(): String? = when {
        Files.exists(job.resolve("cancel.request")) -> "Cancelled"
        !parentIsAlive(execution) -> "ParentLost"
        !execution.isHold && elapsedMillis() >= execution.command.timeoutMillis -> "BuildTimedOut"
        else -> null
    }

    private fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun publishStopped(reason: String, exit: Int? = null) {
        publish(
            if (reason == "BuildTimedOut") WorktreeBuildPhase.Failed else WorktreeBuildPhase.Cancelled,
            exit,
            reason,
        )
    }

    private fun publish(phase: WorktreeBuildPhase, code: Int? = null, failure: String? = null) {
        val output = captured.snapshot()
        Files.writeString(job.resolve("output.log"), output)
        val operation = WorktreeBuildOperation(
            execution.id,
            execution.command.id,
            phase,
            code,
            output,
            failure,
            configurationRevision = execution.configurationRevision,
        )
        val current = ProcessHandle.current()
        writeWorkerState(
            job,
            WorkerState(operation, current.pid(), current.info().startInstant().orElse(null)?.toString()),
        )
    }

    private fun pause() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
    }

    private companion object {
        const val PROGRESS_MILLIS = 500L
        const val OUTPUT_CHUNK = 4_096
    }
}
internal fun parentIsAlive(execution: BuildExecution): Boolean {
    if (execution.parentProcess == 0L) return true
    val parent = ProcessHandle.of(execution.parentProcess).orElse(null) ?: return false
    if (!parent.isAlive) return false
    return execution.parentStartedAt == null || parent.info().startInstant().orElse(
        null,
    )?.toString() == execution.parentStartedAt
}

/** Preserves an initial context and recent output while bounding memory, response and on-disk diagnostics. */
internal class BoundedBuildOutput {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var total: Long = 0

    @Synchronized
    fun append(text: String) {
        total += text.length
        val headRemaining = MAX_BUILD_OUTPUT / 4 - head.length
        if (headRemaining > 0) head.append(text.take(headRemaining))
        tail.append(text)
        val limit = MAX_BUILD_OUTPUT - MAX_BUILD_OUTPUT / 4 - 64
        if (tail.length > limit) tail.delete(0, tail.length - limit)
    }

    @Synchronized
    fun snapshot(): String {
        val overlap = head.length + tail.length - total
        return when {
            total == tail.length.toLong() -> tail.toString()
            overlap >= 0 -> head.toString() + tail.substring(overlap.toInt())
            else -> head.toString() + "\n... recent output ...\n" + tail
        }
    }
}

/** Windows batch wrappers need cmd.exe; each pinned argv value remains quoted and expansion is rejected. */
internal fun workerCommand(execution: BuildExecution): List<String> {
    val executable = execution.command.executable
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ||
        !(executable.endsWith(".bat", ignoreCase = true) || executable.endsWith(".cmd", ignoreCase = true))
    ) {
        return listOf(executable) + execution.command.arguments
    }
    val script = Path.of(
        executable,
    ).let { if (it.isAbsolute) it else Path.of(execution.directory).resolve(it) }.normalize()
    require(Files.isRegularFile(script)) { "BuildWrapperUnavailable" }
    val values = listOf(script.toString()) + execution.command.arguments
    require(values.none { value -> value.any { it < ' ' || it in "\"%!^" } }) { "UnsafeBatchArgument" }
    val quoted = values.joinToString(" ") { "\"$it\"" }
    val shell = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "cmd.exe")
    return listOf(shell.toString(), "/d", "/s", "/c", "\"$quoted\"")
}

private fun terminateTree(child: Process?, descendants: ConcurrentHashMap<Long, ProcessHandle>) {
    child?.descendants()?.use { stream -> stream.forEach { descendants[it.pid()] = it } }
    descendants.values.filter { it.isAlive }.forEach { it.destroyForcibly() }
    if (child?.isAlive == true) child.destroyForcibly()
    while (child?.isAlive == true || descendants.values.any { it.isAlive }) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
        descendants.values.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}

/** Canonical resource identities are case-folded on Windows to cover differently spelled aliases. */
internal fun resourceLockKey(resource: String): String {
    val normalized = Path.of(resource).toAbsolutePath().normalize().toString().let {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) it.lowercase(Locale.ROOT) else it
    }
    return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
        "%02x".format(Locale.ROOT, it)
    }
}

internal fun writeWorkerState(job: Path, state: WorkerState) {
    val target = job.resolve("state.json")
    val temporary = job.resolve("state.pending")
    Files.writeString(temporary, Json.encodeToString(state))
    var attempts = 0
    while (Files.exists(temporary)) {
        try {
            moveWorkerState(temporary, target)
        } catch (error: java.nio.file.AccessDeniedException) {
            // Windows scanners/readers may briefly retain a snapshot handle across a replace operation.
            if (++attempts >= STATE_MOVE_ATTEMPTS) throw error
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
        }
    }
}

private const val STATE_MOVE_ATTEMPTS = 10

private fun moveWorkerState(temporary: Path, target: Path) {
    try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: AtomicMoveNotSupportedException) {
        workerLog.w(IllegalStateException("WorkerStateAtomicMoveUnavailable (${error::class.simpleName.orEmpty()})")) {
            "Worker state uses a replace move"
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
