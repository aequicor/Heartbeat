package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.impl.di.HarnessSpawnStartup
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessSpawnOperationsTest {
    @Test
    fun `returning a helper retains its lease until exact terminal result and confirmed release`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val result = fixture.operations.spawn(fixture.submission())
        assertEquals(fixture.helpers.helper, result.helper)
        assertEquals(listOf("acquire", "grant", "create", "checkpoint", "binding", "prompt"), fixture.trace)
        assertEquals(0, fixture.helpers.releases)
        assertFalse(fixture.operations.isQuiescent())
        fixture.helpers.result = HelperResult(fixture.record.request, HelperOutcome.Completed, "private answer")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf("release", "settle"), fixture.trace.takeLast(2))
        assertTrue(fixture.operations.isQuiescent())
    }

    @Test
    fun `cancelling script wait leaves admitted profile producer and journal ownership intact`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        fixture.helpers.beforeCreate = { gate.await() }
        val waiting = async { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        waiting.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.helpers.prompts.size)
        assertEquals(0, fixture.helpers.releases)
        assertFalse(fixture.operations.isQuiescent())
        assertFalse(fixture.operations.retire(fixture.record.owner))
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
    }

    @Test
    fun `revocation waits for ambiguous grant write and never creates after cancellation`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val write = CompletableDeferred<Unit>()
        fixture.journal.beforeGrant = { write.await() }
        val waiting = async { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        assertFalse(fixture.operations.retire(fixture.record.owner))
        runCurrent()
        assertEquals(0, fixture.helpers.releases)
        assertTrue(fixture.journal.records.isEmpty())
        write.complete(Unit)
        runCurrent()
        assertFailsWith<CancellationException> { waiting.await() }
        assertEquals(listOf("acquire", "grant", "release", "settle"), fixture.trace)
        assertTrue(fixture.operations.isQuiescent())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `lost grant acknowledgement and rejected admission release without prompt`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.afterGrant = { error("private journal failure") }
        assertFailsWith<IllegalStateException> { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        assertEquals(listOf("acquire", "grant", "release", "settle"), fixture.trace)
        assertTrue(fixture.operations.isQuiescent())
    }

    @Test
    fun `revocation during helper checkpoint joins write before release and prevents binding prompt`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val write = CompletableDeferred<Unit>()
        fixture.journal.beforeBind = { write.await() }
        val waiting = async { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        fixture.operations.retire(fixture.record.owner)
        runCurrent()
        assertEquals(0, fixture.helpers.releases)
        write.complete(Unit)
        runCurrent()
        assertFailsWith<CancellationException> { waiting.await() }
        assertTrue(fixture.helpers.prompts.isEmpty())
        assertEquals(listOf("checkpoint", "binding", "release", "settle"), fixture.trace.takeLast(4))
        assertTrue(fixture.operations.isQuiescent())
    }

    @Test
    fun `blocked result read cannot delay revocation native barrier or free unconfirmed slot`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val read = CompletableDeferred<Unit>()
        fixture.helpers.beforeResult = { withContext(NonCancellable) { read.await() } }
        fixture.helpers.release = { HelperReleaseResult.Unconfirmed }
        fixture.operations.spawn(fixture.submission())
        runCurrent()
        fixture.operations.retire(fixture.record.owner)
        runCurrent()
        assertEquals(1, fixture.helpers.releases)
        assertFalse(fixture.operations.isQuiescent())
        assertEquals(1, fixture.journal.records.size)
        fixture.helpers.release = { HelperReleaseResult.Released }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, fixture.helpers.releases)
        assertTrue(fixture.operations.isQuiescent())
        read.complete(Unit)
        runCurrent()
        assertEquals(2, fixture.helpers.releases)
    }

    @Test
    fun `generation fence rejects older admission and never cancels newer instance`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        assertTrue(fixture.operations.retire(fixture.record.owner))
        assertFailsWith<IllegalStateException> { fixture.operations.spawn(fixture.submission()) }
        val newer = fixture.record.copy(owner = fixture.record.owner.copy(generation = 8))
        fixture.operations.spawn(fixture.submission(newer))
        assertTrue(fixture.operations.retire(fixture.record.owner))
        runCurrent()
        assertEquals(0, fixture.helpers.releases)
        assertFalse(fixture.operations.isQuiescent())
    }

    @Test
    fun `recovery restores exact slots and only cleans up without replaying callbacks or prompts`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val bound = fixture.record.copy(
            reservation = ActionId("bound"),
            action = ActionId("other"),
            helper = fixture.helpers.helper,
        )
        fixture.journal.records[fixture.record.reservation] = fixture.record
        fixture.journal.records[bound.reservation] = bound
        fixture.helpers.release = { HelperReleaseResult.Unconfirmed }
        fixture.operations.initialize()
        runCurrent()
        assertEquals(2, fixture.helpers.acquisitions.size)
        assertEquals(setOf(null, fixture.helpers.helper), fixture.helpers.acquisitions.map { it.helper }.toSet())
        assertEquals(
            setOf(bound.reservation, fixture.record.reservation),
            fixture.helpers.acquisitions.map { it.slot }.toSet(),
        )
        assertEquals(0, fixture.helpers.creates)
        assertTrue(fixture.helpers.prompts.isEmpty())
        assertFalse(fixture.operations.isQuiescent())
        fixture.helpers.release = { HelperReleaseResult.Released }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
        assertEquals(2, fixture.helpers.acquisitions.size)
        assertEquals(1, fixture.journal.reads)
    }

    @Test
    fun `snapshot failure blocks new admission and retries without treating live grants as restored`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.beforeRead = { error("Storage unavailable") }
        assertFailsWith<IllegalStateException> { fixture.operations.spawn(fixture.submission()) }
        assertTrue(fixture.helpers.acquisitions.isEmpty())
        fixture.journal.beforeRead = {}
        fixture.operations.spawn(fixture.submission())
        fixture.operations.initialize()
        assertEquals(2, fixture.journal.reads)
        assertEquals(0, fixture.helpers.releases)
    }

    @Test
    fun `lost settle acknowledgement retries the same released lease without acquiring another slot`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.records[fixture.record.reservation] = fixture.record
        fixture.journal.beforeSettle = { error("Storage unavailable") }
        fixture.operations.initialize()
        runCurrent()
        assertFalse(fixture.operations.isQuiescent())
        assertEquals(1, fixture.helpers.acquisitions.size)
        fixture.journal.beforeSettle = {}
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
        assertEquals(1, fixture.helpers.acquisitions.size)
    }

    @Test
    fun `retirement cancels queued acquisition without persisting a grant or releasing nonexistent lease`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.helpers.beforeAcquire = { awaitCancellation() }
        val waiting = async { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        fixture.operations.retire(fixture.record.owner)
        runCurrent()
        assertFailsWith<CancellationException> { waiting.await() }
        assertTrue(fixture.operations.isQuiescent())
        assertTrue(fixture.journal.records.isEmpty())
        assertEquals(0, fixture.helpers.releases)
    }

    @Test
    fun `dependency self cancellation leaves cleanup retryable while profile remains open`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.records[fixture.record.reservation] = fixture.record
        fixture.helpers.beforeAcquire = { throw CancellationException("metadata interrupted") }
        fixture.operations.initialize()
        runCurrent()
        assertFalse(fixture.operations.isQuiescent())
        fixture.helpers.beforeAcquire = {}
        fixture.helpers.release = { throw CancellationException("barrier interrupted") }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, fixture.helpers.acquisitions.size)
        assertFalse(fixture.operations.isQuiescent())
        fixture.helpers.release = { HelperReleaseResult.Released }
        fixture.journal.beforeSettle = { throw CancellationException("settle interrupted") }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertFalse(fixture.operations.isQuiescent())
        fixture.journal.beforeSettle = {}
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
        assertEquals(1, fixture.helpers.acquisitions.size)
    }

    @Test
    fun `off startup retries cancelled snapshot without a script or feature toggle`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.records[fixture.record.reservation] = fixture.record
        fixture.journal.beforeRead = { throw CancellationException("read interrupted") }
        val profile = object : ScopeHandle {
            override val name = "test"
            override val coroutineScope = backgroundScope
            override val isClosed = false
            override val savedState: ScopeSavedState get() = error("Not used")
            override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
        }
        HarnessSpawnStartup(lazyOf(fixture.operations), profile).start()
        runCurrent()
        assertEquals(1, fixture.journal.reads)
        assertTrue(fixture.helpers.acquisitions.isEmpty())
        fixture.journal.beforeRead = {}
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
        assertEquals(2, fixture.journal.reads)
        assertEquals(1, fixture.helpers.releases)
        assertTrue(fixture.helpers.prompts.isEmpty())
    }

    @Test
    fun `revocation of uncertain prompt uses one detached barrier despite repeated cleanup requests`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        val barrier = CompletableDeferred<Unit>()
        fixture.helpers.beforePrompt = { awaitCancellation() }
        fixture.helpers.release = {
            barrier.await()
            HelperReleaseResult.Unconfirmed
        }
        val waiting = async { fixture.operations.spawn(fixture.submission()) }
        runCurrent()
        assertFalse(fixture.operations.retire(fixture.record.owner))
        runCurrent()
        assertFailsWith<CancellationException> { waiting.await() }
        repeat(3) { assertFalse(fixture.operations.retire(fixture.record.owner)) }
        runCurrent()
        assertEquals(1, fixture.helpers.releases)
        assertEquals(1, fixture.journal.records.size)
        barrier.complete(Unit)
        runCurrent()
        assertFalse(fixture.operations.isQuiescent())
        fixture.helpers.release = { HelperReleaseResult.Released }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(fixture.operations.isQuiescent())
        assertEquals(2, fixture.helpers.releases)
        assertEquals(1, fixture.helpers.acquisitions.size)
    }

    @Test
    fun `restored prior profile generation never hides unresolved cleanup from a new lower fence`() = runTest {
        val fixture = HarnessSpawnFixture(backgroundScope)
        fixture.journal.records[fixture.record.reservation] = fixture.record
        fixture.helpers.release = { HelperReleaseResult.Unconfirmed }
        fixture.operations.initialize()
        runCurrent()
        val current = fixture.record.owner.copy(generation = 1)
        assertFalse(fixture.operations.retire(current))
        assertFalse(
            fixture.operations.deactivate(
                HarnessEffect.Deactivate(emptyList(), true, setOf(current.harness), generation = 1),
            ),
        )
        assertFalse(fixture.operations.isQuiescent(current.harness))
    }
}
