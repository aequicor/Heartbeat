package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoogMaskingTest {
    private val output = "o".repeat(1_000)
    private val items = (1..5).flatMap { turn ->
        listOf(
            SessionItem.ToolCall(
                info("c$turn", turn),
                ToolCallId("call$turn"),
                "read_file",
                "{}",
                ToolCallStatus.Succeeded,
            ),
            SessionItem.ToolResult(info("r$turn", turn), ToolCallId("call$turn"), listOf(ContentPart.Text(output))),
        )
    }

    @Test
    fun `older tool results keep their beginning and a note the newest turns stay verbatim`() {
        val masked = items.masked(ContextPolicy(keepRecentTurns = 2, toolStubChars = 50))

        val results = masked.filterIsInstance<SessionItem.ToolResult>()
            .map { (it.parts.single() as ContentPart.Text).text }
        val stub = "o".repeat(50) + "\n[950 more characters"
        assertTrue(results.take(3).all { it.startsWith(stub) && "read_file" in it })
        assertEquals(listOf(output, output), results.drop(3))
        assertEquals(items.filterIsInstance<SessionItem.ToolCall>(), masked.filterIsInstance<SessionItem.ToolCall>())
    }

    @Test
    fun `masking advances in steps so the prompt prefix stays stable`() {
        val policy = ContextPolicy(keepRecentTurns = 2, toolStubChars = 50, maskStepTurns = 2)

        val masked = items.masked(policy).filterIsInstance<SessionItem.ToolResult>()

        assertEquals(2, masked.count { (it.parts.single() as ContentPart.Text).text != output })
    }

    @Test
    fun `without a policy or with few turns the context is unchanged`() {
        assertSame(items, items.masked(null))
        assertSame(items, items.masked(ContextPolicy(keepRecentTurns = 5, toolStubChars = 50)))
    }

    private fun info(id: String, turn: Int) = ItemInfo(ItemId(id), id.drop(1).toLong(), 0, TurnId("t$turn"))
}
