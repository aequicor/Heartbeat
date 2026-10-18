package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganismDecisionModeTest {
    @Test
    fun `host policy survives conception division restart resume and follow-up`() {
        val conception = Conception(
            ORGANISM,
            GOAL,
            target = TARGET,
            decisionMode = AgentToolDecisionMode.AutomaticSafety,
        )
        val conceived = checkNotNull(
            OrganicAiState.Living().evolve(OrganicAiIntent.Public.Conceive(conception)),
        ).organism
        assertEquals(AgentToolDecisionMode.AutomaticSafety, conceived.decisionMode)
        val divided = checkNotNull(
            OrganicAiState.Living(mapOf(ORGANISM to conceived)).evolve(
                OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "scout", "explore"),
            ),
        ).organism
        val saved = Json.encodeToString(Organism.serializer(), divided)
        val restored = Json.decodeFromString(Organism.serializer(), saved).awakened()
        assertEquals(AgentToolDecisionMode.AutomaticSafety, restored.decisionMode)
        assertEquals(2, restored.cells.size)

        val stalled = restored.copy(cells = listOf(zygoteCell(CellPhase.Stalled(Breakdown.Interrupted, Work.Genesis))))
        val resumed = checkNotNull(
            OrganicAiState.Living(mapOf(ORGANISM to stalled)).evolve(OrganicAiIntent.Public.Resume(ORGANISM)),
        ).organism
        assertEquals(AgentToolDecisionMode.AutomaticSafety, resumed.decisionMode)
        val completed = restored.copy(
            cells = listOf(zygoteCell(CellPhase.Completed("done"))),
            status = OrganismStatus.Completed("done"),
        )
        val continued = checkNotNull(
            OrganicAiState.Living(mapOf(ORGANISM to completed)).evolve(
                OrganicAiIntent.Public.FollowUp(ORGANISM, "More"),
            ),
        ).organism
        assertEquals(AgentToolDecisionMode.AutomaticSafety, continued.decisionMode)
    }

    @Test
    fun `existing conceptions and journals retain interactive policy`() {
        assertEquals(AgentToolDecisionMode.Interactive, Conception(ORGANISM, GOAL).decisionMode)
        val encoded = Json.encodeToJsonElement(
            Organism.serializer(),
            organism().copy(decisionMode = AgentToolDecisionMode.AutomaticSafety),
        ).jsonObject
        val legacy = JsonObject(encoded - "decisionMode")
        assertEquals(
            AgentToolDecisionMode.Interactive,
            Json.decodeFromJsonElement(Organism.serializer(), legacy).decisionMode,
        )
    }
}
