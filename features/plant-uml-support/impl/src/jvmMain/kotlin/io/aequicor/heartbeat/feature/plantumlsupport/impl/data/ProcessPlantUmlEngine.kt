package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.JavaPlantUmlWorkerLauncher
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PLANTUML_WORKER_READY
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerDiagnostic
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerLauncher
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerReply
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerRequest
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.WORKER_OUT_OF_MEMORY_EXIT
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.readReply
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.writeRequest
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Draws in a separate worker process, because the sources come from language models and PlantUML's preprocessor is a
 * programming language: a few lines of variables, functions or regular expressions grow memory or time without bound,
 * and no filtering of the source can tell them apart. The worker has its own small heap and exits when it runs out
 * (a too-large failure, which lets the renderer retry at scale 1 in a fresh worker: the canvas shrinks with the
 * square of the scale); a drawing that outlives its time limit is ended by killing the worker (a timeout). Either
 * way the app's heap and the renderer's worker thread stay free, and the next drawing starts a new worker.
 *
 * The worker starts on the first drawing — its start has a time limit of its own — serves drawings one at a time
 * (callers are serialized by the renderer) and stops after [idleTimeout] without drawings or when [scope] is
 * cancelled; timers run in [scope] on [timers]. The worker also ends itself when the app's process does. After
 * [MAX_START_FAILURES] workers in a row fail to start, starts pause for [START_PAUSE].
 */
