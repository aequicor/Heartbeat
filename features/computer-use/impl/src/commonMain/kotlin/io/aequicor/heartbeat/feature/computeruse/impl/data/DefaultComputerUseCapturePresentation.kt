package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes native presentation exclusion without duplicating the feature's capture/session flow.
 * Every field is confined to [DispatcherProvider.main]: registration happens there, and each operation switches
 * there to suppress and restore.
 */
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
    private var suppressionReason = ComputerUseSuppressionReason.CapturePixels

    override fun register(presentation: ComputerUsePresentation): AutoCloseable {
        val registration = Any()
        presentations[registration] = presentation
        log.d { "native computer-use presentation registered" }
        if (isSuppressed) suppressLate(registration, presentation)
        return AutoCloseable {
            presentations.remove(registration)
            log.d { "native computer-use presentation unregistered" }
        }
    }

    override suspend fun <T> withoutPresentation(action: suspend () -> T): T =
        withoutPresentation(ComputerUseSuppressionReason.CapturePixels, action)

    override suspend fun <T> withoutPresentation(reason: ComputerUseSuppressionReason, action: suspend () -> T): T =
        operations.withLock {
            var failure: Exception? = null
            var restoreFailure: Throwable? = null
            val result = try {
                withContext(NonCancellable + dispatchers.main) {
                    suppressionReason = reason
                    suppressPresentations()
                }
                currentCoroutineContext().ensureActive()
                action()
            } catch (e: CancellationException) {
                failure = e
                throw e
            } catch (e: Exception) {
                failure = e
                throw e
            } finally {
                restoreFailure = withContext(NonCancellable) { restoreOnMain() }
                // The operation's own failure stays primary and carries the restore failure.
                restoreFailure?.let { restore -> failure?.addSuppressed(restore) }
            }
            // A failed restore still fails a successful operation.
            restoreFailure?.let { throw it }
            result
        }

    /** Switches dispatchers inside the caller's NonCancellable block, so returning cannot skip restore handling. */
    private suspend fun restoreOnMain(): Throwable? = withContext(dispatchers.main) { restorePresentations() }

    /** A late window that cannot be hidden stays registered, so the next operation excludes it again. */
    private fun suppressLate(registration: Any, presentation: ComputerUsePresentation) {
        try {
            restores += presentation.suppress(suppressionReason)
        } catch (e: CancellationException) {
            // The caller gets no handle to close, so the registration must not outlive the failed call.
            presentations.remove(registration)
            throw e
        } catch (e: Exception) {
            log.w(e) { "native computer-use presentation registered during capture could not be excluded" }
        }
    }

    private fun suppressPresentations() {
        isSuppressed = true
        try {
            presentations.values.toList().forEach { restores += it.suppress(suppressionReason) }
        } catch (e: CancellationException) {
            rollBack(e)
            throw e
        } catch (e: Exception) {
            rollBack(e)
            throw e
        }
        log.d { "native computer-use presentation excluded count=${restores.size}" }
    }

    /** Restores the windows hidden before [failure]; their own restore failures are attached to it. */
    private fun rollBack(failure: Exception) {
        restorePresentations()?.let(failure::addSuppressed)
    }

    /** Drains every lease and returns the first failure, cancellation first, with the others attached to it. */
    private fun restorePresentations(): Throwable? {
        val leases = restores.toList()
        restores.clear()
        isSuppressed = false
        val failures = leases.asReversed().mapNotNull { restore ->
            runCatching { restore.close() }.exceptionOrNull()?.also { failure ->
                log.w(failure) { "native computer-use presentation restoration failed" }
            }
        }
        if (leases.isNotEmpty()) {
            log.d { "native computer-use presentation restored ${leases.size - failures.size}/${leases.size}" }
        }
        val primary = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull()
        if (primary != null) failures.filter { failure -> failure !== primary }.forEach(primary::addSuppressed)
        return primary
    }
}
