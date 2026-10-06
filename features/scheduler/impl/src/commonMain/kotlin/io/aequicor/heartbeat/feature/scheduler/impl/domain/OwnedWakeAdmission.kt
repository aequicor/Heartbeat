package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** One attempt: every subscription refreshes current admission; any observed refusal stays terminal. */
internal class OwnedWakeAdmission(owner: ScheduledWakeOwner, private val request: WakeRequest) {
    private val log = Log.tag("OwnedWakeAdmission")
    private val revoked = MutableStateFlow<ScheduledWakeAdmission?>(null)
    var failure: WakeFailure = WakeFailure.OwnerRejected
        private set

    val decisions: Flow<ScheduledWakeAdmission> = flow {
        val previous = revoked.value
        if (previous != null) {
            emit(previous)
            return@flow
        }
        val source = owner.admission(request)
        val first = withTimeoutOrNull(INITIAL_TIMEOUT) { source.first() }
        if (first == null) {
            log.w { "Wake owner did not supply current admission" }
            failure = WakeFailure.OwnerUnavailable
        }
        emit(record(first ?: ScheduledWakeAdmission.Drop))
        emitAll(combine(source, revoked) { next, terminal -> terminal ?: record(next) })
    }.catch { error ->
        if (error is CancellationException) throw error
        log.w(error) { "Wake owner admission unavailable" }
        failure = WakeFailure.OwnerUnavailable
        emit(record(ScheduledWakeAdmission.Drop))
    }

    private fun record(decision: ScheduledWakeAdmission): ScheduledWakeAdmission {
        val next = if (decision == ScheduledWakeAdmission.Defer && request.condition.deadline != null) {
            // Defer relies on event replay. A past deadline would otherwise start an immediate retry loop.
            ScheduledWakeAdmission.Drop
        } else {
            decision
        }
        if (next != ScheduledWakeAdmission.Allow) {
            val isChanged = revoked.compareAndSet(null, next)
            log.v { "Wake admission revocation accepted=$isChanged" }
        }
        return revoked.value ?: next
    }

    private companion object {
        val INITIAL_TIMEOUT = 2.seconds
    }
}

internal class WakeOwnerUnavailableException : Exception("Wake owner is missing or ambiguous")
