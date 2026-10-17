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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.impl.data.run.MachineOrganismHost
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismConception
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismView
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isEnded
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
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
            organism() to OrganismView.Developing,
            organism(phase = CellPhase.Resting) to OrganismView.Developing,
            organism(phase = CellPhase.Stalled(Breakdown.NoModel, Work.Genesis)) to
                OrganismView.Stalled(OrganismEnd.NoModel),
            organism(phase = interrupted) to OrganismView.Stalled(OrganismEnd.Failed),
            organism(OrganismStatus.Completed("answer")) to OrganismView.Answered("answer"),
            organism(OrganismStatus.Aborted) to OrganismView.Aborted,
        )
        for ((found, expected) in views) {
            val ref = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to found)))
            val view = host(ref).view(bound)
            assertEquals(expected, view)
            assertEquals(expected is OrganismView.Answered || expected == OrganismView.Aborted, view.isEnded)
        }
    }

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
    fun `the goal bound of a step is the bound of an organism`() {
        assertEquals(OrganismBounds.MAX_GOAL, HarnessLimits.ORGANISM_GOAL_CHARS)
        assertFalse(OrganismView.Answered("private-answer").toString().contains("private-answer"))
    }

    private fun organism(
        status: OrganismStatus = OrganismStatus.Developing,
        phase: CellPhase = CellPhase.Working(RequestId("request"), Work.Genesis),
    ) = Organism(
        id,
        "goal",
        null,
        null,
        null,
        null,
        GrowthLimits(),
        listOf(Cell(CellId.ZYGOTE, "zygote", null, "goal", phase)),
        status = status,
    )
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
