package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HARNESS_COMPILER_WORKER_READY
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerReply
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerRequest
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.JavaHarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.WORKER_FATAL_EXIT
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.WORKER_OUT_OF_MEMORY_EXIT
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.WORKER_UNCAPPED_HEAP_EXIT
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.readReply
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.writeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Compiles one harness source into a jar at a host-owned path; tests substitute a fake compiler. */
internal fun interface HarnessKotlinCompiler {
    suspend fun compile(kind: HarnessCodeKind, source: String, output: Path): HarnessCompilerOutcome
}

/** How one compilation ended. Diagnostics are bounded UI data, never logged. */
internal sealed interface HarnessCompilerOutcome {
    /** The jar is in the output file. */
    data class Compiled(val warnings: List<HarnessCodeDiagnostic>) : HarnessCompilerOutcome {
        override fun toString(): String = "HarnessCompilerOutcome.Compiled(***)"
    }

    data class Failed(val diagnostics: List<HarnessCodeDiagnostic>) : HarnessCompilerOutcome {
        override fun toString(): String = "HarnessCompilerOutcome.Failed(***)"
    }

    /** The compiler outlived its budget; its worker is dead. */
    data object TimedOut : HarnessCompilerOutcome

    /** The worker could not start, failed internally or died; the reason is logged. */
    data object Unavailable : HarnessCompilerOutcome
}

/**
 * Runs K2 in a separate worker process: the compiler cannot be interrupted inside a thread, so a source that keeps it
 * busy would hold the app's heap and CPU and could leave the compiler's shared jar caches half closed. A compilation
 * that outlives [budget] — which includes the worker's start — kills the worker, and [compile] returns only after the
 * process has died, so the caller's single compiler permit never outlives its compiler. The worker has its own capped
 * heap and exits when it runs out; evaluation of the compiled jar stays in the app.
 *
 * The worker starts on the first compilation, serves compilations one at a time and stops after [idleTimeout]
 * without one, when it dies or is killed (the next compilation starts a fresh one), or when [scope] is cancelled.
 * Blocking stream IO runs on [blocking]; the budget and the idle timer on [timers]. The worker also ends itself
 * when the app's process does.
 */
