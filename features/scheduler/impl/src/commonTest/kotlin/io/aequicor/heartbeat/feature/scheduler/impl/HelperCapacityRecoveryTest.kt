package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityRejection
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoveryRecord
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HelperCapacityRecoveryTest {
    private val owner = ActionId("workflow")
    private val reservation = ActionId("stable")
    private val helper = HelperId("persisted")
    private val request = RequestId("original")
    private val record = HelperCapacityRecoveryRecord(reservation, owner, null, helper)

    @Test
    fun `restored helpers occupy every slot before new scheduled admission and adopt without queue`() = runTest {
        val records = (0..8).map {
            HelperCapacityRecoveryRecord(ActionId("slot$it"), ActionId("run${it / 4}"), null, HelperId("h$it"))
        }
        val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { records }))
        records.forEach { item ->
            val id = checkNotNull(item.helper)
            fixture.host.metadata[id] = HelperMetadata(id, item.owner, null, null, request)
        }
        assertEquals(
            BackgroundCapacityRejection.ProfileLimit,
            fixture.actions.capacity.tryAcquireScheduled(ActionId("new"), SESSION),
        )
        assertEquals(9, fixture.active)
        val first = records.first()
        val lease = fixture.service.acquire(first.owner, existing = first.helper, reservation = first.reservation)
        assertEquals(9, fixture.active)
        assertTrue((fixture.actions.capacityMachine.state.value as BackgroundCapacityState.Ready).queued.isEmpty())
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(8, fixture.active)
        assertEquals(listOf(request), fixture.host.cancellations)
    }

    @Test
    fun `source failure refuses all new admission and retry reads evidence again`() = runTest {
        var isFailing = true
        var reads = 0
        val source = HelperCapacityRecoverySource {
            reads++
            check(!isFailing) { "Storage unavailable" }
            listOf(record.copy(helper = null))
        }
        val fixture = HelpersFixture(this, recovery = setOf(source))
        assertFailsWith<IllegalStateException> {
            fixture.actions.capacity.tryAcquireScheduled(ActionId("new"), SESSION)
        }
        assertEquals(0, fixture.active)
        isFailing = false
        assertNull(fixture.actions.capacity.tryAcquireScheduled(ActionId("new"), SESSION))
        assertEquals(2, fixture.active)
        assertEquals(2, reads)
    }

    @Test
    fun `conflicting restored reservation or helper identities cannot partially restore`() = runTest {
        for (second in listOf(record.copy(owner = ActionId("foreign")), record.copy(reservation = ActionId("other")))) {
            val fixture = HelpersFixture(
                this,
                recovery = setOf(HelperCapacityRecoverySource { listOf(record, second) }),
            )
            assertFailsWith<IllegalStateException> { fixture.actions.capacity.restore() }
            assertEquals(0, fixture.active)
        }
    }

    @Test
    fun `metadata reread failure returns claim but never a restored running slot`() = runTest {
        verifyMetadataFailure(cancel = false)
    }

    @Test
    fun `metadata reread cancellation returns claim but never a restored running slot`() = runTest {
        verifyMetadataFailure(cancel = true)
    }

    private suspend fun TestScope.verifyMetadataFailure(cancel: Boolean) {
        val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { listOf(record) }))
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, request)
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        fixture.host.beforeMetadata = {
            reads++
            if (reads == 2) {
                entered.complete(Unit)
                if (cancel) awaitCancellation() else error("Read failed")
            }
        }
        val waiting = async {
            assertFailsWith<Exception> {
                fixture.service.acquire(owner, existing = helper, reservation = reservation)
            }
        }
        entered.await()
        if (cancel) waiting.cancelAndJoin() else waiting.await()
        assertEquals(1, fixture.active)
        assertTrue(fixture.host.cancellations.isEmpty())
        fixture.host.beforeMetadata = {}
        val lease = fixture.service.acquire(owner, existing = helper, reservation = reservation)
        fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertEquals(1, fixture.active)
    }

    @Test
    fun `only exact source proof grants cleanup lease without host lookup and it cannot create`() = runTest {
        val fixture = HelpersFixture(
            this,
            recovery = setOf(HelperCapacityRecoverySource { listOf(record.copy(helper = null)) }),
        )
        fixture.host.beforeMetadata = { error("No host lookup for pre-prompt proof") }
        fixture.host.beforeCreate = { error("Must not create") }
        val lease = fixture.service.acquire(owner, reservation = reservation)
        assertEquals(1, fixture.active)
        assertFailsWith<IllegalStateException> { fixture.service.create(lease, null, TARGET, "Forbidden") }
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, HelperPrompt(request, "Forbidden")) }
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, reservation = reservation) }
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(0, fixture.active)
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, reservation = reservation) }
        assertTrue(fixture.host.prompts.isEmpty())
        assertTrue(fixture.host.cancellations.isEmpty())
    }

    @Test
    fun `caller cannot erase source helper identity or change owner and parent`() = runTest {
        val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { listOf(record) }))
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, reservation = reservation) }
        assertFailsWith<IllegalStateException> {
            fixture.service.acquire(ActionId("foreign"), existing = helper, reservation = reservation)
        }
        assertFailsWith<IllegalStateException> {
            fixture.service.acquire(owner, SESSION, helper, reservation)
        }
        assertEquals(1, fixture.active)
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, request)
        val lease = fixture.service.acquire(owner, existing = helper, reservation = reservation)
        assertEquals(HelperReleaseResult.Released, lease.release())
    }

    @Test
    fun `one owner can recover distinct acquisitions and crash before settled repeats empty cleanup safely`() =
        runTest {
            val records = (0..3).map { record.copy(reservation = ActionId("slot$it"), helper = null) }
            repeat(2) {
                val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { records }))
                val leases = records.map { fixture.service.acquire(owner, reservation = it.reservation) }
                assertEquals(4, fixture.active)
                leases.first().release()
                assertEquals(3, fixture.active)
                leases.drop(1).forEach { assertEquals(HelperReleaseResult.Released, it.release()) }
                assertEquals(0, fixture.active)
            }
        }

    @Test
    fun `cancelled handoff retains closing lease for exact retry after unconfirmed cleanup`() = runTest {
        val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { listOf(record) }))
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, request)
        fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
        var reads = 0
        fixture.host.beforeMetadata = {
            if (++reads == 2) currentCoroutineContext().cancel()
        }
        val waiting = async { fixture.service.acquire(owner, existing = helper, reservation = reservation) }
        assertFailsWith<CancellationException> { waiting.await() }
        runCurrent()
        assertEquals(listOf(request), fixture.host.cancellations)
        assertEquals(1, fixture.active)
        fixture.host.beforeMetadata = {}
        val closing = fixture.service.acquire(owner, existing = helper, reservation = reservation)
        assertFailsWith<IllegalStateException> { fixture.service.create(closing, null, TARGET, "Forbidden") }
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, HelperPrompt(request, "Forbidden")) }
        assertEquals(HelperReleaseResult.Unconfirmed, closing.release())
        assertEquals(1, fixture.active)
        fixture.host.cancellation = { HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, "")) }
        assertEquals(HelperReleaseResult.Released, closing.release())
        assertEquals(0, fixture.active)
        assertSame(closing, fixture.service.acquire(owner, existing = helper, reservation = reservation))
        assertEquals(HelperReleaseResult.Released, closing.release())
    }

    @Test
    fun `known recovered helper cannot allocate a different slot when stable identity is omitted`() = runTest {
        val fixture = HelpersFixture(this, recovery = setOf(HelperCapacityRecoverySource { listOf(record) }))
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, request)
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, existing = helper) }
        assertFailsWith<IllegalStateException> {
            fixture.service.acquire(owner, existing = helper, reservation = ActionId("wrong"))
        }
        assertEquals(1, fixture.active)
        assertTrue((fixture.actions.capacityMachine.state.value as BackgroundCapacityState.Ready).queued.isEmpty())
    }

    @Test
    fun `new journal id follows normal capacity admission and retains the supplied identity`() = runTest {
        val fixture = HelpersFixture(this)
        val lease = fixture.service.acquire(owner, reservation = reservation)
        val state = fixture.actions.capacityMachine.state.value as BackgroundCapacityState.Ready
        assertEquals(listOf(reservation), state.active.map { it.id })
        assertEquals(HelperReleaseResult.Released, lease.release())
    }
}
