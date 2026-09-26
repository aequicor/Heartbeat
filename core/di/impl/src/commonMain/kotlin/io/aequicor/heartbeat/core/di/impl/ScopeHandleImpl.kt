package io.aequicor.heartbeat.core.di.impl

import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Scope with a [SupervisorJob] linked to the parent job and synchronous, ordered close:
 * mark closed → cancel coroutines (children's too, via the job tree) → run close actions LIFO.
 * Child scopes are closed through a close action registered on the parent by [ScopeFactoryImpl],
 * so a child is always closed before the resources its parent registered earlier.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ScopeHandleImpl(
    override val name: String,
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
    override val savedState: ScopeSavedStateImpl,
) : OwnedScope {

    private val log = Log.tag(LOG_TAG)
    private val job = SupervisorJob(parentJob)

    /** Registered close actions; `null` once closed. */
    private val actions = AtomicReference<List<CloseAction>?>(emptyList())

    override val coroutineScope: CoroutineScope = CoroutineScope(
        job + dispatcher + CoroutineName(name) +
            CoroutineExceptionHandler { _, error -> log.e(error) { "uncaught failure in scope $name" } },
    )

    override val isClosed: Boolean get() = actions.load() == null

    override fun onClose(action: () -> Unit): DisposableHandle {
        val entry = CloseAction(action)
        while (true) {
            val current = actions.load()
            if (current == null) {
                runSafely(entry)
                return NoopHandle
            }
            if (actions.compareAndSet(current, current + entry)) return DisposableHandle { remove(entry) }
        }
    }

    override fun close() {
        val toRun = actions.exchange(null) ?: return
        job.cancel(CancellationException("scope $name closed"))
        try {
            runAll(toRun)
        } finally {
            log.i { "scope $name closed" }
        }
    }

    /**
     * Runs [actions] last-to-first. `finally` guarantees the rest still run if one throws a
     * CancellationException (which [runSafely] propagates); the exception surfaces after all of them.
     */
    private fun runAll(actions: List<CloseAction>) {
        if (actions.isEmpty()) return
        try {
            runSafely(actions.last())
        } finally {
            runAll(actions.dropLast(1))
        }
    }

    private fun remove(entry: CloseAction) {
        while (true) {
            val current = actions.load() ?: return
            if (actions.compareAndSet(current, current - entry)) return
        }
    }

    private fun runSafely(entry: CloseAction) {
        try {
            entry.action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "close action failed in scope $name" }
        }
    }

    /** Identity wrapper: the same lambda may be registered twice and disposed independently. */
    @Suppress("UseDataClass") // identity equality is the point: removal must not match an equal twin
    private class CloseAction(val action: () -> Unit)

    private object NoopHandle : DisposableHandle {
        override fun dispose() = Unit
    }

    companion object {
        const val LOG_TAG = "DI"
    }
}
