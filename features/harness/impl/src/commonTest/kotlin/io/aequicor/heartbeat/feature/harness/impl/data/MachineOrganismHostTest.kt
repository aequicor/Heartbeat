package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.impl.data.run.MachineOrganismHost
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismCellRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismConception
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismView
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isAbortable
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isEnded
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.Work
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachineOrganismHostTest {
    private val bound = WorkflowOrganism("wf-one")
    private val id = OrganismId("wf-one")
    private val options = OrganismOptions("Stage")
    private val machine = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to organism())))

    private fun host(ref: FakeOrganicMachine? = machine) = MachineOrganismHost(FakeOrganicRegistry(ref))

    @Test
    fun `a missing or sleeping machine is unavailable and never absent`() = runTest {
        assertEquals(OrganismView.Unavailable, host(null).view(bound))
        for (state in listOf(OrganicAiState.Dormant, OrganicAiState.Awakening, OrganicAiState.Broken)) {
            assertEquals(OrganismView.Unavailable, host(FakeOrganicMachine(state)).view(bound))
        }
    }

    @Test
    fun `an awake machine that does not know the organism shows it absent and ended`() = runTest {
        val empty = FakeOrganicMachine(OrganicAiState.Living())
        assertEquals(OrganismView.Absent, host(empty).view(bound))
        assertTrue(OrganismView.Absent.isEnded)
    }

    @Test
    fun `the state of the organism decides its view and only an ended one is ended`() = runTest {
        val interrupted = CellPhase.Stalled(Breakdown.Interrupted, Work.Genesis)
        val views = listOf(
            organism() to OrganismView.Developing(),
            organism(phase = CellPhase.Resting) to OrganismView.Developing(),
            organism(phase = CellPhase.Stalled(Breakdown.NoModel, Work.Genesis)) to
                OrganismView.Stalled(OrganismEnd.NoModel),
            organism(phase = interrupted) to OrganismView.Stalled(OrganismEnd.Failed),
            organism(OrganismStatus.Completed("answer")) to OrganismView.Answered("answer"),
            organism(OrganismStatus.Aborted) to OrganismView.Aborted,
            organism(OrganismStatus.Aborted, sessions = SessionRelease.Releasing) to OrganismView.Aborting,
            organism(OrganismStatus.Aborted, sessions = SessionRelease.Unconfirmed) to
                OrganismView.AbortUnconfirmed,
        )
        for ((found, expected) in views) {
            val ref = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to found)))
            val view = host(ref).view(bound)
            assertEquals(expected, view)
            assertEquals(expected is OrganismView.Answered || expected == OrganismView.Aborted, view.isEnded)
        }
    }

    @Test
    fun `an organism that is only marked aborted is not ended until its sessions are released`() = runTest {
        val developing = OrganicAiState.Living(mapOf(id to organism()))
        val aborting = step(developing, OrganicAiIntent.Public.Abort(id))
        assertEquals(OrganismView.Aborting, viewIn(aborting))
        assertFalse(OrganismView.Aborting.isEnded)

        val failed = step(aborting, OrganicAiIntent.Internal.SessionsReleased(id, isConfirmed = false))
        assertEquals(OrganismView.AbortUnconfirmed, viewIn(failed))
        assertFalse(OrganismView.AbortUnconfirmed.isEnded)
        assertTrue(OrganismView.AbortUnconfirmed.isAbortable)

        val retried = step(failed, OrganicAiIntent.Public.Abort(id))
        assertEquals(OrganismView.Aborting, viewIn(retried))
        assertFalse(OrganismView.Aborting.isAbortable)

        val released = step(retried, OrganicAiIntent.Internal.SessionsReleased(id, isConfirmed = true))
        assertEquals(OrganismView.Aborted, viewIn(released))
        assertTrue(OrganismView.Aborted.isEnded)
    }

    /** The state the real organic AI spec moves to, so the host reads what the machine really shows. */
    private fun step(from: OrganicAiState, intent: OrganicAiIntent): OrganicAiState =
        OrganicAiMachineSpec.resolve(from, intent)?.to ?: error("The machine ignored $intent")

    private suspend fun viewIn(state: OrganicAiState): OrganismView = host(FakeOrganicMachine(state)).view(bound)

    @Test
    fun `a conception pins the models and the route and caps trust at Ask`() = runTest {
        val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
        val pinned = options.copy(target = target, immunityTarget = target)
        val empty = FakeOrganicMachine(OrganicAiState.Living())
        val result = host(empty).conceive(bound, "Ship it", pinned, WorkspaceRef("project"))
        assertEquals(OrganismConception.Accepted, result)
        val conception = (empty.sent.single() as OrganicAiIntent.Public.Conceive).conception
        assertEquals(id, conception.id)
        assertEquals("Ship it", conception.goal)
        assertEquals(target, conception.target)
        assertEquals(target, conception.immunityTarget)
        assertEquals(WorkspaceRef("project"), conception.workspace)
        assertEquals(TrustLevel.Ask, conception.trust)
    }

    @Test
    fun `conception is unavailable without an awake machine and sends nothing`() = runTest {
        assertEquals(OrganismConception.Unavailable, host(null).conceive(bound, "Ship it", options, null))
        val asleep = FakeOrganicMachine(OrganicAiState.Dormant)
        assertEquals(OrganismConception.Unavailable, host(asleep).conceive(bound, "Ship it", options, null))
        assertTrue(asleep.sent.isEmpty())
        val gone = FakeOrganicMachine(OrganicAiState.Living(), SendResult.NotRunning)
        assertEquals(OrganismConception.Unavailable, host(gone).conceive(bound, "Ship it", options, null))
    }

    @Test
    fun `an ignored conception is accepted only for an organism that exists`() = runTest {
        val existing = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to organism())), SendResult.Ignored)
        assertEquals(OrganismConception.Accepted, host(existing).conceive(bound, "Ship it", options, null))
        val missing = FakeOrganicMachine(OrganicAiState.Living(), SendResult.Ignored)
        assertEquals(OrganismConception.Rejected, host(missing).conceive(bound, "Ship it", options, null))
    }

    @Test
    fun `an abort addresses only the organism of the step`() = runTest {
        host().abort(bound)
        assertEquals(listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.Abort(id)), machine.sent)
    }

    @Test
    fun `the requests of living cells are shown in cell order and nothing else asks`() = runTest {
        val builder = request("builder")
        val tester = request("tester")
        val cells = listOf(
            cell(CellId.ZYGOTE, CellPhase.Working(RequestId("z"), Work.Genesis)),
            cell(CellId("c1"), CellPhase.Working(RequestId("a"), Work.Genesis, awaiting = listOf(builder))),
            cell(CellId("c2"), CellPhase.Resting),
            cell(CellId("c3"), CellPhase.Working(RequestId("b"), Work.Genesis, awaiting = listOf(tester))),
        )
        val view = host(living(organism(cells = cells))).view(bound)
        val asking = listOf(
            OrganismCellRequest("c1", "cell-c1", builder),
            OrganismCellRequest("c3", "cell-c3", tester),
        )
        assertEquals(OrganismView.Developing(asking), view)
        assertFalse(view.isEnded)
        val answered = organism(OrganismStatus.Completed("done"), cells = cells)
        assertEquals(OrganismView.Answered("done"), host(living(answered)).view(bound))
    }

    @Test
    fun `an organism shows no more requests than the runs view holds`() = runTest {
        val many = (1..HarnessLimits.ORGANISM_REQUESTS + 5).map { request("req-$it") }
        val cells = listOf(
            cell(CellId.ZYGOTE, CellPhase.Working(RequestId("z"), Work.Genesis, awaiting = many)),
        )
        val view = host(living(organism(cells = cells))).view(bound) as OrganismView.Developing
        assertEquals(HarnessLimits.ORGANISM_REQUESTS, view.requests.size)
        assertEquals(many.take(HarnessLimits.ORGANISM_REQUESTS), view.requests.map { it.request })
    }

    @Test
    fun `a decision addresses the cell of the organism and tells whether the machine took it`() = runTest {
        val decision = PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("allow"))
        assertTrue(host().decide(bound, "c1", decision))
        assertEquals(
            listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.Decide(id, CellId("c1"), decision)),
            machine.sent,
        )
        val ignoring = FakeOrganicMachine(OrganicAiState.Living(), SendResult.Ignored)
        assertFalse(host(ignoring).decide(bound, "c1", decision))
        assertFalse(host(null).decide(bound, "c1", decision))
        val gone = FakeOrganicMachine(OrganicAiState.Dormant, SendResult.NotRunning)
        assertFalse(host(gone).decide(bound, "c1", decision))
    }

    @Test
    fun `the goal bound of a step is the bound of an organism`() {
        assertEquals(OrganismBounds.MAX_GOAL, HarnessLimits.ORGANISM_GOAL_CHARS)
        assertFalse(OrganismView.Answered("private-answer").toString().contains("private-answer"))
    }

    private fun living(found: Organism) = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to found)))

    private fun cell(cell: CellId, phase: CellPhase) =
        Cell(cell, "cell-${cell.value}", CellId.ZYGOTE.takeIf { cell != CellId.ZYGOTE }, "goal", phase)

    private fun request(name: String) = PermissionRequest(
        PermissionRequestId(name),
        TurnId("turn"),
        "Run $name?",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )

    private fun organism(
        status: OrganismStatus = OrganismStatus.Developing,
        phase: CellPhase = CellPhase.Working(RequestId("request"), Work.Genesis),
        cells: List<Cell> = listOf(Cell(CellId.ZYGOTE, "zygote", null, "goal", phase)),
        sessions: SessionRelease = SessionRelease.Released,
    ) = Organism(id, "goal", null, null, null, null, GrowthLimits(), cells, status = status, sessions = sessions)
}

private class FakeOrganicMachine(initial: OrganicAiState, private val result: SendResult = SendResult.Accepted) :
    MachineRef<OrganicAiState, OrganicAiIntent.Public, OrganicAiOutput> {
    override val name: String = "organic-ai"
    override val state: StateFlow<OrganicAiState> = MutableStateFlow(initial)
    override val outputs: Flow<OrganicAiOutput> = emptyFlow()
    val sent = mutableListOf<OrganicAiIntent.Public>()

    override suspend fun send(intent: OrganicAiIntent.Public): SendResult {
        sent += intent
        return result
    }
}

private class FakeOrganicRegistry(private val ref: FakeOrganicMachine?) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // tests address the one organic AI machine key
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = ref as MachineRef<S, P, O>?

    @Suppress("UNCHECKED_CAST") // tests address the one organic AI machine key
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(ref as MachineRef<S, P, O>?)

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}
