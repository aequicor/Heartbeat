package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.TaskProcess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Background shell commands: PowerShell on Windows, `/bin/sh` elsewhere, in the project directory. Secrets-looking
 * environment variables are not inherited, stdin is closed, and the process tree is killed on timeout or when the
 * profile closes, including observed children still alive after their shell exits. Output is polled (a detached
 * child may hold the pipe) and kept as head and tail. Commands are never
 * logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopCommandRunner(private val dispatchers: DispatcherProvider) : CommandRunner {
    private val log = Log.tag("DesktopCommandRunner")
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    override val isAvailable: Boolean = true
    override val isStartTracked: Boolean = true

    override suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome =
        runTracked(directory, command, timeout) {}

    override suspend fun runTracked(
        directory: String,
        command: String,
        timeout: Duration,
        onStarted: suspend (TaskProcess) -> Unit,
    ): CommandOutcome = withContext(dispatchers.io) {
        val environment = ProcessBuilder().environment().toMutableMap()
        val removed = environment.keys.filter(::isSecretName)
        removed.forEach(environment::remove)
        log.i { "starting contained background command; ${removed.size} secret-like variables withheld" }
        val process = startProcess(directory, command, environment)
        var job: WindowsGraphJob? = null
        val output = HeadTail()
        val children = mutableMapOf<Long, ProcessHandle>()
        var hasExited = false
        try {
            if (isWindows) job = WindowsGraphJob.attach(process)
            val startedAt = checkNotNull(process.info().startInstant().orElse(null)) {
                "The command process has no verifiable start time"
            }
            onStarted(
                TaskProcess(
                    process.pid(),
                    startedAt.toString(),
                    group = process.pid().takeIf { !isWindows },
                    isJobContained = isWindows,
                    job = job?.name,
                ),
            )
            process.outputStream.use {
                it.write("heartbeat-run\n".toByteArray(Charsets.UTF_8))
                it.flush()
            }
            hasExited = drain(process, output, timeout) {
                if (isWindows) {
                    process.descendants().use { descendants ->
                        descendants.forEach {
                            children[it.pid()] =
                                it
                        }
                    }
                }
            }
        } finally {
            // A cancelled drain never reports an exit, so cancellation also kills the tree.
            val isKillRequired = !hasExited || process.isAlive || children.values.any { it.isAlive }
            withContext(NonCancellable) {
                try {
                    if (isKillRequired) kill(process, children.values.toList())
                    confirmStopped(process, job)
                } finally {
                    job?.close()
                    close(process)
                }
            }
        }
        val exitCode = if (hasExited) process.exitValue() else null
        log.i { "background command ended exitCode=${exitCode ?: "timeout"}" }
        CommandOutcome(exitCode, output.text())
    }

    private fun startProcess(directory: String, command: String, environment: Map<String, String>): Process {
        if (!isWindows) return PosixGraphProcess.start(directory, command, environment)
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
        val script = "if ([Console]::ReadLine() -ne 'heartbeat-run') { exit 125 }; " +
            "& powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand $encoded; exit \$LASTEXITCODE"
        val shell = listOf(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
        )
        return ProcessBuilder(shell).directory(File(directory)).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()
    }

    private suspend fun confirmStopped(process: Process, job: WindowsGraphJob?) {
        if (isWindows) job?.terminate() else PosixGraphProcess.signalGroup(process.pid(), SIGKILL)
        val isStopped = withTimeoutOrNull(KILL_TIMEOUT) {
            while (if (isWindows) job?.isEmpty() == false else !PosixGraphProcess.isGroupStopped(process.pid())) {
                delay(POLL)
            }
            true
        } == true
        check(isStopped) { "Native descendants may still be running" }
    }

    override suspend fun isStopped(process: TaskProcess): Boolean = withContext(dispatchers.io) {
        process.group?.let { group ->
            val leader = ProcessHandle.of(group).orElse(null)
            val startedAt = leader?.info()?.startInstant()?.orElse(null)?.toString()
            val isReused = startedAt != null && startedAt != process.startedAt
            return@withContext isReused || PosixGraphProcess.isGroupStopped(group)
        }
        if (isWindows) return@withContext process.job?.let { WindowsGraphJob.inspect(it) } == true
        val handle = ProcessHandle.of(process.pid).orElse(null)
        handle == null || !handle.isAlive ||
            handle.info().startInstant().orElse(null)?.toString()?.let { it != process.startedAt } == true
    }

    override suspend fun stop(process: TaskProcess): Boolean = withContext(dispatchers.io) {
        if (isStopped(process)) return@withContext true
        val group = process.group
        when {
            group != null -> stopGroup(group)
            isWindows -> process.job?.let { stopJob(it) } == true
            else -> stopTree(process)
        }
    }

    private suspend fun stopGroup(group: Long): Boolean {
        PosixGraphProcess.signalGroup(group, SIGKILL)
        return withTimeoutOrNull(KILL_TIMEOUT) {
            while (!PosixGraphProcess.isGroupStopped(group)) delay(POLL)
            true
        } == true
    }

    private suspend fun stopJob(name: String): Boolean = withTimeoutOrNull(KILL_TIMEOUT) {
        while (!WindowsGraphJob.inspect(name, terminate = true)) delay(POLL)
        true
    } == true

    private suspend fun stopTree(process: TaskProcess): Boolean {
        val handle = ProcessHandle.of(process.pid).orElse(null) ?: return true
        if (handle.info().startInstant().orElse(null)?.toString() != process.startedAt) return false
        log.i { "stop an interrupted graph command" }
        val children = handle.descendants().use { it.toList() }
        children.forEach { it.destroyForcibly() }
        handle.destroyForcibly()
        return withTimeoutOrNull(KILL_TIMEOUT) {
            while (handle.isAlive || children.any { it.isAlive }) delay(POLL)
            true
        } == true
    }

    /** Collects output until the process exits (and its pipe went quiet) or [timeout] passes. */
    private suspend fun drain(process: Process, output: HeadTail, timeout: Duration, observe: () -> Unit): Boolean {
        val input = process.inputStream
        val buffer = ByteArray(BUFFER_BYTES)
        val started = TimeSource.Monotonic.markNow()
        var exitedAt: TimeSource.Monotonic.ValueTimeMark? = null
        var isFinished: Boolean? = null
        while (isFinished == null) {
            currentCoroutineContext().ensureActive()
            observe()
            val read = readAvailable(input, buffer, output)
            if (!process.isAlive && exitedAt == null) exitedAt = TimeSource.Monotonic.markNow()
            isFinished = drainOutcome(read, started.elapsedNow() > timeout, exitedAt?.elapsedNow())
            if (isFinished == null && read == 0) delay(POLL)
        }
        return isFinished
    }

    /**
     * Whether draining is over: true once the exited process's pipe stayed quiet for a moment
     * (a detached child may keep it open), false on timeout, null to keep reading.
     */
    private fun drainOutcome(read: Int, isPastDeadline: Boolean, sinceExit: Duration?): Boolean? = when {
        sinceExit == null -> if (isPastDeadline) false else null
        sinceExit > AFTER_EXIT_LIMIT -> true
        read == 0 && sinceExit > EXIT_GRACE -> true
        else -> null
    }

    private fun readAvailable(input: InputStream, buffer: ByteArray, output: HeadTail): Int {
        val available = input.available()
        if (available <= 0) return 0
        val read = input.read(buffer, 0, minOf(available, buffer.size))
        if (read > 0) output.append(buffer, read)
        return read
    }

    private suspend fun kill(process: Process, children: List<ProcessHandle>) {
        log.i { "killing background command process tree" }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        // A process the OS refuses to kill must not keep this coroutine spinning forever.
        val isDead = withTimeoutOrNull(KILL_TIMEOUT) {
            while (process.isAlive || children.any { it.isAlive }) {
                children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                delay(POLL)
            }
        } != null
        if (!isDead) log.w { "background command processes survived the kill" }
    }

    private fun close(process: Process) {
        try {
            process.inputStream.close()
            process.outputStream.close()
        } catch (e: IOException) {
            log.w(e) { "background command output could not be closed" }
        }
    }

    private companion object {
        const val SIGKILL = 9
        const val BUFFER_BYTES = 4096
        val POLL = 100.milliseconds
        val EXIT_GRACE = 200.milliseconds
        val AFTER_EXIT_LIMIT = 2_000.milliseconds
        val KILL_TIMEOUT = 10_000.milliseconds
        val SECRET_PARTS = listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH")

        /** Sockets and display cookies, not credentials: git over ssh and GUI tools need them. */
        val KEPT_NAMES = setOf("SSH_AUTH_SOCK", "XAUTHORITY")

        fun isSecretName(name: String): Boolean = name.uppercase().let { upper ->
            upper !in KEPT_NAMES && SECRET_PARTS.any { it in upper }
        }
    }
}

/** Keeps the head and the tail of a stream, where commands print what matters. */
private class HeadTail {
    private val head = ByteArrayOutputStream()
    private val tail = ByteArray(TAIL_BYTES)
    private var tailStart = 0
    private var tailSize = 0
    private var dropped = 0L

    fun append(bytes: ByteArray, count: Int) {
        val toHead = minOf(count, HEAD_BYTES - head.size())
        if (toHead > 0) head.write(bytes, 0, toHead)
        for (i in toHead until count) {
            if (tailSize < TAIL_BYTES) {
                tail[(tailStart + tailSize) % TAIL_BYTES] = bytes[i]
                tailSize++
            } else {
                tail[tailStart] = bytes[i]
                tailStart = (tailStart + 1) % TAIL_BYTES
                dropped++
            }
        }
    }

    fun text(): String {
        val tailBytes = ByteArray(tailSize) { tail[(tailStart + it) % TAIL_BYTES] }
        return buildString {
            append(head.toString(Charsets.UTF_8))
            if (dropped > 0) append("\n… $dropped bytes omitted …\n")
            append(tailBytes.toString(Charsets.UTF_8))
        }
    }

    private companion object {
        const val HEAD_BYTES = 2_000
        const val TAIL_BYTES = 5_000
    }
}
