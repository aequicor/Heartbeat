package io.aequicor.heartbeat.feature.harness.impl.domain.ownership

import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipReduction
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipRejection
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.reduceOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class OwnershipCoordinatorClientTest {
    private val source = ExecutorHostId("a".repeat(32))
    private val target = ExecutorHostId("b".repeat(32))
    private val key = OwnershipKey("c".repeat(64))
    private val transfer = "d".repeat(32)
    private val fence = OwnershipFence(key, source, 1)
    private val checkpoint = OwnershipCheckpoint("e".repeat(64), "manifest")

    @Test
    fun `two hosts concurrently create exactly one initial owner`() = runTest {
        val backend = FakeBackend()
        val sender = OwnershipCoordinatorClient(backend, source)
        val recipient = OwnershipCoordinatorClient(backend, target)
        backend.rendezvous()
        val first = async { sender.create(key) }
        val second = async { recipient.create(key) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is OwnershipChange.Current })
        assertEquals(1, results.count { it == OwnershipChange.Conflict })
        val stored = assertIs<OwnershipRead.Current>(sender.read(key)).record
        assertEquals(assertIs<OwnershipChange.Current>(results.first { it is OwnershipChange.Current }).record, stored)
    }

    @Test
    fun `claim racing cancellation has one winner and never two active hosts`() = runTest {
        val winners = mutableSetOf<ExecutorHostId>()
        for (isClaimFirst in listOf(false, true)) {
            val exported = exported()
            val backend = FakeBackend(exported)
            val sender = OwnershipCoordinatorClient(backend, source)
            val recipient = OwnershipCoordinatorClient(backend, target)
            backend.rendezvous()
            val cancel: suspend () -> OwnershipChange = {
                sender.apply(exported, OwnershipCommand.Cancel(source, fence, transfer))
            }
            val claim: suspend () -> OwnershipChange = {
                recipient.apply(exported, OwnershipCommand.Claim(target, fence, transfer))
            }
            val actions = if (isClaimFirst) listOf(claim, cancel) else listOf(cancel, claim)
            val results = actions.map { action -> async { action() } }.awaitAll()
            assertEquals(1, results.count { it is OwnershipChange.Current })
            assertEquals(1, results.count { it == OwnershipChange.Conflict })
            val stored = checkNotNull(assertIs<OwnershipRead.Current>(sender.read(key)).record)
            assertEquals(OwnershipPhase.Reconciling, stored.phase)
            assertEquals(2L, stored.fence.epoch)
            assertNotEquals(true, stored.accepts(fence))
            winners += stored.fence.host
        }
        assertEquals(setOf(source, target), winners)
    }

    @Test
    fun `lost claim response does not retry and a restarted client reads the committed epoch`() = runTest {
        val exported = exported()
        val backend = FakeBackend(exported)
        backend.loseNextResponse = true
        val command = OwnershipCommand.Claim(target, fence, transfer)
        val client = OwnershipCoordinatorClient(backend, target)
        assertEquals(OwnershipChange.Unavailable(OwnershipUnavailable.UnknownCommit), client.apply(exported, command))
        assertEquals(1, backend.commits)
        val restarted = OwnershipCoordinatorClient(backend, target)
        val stored = checkNotNull(assertIs<OwnershipRead.Current>(restarted.read(key)).record)
        assertEquals(2L, stored.fence.epoch)
        assertEquals(OwnershipPhase.Reconciling, stored.phase)
        assertEquals(OwnershipChange.Current(stored), restarted.apply(stored, command))
        assertEquals(1, backend.commits)
    }

    @Test
    fun `cancellation after commit propagates and restart reads the new epoch`() = runTest {
        val exported = exported()
        val backend = FakeBackend(exported)
        backend.cancelNextResponse = true
        val client = OwnershipCoordinatorClient(backend, target)
        assertFailsWith<CancellationException> {
            client.apply(exported, OwnershipCommand.Claim(target, fence, transfer))
        }
        val restarted = OwnershipCoordinatorClient(backend, target)
        val stored = checkNotNull(assertIs<OwnershipRead.Current>(restarted.read(key)).record)
        assertEquals(2L, stored.fence.epoch)
        assertEquals(OwnershipPhase.Reconciling, stored.phase)
        assertEquals(1, backend.commits)
    }

    @Test
    fun `an unchanged local replay checks the online state instead of trusting the cache`() = runTest {
        val initial = ProcessOwnership(fence)
        val prepare = OwnershipCommand.Prepare(source, fence, transfer, target)
        val prepared = assertIs<OwnershipReduction.Changed>(initial.reduceOwnership(prepare)).record
        val backend = FakeBackend(prepared)
        val client = OwnershipCoordinatorClient(backend, source)
        val cancelled = assertIs<OwnershipChange.Current>(
            client.apply(prepared, OwnershipCommand.Cancel(source, fence, transfer)),
        ).record
        assertEquals(OwnershipChange.Conflict, client.apply(prepared, prepare))
        assertEquals(cancelled, assertIs<OwnershipRead.Current>(client.read(key)).record)
        backend.isOffline = true
        assertEquals(OwnershipChange.Unavailable(OwnershipUnavailable.Transport), client.apply(prepared, prepare))
    }

    @Test
    fun `unsupported coordination and network loss never fall back to local ownership`() = runTest {
        val backend = FakeBackend()
        backend.isOffline = true
        val client = OwnershipCoordinatorClient(backend, source)
        assertEquals(OwnershipChange.Unavailable(OwnershipUnavailable.Transport), client.create(key))
        assertEquals(0, backend.commits)
        val unsupported = OwnershipCoordinatorClient(
            UnavailableOwnershipBackend(OwnershipUnavailable.AtomicCompareAndSetUnsupported),
            source,
        )
        assertEquals(
            OwnershipChange.Unavailable(OwnershipUnavailable.AtomicCompareAndSetUnsupported),
            unsupported.create(key),
        )
        assertIs<OwnershipRead.Unavailable>(unsupported.read(key))
        assertEquals(0, backend.commits)
    }

    @Test
    fun `client cannot impersonate another owner or recipient`() = runTest {
        val exported = exported()
        val backend = FakeBackend(exported)
        val client = OwnershipCoordinatorClient(backend, source)
        assertEquals(
            OwnershipChange.Rejected(OwnershipRejection.Actor),
            client.apply(exported, OwnershipCommand.Claim(target, fence, transfer)),
        )
        assertEquals(0, backend.commits)
    }

    @Test
    fun `a stale receipt revision cannot overwrite newer coordinator facts`() = runTest {
        val exported = exported()
        val backend = FakeBackend(exported.copy(revision = exported.revision + 1))
        val client = OwnershipCoordinatorClient(backend, target)
        assertEquals(OwnershipChange.Conflict, client.apply(exported, OwnershipCommand.Claim(target, fence, transfer)))
        assertEquals(exported.fence, assertIs<OwnershipRead.Current>(client.read(key)).record?.fence)
    }

    @Test
    fun `a backend returning another work key is unavailable`() = runTest {
        val backend = FakeBackend(ProcessOwnership(fence.copy(key = OwnershipKey("0".repeat(64)))))
        assertEquals(
            OwnershipRead.Unavailable(OwnershipUnavailable.InvalidRecord),
            OwnershipCoordinatorClient(backend, source).read(key),
        )
    }

    private fun exported(): ProcessOwnership {
        var record = ProcessOwnership(fence)
        for (command in listOf(
            OwnershipCommand.Prepare(source, fence, transfer, target),
            OwnershipCommand.Quiesce(source, fence, transfer),
            OwnershipCommand.Export(source, fence, transfer, checkpoint, 0, 0),
        )) {
            record = assertIs<OwnershipReduction.Changed>(record.reduceOwnership(command)).record
        }
        return record
    }

    private class FakeBackend(initial: ProcessOwnership? = null) : OwnershipBackend {
        private val gate = Mutex()
        private var record = initial
        private var beforeCommit: suspend () -> Unit = {}
        var loseNextResponse = false
        var cancelNextResponse = false
        var isOffline = false
        var commits = 0
            private set

        fun rendezvous() {
            val ready = CompletableDeferred<Unit>()
            var arrivals = 0
            beforeCommit = {
                arrivals++
                if (arrivals == 2) ready.complete(Unit)
                ready.await()
            }
        }

        override suspend fun read(key: OwnershipKey): OwnershipRead = gate.withLock {
            if (isOffline) OwnershipRead.Unavailable(OwnershipUnavailable.Transport) else OwnershipRead.Current(record)
        }

        override suspend fun compareAndSet(
            key: OwnershipKey,
            expected: ProcessOwnership?,
            next: ProcessOwnership,
        ): OwnershipCommit {
            beforeCommit()
            return gate.withLock {
                when {
                    isOffline -> OwnershipCommit.Unavailable(OwnershipUnavailable.Transport)

                    record != expected -> OwnershipCommit.Conflict

                    else -> {
                        record = next
                        commits++
                        if (cancelNextResponse) {
                            cancelNextResponse = false
                            throw CancellationException("Response cancelled after commit")
                        }
                        if (loseNextResponse) {
                            loseNextResponse = false
                            OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit)
                        } else {
                            OwnershipCommit.Applied
                        }
                    }
                }
            }
        }
    }
}