internal class ProcessHarnessCompiler(
    private val scope: CoroutineScope,
    private val blocking: CoroutineDispatcher,
    private val timers: CoroutineDispatcher,
    private val launcher: HarnessCompilerLauncher = JavaHarnessCompilerLauncher(),
    private val budget: Duration = COMPILER_RUN_BUDGET,
    private val idleTimeout: Duration = IDLE_TIMEOUT,
) : HarnessKotlinCompiler {
    private val runs = Mutex()
    private val lock = Any()

    /** Guarded by [lock]. */
    private var worker: Worker? = null
    private var idleStop: Job? = null

    /** The running worker process, for tests. */
    internal val process: ProcessHandle? get() = synchronized(lock) { worker?.process?.toHandle() }

    override suspend fun compile(kind: HarnessCodeKind, source: String, output: Path): HarnessCompilerOutcome =
        runs.withLock {
            val worker = acquire() ?: return@withLock HarnessCompilerOutcome.Unavailable
            try {
                exchange(worker, HarnessCompilerRequest(kind, source, output.toString()))
            } catch (e: CancellationException) {
                // Cancellation can complete the queued reader before it starts, or precede exchange altogether.
                // That still owns a live process: retire and reap it before returning the compiler permit.
                withContext(NonCancellable) { worker.stop() }
                throw e
            } finally {
                release(worker)
            }
        }

    /**
     * Sends [request] to [worker] and waits for its reply for at most [budget]. On a timeout or cancellation the
     * reader is blocked in the worker's streams: the worker is killed, and this returns only after its death.
     */
    private suspend fun exchange(worker: Worker, request: HarnessCompilerRequest): HarnessCompilerOutcome =
        coroutineScope {
            val reading = async(blocking) { worker.serve(request) }
            try {
                val served = withContext(timers) { withTimeoutOrNull(budget) { reading.await() } }
                when {
                    served == null -> {
                        log.w { "harness compiler exceeded its budget of $budget; worker killed" }
                        worker.kill()
                        HarnessCompilerOutcome.TimedOut
                    }

                    served.isSuccess -> served.getOrThrow().outcome()

                    else -> {
                        // The streams end before the dying worker's exit is reported: let it finish exiting first.
                        val exit = withContext(NonCancellable + timers) { worker.stop(EXIT_GRACE) }
                        log.w(served.exceptionOrNull()) { "harness compiler worker ended: ${exit.describeExit()}" }
                        if (exit == WORKER_OUT_OF_MEMORY_EXIT) {
                            HarnessCompilerOutcome.Failed(listOf(OUT_OF_MEMORY_DIAGNOSTIC))
                        } else {
                            HarnessCompilerOutcome.Unavailable
                        }
                    }
                }
            } finally {
                if (!reading.isCompleted) worker.kill()
                withContext(NonCancellable) {
                    reading.join()
                    if (!worker.isUsable) worker.stop()
                }
            }
        }

    private fun HarnessCompilerReply.outcome(): HarnessCompilerOutcome = when (this) {
        is HarnessCompilerReply.Compiled -> HarnessCompilerOutcome.Compiled(warnings)

        is HarnessCompilerReply.Failed -> HarnessCompilerOutcome.Failed(diagnostics)

        HarnessCompilerReply.Internal -> {
            log.w { "harness compiler worker reported an internal failure" }
            HarnessCompilerOutcome.Unavailable
        }
    }

    /** The idle worker, or a new one; null after [scope] ended or when no worker can start. */
    private fun acquire(): Worker? = synchronized(lock) {
        worker?.let { current ->
            // Busy first: the cancelled idle timer then leaves this worker running.
            current.isBusy = true
            idleStop?.cancel()
            idleStop = null
            if (current.isUsable && scope.isActive) return current
            worker = null
            current.dispose()
        }
        if (!scope.isActive) return null
        try {
            Worker(launcher.start()).also {
                log.d { "harness compiler worker started pid=${it.process.pid()}" }
                it.isBusy = true
                worker = it
            }
        } catch (e: IOException) {
            log.e(e) { "harness compiler worker could not start" }
            null
        } catch (e: IllegalStateException) {
            log.e(e) { "harness compiler worker has no launcher" }
            null
        }
    }

    /** Retires a dead or killed worker, or one whose scope ended; schedules a stop of a live one that stays idle. */
    private fun release(worker: Worker) {
        val isRetired = synchronized(lock) {
            worker.isBusy = false
            if (this.worker === worker && worker.isUsable && scope.isActive) {
                val timer = scope.launch(timers) { delay(idleTimeout) }
                idleStop = timer
                // Also on cancellation of the scope, including before the timer ever ran.
                timer.invokeOnCompletion { stopIfIdle(worker, timer) }
                false
            } else {
                if (this.worker === worker) this.worker = null
                true
            }
        }
        if (isRetired) worker.dispose()
    }

    private fun stopIfIdle(worker: Worker, timer: Job) {
        val isIdle = synchronized(lock) {
            (idleStop === timer && this.worker === worker && !worker.isBusy).also {
                if (it) {
                    this.worker = null
                    idleStop = null
                }
            }
        }
        if (!isIdle) return
        log.d { "harness compiler worker stopped while idle" }
        worker.dispose()
    }

    /**
     * One worker process and its streams. [serve] runs on one thread at a time; [kill] on any thread; [dispose] only
     * when no thread reads the streams. [isBusy] is guarded by the compiler's lock.
     */
    private class Worker(val process: Process) {
        private val input = DataInputStream(BufferedInputStream(process.inputStream))
        private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
        var isBusy = false

        /** Only the serving thread reads it. */
        private var isReady = false

        @Volatile
        private var isKilled = false

        val isUsable: Boolean get() = !isKilled && process.isAlive

        /** Blocking; a failure of the streams means the worker is dead or out of sync. */
        fun serve(request: HarnessCompilerRequest): Result<HarnessCompilerReply> = try {
            if (!isReady) {
                if (input.readInt() != HARNESS_COMPILER_WORKER_READY) throw IOException("Not a harness compiler worker")
                isReady = true
            }
            output.writeRequest(request)
            Result.success(input.readReply())
        } catch (e: IOException) {
            Result.failure(e)
        }

        fun kill() {
            isKilled = true
            process.destroyForcibly()
        }

        /**
         * Waits up to [grace] for the worker to exit on its own, then kills it if it still runs, and waits for its
         * death; the exit code, or null when it had to be killed.
         */
        suspend fun stop(grace: Duration = Duration.ZERO): Int? {
            val hasExited = !process.isAlive ||
                (grace.isPositive() && withTimeoutOrNull(grace) { process.onExit().await() } != null)
            if (!hasExited) kill()
            process.onExit().await()
            return if (hasExited) process.exitValue() else null
        }

        /**
         * Kills the process and closes its streams, whose handles would otherwise wait for collection (Windows). The
         * raw streams: flushing a buffered request into a killed process would only fail.
         */
        fun dispose() {
            kill()
            listOf(process.inputStream, process.errorStream, process.outputStream).forEach { stream ->
                try {
                    stream.close()
                } catch (e: IOException) {
                    log.w(e) { "harness compiler worker stream did not close" }
                }
            }
        }
    }

    private companion object {
        val log = Log.tag("HarnessCompilerWorker")
        val IDLE_TIMEOUT = 2.minutes

        /** How long a worker whose streams ended may take to report its own exit code. */
        val EXIT_GRACE = 5.seconds
        val OUT_OF_MEMORY_DIAGNOSTIC = HarnessCodeDiagnostic(
            HarnessDiagnosticSeverity.Error,
            "The compiler ran out of memory compiling this code",
        )
    }
}

/** Upper bound of one compilation, the worker's start included. */
internal val COMPILER_RUN_BUDGET: Duration = 60.seconds

private fun Int?.describeExit(): String = when (this) {
    null -> "killed"
    WORKER_OUT_OF_MEMORY_EXIT -> "out of memory"
    WORKER_UNCAPPED_HEAP_EXIT -> "heap not capped"
    WORKER_FATAL_EXIT -> "fatal error"
    else -> "exit=$this"
}
