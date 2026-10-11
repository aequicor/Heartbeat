package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.di.OwnedScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Completes scope cancellation and physical Room close before tests reset Main or delete storage files. */
internal suspend fun TestAppGraph.closeAndAwaitStorages() = withContext(NonCancellable) {
    (appScope as OwnedScope).close()
    checkNotNull(appScope.coroutineScope.coroutineContext[Job]).join()
    storageMaintenance.awaitClosed()
}
