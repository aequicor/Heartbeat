package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Profile-owned reservations bridge the gap before a scheduler receipt. Durable wakes, including delivering
 * ones, count together with uncertain reservations by exact id. Send rate is shared by all items of a harness.
 * The snapshot supplier is called inside the mutex; a snapshot taken before waiting could miss a concurrent ACK.
 */
internal class HarnessWakeQuotas {
    private val mutex = Mutex()
    private val reservations = mutableMapOf<WakeId, SessionRef>()
    private val sends = mutableMapOf<WakeId, HarnessSendStamp>()
    private val log = Log.tag("HarnessServices")

    @HighFrequency
    suspend fun reserve(reservation: HarnessWakeReservation, snapshot: () -> SchedulerState.Ready?) = mutex.withLock {
        log.v { "reserve harness wake capacity" }
        val id = reservation.id
        val harness = reservation.harness
        val session = reservation.session
        val at = reservation.at
        val isSend = reservation.isSend
        val ready = checkNotNull(snapshot()) { "Scheduler is not ready" }
        val pending = ready.wakes.filter { it.request.ownerFeature == HARNESS_WAKE_OWNER }
            .associate { it.id to it.session } + reservations
        check(id !in pending) { "Wake identity already reserved" }
        check(pending.size < HarnessLimits.WAKES_PER_PROFILE) { "Harness profile wake quota reached" }
        check(pending.values.count { it == session } < HarnessLimits.WAKES_PER_SESSION) {
            "Harness session wake quota reached"
        }
        sends.entries.removeAll { at - it.value.at >= 1.minutes }
        if (isSend) {
            check(sends.values.count { it.harness == harness } < HarnessLimits.SENDS_PER_MINUTE) {
                "Harness send rate quota reached"
            }
            sends[id] = HarnessSendStamp(harness, at)
        }
        reservations[id] = session
    }

    /** A positive scheduler receipt transfers capacity accounting to its durable state. */
    @HighFrequency
    suspend fun acknowledged(id: WakeId) = mutex.withLock {
        log.v { "transfer harness wake reservation" }
        reservations.remove(id)
        Unit
    }

    /** Only an authoritative refusal may return the send-rate debit as well as pending capacity. */
    @HighFrequency
    suspend fun rejected(id: WakeId) = mutex.withLock {
        log.v { "release rejected harness wake reservation" }
        reservations.remove(id)
        sends.remove(id)
        Unit
    }
}

internal const val HARNESS_WAKE_OWNER = "harness"

private data class HarnessSendStamp(val harness: HarnessId, val at: Instant) {
    override fun toString(): String = "HarnessSendStamp(***)"
}

/** Capacity reservation made before an immutable scheduling attempt begins. */
internal data class HarnessWakeReservation(
    val id: WakeId,
    val harness: HarnessId,
    val session: SessionRef,
    val at: Instant,
    val isSend: Boolean,
) {
    override fun toString(): String = "HarnessWakeReservation(***)"
}
