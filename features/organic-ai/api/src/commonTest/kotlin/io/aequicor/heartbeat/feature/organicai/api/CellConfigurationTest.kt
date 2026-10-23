package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
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
}
