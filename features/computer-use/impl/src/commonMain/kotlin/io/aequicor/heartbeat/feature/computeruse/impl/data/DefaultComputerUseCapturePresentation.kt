package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes native presentation exclusion without duplicating the feature's capture/session flow. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class DefaultComputerUseCapturePresentation(private val dispatchers: DispatcherProvider) :
    ComputerUseCapturePresentation {
    private val log = Log.tag("ComputerUseCapturePresentation")
    private val operations = Mutex()
    private val presentations = mutableMapOf<Any, ComputerUsePresentation>()
    private val restores = mutableListOf<AutoCloseable>()
    private var isSuppressed = false

    override fun register(presentation: ComputerUsePresentation): AutoCloseable {
        val restore = if (isSuppressed) presentation.suppress() else null
        val registration = Any()
        presentations[registration] = presentation
        if (restore != null) restores += restore
        log.d { "native computer-use presentation registered" }
        return AutoCloseable {
            presentations.remove(registration)
            log.d { "native computer-use presentation unregistered" }
        }
    }

    override suspend fun <T> withoutPresentation(action: suspend () -> T): T = operations.withLock {
        try {
            withContext(NonCancellable + dispatchers.main) { suppressPresentations() }
            currentCoroutineContext().ensureActive()
            action()
        } finally {
            withContext(NonCancellable) { restoreOnMain() }
        }
    }

    private suspend fun restoreOnMain() = withContext(dispatchers.main) { restorePresentations() }

    private fun suppressPresentations() {
        isSuppressed = true
        var isAcquired = false
        try {
            presentations.values.toList().forEach { restores += it.suppress() }
            isAcquired = true
            log.d { "native computer-use presentation excluded" }
        } finally {
            if (!isAcquired) restorePresentations()
        }
    }

    private fun restorePresentations() {
        val leases = restores.toList()
        restores.clear()
        isSuppressed = false
        val failures = leases.asReversed().mapNotNull { restore ->
            runCatching { restore.close() }.exceptionOrNull()?.also { failure ->
                if (failure !is CancellationException) {
                    log.w(failure) { "native computer-use presentation restoration failed" }
                }
            }
        }
        log.d { "native computer-use presentation restored" }
        val cancellation = failures.filterIsInstance<CancellationException>().firstOrNull()
        (cancellation ?: failures.firstOrNull())?.let { throw it }
    }
}
