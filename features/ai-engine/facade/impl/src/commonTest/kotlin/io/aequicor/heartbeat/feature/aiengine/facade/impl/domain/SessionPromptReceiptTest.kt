package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptPreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptReceipt
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.composeSessionPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionPromptReceiptTest {
    private val context = SessionHookContext(
        SessionRef(EngineId("engine"), SessionSourceId("source"), "native"),
        null,
        RequestId("request"),
        TurnId("turn"),
        SessionOwner("owner"),
    )

    @Test
    fun `whole receipted blocks precede notes and omitted blocks never receive acceptance`() {
        val included = PromptReceiptFixture()
        val omitted = PromptReceiptFixture()
        val result = assertNotNull(
            composeSessionPrompt(
                listOf(
                    SessionPromptAddition("legacy note"),
                    SessionPromptAddition("whole", included),
                    SessionPromptAddition("too much for the remaining space", omitted),
                ),
                limit = 10,
            ),
        )
        assertEquals("whole\n\nleg", result.text)
        assertEquals(1, omitted.discards)
        result.receipts.forEach { it.accepted(context, "revision") }
        result.receipts.forEach { it.accepted(context, "revision") }
        assertEquals<List<Pair<SessionHookContext, String?>>>(listOf(context to "revision"), included.accepted)
        assertTrue(omitted.accepted.isEmpty())
    }

    @Test
    fun `native receipt requires exact owned turn and request and survives owner closure`() = runTest {
        val receipt = PromptReceiptFixture()
        var revision = "before"
        val hooks = object : SessionHooks {
            override suspend fun preparePrompt(
                context: SessionHookContext,
                text: String,
                contextRevision: String?,
            ): SessionPromptPreparation? {
                assertEquals("before", contextRevision)
                return composeSessionPrompt(listOf(SessionPromptAddition("whole", receipt)))
            }
        }
        val handle = SessionHookHandle(hooks, context, { revision }) {}
        val other = SessionHookHandle(hooks, context.copy(owner = SessionOwner("other"))) {}
        val turn = Turn(TurnId("turn"), RequestId("request"), TestTarget)
        val prepared = handle.prepare(PromptRequest(checkNotNull(turn.request), listOf(ContentPart.Text("user"))), turn)
        assertEquals("user\n\nwhole", (prepared.parts.single() as ContentPart.Text).text)
        other.accepted(turn)
        handle.accepted(turn.copy(request = RequestId("foreign")))
        assertTrue(receipt.accepted.isEmpty())
        handle.closed()
        revision = "after"
        handle.accepted(turn)
        handle.accepted(turn)
        assertEquals<List<Pair<SessionHookContext, String?>>>(listOf(context to "after"), receipt.accepted)
    }

    @Test
    fun `definitive refusal discards receipt and a later accepted event cannot confirm it`() = runTest {
        val receipt = PromptReceiptFixture()
        val hooks = object : SessionHooks {
            override suspend fun preparePrompt(
                context: SessionHookContext,
                text: String,
                contextRevision: String?,
            ): SessionPromptPreparation? = composeSessionPrompt(listOf(SessionPromptAddition("whole", receipt)))
        }
        val handle = SessionHookHandle(hooks, context) {}
        val turn = Turn(TurnId("turn"), RequestId("request"), TestTarget)
        handle.prepare(PromptRequest(checkNotNull(turn.request), listOf(ContentPart.Text("user"))), turn)
        handle.refused(turn.id)
        handle.accepted(turn)
        assertEquals(1, receipt.discards)
        assertTrue(receipt.accepted.isEmpty())
    }
}

private class PromptReceiptFixture : SessionPromptReceipt {
    val accepted = mutableListOf<Pair<SessionHookContext, String?>>()
    var discards = 0
    override fun accepted(context: SessionHookContext, contextRevision: String?) {
        accepted += context to contextRevision
    }
    override fun discarded() {
        discards++
    }
}
