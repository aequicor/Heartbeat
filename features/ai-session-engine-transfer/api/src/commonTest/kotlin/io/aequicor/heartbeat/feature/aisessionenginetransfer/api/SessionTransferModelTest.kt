package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionTransferModelTest {
    @Test
    fun `identifiers reject unsafe values`() {
        assertFailsWith<IllegalArgumentException> { ConversationId("") }
        assertFailsWith<IllegalArgumentException> { ConversationId("/home/user/.codex/sessions") }
        assertFailsWith<IllegalArgumentException> { TransferId("user@example.com") }
        assertFailsWith<IllegalArgumentException> { ConversationId("__hb_reserved") }
        assertEquals("abc_1-2", TransferId("abc_1-2").value)
    }

    @Test
    fun `conversations append segments and never repeat a native session`() {
        val extended = TestConversation + TestSegment
        assertEquals(TestSegment, extended.current)
        assertEquals(TestSource, extended.segments.first().ref)
        assertFailsWith<IllegalArgumentException> { extended + TestSegment }
        assertFailsWith<IllegalArgumentException> { LogicalConversation(ConversationId("empty"), emptyList()) }
    }

    @Test
    fun `transferred segments carry a target of their own engine`() {
        assertFailsWith<IllegalArgumentException> { TestSegment.copy(handoff = null) }
        assertFailsWith<IllegalArgumentException> { TestSegment.copy(target = TestTarget.copy(engine = EngineId("x"))) }
        assertFailsWith<IllegalArgumentException> { TestConversation.current.copy(target = TestTarget) }
    }

    @Test
    fun `only settled handoffs complete a transfer`() {
        val pending = TestSegment.copy(handoff = TestSegment.handoff?.copy(status = HandoffStatus.Pending))
        assertFailsWith<IllegalArgumentException> {
            TransferResult.Completed(TestRequest.transfer, TestConversation.id, pending)
        }
        assertFailsWith<IllegalArgumentException> {
            SessionTransferIntent.Internal.Seeded(TestRequest.transfer, TestConversation.id, pending)
        }
    }

    @Test
    fun `journal records and results roundtrip`() {
        val conversation = TestConversation + TestSegment
        assertEquals(conversation, Json.decodeFromString<LogicalConversation>(Json.encodeToString(conversation)))
        val results = listOf<TransferResult>(
            TransferResult.Completed(TestRequest.transfer, conversation.id, TestSegment),
            TransferResult.Failed(
                TestRequest.transfer,
                TransferFailure.Engine(EngineFailure.Request(RequestFailureReason.Invalid)),
            ),
            TransferResult.Cancelled(TestRequest.transfer),
        )
        results.forEach { assertEquals(it, Json.decodeFromString<TransferResult>(Json.encodeToString(it))) }
    }

    @Test
    fun `transfer toggle is disabled by default`() {
        assertEquals("ai.session_transfer", SessionEngineTransfer.key)
        assertEquals(false, SessionEngineTransfer.default)
    }
}
