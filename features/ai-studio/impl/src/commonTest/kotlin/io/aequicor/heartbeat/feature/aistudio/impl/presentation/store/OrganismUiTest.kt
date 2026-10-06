package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Trial
import io.aequicor.heartbeat.feature.organicai.api.Work
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganismUiTest {
    private fun ref(native: String) = SessionRef(EngineId("pi"), SessionSourceId("local"), native)

    private val awaiting = PermissionRequest(
        PermissionRequestId("p1"),
        TurnId("t1"),
        "Run gradle",
        listOf(
            PermissionOption(PermissionOptionId("allow"), "Allow"),
            PermissionOption(PermissionOptionId("skip"), "Skip", isSkip = true),
        ),
        input = PermissionInput.FreeText(),
    )
    private val complaint = ImmuneCase.Complaint(CaseId("k1"), CellId.ZYGOTE, CellId("c2"), "loops")
    private val organism = Organism(
        OrganismId("chat"),
        "Build",
        target = null,
        immunityTarget = null,
        workspace = null,
        trust = null,
        limits = GrowthLimits(),
        cells = listOf(
            Cell(CellId.ZYGOTE, "zygote", null, "Build", CellPhase.Resting, ref("z"), isAwaitingResults = true),
            Cell(
                CellId("c1"),
                "tests",
                CellId.ZYGOTE,
                "Test",
                CellPhase.Working(RequestId("r"), Work.Genesis, awaiting = listOf(awaiting)),
                ref("c1"),
            ),
            Cell(CellId("c2"), "loop", CellId.ZYGOTE, "Loop", CellPhase.Dead(DeathCause.Lysed(complaint.id, "x"))),
            Cell(CellId("c3"), "new", CellId.ZYGOTE, "New", CellPhase.Working(RequestId("r3"), Work.Genesis)),
        ),
        trials = listOf(Trial(complaint, ref("judge"), Ruling.Kill("loops"))),
    )

    @Test
    fun `cells and judges become sub-sessions with their state`() {
        val ui = organism.toUi()
        assertEquals(OrganismStatusUi.Developing, ui.status)
        assertEquals(
            listOf(
                SubSessionUi("zygote", SubSessionKindUi.Zygote, "zygote", SubSessionStateUi.Resting, isViewable = true),
                SubSessionUi(
                    "c1",
                    SubSessionKindUi.Cell,
                    "tests",
                    SubSessionStateUi.AwaitingUser,
                    isViewable = true,
                    depth = 1,
                ),
                SubSessionUi("c2", SubSessionKindUi.Cell, "loop", SubSessionStateUi.Killed, depth = 1),
                SubSessionUi("c3", SubSessionKindUi.Cell, "new", SubSessionStateUi.Germinating, depth = 1),
                SubSessionUi(
                    "k1",
                    SubSessionKindUi.Complaint,
                    "k1",
                    SubSessionStateUi.Sentenced,
                    "c2",
                    true,
                    depth = 1,
                ),
            ),
            ui.subSessions,
        )
    }

    @Test
    fun `a request with an input offers only its skip option`() {
        val request = organism.toUi().permissions.single()
        assertEquals(listOf(PermissionOptionUi("skip", "Skip")), request.options)
        assertEquals(
            listOf("c1", "tests", "t1", "p1"),
            listOf(request.cell, request.cellName, request.turn, request.requestId),
        )
    }

    @Test
    fun `the organism status follows its zygote and its end`() {
        val stalled = organism.copy(
            cells = listOf(organism.cells[0].copy(phase = CellPhase.Stalled(Breakdown.NoModel, Work.Genesis))),
        )
        assertEquals(OrganismStatusUi.Stalled, stalled.toUi().status)
        val unarmed = organism.copy(cells = organism.cells.map { it.copy(isAwaitingResults = false) })
        assertEquals(OrganismStatusUi.Stalled, unarmed.toUi().status)
        assertEquals(OrganismStatusUi.Aborted, organism.copy(status = OrganismStatus.Aborted).toUi().status)
        assertEquals(OrganismStatusUi.Completed, organism.copy(status = OrganismStatus.Completed("ok")).toUi().status)
    }
}
