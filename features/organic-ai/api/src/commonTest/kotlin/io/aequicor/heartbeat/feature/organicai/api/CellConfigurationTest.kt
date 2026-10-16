package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
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
}
