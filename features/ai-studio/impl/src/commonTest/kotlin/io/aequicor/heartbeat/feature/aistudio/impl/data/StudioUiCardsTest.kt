package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentEvent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.apply
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toTranscriptUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioUiCardsTest {
    private val now = Instant.fromEpochSeconds(100)
    private val call = ToolCallId("call_0")

    @Test
    fun `a hosted ui_show result anchors its card on the reply`() {
        val items = listOf(invocation("mcp__heartbeat_tools__ui_show"), result(ServiceUiTools.shown("c_map")))
        val reply = items.toStudioMessages(now, false).filterIsInstance<StudioMessage.Reply>().single()

        assertEquals("c_map", reply.tools.single().shownCardId)
        assertEquals(listOf("c_map"), (reply.toUi() as MessageUi.Reply).cardIds)
    }

    @Test
    fun `arguments failures and other tools cannot show a card`() {
        val forged = invocation("ui_show").copy(arguments = ServiceUiTools.shown("c_forged"))
        assertNull(listOf(forged).shownCardId())
        assertNull(listOf(forged, result("Not shown: invalid spec")).shownCardId())
        assertNull(listOf(invocation("read_file"), result(ServiceUiTools.shown("c_forged"))).shownCardId())
        assertNull(
            listOf(
                invocation("ui_show"),
                result(ServiceUiTools.shown("c_forged")).copy(failure = EngineFailure.Unknown()),
            )
                .shownCardId(),
        )
    }

    @Test
    fun `legacy library receipts collapse at their first anchor while inline snapshots stay separate`() {
        val items = showItems(0, "old", "{\"surface\":\"harness:one:orbit\"}") +
            showItems(1, "note", "{\"spec\":{\"version\":1}}") +
            showItems(2, "current", "{\"surface\":\"harness:two:renamed\"}") +
            showItems(3, "current", "{\"surface\":\"harness:two:renamed\"}")
        val replies = items.toStudioMessages(now, false).toTranscriptUi().filterIsInstance<MessageUi.Reply>()
        assertEquals(listOf("current"), replies[0].cardIds)
        assertEquals(listOf("old", "current"), replies[0].liveCardAliases)
        assertEquals(listOf("note"), replies[1].cardIds)
        assertTrue(replies.drop(2).all { it.cardIds.isEmpty() && it.liveCardAliases.isEmpty() })
        assertEquals("invocation-0", replies[0].id)
    }

    @Test
    fun `live receipt identity changes keep the first anchor during streaming and after history reload`() {
        fun streamed(reply: String, card: String) = StudioMessage.Reply(reply, now, isStreaming = true)
            .apply(AgentEvent.ToolStarted("call-$reply", "ui_show"))
            .apply(AgentEvent.ToolOutput("call-$reply", ServiceUiTools.shown(card, isLive = true)))
            .apply(AgentEvent.ToolFinished("call-$reply", "ui_show", isSuccess = true))
        val live = listOf(streamed("first", "old"), streamed("second", "new")).toTranscriptUi()
            .filterIsInstance<MessageUi.Reply>()
        assertEquals(listOf("new"), live[0].cardIds)
        assertEquals(listOf("old", "new"), live[0].liveCardAliases)
        assertTrue(live[1].cardIds.isEmpty())
        val history = showItems(0, "old", "{}", isLive = true) + showItems(1, "new", "{}", isLive = true)
        val restored = history.toStudioMessages(now, false).toTranscriptUi().filterIsInstance<MessageUi.Reply>()
        assertEquals(live.map { it.cardIds }, restored.map { it.cardIds })
        assertEquals(live.map { it.liveCardAliases }, restored.map { it.liveCardAliases })
    }

    @Test
    fun `library arguments and failed live results never hide an existing successful app`() {
        val first = showItems(0, "good", "{}", isLive = true)
        val failure = showItems(1, "failed", "{\"surface\":\"another\"}", isLive = true).map { item ->
            if (item is SessionItem.ToolResult) item.copy(failure = EngineFailure.Unknown()) else item
        }
        val pending = showItems(2, "pending", "{\"surface\":\"another\"}").take(1)
        val replies = (first + failure + pending).toStudioMessages(now, true).toTranscriptUi()
            .filterIsInstance<MessageUi.Reply>()
        assertEquals(listOf("good"), replies.flatMap { it.cardIds })
        assertEquals(listOf("good"), replies.flatMap { it.liveCardAliases })
    }

    @Test
    fun `a live ui_show shows its card once it succeeds`() {
        val started = StudioMessage.Reply("r", now, isStreaming = true)
            .apply(AgentEvent.ToolStarted("t", "ui_show"))
            .apply(AgentEvent.ToolOutput("t", ServiceUiTools.shown("c_live")))
        assertNull(started.tools.single().shownCardId)

        val failed = started.apply(AgentEvent.ToolFinished("t", "ui_show", isSuccess = false))
        assertNull(failed.tools.single().shownCardId)

        val shown = started.apply(AgentEvent.ToolFinished("t", "ui_show", isSuccess = true))
        assertEquals("c_live", shown.tools.single().shownCardId)
    }

    private fun List<SessionItem>.shownCardId(): String? = toStudioMessages(now, false)
        .filterIsInstance<StudioMessage.Reply>().single().tools.single().shownCardId

    private fun invocation(name: String) =
        SessionItem.ToolCall(info("call", 0), call, name, "{}", ToolCallStatus.Succeeded)

    private fun result(text: String) = SessionItem.ToolResult(info("result", 1), call, listOf(ContentPart.Text(text)))

    private fun info(id: String, position: Long) = ItemInfo(ItemId(id), position, revision = 0, turn = null)

    private fun showItems(index: Int, card: String, arguments: String, isLive: Boolean = false): List<SessionItem> {
        val turn = TurnId("turn-$index")
        val callId = ToolCallId("call-$index")
        return listOf(
            SessionItem.ToolCall(
                info("invocation-$index", index * 2L).copy(turn = turn),
                callId,
                "ui_show",
                arguments,
                ToolCallStatus.Succeeded,
            ),
            SessionItem.ToolResult(
                info("result-$index", index * 2L + 1).copy(turn = turn),
                callId,
                listOf(ContentPart.Text(ServiceUiTools.shown(card, isLive))),
            ),
        )
    }
}
