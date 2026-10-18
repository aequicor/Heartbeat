package io.aequicor.heartbeat.feature.harness.impl.domain.ownership

import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnershipExecutionGateTest {
    private val source = ExecutorHostId("a".repeat(32))
    private val target = ExecutorHostId("b".repeat(32))
    private val fence = OwnershipFence(OwnershipKey("c".repeat(64)), source, 1)
    private val transfer = "d".repeat(32)
    private val correlation = "e".repeat(32)
    private val checkpoint = OwnershipCheckpoint("f".repeat(64), "manifest")

    @Test
    fun `lost permit response and restart never cause a second native dispatch`() = runTest {
        val backend = Backend(ProcessOwnership(fence))
        backend.loseNextResponse = true
        val gate = gate(backend)
        assertEquals(OwnershipAdmission.Unavailable, gate.admit(proposal(OwnershipPermitKind.GitHub)))
        val recovered = assertIs<OwnershipAdmission.Recover>(
            gate(backend).admit(proposal(OwnershipPermitKind.GitHub)),
        )
        assertEquals(1L, recovered.permit.sequence)
        assertEquals(1, backend.writes)
        assertTrue(gate.complete(recovered.permit))
        assertTrue(backend.record.permits.isEmpty())
        assertEquals(OwnershipAdmission.Unavailable, gate.admit(proposal(OwnershipPermitKind.GitHub)))
        assertEquals(2, backend.writes)
    }

    @Test
    fun `prepare waits for native completion and receiver must reconcile before dispatch`() = runTest {
        val backend = Backend(ProcessOwnership(fence))
        val sender = OwnershipCoordinatorClient(backend, source)
        val gate = gate(backend)
        val permit = assertIs<OwnershipAdmission.Fresh>(
            gate.admit(proposal(OwnershipPermitKind.Workflow)),
        ).permit
        suspend fun apply(command: OwnershipCommand) = sender.apply(backend.record, command)
        assertIs<OwnershipChange.Current>(apply(OwnershipCommand.Prepare(source, fence, transfer, target)))
        assertEquals(OwnershipAdmission.Unavailable, gate.admit(proposal(OwnershipPermitKind.Command)))
        assertIs<OwnershipChange.Current>(apply(OwnershipCommand.Quiesce(source, fence, transfer)))
        assertIs<OwnershipChange.Rejected>(
            apply(OwnershipCommand.Export(source, fence, transfer, checkpoint, 0, 0)),
        )
        // Only the native owner's positive stop receipt calls complete; cancellation/time does not.
        assertTrue(gate.complete(permit))
        assertIs<OwnershipChange.Current>(apply(OwnershipCommand.Export(source, fence, transfer, checkpoint, 0, 0)))
        val receiver = OwnershipCoordinatorClient(backend, target)
        assertIs<OwnershipChange.Current>(
            receiver.apply(backend.record, OwnershipCommand.Claim(target, fence, transfer)),
        )
        val targetFence = backend.record.fence
        val recipientGate = OwnershipExecutionGate(receiver, targetFence)
        assertEquals(
            OwnershipAdmission.Unavailable,
            recipientGate.admit(OwnershipPermit(correlation, 2, targetFence, OwnershipPermitKind.Workflow)),
        )
        assertIs<OwnershipChange.Current>(
            receiver.apply(backend.record, OwnershipCommand.Activate(target, targetFence, transfer, checkpoint, 0)),
        )
        assertEquals(OwnershipAdmission.Unavailable, gate.admit(proposal(OwnershipPermitKind.GitHub)))
        assertIs<OwnershipAdmission.Fresh>(
            recipientGate.admit(OwnershipPermit(correlation, 2, targetFence, OwnershipPermitKind.Workflow)),
        )
        assertEquals(2L, backend.record.permits.single().sequence)
    }

    @Test
    fun `offline completion retains the permit and foreign completion is refused`() = runTest {
        val backend = Backend(ProcessOwnership(fence))
        val gate = gate(backend)
        val permit = assertIs<OwnershipAdmission.Fresh>(
            gate.admit(proposal(OwnershipPermitKind.Command)),
        ).permit
        backend.isOffline = true
        assertFalse(gate.complete(permit))
        assertEquals(listOf(permit), backend.record.permits)
        backend.isOffline = false
        assertFalse(gate.complete(permit.copy(fence = fence.copy(host = target))))
        assertEquals(listOf(permit), backend.record.permits)
        assertTrue(gate.complete(permit))
    }

    private fun proposal(kind: OwnershipPermitKind) = OwnershipPermit(correlation, 1, fence, kind)

    private fun gate(backend: Backend) = OwnershipExecutionGate(OwnershipCoordinatorClient(backend, source), fence)

    private class Backend(var record: ProcessOwnership) : OwnershipBackend {
        private val mutex = Mutex()
        var loseNextResponse = false
        var isOffline = false
        var writes = 0
        override suspend fun read(key: OwnershipKey): OwnershipRead = if (isOffline) {
            OwnershipRead.Unavailable(OwnershipUnavailable.Transport)
        } else {
            OwnershipRead.Current(record)
        }

        override suspend fun compareAndSet(
            key: OwnershipKey,
            expected: ProcessOwnership?,
            next: ProcessOwnership,
        ): OwnershipCommit = mutex.withLock {
            if (expected != record) return@withLock OwnershipCommit.Conflict
            record = next
            writes++
            if (loseNextResponse) {
                loseNextResponse = false
                OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit)
            } else {
                OwnershipCommit.Applied
            }
        }
    }
}
