package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OwnershipPermitTest {
    private val host = ExecutorHostId("a".repeat(32))
    private val target = ExecutorHostId("b".repeat(32))
    private val fence = OwnershipFence(OwnershipKey("c".repeat(64)), host, 1)
    private val initial = ProcessOwnership(fence)
    private val transfer = "d".repeat(32)
    private val checkpoint = OwnershipCheckpoint("e".repeat(64), "manifest")
    private val admission = OwnershipCommand.Admit(host, fence, "f".repeat(32), 1, OwnershipPermitKind.Workflow)
    private val prepare = OwnershipCommand.Prepare(host, fence, transfer, target)

    @Test
    fun `prepare and admission cannot both win from the same coordinator revision`() {
        val prepared = initial.changed(prepare)
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Phase),
            prepared.reduceOwnership(admission),
        )
        val admitted = initial.changed(admission)
        val draining = admitted.changed(prepare).changed(OwnershipCommand.Quiesce(host, fence, transfer))
        assertEquals(admitted.permits, draining.permits)
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Unresolved),
            draining.reduceOwnership(OwnershipCommand.Export(host, fence, transfer, checkpoint, 0, 0)),
        )
    }

    @Test
    fun `unknown outcomes survive restart and never expire into an export`() {
        val admitted = initial.changed(admission)
        val draining = admitted.changed(prepare).changed(OwnershipCommand.Quiesce(host, fence, transfer))
        val restored = Json.decodeFromString<ProcessOwnership>(Json.encodeToString(draining))
        assertEquals(draining, restored)
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Unresolved),
            restored.reduceOwnership(OwnershipCommand.Export(host, fence, transfer, checkpoint, 0, 0)),
        )
        val settled = restored.changed(OwnershipCommand.Settle(host, fence, admitted.permits.single()))
        assertIs<OwnershipReduction.Changed>(
            settled.reduceOwnership(OwnershipCommand.Export(host, fence, transfer, checkpoint, 0, 0)),
        )
    }

    @Test
    fun `cancelling rotates epoch but admitted work must still be drained before activation`() {
        val admitted = initial.changed(admission)
        val cancelled = admitted.changed(prepare).changed(OwnershipCommand.Cancel(host, fence, transfer))
        val activate = OwnershipCommand.Activate(host, cancelled.fence, transfer, null, 0)
        assertEquals(OwnershipReduction.Rejected(OwnershipRejection.Unresolved), cancelled.reduceOwnership(activate))
        val settled = cancelled.changed(OwnershipCommand.Settle(host, fence, admitted.permits.single()))
        val active = settled.changed(activate)
        assertFalse(active.accepts(fence))
        assertEquals(2L, active.nextPermit)
    }

    @Test
    fun `permit replays cannot dispatch twice or reuse a settled sequence`() {
        val admitted = initial.changed(admission)
        assertEquals(OwnershipReduction.Unchanged(admitted), admitted.reduceOwnership(admission))
        val permit = admitted.permits.single()
        val settled = admitted.changed(OwnershipCommand.Settle(host, fence, permit))
        assertEquals(
            OwnershipReduction.Unchanged(settled),
            settled.reduceOwnership(OwnershipCommand.Settle(host, fence, permit)),
        )
        assertEquals(OwnershipReduction.Rejected(OwnershipRejection.Permit), settled.reduceOwnership(admission))
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Permit),
            admitted.reduceOwnership(admission.copy(kind = OwnershipPermitKind.GitHub)),
        )
    }

    @Test
    fun `another host and a replaced permit cannot forge release`() {
        val admitted = initial.changed(admission)
        val permit = admitted.permits.single()
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Actor),
            admitted.reduceOwnership(OwnershipCommand.Settle(target, fence, permit)),
        )
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Permit),
            admitted.reduceOwnership(
                OwnershipCommand.Settle(host, fence, permit.copy(kind = OwnershipPermitKind.Command)),
            ),
        )
    }

    @Test
    fun `full ledger and exhausted counters refuse before dispatch`() {
        var record = initial
        repeat(MAX_OWNERSHIP_PERMITS) { index ->
            val id = index.toString(16).padStart(32, '0')
            record = record.changed(admission.copy(id = id, sequence = record.nextPermit))
        }
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Quota),
            record.reduceOwnership(admission.copy(sequence = record.nextPermit)),
        )
        val exhausted = initial.copy(nextPermit = Long.MAX_VALUE)
        assertEquals(
            OwnershipReduction.Rejected(OwnershipRejection.Exhausted),
            exhausted.reduceOwnership(admission.copy(sequence = Long.MAX_VALUE)),
        )
    }

    private fun ProcessOwnership.changed(command: OwnershipCommand): ProcessOwnership =
        assertIs<OwnershipReduction.Changed>(reduceOwnership(command)).record
}