internal class ProcessPlantUmlEngine(
    private val scope: CoroutineScope,
    private val timers: CoroutineDispatcher,
    private val launcher: PlantUmlWorkerLauncher = JavaPlantUmlWorkerLauncher(),
    private val idleTimeout: Duration = IDLE_TIMEOUT,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PlantUmlEngine {
    private val lock = ReentrantLock()

    /** Guarded by [lock]. */
    private var worker: Worker? = null
    private var idleStop: Job? = null
    private var isClosed = false
    private var startFailures = 0
    private var startsPausedAt: TimeMark? = null

    init {
        // On cancellation, not completion: a drawing blocked on a hung worker would keep the scope from completing.
        scope.launch(timers, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
    }

    /** The running worker process, for tests. */
    internal val process: ProcessHandle? get() = lock.withLock { worker?.process?.toHandle() }

    override fun render(source: PlantUmlSource, preamble: List<String>, limits: PlantUmlLimits): PlantUmlResult {
        val worker = acquire() ?: return PlantUmlResult.Failed(PlantUmlFailure.Internal)
        return try {
            if (!worker.isReady) {
                bounded(worker, limits.timeout) { worker.awaitReady() }
                lock.withLock { startFailures = 0 }
            }
            val reply = bounded(worker, limits.timeout) { worker.draw(PlantUmlWorkerRequest(source, preamble, limits)) }
            reply.diagnostics.forEach(::logDiagnostic)
            reply.result
        } catch (e: IOException) {
            val (end, exit) = lost(worker)
            log.w(
                e,
            ) { "PlantUML worker ended type=${source.type}: $end exit=${exit ?: "killed"} limit=${limits.timeout}" }
            end.result
        } finally {
            release(worker)
        }
    }

    /**
     * Runs [block] on [worker] for at most [timeout]: past it the worker is killed, which ends [block] with an
     * [IOException]. A kill that races a finished [block] only retires the worker; its result stands.
     */
    private inline fun <T> bounded(worker: Worker, timeout: Duration, block: () -> T): T {
        val isSettled = AtomicBoolean(false)
        val deadline = scope.launch(timers) {
            delay(timeout)
            if (isSettled.compareAndSet(false, true)) worker.kill(isTimeout = true)
        }
        try {
            return block()
        } finally {
            isSettled.set(true)
            deadline.cancel()
        }
    }

    /** The running worker, or a new one; null after [scope] ended, while starts pause, or when it cannot start. */
    private fun acquire(): Worker? = lock.withLock {
        idleStop?.cancel()
        idleStop = null
        if (isClosed) return null
        worker?.takeIf { it.isUsable }?.let { current ->
            current.isBusy = true
            return current
        }
        worker = null
        if (startsPausedAt?.let { it.elapsedNow() < START_PAUSE } == true) {
            log.v { "PlantUML worker starts are paused" }
            return null
        }
        startsPausedAt = null
        try {
            Worker(launcher.start()).also {
                log.d { "PlantUML worker started pid=${it.process.pid()}" }
                it.isBusy = true
                worker = it
            }
        } catch (e: IOException) {
            log.e(e) { "PlantUML worker could not start" }
            countStartFailure()
            null
        } catch (e: IllegalStateException) {
            log.e(e) { "PlantUML worker has no launcher" }
            countStartFailure()
            null
        }
    }

    /** Guarded by [lock]. */
    private fun countStartFailure() {
        startFailures++
        if (startFailures < MAX_START_FAILURES) return
        log.e { "PlantUML workers failed to start $startFailures times in a row; starts pause for $START_PAUSE" }
        startFailures = 0
        startsPausedAt = timeSource.markNow()
    }

    /** Retires a dead or killed worker; schedules a stop of a live one that stays idle. */
    private fun release(worker: Worker) {
        val isRetired = lock.withLock {
            worker.isBusy = false
            when {
                this.worker !== worker -> !worker.isUsable

                !worker.isUsable -> {
                    this.worker = null
                    true
                }

                !isClosed -> {
                    idleStop = scope.launch(timers) {
                        delay(idleTimeout)
                        stopIfIdle(worker)
                    }
                    false
                }

                else -> false
            }
        }
        if (isRetired) worker.dispose()
    }

    private fun stopIfIdle(worker: Worker) {
        val isIdle = lock.withLock {
            (this.worker === worker && !worker.isBusy).also { if (it) this.worker = null }
        }
        if (!isIdle) return
        log.d { "PlantUML worker stopped after $idleTimeout idle" }
        worker.dispose()
    }

    /** Why the [worker] of a drawing died, and its exit code unless it had to be killed. */
    private fun lost(worker: Worker): Pair<WorkerEnd, Int?> {
        val exit = worker.awaitExit()
        val end = lock.withLock {
            when {
                worker.isTimedOut -> WorkerEnd.TimedOut
                exit == WORKER_OUT_OF_MEMORY_EXIT -> WorkerEnd.OutOfMemory
                isClosed -> WorkerEnd.Stopped
                !worker.isReady -> WorkerEnd.StartFailed.also { countStartFailure() }
                else -> WorkerEnd.Failed
            }
        }
        return end to exit
    }

    private fun close() {
        val stopped = lock.withLock {
            isClosed = true
            idleStop?.cancel()
            idleStop = null
            worker.also { worker = null }
        }
        stopped?.let {
            log.d { "PlantUML worker stopped with its scope" }
            // A drawing may still read from it: its own thread releases the streams.
            it.kill(isTimeout = false)
        }
    }

    /** Logs a warning or error of the worker at its level, under the worker's tag. */
    private fun logDiagnostic(diagnostic: PlantUmlWorkerDiagnostic) {
        val workerLog = Log.tag("PlantUmlWorker/${diagnostic.tag}")
        val error = PlantUmlWorkerException(diagnostic.message)
        if (diagnostic.level == LogLevel.ERROR) {
            workerLog.e(error) { "PlantUML worker logged an error" }
        } else {
            workerLog.w(error) { "PlantUML worker logged a warning" }
        }
    }

    /**
     * One worker process and its streams. [awaitReady] and [draw] run on one thread at a time; [kill] on any thread;
     * [dispose] only when no thread reads the streams. [isBusy] is guarded by the engine's lock.
     */
    private class Worker(val process: Process) {
        private val input = DataInputStream(BufferedInputStream(process.inputStream))
        private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
        var isBusy = false

        var isReady = false
            private set

        @Volatile
        var isTimedOut = false
            private set

        @Volatile
        private var isKilled = false

        val isUsable: Boolean get() = !isKilled && process.isAlive

        fun awaitReady() {
            if (input.readInt() != PLANTUML_WORKER_READY) throw IOException("Not a PlantUML worker")
            isReady = true
        }

        fun draw(request: PlantUmlWorkerRequest): PlantUmlWorkerReply {
            output.writeRequest(request)
            return input.readReply(request.limits.maxPngBytes)
        }

        fun kill(isTimeout: Boolean) {
            if (isTimeout) isTimedOut = true
            isKilled = true
            process.destroyForcibly()
        }

        /** Kills the process and closes its streams, whose handles would otherwise wait for collection (Windows). */
        fun dispose() {
            kill(isTimeout = false)
            listOf(input, output, process.errorStream).forEach { stream ->
                try {
                    stream.close()
                } catch (e: IOException) {
                    log.w(e) { "PlantUML worker stream did not close" }
                }
            }
        }

        /** The exit code once the process has ended, or null when it had to be killed. */
        fun awaitExit(): Int? {
            if (process.waitFor(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) return process.exitValue()
            kill(isTimeout = false)
            return null
        }
    }

    private companion object {
        val log = Log.tag("PlantUmlWorker")
        val IDLE_TIMEOUT = 2.minutes
        val START_PAUSE = 1.minutes
        const val MAX_START_FAILURES = 3

        /** A worker that closed its output exits right after; a live one is out of sync and is killed. */
        const val EXIT_WAIT_MILLIS = 500L
    }
}

/** How a worker that died during a drawing ended, and what the drawing [result] is then. */
private enum class WorkerEnd(val result: PlantUmlResult) {
    TimedOut(PlantUmlResult.Failed(PlantUmlFailure.Timeout)),
    OutOfMemory(PlantUmlResult.Failed(PlantUmlFailure.TooLarge)),
    Stopped(PlantUmlResult.Failed(PlantUmlFailure.Internal)),
    StartFailed(PlantUmlResult.Failed(PlantUmlFailure.Internal)),
    Failed(PlantUmlResult.Failed(PlantUmlFailure.Internal)),
}

/** A warning or error the worker process logged; its message carries the worker's stack trace as text. */
internal class PlantUmlWorkerException(diagnostic: String) : Exception(diagnostic) {
    // The host's stack says nothing about the worker's failure.
    override fun fillInStackTrace(): Throwable = this
}
