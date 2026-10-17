package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class OrganismHarnessesTest {
    private val spec = OrganicAiMachineSpec
    private val chosen = setOf("compose", "verify")
    private val json = Json { ignoreUnknownKeys = true }

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))

    private fun step(state: OrganicAiState.Living, intent: OrganismIntent): Organism =
        checkNotNull(state.evolve(intent)) { "Intent was ignored" }.organism

    @Test
    fun `conceiving records the harnesses the user chose`() {
        val conception = Conception(ORGANISM, GOAL, target = TARGET, harnesses = chosen)
        val zygote = Cell(ZYGOTE, "zygote", null, GOAL, working(ZYGOTE), turns = 1)
        val conceived = Organism(
            ORGANISM, GOAL, TARGET, null, null, null, GrowthLimits(), listOf(zygote),
            version = 1,
            harnesses = chosen,
        )
        spec.assertTransition(
            OrganicAiState.Living(),
            OrganicAiIntent.Public.Conceive(conception),
            living(conceived),
            effects = listOf(
                OrganicAiEffect.Persist(conceived),
                OrganicAiEffect.Drive(conceived, ZYGOTE, request(ZYGOTE)),
            ),
        )
    }

    @Test
    fun `a conception without harnesses records none`() {
        val conceived = step(
            OrganicAiState.Living(),
            OrganicAiIntent.Public.Conceive(Conception(ORGANISM, GOAL, target = TARGET)),
        )
        assertEquals(emptySet(), conceived.harnesses)
    }

    @Test
    fun `division restart follow-up and resume never change the recorded choice`() {
        val base = organism().copy(harnesses = chosen)
        val divided = step(living(base), OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "scout", "explore"))
        assertEquals(chosen, divided.harnesses)

        assertEquals(chosen, base.awakened().harnesses)

        val finished = base.copy(
            cells = listOf(zygoteCell(CellPhase.Completed("done"))),
            status = OrganismStatus.Completed("done"),
        )
        val continued = step(living(finished), OrganicAiIntent.Public.FollowUp(ORGANISM, "More"))
        assertEquals(chosen, continued.harnesses)

        val stalled = base.copy(cells = listOf(zygoteCell(CellPhase.Stalled(Breakdown.Interrupted, Work.Genesis))))
        assertEquals(chosen, step(living(stalled), OrganicAiIntent.Public.Resume(ORGANISM)).harnesses)
    }

    @Test
    fun `a journal written before harnesses existed reads as none`() {
        val record = json.encodeToString(Organism.serializer(), organism().copy(harnesses = chosen)).let {
            JsonObject(json.parseToJsonElement(it).jsonObject - "harnesses").toString()
        }
        val restored = json.decodeFromString(Organism.serializer(), record)
        assertEquals(emptySet(), restored.harnesses)
        assertEquals(organism(), restored)
    }

    @Test
    fun `the choice survives a round trip and an empty choice is not written`() {
        val saved = organism().copy(harnesses = chosen)
        val written = json.encodeToString(Organism.serializer(), saved)
        assertEquals(saved, json.decodeFromString(Organism.serializer(), written))
        assertNotNull(json.parseToJsonElement(written).jsonObject["harnesses"])
        val empty = json.parseToJsonElement(json.encodeToString(Organism.serializer(), organism())).jsonObject
        assertFalse("harnesses" in empty)
    }

    @Test
    fun `a conception refuses too many or malformed harness ids`() {
        val tooMany = (1..OrganismBounds.MAX_HARNESSES + 1).mapTo(mutableSetOf()) { "h$it" }
        assertFailsWith<IllegalArgumentException> { Conception(ORGANISM, GOAL, harnesses = tooMany) }
        assertFailsWith<IllegalArgumentException> { Conception(ORGANISM, GOAL, harnesses = setOf(" ")) }
        assertFailsWith<IllegalArgumentException> {
            Conception(ORGANISM, GOAL, harnesses = setOf("x".repeat(OrganismBounds.MAX_HARNESS_ID + 1)))
        }
        val most = (1..OrganismBounds.MAX_HARNESSES).mapTo(mutableSetOf()) { "h$it" }
        assertEquals(most, Conception(ORGANISM, GOAL, harnesses = most).harnesses)
    }
}
