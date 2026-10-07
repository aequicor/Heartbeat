package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Standalone release-image probe, invoked before profile DI or any user data is opened. */
public object HarnessHostProbe {
    /**
     * Compiles and evaluates fixed local fixtures, checks a fresh host's disk cache and bounded diagnostics.
     * Completion proves that every owned coroutine, artifact lease and executor has finished. A compiler wait
     * timeout is a failure, but never shortens this resource barrier. Errors contain no source or filesystem paths.
     */
    @JvmStatic
    public fun run(cacheDirectory: String, appVersion: String): CompletionStage<String> =
        runHarnessProbe(cacheDirectory, appVersion) { directory, version, scope, dispatchers ->
            // Full compiler exceptions are safe only here: this host sees fixed fixtures and no profile data.
            JvmHarnessScriptHost(directory, version, scope, dispatchers) { error ->
                log.w(error) { "Fixed compiler fixture failed inside the packaged compiler" }
            }
        }
}

internal typealias HarnessProbeHostFactory = (Path, String, CoroutineScope, DispatcherProvider) -> HarnessScriptHost

/** Constructor injection is also used by deterministic cleanup tests; production always uses the real host. */
internal fun runHarnessProbe(
    cacheDirectory: String,
    appVersion: String,
    factory: HarnessProbeHostFactory,
): CompletionStage<String> {
    val directory = try {
        Paths.get(cacheDirectory)
    } catch (error: InvalidPathException) {
        log.w(error.sanitizedProbeFailure()) { "Harness compiler probe setup failed" }
        return CompletableFuture.failedStage(error.sanitizedProbeFailure())
    }
    val serial = AtomicInteger()
    val threadPrefix = "heartbeat-harness-probe-${UUID.randomUUID()}"
    val executor = Executors.newFixedThreadPool(PROBE_THREADS) { task ->
        Thread(task, "$threadPrefix-${serial.incrementAndGet()}").apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()
    val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val failure = AtomicReference<HarnessProbeFailure?>()
    val root = SupervisorJob()
    val errors = CoroutineExceptionHandler { _, error ->
        log.w(error.sanitizedProbeFailure()) { "Harness compiler probe coroutine failed" }
        failure.remember(error)
    }
    val scope = CoroutineScope(root + dispatcher + errors)
    val environment = HarnessProbeEnvironment(directory, appVersion, dispatchers, factory)
    val completed = CompletableFuture<String>()
    val worker = scope.launch { exerciseProbe(environment, scope, failure) }
    worker.invokeOnCompletion { error ->
        if (error != null) failure.remember(error)
        root.cancel()
        root.invokeOnCompletion {
            // This waiter cannot run on the owned executor: it waits for that executor's final worker to exit.
            CompletableFuture.runAsync { completeProbe(dispatcher, executor, failure, completed) }
        }
    }
    // Cancelling a caller's toCompletableFuture copy cannot bypass the probe's resource barrier.
    return completed.minimalCompletionStage()
}

private suspend fun exerciseProbe(
    environment: HarnessProbeEnvironment,
    scope: CoroutineScope,
    failure: AtomicReference<HarnessProbeFailure?>,
) {
    try {
        exerciseHarnessHost(environment, scope)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.sanitizedProbeFailure()) { "Harness compiler probe execution failed" }
        failure.remember(error)
    } catch (error: LinkageError) {
        log.w(error.sanitizedProbeFailure()) { "Harness compiler probe runtime linkage failed" }
        failure.remember(error)
    }
}

private fun completeProbe(
    dispatcher: ExecutorCoroutineDispatcher,
    executor: ExecutorService,
    failure: AtomicReference<HarnessProbeFailure?>,
    completed: CompletableFuture<String>,
) {
    dispatcher.close()
    var isInterrupted = false
    while (!executor.isTerminated) {
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            log.w(error.sanitizedProbeFailure()) { "Harness compiler probe cleanup interrupted" }
            isInterrupted = true
            failure.remember(error)
        }
    }
    if (isInterrupted) Thread.currentThread().interrupt()
    val error = failure.get()
    if (error == null) completed.complete(HARNESS_PROBE_SUCCESS) else completed.completeExceptionally(error)
}

private fun AtomicReference<HarnessProbeFailure?>.remember(error: Throwable) {
    compareAndSet(null, error.sanitizedProbeFailure())
}

private fun Throwable.sanitizedProbeFailure(): HarnessProbeFailure =
    this as? HarnessProbeFailure ?: HarnessProbeFailure("Execution")

/** Only fixed stage names are accepted by callers in this file family; no original cause is retained. */
internal class HarnessProbeFailure(stage: String) : IllegalStateException("HarnessCompilerProbeFailed:$stage")

internal const val HARNESS_PROBE_SUCCESS = "heartbeat-harness-probe-ok-v1"
private const val PROBE_THREADS = 2
private val log = Log.tag("HarnessHostProbe")
