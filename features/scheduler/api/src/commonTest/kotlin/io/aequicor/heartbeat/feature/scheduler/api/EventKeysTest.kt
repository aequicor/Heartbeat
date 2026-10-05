package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.scheduler.api.spi.SourceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EventKeysTest {
    private val session = SessionRef(EngineId("Claude Code"), SessionSourceId("cli"), "abc/DEF 123")

    @Test
    fun `keys are validated and know their namespace`() {
        assertEquals(EventNamespace.System, EventKeys.NetworkAvailable.namespace)
        assertEquals(EventNamespace.Custom, EventKeys.custom("tests.green").namespace)
        assertEquals(EventKey("action.a1.finished"), EventKeys.actionFinished(ActionId("a1")))
        assertNull(EventKey.parse("time.tick"))
        assertNull(EventKey.parse("custom"))
        assertNull(EventKey.parse("Custom.Upper"))
        assertEquals(EventKey("custom.x"), EventKey.parse(" custom.x "))
        assertFailsWith<IllegalArgumentException> { EventKeys.custom("bad name") }
        assertFailsWith<IllegalArgumentException> { EventKey("custom." + "a".repeat(EventKey.MAX_LENGTH)) }
    }

    @Test
    fun `session keys are stable and distinct`() {
        val key = EventKeys.turnFinished(session)
        assertEquals(key, EventKeys.turnFinished(session.copy()))
        assertEquals(EventNamespace.Session, key.namespace)
        assertEquals("session.claude-code.", key.value.substringBeforeLast('.').dropLast(16))
        assertNotEquals(key, EventKeys.turnFinished(session.copy(nativeId = "abc/DEF 124")))
        assertNotEquals(key, EventKeys.turnFinished(session.copy(source = SessionSourceId("other"))))
    }

    @Test
    fun `fnv hash matches the reference vector`() {
        // FNV-1a 64 of "a\u0000b\u0000c": stored keys must survive a reimplementation of the hash.
        val segment = EventKeys.sessionSegment(SessionRef(EngineId("a"), SessionSourceId("b"), "c"))
        assertEquals("a.3fae46fc8499a3af", segment)
    }

    @Test
    fun `sources publish system events only`() {
        SourceEvent(EventKeys.NetworkLost)
        assertFailsWith<IllegalArgumentException> { SourceEvent(EventKeys.custom("x")) }
    }

    @Test
    fun `conditions need a trigger`() {
        assertFailsWith<IllegalArgumentException> { WakeCondition() }
        assertFailsWith<IllegalArgumentException> { WakeId("Bad Id") }
    }
}
