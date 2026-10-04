package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganismLifeTest {
    private val grown = organism(
        cell(C1),
        cell(C2, parent = C1),
        cell(C3, parent = C2, phase = CellPhase.Completed("done")),
    )

    @Test
    fun `lineage, depth and subtree follow the parents`() {
        assertEquals(listOf(C2, C1, ZYGOTE), grown.lineage(C2).map { it.id })
        assertEquals(0, grown.depth(ZYGOTE))
        assertEquals(3, grown.depth(C3))
        assertEquals(listOf(C1, C2, C3), grown.subtree(C1).map { it.id })
        assertEquals(emptyList(), grown.subtree(CellId("missing")))
        assertEquals(listOf(C1), grown.children(ZYGOTE).map { it.id })
    }

    @Test
    fun `ids continue after the existing cells and cases`() {
        assertEquals(CellId("c4"), grown.nextCellId())
        assertEquals(CaseId("k3"), grown.copy(casesFiled = 2).nextCaseId())
        assertEquals(request(C1, 2), grown.requestFor(C1, 2))
    }

    @Test
    fun `sub-sessions reopen as the organism opened them`() {
        val workspace = WorkspaceRef("project")
        val judge = TARGET.copy(model = ModelId("judge"))
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "loops")
        val judged = grown.copy(
            workspace = workspace,
            immunityTarget = judge,
            trials = listOf(Trial(complaint, session("j"))),
        )
        assertEquals(
            OrganismSession(session("c1"), ResumeSessionRequest(TARGET, workspace, areDetachedToolsEnabled = true)),
            judged.sessionOf("c1"),
        )
        assertEquals(OrganismSession(session("j"), ResumeSessionRequest(judge)), judged.sessionOf("k1"))
        assertEquals(TARGET, judged.copy(immunityTarget = null).sessionOf("k1")?.reopening?.target)
        assertNull(judged.copy(cells = listOf(zygoteCell(), cell(C1).copy(session = null))).sessionOf("c1"))
        assertNull(judged.sessionOf("k2"))
    }

    @Test
    fun `a cell waits for living children and its own dispute`() {
        assertTrue(grown.isWaiting(C1))
        assertFalse(grown.isWaiting(C2))
        val asked = grown.copy(cases = listOf(ImmuneCase.Dispute(CaseId("k1"), C2, "Which?")))
        assertTrue(asked.isWaiting(C2))
    }

    @Test
    fun `only a working cell divides, within the explicit limits`() {
        assertNull(grown.divisionRefusal(C1))
        assertEquals(Refusal.UnknownCell, grown.divisionRefusal(CellId("c9")))
        assertEquals(Refusal.NotWorking, grown.divisionRefusal(C3))
        assertEquals(
            Refusal.NotDeveloping,
            grown.copy(status = OrganismStatus.Aborted).divisionRefusal(C1),
        )
        assertEquals(Refusal.TooManyCells, grown.copy(limits = GrowthLimits(maxCells = 4)).divisionRefusal(C1))
        assertNull(grown.copy(limits = GrowthLimits(maxCells = 5)).divisionRefusal(C1))
        assertEquals(Refusal.TooDeep, grown.copy(limits = GrowthLimits(maxDepth = 2)).divisionRefusal(C2))
        assertNull(grown.copy(limits = GrowthLimits(maxDepth = 2)).divisionRefusal(C1))
    }

    @Test
    fun `the zygote is never accused and complaints are not repeated`() {
        assertNull(grown.complaintRefusal(C1, C2))
        assertEquals(Refusal.ZygoteImmune, grown.complaintRefusal(C1, ZYGOTE))
        assertEquals(Refusal.SelfComplaint, grown.complaintRefusal(C1, C1))
        assertEquals(Refusal.CellNotAlive, grown.complaintRefusal(C1, C3))
        assertEquals(Refusal.NotWorking, grown.complaintRefusal(C3, C1))
        assertEquals(Refusal.UnknownCell, grown.complaintRefusal(C1, CellId("c9")))
        val filed = grown.copy(cases = listOf(ImmuneCase.Complaint(CaseId("k1"), C1, C2, "loops")))
        assertEquals(Refusal.DuplicateComplaint, filed.complaintRefusal(C1, C2))
        assertNull(filed.complaintRefusal(ZYGOTE, C2))
    }

    @Test
    fun `dispute parties are distinct known cells other than the asker`() {
        assertNull(grown.disputeRefusal(C1, listOf(C2, C3)))
        assertEquals(Refusal.InvalidParties, grown.disputeRefusal(C1, listOf(C1)))
        assertEquals(Refusal.InvalidParties, grown.disputeRefusal(C1, listOf(C2, C2)))
        assertEquals(Refusal.UnknownCell, grown.disputeRefusal(C1, listOf(CellId("c9"))))
        assertEquals(Refusal.NotWorking, grown.disputeRefusal(C3, emptyList()))
        val many = organism(*(1..6).map { cell(CellId("c$it")) }.toTypedArray())
        assertEquals(
            Refusal.InvalidParties,
            many.disputeRefusal(C1, (2..6).map { CellId("c$it") }),
        )
    }

    @Test
    fun `a saved organism drops the observed turn and permission requests`() {
        val awaiting = PermissionRequest(
            PermissionRequestId("p"),
            TurnId("t"),
            "Run",
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
        )
        val observed = working(C1).copy(turn = TurnId("t"), awaiting = listOf(awaiting))
        val saved = organism(cell(C1, phase = observed))
        val json = Json.encodeToString(Organism.serializer(), saved)
        val restored = Json.decodeFromString(Organism.serializer(), json)
        assertEquals(organism(cell(C1, phase = working(C1))), restored)
    }

    @Test
    fun `invalid identities and organisms are rejected`() {
        assertFailsWith<IllegalArgumentException> { CellId("a b") }
        assertFailsWith<IllegalArgumentException> { OrganismId("x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { Conception(ORGANISM, " ") }
        assertFailsWith<IllegalArgumentException> { organism(cell(C1, parent = C1).copy(parent = null)) }
        assertFailsWith<IllegalArgumentException> { organism(cell(C1), cell(C1)) }
        assertFailsWith<IllegalArgumentException> { GrowthLimits(maxCells = 0) }
    }

    @Test
    fun `texts never appear in descriptions`() {
        val secret = "secret goal text"
        val described = listOf(
            Conception(ORGANISM, secret).toString(),
            organism(cell(C1, phase = CellPhase.Completed(secret))).toString(),
            Letter.ChildFinished(C1, "n", secret).toString(),
            ImmuneCase.Complaint(CaseId("k1"), C1, C2, secret).toString(),
            Ruling.Answer(secret, secret).toString(),
            OrganismStatus.Completed(secret).toString(),
        )
        described.forEach { assertFalse(secret in it, it) }
    }
}
