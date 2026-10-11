package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HarnessWakeQuotasTest {
    private var now = AT
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun `concurrent callers share two slots per session even before receipt`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        val results = (1..8).map { index ->
            async {
                try {
                    quota.reserve(
                        HarnessWakeReservation(WakeId("w$index"), HARNESS, dispatchSession, false),
                    ) { SchedulerState.Ready() }
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalStateException) {
                    Result.failure(error)
                }
            }
        }.awaitAll()
        assertEquals(2, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach {
            assertEquals("Harness session wake quota reached", it.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `restored delivering wakes and uncertain reservations consume capacity once`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        val first = ownedWake("first")
        var ready = SchedulerState.Ready(listOf(first), setOf(first.id))
        val second = ownedWake("second")
        quota.reserve(HarnessWakeReservation(second.id, HARNESS, dispatchSession, false)) { ready }
        ready = ready.copy(wakes = ready.wakes + second)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, false)) { ready }
        }
        quota.acknowledged(second.id)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, false)) { ready }
        }
        ready = ready.copy(wakes = listOf(second), delivering = emptySet())
        quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, false)) { ready }
    }

    @Test
    fun `rate applies across sessions and items but rejected attempt returns its debit`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        repeat(6) { index ->
            val id = WakeId("w$index")
            quota.reserve(
                HarnessWakeReservation(id, HARNESS, dispatchSession.copy(nativeId = "s$index"), true),
            ) { SchedulerState.Ready() }
            quota.acknowledged(id)
        }
        assertFailsWith<IllegalStateException> {
            quota.reserve(
                HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, true),
            ) { SchedulerState.Ready() }
        }
        quota.rejected(WakeId("w5"))
        quota.reserve(
            HarnessWakeReservation(WakeId("retry"), HARNESS, dispatchSession, true),
        ) { SchedulerState.Ready() }
        quota.acknowledged(WakeId("retry"))
        now += 1.minutes
        quota.reserve(
            HarnessWakeReservation(WakeId("later"), HARNESS, dispatchSession, true),
        ) { SchedulerState.Ready() }
    }

    @Test
    fun `unconfirmed sends never age out and receipt starts the rate window only once`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        repeat(6) { index ->
            quota.reserve(
                HarnessWakeReservation(WakeId("w$index"), HARNESS, dispatchSession.copy(nativeId = "s$index"), true),
            ) { SchedulerState.Ready() }
        }
        val next = HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, true)
        now += 2.minutes
        assertFailsWith<IllegalStateException> { quota.reserve(next) { SchedulerState.Ready() } }
        repeat(6) { quota.acknowledged(WakeId("w$it")) }
        assertFailsWith<IllegalStateException> { quota.reserve(next) { SchedulerState.Ready() } }
        now += 59.seconds
        assertFailsWith<IllegalStateException> { quota.reserve(next) { SchedulerState.Ready() } }
        repeat(6) { quota.acknowledged(WakeId("w$it")) }
        now += 1.seconds
        quota.reserve(next) { SchedulerState.Ready() }
    }

    @Test
    fun `uncertain reservations settle against the scheduler snapshot instead of leaking`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        val accepted = ownedWake("accepted")
        val lost = ownedWake("lost")
        for (wake in listOf(accepted, lost)) {
            quota.reserve(HarnessWakeReservation(wake.id, HARNESS, dispatchSession, true)) { SchedulerState.Ready() }
            quota.uncertain(wake.id)
        }
        val next = HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, false)
        // A durable id is counted once by the snapshot, but the lost one still holds the second session slot.
        assertFailsWith<IllegalStateException> { quota.reserve(next) { SchedulerState.Ready(listOf(accepted)) } }
        now += 1.minutes
        quota.reserve(next) { SchedulerState.Ready(listOf(accepted)) }
        quota.rejected(next.id)
        // The released attempt still holds its rate debit for one window from settlement, then ages out.
        repeat(HarnessLimits.SENDS_PER_MINUTE - 1) { index ->
            val id = WakeId("rate$index")
            quota.reserve(
                HarnessWakeReservation(id, HARNESS, dispatchSession.copy(nativeId = "r$index"), true),
            ) { SchedulerState.Ready() }
            quota.acknowledged(id)
        }
        val limited = HarnessWakeReservation(WakeId("limited"), HARNESS, dispatchSession, true)
        assertFailsWith<IllegalStateException> { quota.reserve(limited) { SchedulerState.Ready() } }
        now += 1.minutes
        quota.reserve(limited) { SchedulerState.Ready() }
    }

    @Test
    fun `sixteen profile slots include all harness owners but exclude other features`() = runTest {
        val quota = HarnessWakeQuotas(clock)
        val wakes = (1..16).map { index ->
            val wake = ownedWake("w$index")
            wake.copy(request = wake.request.copy(session = dispatchSession.copy(nativeId = "s$index")))
        }
        assertFailsWith<IllegalStateException> {
            quota.reserve(
                HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, false),
            ) { SchedulerState.Ready(wakes) }
        }
        val other = wakes.map { it.copy(request = it.request.copy(ownerFeature = "checklist")) }
        quota.reserve(
            HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, false),
        ) { SchedulerState.Ready(other) }
    }
}

private fun ownedWake(id: String): ScheduledWake = ScheduledWake(
    WakeRequest(
        WakeId(id),
        dispatchSession,
        null,
        WakeCondition(deadline = AT),
        "private",
        WakeOrigin.Feature(HARNESS_WAKE_OWNER),
        ownerFeature = HARNESS_WAKE_OWNER,
    ),
    AT,
)

private val AT = Instant.fromEpochMilliseconds(1_000)
private val HARNESS = HarnessId("owner")
