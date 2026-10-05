package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.JavaPlantUmlWorkerLauncher
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PLANTUML_WORKER_READY
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Draws in a separate worker process, because the sources come from language models and PlantUML's preprocessor is a
 * programming language: a few lines of variables, functions or regular expressions grow memory or time without bound,
 * and no filtering of the source can tell them apart. The worker has its own small heap and exits when it runs out
 * (a too-large failure); a drawing that outlives its time limit is ended by killing the worker (a timeout). Either way
 * the app's heap and the renderer's worker thread stay free, and the next drawing starts a new worker.
 *
 * The worker starts on the first drawing, serves drawings one at a time (callers are serialized by the renderer) and
 * stops after [idleTimeout] without drawings or when [scope] is cancelled; timers run in [scope] on [timers]. The
 * worker also ends itself when the app's process does.
 */
internal class ProcessPlantUmlEngine(
    private val scope: CoroutineScope,
    private val timers: CoroutineDispatcher,
    private val launcher: PlantUmlWorkerLauncher = JavaPlantUmlWorkerLauncher(),
    private val idleTimeout: Duration = IDLE_TIMEOUT,
) : PlantUmlEngine {
    private val lock = ReentrantLock()

    /** Guarded by [lock]. */
    private var worker: Worker? = null
    private var idleStop: Job? = null
    private var isClosed = false

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
        val deadline = scope.launch(timers) {
            delay(limits.timeout)
            worker.kill(isTimeout = true)
        }
        return try {
            val reply = worker.draw(PlantUmlWorkerRequest(source, preamble, limits))
            reply.diagnostics.forEach { diagnostic ->
                log.w(PlantUmlWorkerException(diagnostic)) { "PlantUML worker reported a problem type=${source.type}" }
            }
            reply.result
        } catch (e: IOException) {
            val end = lost(worker)
            log.w(e) { "PlantUML worker ended type=${source.type}: $end (limit ${limits.timeout})" }
            end.result
        } finally {
            deadline.cancel()
            release(worker)
        }
    }

    /** The running worker, or a new one; null after [scope] ended or when the worker cannot start. */
    private fun acquire(): Worker? = lock.withLock {
        idleStop?.cancel()
        idleStop = null
        if (isClosed) return null
        worker?.takeIf { it.process.isAlive }?.let { return it }
        worker = null
        try {
            Worker(launcher.start()).also {
                log.d { "PlantUML worker started pid=${it.process.pid()}" }
                worker = it
            }
        } catch (e: IOException) {
            log.e(e) { "PlantUML worker could not start" }
            null
        } catch (e: IllegalStateException) {
            log.e(e) { "PlantUML worker has no launcher" }
            null
        }
    }

    /** Forgets a dead worker; schedules a stop of a live one that stays idle. */
    private fun release(worker: Worker) = lock.withLock {
        when {
            this.worker !== worker || isClosed -> Unit

            !worker.process.isAlive -> this.worker = null

            else -> idleStop = scope.launch(timers) {
                delay(idleTimeout)
                stopIfIdle(worker)
            }
        }
    }

    private fun stopIfIdle(worker: Worker) {
        val isIdle = lock.withLock { (this.worker === worker).also { if (it) this.worker = null } }
        if (!isIdle) return
        log.d { "PlantUML worker stopped after $idleTimeout idle" }
        worker.kill(isTimeout = false)
    }

    /** Why the [worker] of a drawing died: its time limit, a source too large for its heap, the scope, or a failure. */
    private fun lost(worker: Worker): WorkerEnd {
        val exit = worker.awaitExit()
        lock.withLock { if (this.worker === worker) this.worker = null }
        return when {
            worker.isTimedOut -> WorkerEnd.TimedOut
            exit == WORKER_OUT_OF_MEMORY_EXIT -> WorkerEnd.OutOfMemory
            isClosedNow() -> WorkerEnd.Stopped
            else -> WorkerEnd.Failed
        }
    }

    private fun isClosedNow(): Boolean = lock.withLock { isClosed }

    private fun close() {
        val stopped = lock.withLock {
            isClosed = true
            idleStop?.cancel()
            idleStop = null
            worker.also { worker = null }
        }
        stopped?.let {
            log.d { "PlantUML worker stopped with its scope" }
            it.kill(isTimeout = false)
        }
    }

    /** One worker process and its streams; [draw] runs on one thread at a time, [kill] on any thread. */
    private class Worker(val process: Process) {
        private val input = DataInputStream(BufferedInputStream(process.inputStream))
        private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
        private var isReady = false

        @Volatile
        var isTimedOut = false
            private set

        fun draw(request: PlantUmlWorkerRequest): PlantUmlWorkerReply {
            if (!isReady) {
                if (input.readInt() != PLANTUML_WORKER_READY) throw IOException("Not a PlantUML worker")
                isReady = true
            }
            output.writeRequest(request)
            return input.readReply(request.limits.maxPngBytes)
        }

        fun kill(isTimeout: Boolean) {
            if (isTimeout) isTimedOut = true
            process.destroyForcibly()
        }

        /** The exit code once the process has ended, or null when it is still running; then it is killed. */
        fun awaitExit(): Int? {
            if (process.waitFor(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) return process.exitValue()
            process.destroyForcibly()
            return null
        }
    }

    private companion object {
        val log = Log.tag("PlantUmlWorker")
        val IDLE_TIMEOUT = 2.minutes
        const val EXIT_WAIT_MILLIS = 2_000L
    }
}

/** How a worker that died during a drawing ended, and what the drawing [result] is then. */
private enum class WorkerEnd(val result: PlantUmlResult) {
    TimedOut(PlantUmlResult.Failed(PlantUmlFailure.Timeout)),
    OutOfMemory(PlantUmlResult.Failed(PlantUmlFailure.TooLarge)),
    Stopped(PlantUmlResult.Failed(PlantUmlFailure.Internal)),
    Failed(PlantUmlResult.Failed(PlantUmlFailure.Internal)),
}

/** A warning or error the worker process logged, with its stack trace as text. */
internal class PlantUmlWorkerException(diagnostic: String) : Exception(diagnostic)
