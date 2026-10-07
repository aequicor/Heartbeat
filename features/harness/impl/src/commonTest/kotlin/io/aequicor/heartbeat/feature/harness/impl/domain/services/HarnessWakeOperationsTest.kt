package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessWakeOperationsTest {
    @Test
    fun `failed ancestry write returns capacity before scheduler is called`() = runTest {
        val port = WakePortFixture()
        val quota = HarnessWakeQuotas()
        val storage = MemoryHarnessRequestAncestry().apply { failure = IllegalStateException("IO failed") }
        val operations = HarnessWakeOperations(backgroundScope, port, quota, HarnessRequestOrigins(storage))
        repeat(3) { index ->
            assertFailsWith<IllegalStateException> {
                operations.schedule(
                    HarnessWakeSubmission(OWNER, wake("failed$index"), HarnessCallOrigin(true), AT, false),
                ) { true }
            }
        }
        quota.reserve(HarnessWakeReservation(WakeId("free1"), OWNER, dispatchSession, AT, false), port::snapshot)
        quota.reserve(HarnessWakeReservation(WakeId("free2"), OWNER, dispatchSession, AT, false), port::snapshot)
        assertEquals(0, port.calls)
    }

    @Test
    fun `cancelled ancestry write returns reservation with cancelled profile job`() = runTest {
        val port = WakePortFixture()
        val quota = HarnessWakeQuotas()
        val entered = CompletableDeferred<Unit>()
        val storage = MemoryHarnessRequestAncestry().apply {
            beforeRestrict = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = Job()
        val owner = CoroutineScope(coroutineContext + job)
        val operations = HarnessWakeOperations(owner, port, quota, HarnessRequestOrigins(storage))
        val caller = async {
            assertFailsWith<CancellationException> {
                operations.schedule(
                    HarnessWakeSubmission(OWNER, wake("cancelled-write"), HarnessCallOrigin(true), AT, false),
                ) { true }
            }
        }
        entered.await()
        job.cancelAndJoin()
        caller.await()
        quota.reserve(HarnessWakeReservation(WakeId("free1"), OWNER, dispatchSession, AT, false), port::snapshot)
        quota.reserve(HarnessWakeReservation(WakeId("free2"), OWNER, dispatchSession, AT, false), port::snapshot)
        assertEquals(0, port.calls)
    }

    @Test
    fun `cancelled profile rejects a live caller without submitting or hanging`() = runTest {
        val port = WakePortFixture()
        val job = Job().also { it.cancel() }
        val owner = CoroutineScope(coroutineContext + job)
        val operations = HarnessWakeOperations(
            owner,
            port,
            HarnessWakeQuotas(),
            HarnessRequestOrigins(MemoryHarnessRequestAncestry()),
        )
        assertFailsWith<CancellationException> {
            operations.schedule(
                HarnessWakeSubmission(OWNER, wake("closed-profile"), HarnessCallOrigin(), AT, false),
            ) { true }
        }
        assertEquals(0, port.calls)
    }

    @Test
    fun `cancelled waiter leaves owned submission and reservation alive until receipt`() = runTest {
        val port = WakePortFixture()
        val origins = HarnessRequestOrigins(MemoryHarnessRequestAncestry())
        val quota = HarnessWakeQuotas()
        val operations = HarnessWakeOperations(backgroundScope, port, quota, origins)
        val request = wake("first")
        val origin = HarnessCallOrigin(sendChain = mapOf(OWNER to 2))
        val waiter = async { operations.schedule(HarnessWakeSubmission(OWNER, request, origin, AT, true)) { true } }
        runCurrent()
        val context = SessionHookContext(
            dispatchSession,
            null,
            request.id.deliveryRequestId(),
            null,
            SessionOwner("host"),
        )
        assertEquals(origin, origins.origin(context))
        waiter.cancel()
        runCurrent()
        assertTrue(port.isWaiting)
        quota.reserve(HarnessWakeReservation(WakeId("second"), OWNER, dispatchSession, AT, false), port::snapshot)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), OWNER, dispatchSession, AT, false), port::snapshot)
        }
        port.receipt.complete(HarnessWakeReceipt.Scheduled)
        runCurrent()
        assertEquals(listOf(request), port.state.wakes.map { it.request })
        assertEquals(1, port.calls)
    }

    @Test
    fun `unknown receipt retains capacity while definitive rejection returns it`() = runTest {
        val port = WakePortFixture()
        val quota = HarnessWakeQuotas()
        val operations = HarnessWakeOperations(
            backgroundScope,
            port,
            quota,
            HarnessRequestOrigins(MemoryHarnessRequestAncestry()),
        )
        port.receipt.complete(HarnessWakeReceipt.Unknown)
        assertFailsWith<IllegalStateException> {
            operations.schedule(HarnessWakeSubmission(OWNER, wake("unknown"), HarnessCallOrigin(), AT, false)) { true }
        }
        port.receipt = CompletableDeferred<HarnessWakeReceipt>().also { it.complete(HarnessWakeReceipt.Rejected) }
        repeat(3) { index ->
            assertFailsWith<IllegalStateException> {
                operations.schedule(
                    HarnessWakeSubmission(OWNER, wake("rejected$index"), HarnessCallOrigin(), AT, false),
                ) { true }
            }
        }
        quota.reserve(HarnessWakeReservation(WakeId("second"), OWNER, dispatchSession, AT, false), port::snapshot)
        assertFailsWith<IllegalStateException> {
            quota.reserve(HarnessWakeReservation(WakeId("third"), OWNER, dispatchSession, AT, false), port::snapshot)
        }
        assertEquals(4, port.calls)
    }

    @Test
    fun `revoked admission never reaches scheduler`() = runTest {
        val port = WakePortFixture()
        val operations = HarnessWakeOperations(
            backgroundScope,
            port,
            HarnessWakeQuotas(),
            HarnessRequestOrigins(MemoryHarnessRequestAncestry()),
        )
        var isAdmitted = true
        val waiter = async {
            assertFailsWith<IllegalStateException> {
                operations.schedule(
                    HarnessWakeSubmission(OWNER, wake("revoked"), HarnessCallOrigin(), AT, false),
                ) { isAdmitted }
            }
        }
        isAdmitted = false
        waiter.await()
        assertEquals(0, port.calls)
    }
}

private class WakePortFixture : HarnessWakePort {
    var state = SchedulerState.Ready()
    var receipt = CompletableDeferred<HarnessWakeReceipt>()
    var calls = 0
    var isWaiting = false
    override fun snapshot(): SchedulerState.Ready = state
    override suspend fun schedule(request: WakeRequest, at: Instant): HarnessWakeReceipt {
        calls++
        isWaiting = true
        val result = receipt.await()
        if (result == HarnessWakeReceipt.Scheduled) state = state.copy(wakes = state.wakes + ScheduledWake(request, at))
        isWaiting = false
        return result
    }
    override suspend fun cancel(id: WakeId, cause: EventOrigin?): Boolean = false
}

private fun wake(id: String): WakeRequest = WakeRequest(
    WakeId(id),
    dispatchSession,
    null,
    WakeCondition(deadline = AT),
    "private note",
    WakeOrigin.Feature(HARNESS_WAKE_OWNER),
    ownerFeature = HARNESS_WAKE_OWNER,
)

private val AT = Instant.fromEpochMilliseconds(1_000)
private val OWNER = HarnessId("owner")
