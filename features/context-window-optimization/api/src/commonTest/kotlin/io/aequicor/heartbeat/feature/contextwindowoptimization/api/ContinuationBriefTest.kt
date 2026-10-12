package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuationBriefTest {
    private var position = 0L

    @Test
    fun `every user message is kept verbatim and in order`() {
        val items = listOf(user("Fix every open issue"), assistant("On it"), user("Skip #7"), assistant("Done #1"))

        val brief = continuationBrief(items, items, summary = "Fixed #1", BriefMode.Rollover)

        val first = brief.text.indexOf("[1] Fix every open issue")
        val second = brief.text.indexOf("[2] Skip #7")
        assertTrue(first in 0 until second)
        assertEquals(2, brief.userMessages)
        assertEquals(0, brief.omittedUserMessages)
    }

    @Test
    fun `service messages without user text are skipped and directives stripped`() {
        val items = listOf(
            user("Task\n\n" + hostDirective("learned hint")),
            user(hostDirective("scheduled wake note")),
            message(MessageRole.User, ContentPart.Text("See"), ContentPart.Image(ResourceRef("img", "image/png"))),
        )

        val brief = continuationBrief(items, items, summary = null, BriefMode.AfterNativeCompaction)

        assertFalse("hint" in brief.text)
        assertFalse("wake note" in brief.text)
        assertTrue("[1] Task" in brief.text)
        assertTrue("[2] See\n[image]" in brief.text)
        assertEquals(2, brief.userMessages)
    }

    @Test
    fun `user messages of earlier segments are kept while the last turn comes from the current one`() {
        val earlier = listOf(user("Original task"), assistant("old answer"))
        val current = listOf(user("Continue"), assistant("new answer"))

        val brief = continuationBrief(earlier + current, current, summary = "s", BriefMode.Rollover)

        assertTrue("[1] Original task" in brief.text)
        assertTrue("[2] Continue" in brief.text)
        assertFalse("old answer" in brief.text)
        assertTrue("Assistant: new answer" in brief.lastTurn())
    }

    @Test
    fun `the last turn is everything after the newest user message`() {
        val items = listOf(
            user("First"),
            assistant("first answer"),
            user("Second"),
            SessionItem.ToolCall(info(), ToolCallId("c"), "shell", """{"cmd":"secret"}""", ToolCallStatus.Succeeded),
            SessionItem.ToolResult(info(), ToolCallId("c"), listOf(ContentPart.Text("r".repeat(5_000)))),
            assistant("Half done"),
        )

        val lastTurn = continuationBrief(items, items, summary = "s", BriefMode.Rollover).lastTurn()

        assertTrue("answering the newest user message" in lastTurn)
        assertTrue("Tool call: shell (succeeded)" in lastTurn)
        assertTrue("Assistant: Half done" in lastTurn)
        assertFalse("first answer" in lastTurn)
        assertFalse("secret" in lastTurn)
        assertTrue(lastTurn.length < 2_000)
    }

    @Test
    fun `a host-started turn is marked as such`() {
        val items = listOf(user("Task"), user(hostDirective("continue")), assistant("Resumed"))

        val lastTurn = continuationBrief(items, items, summary = null, BriefMode.AfterNativeCompaction).lastTurn()

        assertTrue("a turn started by the host" in lastTurn)
        assertTrue("Assistant: Resumed" in lastTurn)
    }

    @Test
    fun `the work summary is used only in rollover`() {
        val items = listOf(user("Task"), assistant("Earlier step"), user("Next"), assistant("Now"))

        val rollover = continuationBrief(items, items, summary = "Decided to use Ktor", BriefMode.Rollover)
        val native = continuationBrief(items, items, summary = "Decided to use Ktor", BriefMode.AfterNativeCompaction)

        assertTrue("## Work so far\nDecided to use Ktor" in rollover.text)
        assertFalse("## Work so far" in native.text)
    }

    @Test
    fun `without a summary rollover falls back to the agent's earlier messages and tool calls`() {
        val items = listOf(
            user("Task"),
            assistant("Earlier step"),
            SessionItem.ToolCall(info(), ToolCallId("c"), "read_file", "{}", ToolCallStatus.Succeeded),
            user("Next"),
            assistant("Now"),
        )

        val work = continuationBrief(items, items, summary = null, BriefMode.Rollover).section("## Work so far")

        assertTrue("No summary was written" in work)
        assertTrue("Assistant: Earlier step" in work)
        assertTrue("Tool call: read_file (succeeded)" in work)
        assertFalse("User:" in work)
    }

    @Test
    fun `the first message survives an overflow while the newest stay whole`() {
        val messages = (0 until 60).map { user("message $it " + "x".repeat(900)) }
        val options = BriefOptions(budgetChars = 30_000, lastTurnChars = 2_000, summaryChars = 2_000)

        val brief = continuationBrief(messages, messages, summary = "s", BriefMode.Rollover, options)

        assertTrue(brief.text.length <= options.budgetChars)
        assertTrue("[1] message 0 " in brief.text)
        assertTrue("[60] message 59 " + "x".repeat(900) in brief.text)
        assertTrue(brief.omittedUserMessages > 0)
        assertTrue("messages omitted" in brief.text)
        assertEquals(60, brief.userMessages)
    }

    @Test
    fun `older messages are shortened to snippets before they are omitted`() {
        val messages = (0 until 30).map { user("message $it " + "y".repeat(2_000)) }
        val options = BriefOptions(budgetChars = 30_000, lastTurnChars = 2_000, summaryChars = 2_000)

        val brief = continuationBrief(messages, messages, summary = "s", BriefMode.Rollover, options)

        assertTrue(brief.text.length <= options.budgetChars)
        assertTrue(" … " in brief.text)
    }

    @Test
    fun `equal input gives equal text`() {
        val items = listOf(user("Task"), assistant("Answer"))

        assertEquals(
            continuationBrief(items, items, summary = "s", BriefMode.Rollover),
            continuationBrief(items, items, summary = "s", BriefMode.Rollover),
        )
    }

    @Test
    fun `brief text never reaches toString`() {
        val items = listOf(user("secret task"))

        assertFalse("secret" in continuationBrief(items, items, summary = null, BriefMode.Rollover).toString())
    }

    private fun ContinuationBrief.lastTurn(): String = section("## Last turn")

    private fun ContinuationBrief.section(title: String): String =
        text.substringAfter("$title\n").substringBefore("\n\n## ")

    private fun user(text: String) = message(MessageRole.User, ContentPart.Text(text))

    private fun assistant(text: String) = message(MessageRole.Assistant, ContentPart.Text(text))

    private fun message(role: MessageRole, vararg parts: ContentPart) =
        SessionItem.Message(info(), role, parts.toList())

    private fun info(): ItemInfo = ItemInfo(ItemId("i$position"), position++, 0)
}
