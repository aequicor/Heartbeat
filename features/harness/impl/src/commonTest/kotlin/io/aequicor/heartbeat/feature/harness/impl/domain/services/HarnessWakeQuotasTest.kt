package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HarnessWakeQuotasTest {
    @Test
    fun `concurrent callers share two slots per session even before receipt`() = runTest {
        val quota = HarnessWakeQuotas()
        val results = (1..8).map { index ->
            async {
                try {
                    quota.reserve(
                        HarnessWakeReservation(WakeId("w$index"), HARNESS, dispatchSession, AT, false),
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
        val quota = HarnessWakeQuotas()
        val first = ownedWake("first")
        var ready = SchedulerState.Ready(listOf(first), setOf(first.id))
        val second = ownedWake("second")
        quota.reserve(HarnessWakeReservation(second.id, HARNESS, dispatchSession, AT, false)) { ready }
        ready = ready.copy(wakes = ready.wakes + second)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, AT, false)) { ready }
        }
        quota.acknowledged(second.id)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, AT, false)) { ready }
        }
        ready = ready.copy(wakes = listOf(second), delivering = emptySet())
        quota.reserve(HarnessWakeReservation(WakeId("third"), HARNESS, dispatchSession, AT, false)) { ready }
    }

    @Test
    fun `rate applies across sessions and items but rejected attempt returns its debit`() = runTest {
        val quota = HarnessWakeQuotas()
        repeat(6) { index ->
            val id = WakeId("w$index")
            quota.reserve(
                HarnessWakeReservation(id, HARNESS, dispatchSession.copy(nativeId = "s$index"), AT, true),
            ) { SchedulerState.Ready() }
            quota.acknowledged(id)
        }
        assertFailsWith<IllegalStateException> {
            quota.reserve(
                HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, AT, true),
            ) { SchedulerState.Ready() }
        }
        quota.rejected(WakeId("w5"))
        quota.reserve(
            HarnessWakeReservation(WakeId("retry"), HARNESS, dispatchSession, AT, true),
        ) { SchedulerState.Ready() }
        quota.acknowledged(WakeId("retry"))
        quota.reserve(
            HarnessWakeReservation(WakeId("later"), HARNESS, dispatchSession, AT + 1.minutes, true),
        ) { SchedulerState.Ready() }
    }

    @Test
    fun `sixteen profile slots include all harness owners but exclude other features`() = runTest {
        val quota = HarnessWakeQuotas()
        val wakes = (1..16).map { index ->
            val wake = ownedWake("w$index")
            wake.copy(request = wake.request.copy(session = dispatchSession.copy(nativeId = "s$index")))
        }
        assertFailsWith<IllegalStateException> {
            quota.reserve(
                HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, AT, false),
            ) { SchedulerState.Ready(wakes) }
        }
        val other = wakes.map { it.copy(request = it.request.copy(ownerFeature = "checklist")) }
        quota.reserve(
            HarnessWakeReservation(WakeId("next"), HARNESS, dispatchSession, AT, false),
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
