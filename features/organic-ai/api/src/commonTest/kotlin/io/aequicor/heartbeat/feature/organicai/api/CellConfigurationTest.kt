package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals

class CellConfigurationTest {
    private val spec = OrganicAiMachineSpec

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))

    @Test
    fun `a child keeps the configuration chosen for it`() {
        val fast = CellConfiguration(TARGET.copy(model = ModelId("mini")), "low", "fast and cheap")
        val divide = OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "scout", "explore", fast)
        val child = Cell(C1, "scout", ZYGOTE, "explore", working(C1), turns = 1, configuration = fast)
        val divided = organism(child, version = 2)
        spec.assertTransition(
            living(organism()),
            divide,
            living(divided),
            effects = listOf(OrganicAiEffect.Persist(divided), OrganicAiEffect.Drive(divided, C1, request(C1))),
        )
        assertEquals(fast.target, divided.targetOf(child))
        assertEquals(TARGET, divided.targetOf(divided.cells.first()))
    }

    @Test
    fun `a cell refused by the provider of its own model restarts on the organism model`() {
        val refused = Breakdown.Engine(EngineFailure.RateLimited(LimitScope.Unknown))
        val own = CellConfiguration(TARGET.copy(model = ModelId("other")))
        val before = organism(cell(C1).copy(configuration = own), zygote = zygoteCell(CellPhase.Resting))
        val broke = OrganicAiIntent.Internal.TurnSettled(ORGANISM, C1, request(C1), Settlement.Broke(refused))
        val after = organism(
            cell(C1, phase = working(C1, turn = 2), turns = 2).copy(session = null),
            zygote = zygoteCell(CellPhase.Resting),
            version = 2,
        )
        spec.assertTransition(
            living(before),
            broke,
            living(after),
            effects = listOf(OrganicAiEffect.Persist(after), OrganicAiEffect.Drive(after, C1, request(C1, 2))),
        )
    }

    @Test
    fun `a second refusal on the organism model kills the cell`() {
        // After the fallback the cell has no configuration of its own and runs its second turn.
        val fallen = cell(C1, phase = working(C1, turn = 2), turns = 2).copy(session = null)
        assertDies(fallen, refusal, turn = 2)
    }

    @Test
    fun `a cell on its own model dies of a breakdown that is not a provider refusal`() {
        val closed = Breakdown.Engine(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        assertDies(cell(C1).copy(configuration = own), closed, turn = 1)
        assertDies(cell(C1).copy(configuration = own), Breakdown.Interrupted, turn = 1)
    }

    @Test
    fun `a refused cell without a configuration of its own dies`() {
        assertDies(cell(C1), refusal, turn = 1)
    }

    @Test
    fun `a cell on its own model refused after its first turn dies`() {
        assertDies(cell(C1, phase = working(C1, turn = 2), turns = 2).copy(configuration = own), refusal, turn = 2)
    }

    private val refusal = Breakdown.Engine(EngineFailure.RateLimited(LimitScope.Unknown))
    private val own = CellConfiguration(TARGET.copy(model = ModelId("other")))

    /** The broken [cell] is excised: it dies and its waiting parent, the zygote, reads the letter. */
    private fun assertDies(cell: Cell, breakdown: Breakdown, turn: Int) {
        val before = organism(cell, zygote = zygoteCell(CellPhase.Resting).copy(isAwaitingResults = true))
        val broke = OrganicAiIntent.Internal.TurnSettled(ORGANISM, C1, request(C1, turn), Settlement.Broke(breakdown))
        val cause = DeathCause.Failed(breakdown)
        val after = organism(
            cell.copy(phase = CellPhase.Dead(cause)),
            zygote = zygoteCell(
                working(ZYGOTE, turn = 2, work = Work.CheckInbox),
                turns = 2,
                inbox = listOf(Letter.ChildDied(C1, cell.name, cause)),
            ),
            version = 2,
        )
        spec.assertTransition(
            living(before),
            broke,
            living(after),
            effects = listOf(
                OrganicAiEffect.Persist(after),
                OrganicAiEffect.Drive(after, ZYGOTE, request(ZYGOTE, 2)),
                OrganicAiEffect.Release(after, listOf(C1), ReleaseMode.Lyse),
            ),
        )
    }
}
