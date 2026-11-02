package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import kotlinx.coroutines.CancellationException

/** All close attempts run before errors escape; cancellation has priority. */
internal fun rethrowKoogCleanupFailures(failures: List<Throwable>) {
    val failure = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull() ?: return
    failures.filter { it !== failure }.forEach(failure::addSuppressed)
    throw failure
}

/**
 * Inline resource bracket that arbitrates operation and cleanup failures without replacing cancellation.
 * Both results are consumed immediately after cleanup, including fatal errors; no failure is discarded.
 * Suspending callers must provide a NonCancellable cleanup lambda, keeping resource ownership until it returns.
 * The block must return its value normally: a nonlocal return from either inline lambda is forbidden.
 */
internal inline fun <T> withKoogCleanup(cleanup: () -> Unit, block: () -> T): T {
    val result = runCatching(block)
    val closed = runCatching(cleanup)
    rethrowKoogCleanupFailures(listOfNotNull(result.exceptionOrNull(), closed.exceptionOrNull()))
    return result.getOrThrow()
}
