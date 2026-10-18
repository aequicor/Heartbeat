package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessOwnershipTest {
    private val source = ExecutorHostId("a".repeat(32))
    private val target = ExecutorHostId("b".repeat(32))
    private val stranger = ExecutorHostId("c".repeat(32))
    private val key = OwnershipKey("d".repeat(64))
    private val fence = OwnershipFence(key, source, 1)
    private val initial = ProcessOwnership(fence)
    private val transfer = "e".repeat(32)
    private val checkpoint = OwnershipCheckpoint("f".repeat(64), "portable_manifest")
    private val prepare = OwnershipCommand.Prepare(source, fence, transfer, target)
    private val quiesce = OwnershipCommand.Quiesce(source, fence, transfer)
    private val export = OwnershipCommand.Export(source, fence, transfer, checkpoint, 0, 0)
    private val claim = OwnershipCommand.Claim(target, fence, transfer)

    @Test
    fun `handover revokes admission before cleanup and requires recipient reconciliation`() {
        assertTrue(initial.accepts(fence))
        val preparing = initial.changed(prepare)
        assertFalse(preparing.accepts(fence))
        val quiescing = preparing.changed(quiesce)
        val exported = quiescing.changed(export)
        val claimed = exported.changed(claim)
        assertEquals(2L, claimed.fence.epoch)
        assertEquals(target, claimed.fence.host)
        assertFalse(claimed.accepts(fence))
        assertFalse(claimed.accepts(claimed.fence))
        val activated = claimed.changed(activation(claimed))
        assertTrue(activated.accepts(claimed.fence))
        assertFalse(activated.accepts(fence))
        assertEquals(6L, activated.revision)
    }

    @Test
    fun `a crash at every boundary preserves the exact command and checkpoint`() {
        var record = initial
        for (command in listOf(prepare, quiesce, export, claim)) {
            record = roundTrip(record).changed(command)
            val restored = roundTrip(record)
            assertEquals(OwnershipReduction.Unchanged(restored), restored.reduceOwnership(command))
        }
        val activated = roundTrip(record).changed(activation(record))
        assertEquals(OwnershipReduction.Unchanged(activated), activated.reduceOwnership(activation(activated)))
        assertEquals(checkpoint, activated.transfer?.checkpoint)
    }

    @Test
    fun `two recipients cannot claim the same export`() {
        val exported = exported()
        assertRejected(OwnershipRejection.Actor, exported, claim.copy(actor = stranger))
        val claimed = exported.changed(claim)
        assertRejected(OwnershipRejection.Actor, claimed, claim.copy(actor = stranger))
        assertRejected(OwnershipRejection.Fence, claimed, prepare)
        assertEquals(OwnershipReduction.Unchanged(claimed), claimed.reduceOwnership(claim))
    }

    @Test
    fun `missing executor termination and unknown in flight calls both prevent export`() {
        val record = initial.changed(prepare).changed(quiesce)
        for (command in listOf(export.copy(executors = 1), export.copy(calls = 1), export.copy(calls = -1))) {
            assertRejected(OwnershipRejection.Unresolved, record, command)
        }
        assertRejected(OwnershipRejection.Phase, initial.changed(prepare), export)
        assertRejected(OwnershipRejection.Phase, record, claim)
    }

    @Test
    fun `an unresolved external outcome or different checkpoint prevents activation`() {
        val claimed = exported().changed(claim)
        assertRejected(OwnershipRejection.Unresolved, claimed, activation(claimed).copy(unresolved = 1))
        assertRejected(OwnershipRejection.Checkpoint, claimed, activation(claimed).copy(checkpoint = null))
        assertRejected(
            OwnershipRejection.Checkpoint,
            claimed,
            activation(claimed).copy(checkpoint = checkpoint.copy(digest = "0".repeat(64))),
        )
    }

    @Test
    fun `cancel rotates source epoch and requires reconciliation at every sender phase`() {
        val preparing = initial.changed(prepare)
        val quiescing = preparing.changed(quiesce)
        for (record in listOf(preparing, quiescing, quiescing.changed(export))) {
            val cancelled = record.changed(OwnershipCommand.Cancel(source, fence, transfer))
            assertEquals(source, cancelled.fence.host)
            assertEquals(2L, cancelled.fence.epoch)
            assertFalse(cancelled.accepts(fence))
            assertFalse(cancelled.accepts(cancelled.fence))
            assertRejected(OwnershipRejection.Phase, cancelled, claim)
            val active = roundTrip(cancelled).changed(activation(cancelled))
            assertTrue(active.accepts(active.fence))
            assertRejected(OwnershipRejection.Fence, active, prepare)
        }
    }

    @Test
    fun `old sender cannot cancel or publish cleanup after recipient claim`() {
        val claimed = exported().changed(claim)
        assertRejected(OwnershipRejection.Fence, claimed, OwnershipCommand.Cancel(source, fence, transfer))
        assertRejected(OwnershipRejection.Fence, claimed, export)
        assertRejected(OwnershipRejection.Actor, claimed, activation(claimed).copy(actor = source))
    }

    @Test
    fun `callbacks for another transfer key or epoch cannot alter current transfer`() {
        val record = initial.changed(prepare)
        assertRejected(OwnershipRejection.Transfer, record, quiesce.copy(transfer = "0".repeat(32)))
        assertRejected(OwnershipRejection.Fence, record, quiesce.copy(expected = fence.copy(epoch = 2)))
        assertRejected(
            OwnershipRejection.Fence,
            record,
            quiesce.copy(expected = fence.copy(key = OwnershipKey("0".repeat(64)))),
        )
        assertRejected(OwnershipRejection.Actor, record, quiesce.copy(actor = target))
    }

    @Test
    fun `duplicate prepare cannot change target or identity`() {
        val record = initial.changed(prepare)
        assertRejected(OwnershipRejection.Phase, record, prepare.copy(target = stranger))
        assertRejected(OwnershipRejection.Phase, record, prepare.copy(id = "0".repeat(32)))
        assertRejected(OwnershipRejection.Transfer, initial, quiesce)
    }

    @Test
    fun `another transfer fences all delayed messages from its predecessor`() {
        val claimed = exported().changed(claim)
        val active = claimed.changed(activation(claimed))
        val next = active.changed(OwnershipCommand.Prepare(target, active.fence, "0".repeat(32), source))
        assertRejected(OwnershipRejection.Transfer, next, claim)
        assertRejected(OwnershipRejection.Transfer, next, activation(active))
        assertRejected(OwnershipRejection.Fence, next, export)
    }

    @Test
    fun `overflow refuses new epochs and revisions`() {
        assertRejected(OwnershipRejection.Exhausted, initial.copy(revision = Long.MAX_VALUE), prepare)
        val lastFence = fence.copy(epoch = Long.MAX_VALUE)
        val preparing = ProcessOwnership(lastFence).changed(prepare.copy(expected = lastFence))
        assertRejected(
            OwnershipRejection.Exhausted,
            preparing,
            OwnershipCommand.Cancel(source, lastFence, transfer),
        )
        val exported = preparing.changed(quiesce.copy(expected = lastFence)).changed(export.copy(expected = lastFence))
        assertRejected(OwnershipRejection.Exhausted, exported, claim.copy(expected = lastFence))
    }

    @Test
    fun `ownership logging never exposes host task checkpoint or transfer identifiers`() {
        val exported = exported()
        val text = listOf(
            key, source, fence, checkpoint, exported.transfer, exported, prepare, quiesce, export, claim,
            activation(exported), OwnershipCommand.Cancel(source, fence, transfer),
        ).joinToString()
        for (secret in listOf(key.value, source.value, checkpoint.digest, checkpoint.reference, transfer)) {
            assertFalse(secret in text)
        }
    }

    @Test
    fun `unreachable persisted ownership is refused without repairing its fence`() {
        val exported = exported()
        val encoded = Json.encodeToString(exported)
        assertFailsWith<IllegalArgumentException> {
            exported.copy(fence = fence.copy(host = stranger))
        }
        val claimed = exported.changed(claim)
        assertFailsWith<IllegalArgumentException> {
            claimed.copy(fence = claimed.fence.copy(epoch = 5))
        }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ProcessOwnership>(encoded.replace("exported", "active"))
        }
    }

    @Test
    fun `a lost cancel response is recovered by reading the rotated source record`() {
        val cancel = OwnershipCommand.Cancel(source, fence, transfer)
        val cancelled = roundTrip(exported().changed(cancel))
        assertRejected(OwnershipRejection.Fence, cancelled, cancel)
        assertRejected(OwnershipRejection.Phase, cancelled, claim)
        assertEquals(OwnershipPhase.Reconciling, cancelled.phase)
        assertTrue(cancelled.changed(activation(cancelled)).accepts(cancelled.fence))
    }

    @Test
    fun `persisted phase names remain stable across hosts`() {
        val names = listOf("active", "preparing", "quiescing", "exported", "reconciling")
        for ((phase, name) in OwnershipPhase.entries.zip(names)) {
            assertEquals("\"$name\"", Json.encodeToString(phase))
            assertEquals(phase, Json.decodeFromString<OwnershipPhase>("\"$name\""))
        }
    }

    private fun exported(): ProcessOwnership = initial.changed(prepare).changed(quiesce).changed(export)

    private fun activation(record: ProcessOwnership): OwnershipCommand.Activate = OwnershipCommand.Activate(
        record.fence.host,
        record.fence,
        transfer,
        record.transfer?.checkpoint,
        0,
    )

    private fun ProcessOwnership.changed(command: OwnershipCommand): ProcessOwnership =
        assertIs<OwnershipReduction.Changed>(reduceOwnership(command)).record

    private fun roundTrip(record: ProcessOwnership): ProcessOwnership =
        Json.decodeFromString(Json.encodeToString(record))

    private fun assertRejected(reason: OwnershipRejection, record: ProcessOwnership, command: OwnershipCommand) {
        assertEquals(OwnershipReduction.Rejected(reason), record.reduceOwnership(command))
    }
}
