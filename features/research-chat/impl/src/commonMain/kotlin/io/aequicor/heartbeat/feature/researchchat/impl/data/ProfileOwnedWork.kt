package io.aequicor.heartbeat.feature.researchchat.impl.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/**
 * Captures a failure of profile-owned work so that the only way such a job ends abnormally is its own
 * cancellation (user cancel, revoked flag, removed source, closed profile).
 */
internal inline fun captureFailure(block: () -> Unit): Result<Unit> = try {
    block()
    Result.success(Unit)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

/**
 * Waits for profile-owned work started with [captureFailure]. Cancellation of the work itself is an expected
 * end for a caller that is still active and returns `false`; the caller's own cancellation still propagates
 * and a captured failure is rethrown.
 */
internal suspend fun Deferred<Result<Unit>>.awaitOwned(): Boolean {
    join()
    if (isCancelled) return false
    await().getOrThrow()
    return true
}
