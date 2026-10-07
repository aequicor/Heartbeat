package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only a restored journal proving no prompt was admitted can construct this lease; it cannot create a helper. */
internal class CleanupOnlyHelperLease(
    override val owner: ActionId,
    private val reservation: ActionId,
    private val capacity: ProfileBackgroundCapacity,
) : HelperLease {
    private val lock = Mutex()
    private var isReleased = false

    override suspend fun release(): HelperReleaseResult = withContext(NonCancellable) {
        lock.withLock {
            if (!isReleased) {
                capacity.release(reservation)
                isReleased = true
            }
            HelperReleaseResult.Released
        }
    }
}
