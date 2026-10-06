package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxDeliveryTest {
    private val spec = OrganicAiMachineSpec
    private val letter = Letter.ChildFinished(C1, "cell c1", "child result")

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(ORGANISM to organism))

    private fun OrganicAiState.organism(): Organism = (this as OrganicAiState.Living).organisms.getValue(ORGANISM)

    private fun settle(id: CellId) = OrganicAiIntent.Internal.TurnSettled(
        ORGANISM,
        id,
        request(id),
        Settlement.Answered("child result"),
    )

    @Test
    fun `child completion does not interrupt a working parent or start an unsolicited next turn`() {
        val before = organism(cell(C1))
        val completed = spec.resolve(living(before), settle(C1))!!
        val parent = completed.to.organism().zygote
        assertEquals(before.zygote.phase, parent.phase)
        assertEquals(listOf(letter), parent.inbox)
        assertFalse(completed.effects.any { it is OrganicAiEffect.Drive })

        val settled = spec.resolve(completed.to, settle(ZYGOTE))!!
        assertEquals(CellPhase.Resting, settled.to.organism().zygote.phase)
        assertFalse(settled.effects.any { it is OrganicAiEffect.Drive })
        assertEquals(OrganismStatus.Developing, settled.to.organism().status)
    }

    @Test
    fun `an unarmed resting parent is not woken by a child result`() {
        val before = organism(cell(C1), zygote = zygoteCell(CellPhase.Resting))
        val result = spec.resolve(living(before), settle(C1))!!
        assertEquals(CellPhase.Resting, result.to.organism().zygote.phase)
        assertEquals(listOf(letter), result.to.organism().zygote.inbox)
        assertFalse(result.effects.any { it is OrganicAiEffect.Drive })
    }

    @Test
    fun `a result arriving between the empty read and registering a wait is not lost`() {
        val before = spec.resolve(living(organism(cell(C1))), settle(C1))!!.to
        val wait = OrganicAiIntent.Internal.AwaitResults(ORGANISM, ZYGOTE, request(ZYGOTE))
        val armed = spec.resolve(before, wait)!!
        assertTrue(armed.to.organism().zygote.isAwaitingResults)
        assertFalse(armed.effects.any { it is OrganicAiEffect.Drive })
        val settled = spec.resolve(armed.to, settle(ZYGOTE))!!
        val parent = settled.to.organism().zygote
        assertEquals(Work.CheckInbox, (parent.phase as CellPhase.Working).work)
        assertEquals(listOf(letter), parent.inbox)
        assertFalse(parent.isAwaitingResults)
        assertEquals(1, settled.effects.count { it is OrganicAiEffect.Drive })
    }

    @Test
    fun `an inbox read acknowledges only its prefix and permits replay without moving the cursor backwards`() {
        val second = Letter.DisputeResolved(CaseId("k1"), "Which?", "A", "reason")
        val before = organism(zygote = zygoteCell(inbox = listOf(letter, second)))
        val read = OrganicAiIntent.Internal.ReceiveLetters(ORGANISM, ZYGOTE, request(ZYGOTE), 1)
        val after = before.copy(cells = listOf(before.zygote.copy(receivedLetters = 1)), version = 2)
        spec.assertTransition(living(before), read, living(after), effects = listOf(OrganicAiEffect.Persist(after)))
        assertEquals(listOf(second), after.zygote.unreadLetters())
        val replay = spec.resolve(living(after), read.copy(until = 0))!!.to.organism()
        assertEquals(1, replay.zygote.receivedLetters)
        assertEquals(listOf(letter, second), replay.zygote.inbox)
        spec.assertIgnored(living(after), read.copy(until = 3))
        spec.assertIgnored(living(after), read.copy(until = -1))
        spec.assertIgnored(living(after), read.copy(request = request(ZYGOTE, 2)))
    }

    @Test
    fun `reading the last result disarms a wait and allows the parent to finish in the same turn`() {
        val before = organism(
            cell(C1, phase = CellPhase.Completed("child result")),
            zygote = zygoteCell(inbox = listOf(letter)).copy(isAwaitingResults = true),
        )
        val read = spec.resolve(
            living(before),
            OrganicAiIntent.Internal.ReceiveLetters(ORGANISM, ZYGOTE, request(ZYGOTE), 1),
        )!!
        assertFalse(read.to.organism().zygote.isAwaitingResults)
        val finished = spec.resolve(read.to, settle(ZYGOTE))!!
        assertTrue(finished.to.organism().status is OrganismStatus.Completed)
        assertFalse(finished.effects.any { it is OrganicAiEffect.Drive })
    }

    @Test
    fun `a wait without outstanding work and stale wait requests are refused`() {
        val wait = OrganicAiIntent.Internal.AwaitResults(ORGANISM, ZYGOTE, request(ZYGOTE))
        spec.assertIgnored(living(organism()), wait)
        spec.assertIgnored(living(organism(cell(C1))), wait.copy(request = request(ZYGOTE, 2)))
        spec.assertIgnored(living(organism(cell(C1), zygote = zygoteCell(CellPhase.Resting))), wait)
        spec.assertIgnored(OrganicAiState.Dormant, wait)
    }

    @Test
    fun `a dispute party can register a wait without children or a case it filed`() {
        val dispute = ImmuneCase.Dispute(CaseId("k1"), ZYGOTE, "Which?", listOf(C1))
        val before = organism(cell(C1), cell(C2), cases = listOf(dispute))
        val wait = OrganicAiIntent.Internal.AwaitResults(ORGANISM, C1, request(C1))
        val after = before.copy(
            cells = before.cells.map { if (it.id == C1) it.copy(isAwaitingResults = true) else it },
            version = before.version + 1,
        )
        spec.assertTransition(living(before), wait, living(after), effects = listOf(OrganicAiEffect.Persist(after)))
        spec.assertIgnored(living(before), wait.copy(cell = C2, request = request(C2)))
        val settled = spec.resolve(living(after), settle(C1))!!.to.organism()
        assertEquals(CellPhase.Resting, settled.cell(C1)?.phase)
        assertTrue(settled.zygote.inbox.isEmpty())
    }

    @Test
    fun `dispute results wake every waiting party whether delivered before or after their turn ends`() {
        val dispute = ImmuneCase.Dispute(CaseId("k1"), C1, "Which?", listOf(ZYGOTE, C2))
        val letter = Letter.DisputeResolved(dispute.id, "Which?", "A", "reason")
        val ruling = OrganicAiIntent.Internal.Ruled(ORGANISM, dispute.id, Ruling.Answer("A", "reason"))
        for (phase in listOf(working(ZYGOTE), CellPhase.Resting)) {
            val before = organism(
                cell(C1, phase = CellPhase.Resting).copy(isAwaitingResults = true),
                cell(C2, phase = CellPhase.Resting).copy(isAwaitingResults = true),
                zygote = zygoteCell(phase).copy(isAwaitingResults = true),
                cases = listOf(dispute),
            )
            val ruled = spec.resolve(living(before), ruling)!!
            val continued = if (phase is CellPhase.Working) spec.resolve(ruled.to, settle(ZYGOTE))!! else ruled
            val after = continued.to.organism()
            for (id in listOf(ZYGOTE, C1, C2)) {
                assertEquals(Work.CheckInbox, (after.cell(id)?.phase as CellPhase.Working).work)
                assertEquals(listOf(letter), after.cell(id)?.unreadLetters())
                assertEquals(2, after.cell(id)?.turns)
            }
            val batch = ruled.effects.filterIsInstance<OrganicAiEffect.DriveCells>().single()
            assertEquals(if (phase is CellPhase.Working) listOf(C2) else listOf(ZYGOTE, C2), batch.cells)
        }
    }

    @Test
    fun `waits and cursors survive serialization and a pending ready wait resumes after restart`() {
        val saved = organism(
            zygote = zygoteCell(CellPhase.Resting, inbox = listOf(letter, letter)).copy(
                isAwaitingResults = true,
                receivedLetters = 1,
            ),
        )
        val restored = Json.decodeFromString<Organism>(Json.encodeToString(saved)).awakened()
        assertEquals(Work.CheckInbox, (restored.zygote.phase as CellPhase.Working).work)
        assertEquals(1, restored.zygote.receivedLetters)
        assertEquals(listOf(letter), restored.zygote.unreadLetters())
        assertFalse(restored.zygote.isAwaitingResults)
        val pending = saved.copy(cells = listOf(saved.zygote.copy(receivedLetters = 2)))
        assertEquals(CellPhase.Resting, pending.awakened().zygote.phase)
        assertTrue(pending.awakened().zygote.isAwaitingResults)
    }

    @Test
    fun `legacy saved letters are restored to the inbox before recovery`() {
        val old = organism(zygote = zygoteCell(working(ZYGOTE, work = Work.Letters(listOf(letter)))))
        val restored = old.awakened()
        assertEquals(listOf(letter), restored.zygote.unreadLetters())
        assertEquals(Work.CheckInbox, (restored.zygote.phase as CellPhase.Working).work)
        assertEquals(listOf(letter), restored.awakened().zygote.unreadLetters())
        val stalledCell = old.zygote.copy(
            phase = CellPhase.Stalled(Breakdown.Interrupted, Work.Letters(listOf(letter))),
        )
        val stalled = old.copy(cells = listOf(stalledCell))
        val resumed = spec.resolve(living(stalled), OrganicAiIntent.Public.Resume(ORGANISM))!!.to.organism()
        assertEquals(listOf(letter), resumed.zygote.unreadLetters())
        assertEquals(Work.CheckInbox, (resumed.zygote.phase as CellPhase.Working).work)
    }

    @Test
    fun `user resume recovers cells that ended without registering a wait and preserves running siblings`() {
        val before = organism(
            cell(C1, phase = CellPhase.Resting),
            cell(C2),
            zygote = zygoteCell(CellPhase.Resting),
        )
        val resumed = spec.resolve(living(before), OrganicAiIntent.Public.Resume(ORGANISM))!!
        val after = resumed.to.organism()
        assertEquals(Work.CheckInbox, (after.zygote.phase as CellPhase.Working).work)
        assertEquals(Work.CheckInbox, (after.cell(C1)?.phase as CellPhase.Working).work)
        assertEquals(before.cell(C2), after.cell(C2))
        assertEquals(
            listOf(OrganicAiEffect.Persist(after), OrganicAiEffect.DriveCells(after, listOf(ZYGOTE, C1))),
            resumed.effects,
        )
    }
}
