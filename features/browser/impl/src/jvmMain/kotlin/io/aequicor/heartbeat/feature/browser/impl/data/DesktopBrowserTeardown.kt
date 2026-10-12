package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Only bounds a caller's wait; the native teardown keeps ownership until its callbacks finish. */
internal suspend fun awaitBrowserTeardown(work: Deferred<Unit>, timeoutMillis: Long = 60_000L) {
    val isComplete = withTimeoutOrNull(timeoutMillis) {
        work.await()
        true
    } ?: false
    check(isComplete) { "Browser shutdown is still pending; retry after native callbacks finish" }
}
