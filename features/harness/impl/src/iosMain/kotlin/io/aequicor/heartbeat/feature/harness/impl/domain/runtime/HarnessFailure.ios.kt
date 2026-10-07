package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CancellationException

internal actual fun Throwable.isRecoverableHarnessFailure(): Boolean =
    this is Exception || this is NotImplementedError || this is AssertionError

internal actual suspend fun <T> capturePlatformHarnessFailure(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
} catch (error: NotImplementedError) {
    Result.failure(error)
} catch (error: AssertionError) {
    Result.failure(error)
}
