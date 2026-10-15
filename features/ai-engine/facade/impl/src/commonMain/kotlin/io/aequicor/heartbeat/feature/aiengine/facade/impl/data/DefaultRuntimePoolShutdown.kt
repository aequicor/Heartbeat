package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePoolShutdown
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One app-scoped owner is shared by profile registration and the desktop's native shutdown participants. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<RuntimePoolShutdown>())
@ContributesIntoSet(AppScope::class, binding = binding<AppShutdownParticipant>())
@Inject
internal class DefaultRuntimePoolShutdown :
    RuntimePoolShutdown,
    AppShutdownParticipant {
    private val log = Log.tag("RuntimePoolShutdown")
    private val state = MutableStateFlow(State())

    override fun register(close: suspend () -> Unit): suspend () -> Unit {
        val cleanup = Cleanup(close)
        while (true) {
            val current = state.value
            if (current.isClosing) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
            if (state.compareAndSet(current, current.copy(cleanups = current.cleanups + cleanup))) {
                log.v { "Retained runtime pool cleanup count=${current.cleanups.size + 1}" }
                return cleanup::close
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun shutdown() {
        val cleanups = state.updateAndGet { it.copy(isClosing = true) }.cleanups
        log.i { "Awaiting runtime pool cleanup count=${cleanups.size}" }
        val failures = supervisorScope {
            val jobs = cleanups.map { cleanup -> async { cleanup.close() } }
            jobs.forEach { it.join() }
            jobs.mapNotNull { it.getCompletionExceptionOrNull() }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    /** Serializes the profile close callback with app shutdown; a failed close stays registered for retry. */
    private inner class Cleanup(private val action: suspend () -> Unit) {
        private val mutex = Mutex()
        private var isClosed = false

        suspend fun close() = mutex.withLock {
            if (!isClosed) {
                action()
                isClosed = true
                state.update { it.copy(cleanups = it.cleanups - this) }
                log.v { "Released completed runtime pool cleanup" }
            }
        }
    }

    private data class State(val isClosing: Boolean = false, val cleanups: Set<Cleanup> = emptySet())
}
