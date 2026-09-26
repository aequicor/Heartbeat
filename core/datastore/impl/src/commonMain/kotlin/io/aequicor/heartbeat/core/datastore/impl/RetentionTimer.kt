package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** Longest single sleep: the deadline is re-checked at least this often, so wall-clock jumps are caught up. */
private const val MAX_SLEEP_MILLIS = 15 * 60 * 1000L

/** Pause before the timer restarts after a failure (IO error, database closed under it). */
private const val RETRY_DELAY_MILLIS = 60 * 1000L

/**
 * Sleeps until the nearest deadline of [nextDeadline] and runs [purge]; the sleep restarts whenever the storage
 * changes. [purge] runs downstream of the sleep, so the change it makes itself does not cancel it halfway.
 * A failure is logged and the timer restarts after a pause. Returns only when cancelled.
 */
internal suspend fun runRetentionTimer(
    label: String,
    clock: RetentionClock,
    nextDeadline: Flow<Long?>,
    purge: suspend () -> Unit,
) {
    while (true) {
        try {
            sleepAndPurge(clock, nextDeadline, purge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.tag(DS_LOG_TAG).w(e) { "$label: retention timer failed, restarting" }
            delay(RETRY_DELAY_MILLIS)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class) // transformLatest: stable in behaviour, experimental only by annotation
private suspend fun sleepAndPurge(clock: RetentionClock, nextDeadline: Flow<Long?>, purge: suspend () -> Unit) {
    nextDeadline
        .transformLatest { deadline ->
            if (deadline == null) return@transformLatest
            while (true) {
                val wait = deadline - clock.now()
                if (wait <= 0) break
                delay(wait.coerceAtMost(MAX_SLEEP_MILLIS))
            }
            emit(Unit)
        }
        .collect { purge() }
}
