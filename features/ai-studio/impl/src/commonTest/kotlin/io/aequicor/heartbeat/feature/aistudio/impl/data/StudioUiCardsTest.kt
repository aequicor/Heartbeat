package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentEvent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.apply
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
