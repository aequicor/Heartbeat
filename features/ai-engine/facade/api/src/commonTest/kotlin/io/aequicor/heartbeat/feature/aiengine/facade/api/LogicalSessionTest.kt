package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicalSessionTest {
    private val root = LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session)

    @Test
    fun `serialized open requests cannot claim a logical owner`() {
        val target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
        val request = CreateSessionRequest(target, logicalSession = root)
        assertNull(Json.decodeFromString<CreateSessionRequest>(Json.encodeToString(request)).logicalSession)
        val resume = ResumeSessionRequest(target, logicalSession = root)
        assertNull(Json.decodeFromString<ResumeSessionRequest>(Json.encodeToString(resume)).logicalSession)
    }

    @Test
    fun `nested subsessions share a sidebar root and retain their direct creator`() {
        val first = root.subsession(LogicalSessionId("first"))
        val second = first.subsession(LogicalSessionId("second"))
        assertEquals(root.id, second.root)
        assertEquals(first.id, second.creator)
        assertTrue(second.isHumanInteractive)
        assertTrue(second.isControlledBy(first.id))
        assertFalse(second.isControlledBy(root.id))
    }

    @Test
    fun `subagent belongs to its direct owner rather than the entire family`() {
        val session = root.subsession(LogicalSessionId("child"))
        val agent = session.subagent(LogicalSessionId("executor"))
        assertEquals(root.id, agent.root)
        assertTrue(agent.isControlledBy(session.id))
        assertFalse(agent.isControlledBy(root.id))
        assertFalse(agent.isHumanInteractive)
        assertEquals(agent, Json.decodeFromString(Json.encodeToString(agent)))
        assertFailsWith<IllegalArgumentException> { agent.subsession(LogicalSessionId("other")) }
        assertFailsWith<IllegalArgumentException> { agent.subagent(LogicalSessionId("other")) }
    }

    @Test
    fun `invalid local ownership is rejected on deserialization too`() {
        assertFailsWith<IllegalArgumentException> { root.subsession(root.id) }
        assertFailsWith<IllegalArgumentException> { root.subagent(root.id) }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<LogicalSessionContext>(
                """{"id":{"value":"a"},"kind":"Subagent","root":{"value":"b"}}""",
            )
        }
    }
}
