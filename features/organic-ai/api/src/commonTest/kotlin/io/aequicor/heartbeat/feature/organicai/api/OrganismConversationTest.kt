package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OrganismConversationTest {
    private val owner = LogicalSessionContext(LogicalSessionId("chat"), SessionKind.Session)
        .subsession(LogicalSessionId("full-child"))

    @Test
    fun `conception and restart retain the full owner without replacing it by the family root`() {
        val conceived = Conception(ORGANISM, GOAL, target = TARGET, conversation = owner).conceive().organism
        val restored = Json.decodeFromString<Organism>(Json.encodeToString(conceived)).awakened()
        assertEquals(owner, restored.conversation)
        assertEquals(owner, restored.copy(version = restored.version + 1).conversation)
    }

    @Test
    fun `legacy and parentless organisms do not infer ownership from their identifier`() {
        val conceived = Conception(ORGANISM, GOAL, target = TARGET).conceive().organism
        assertNull(Json.decodeFromString<Organism>(Json.encodeToString(conceived)).conversation)
    }

    @Test
    fun `an executor cannot become the full conversation owner`() {
        val executor = owner.subagent(LogicalSessionId("executor"))
        assertFailsWith<IllegalArgumentException> { Conception(ORGANISM, GOAL, conversation = executor) }
        assertFailsWith<IllegalArgumentException> {
            Conception(ORGANISM, GOAL).conceive().organism.copy(conversation = executor)
        }
    }
}
