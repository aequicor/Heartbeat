package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class SentPromptUiTest {
    private val time = Instant.fromEpochSeconds(1)
    private val file = AttachmentUi("file", "report.pdf", "application/pdf", 512)
    private val history: ImmutableList<MessageUi> = persistentListOf(
        MessageUi.Prompt("p1", time, "Earlier"),
        MessageUi.Reply("r1", time, "Answer", persistentListOf(), isStreaming = false),
    )
    private val chat = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "chat")),
        transcripts = persistentMapOf("chat" to history),
    )

    @Test
    fun `a message sent to a chat shows in its feed at once`() {
        val sent = chat.withDraft(0, " Next step ").withAttachment().send("send")
        val feed = sent.feed()
        assertEquals(3, feed.size)
        val prompt = feed.last() as MessageUi.Prompt
        assertEquals("Next step", prompt.text)
        assertEquals(listOf(file), prompt.attachments)
        assertEquals(false, prompt.isTimestampKnown)
        // Acceptance clears the draft but the engine has not echoed the message yet.
        assertEquals(feed, sent.acceptSubmission("send").feed())
    }

    @Test
    fun `the echoed message replaces the sent one without a duplicate`() {
        val sent = chat.withDraft(0, "Next step").send("send").acceptSubmission("send")
        val echoed = sent.withTranscripts(persistentMapOf("chat" to history + prompt("p2", "Next step")))
        assertEquals(listOf("p1", "r1", "p2"), echoed.feed().map { it.id })
        assertTrue(echoed.sentPrompts.isEmpty())
    }

    @Test
    fun `a reply streamed before the echo keeps the sent message`() {
        val sent = chat.withDraft(0, "Next step").send("send").acceptSubmission("send")
        val streaming = MessageUi.Reply("r2", time, "Working", persistentListOf(), isStreaming = true)
        val next = sent.withTranscripts(persistentMapOf("chat" to (history + streaming)))
        // The copy keeps its place before the turn's reply, where the echo lands.
        assertEquals(listOf("p1", "r1", "sent:send", "r2"), next.feed().map { it.id })
        val echoed = next.withTranscripts(persistentMapOf("chat" to (history + prompt("p2", "Next step") + streaming)))
        assertEquals(listOf("p1", "r1", "p2", "r2"), echoed.feed().map { it.id })
    }

    @Test
    fun `a chat whose transcript has not loaded yet gets no copy to mistake its history for the echo`() {
        val loading = chat.copy(transcripts = persistentMapOf())
        val sent = loading.withDraft(0, "Next step").send("send")
        assertTrue(sent.sentPrompts.isEmpty())
    }

    @Test
    fun `an ended turn keeps its copy through equal transcripts and drops it once the chat closes`() {
        val ended = chat.withDraft(0, "Next step").send("send").acceptSubmission("send").endSentPrompts("chat")
        val reemitted = ended.withTranscripts(persistentMapOf("chat" to history.toList().toImmutableList()))
        assertEquals(1, reemitted.sentPrompts.size)
        val closed = reemitted.withTranscripts(persistentMapOf())
        assertTrue(closed.sentPrompts.isEmpty())
    }

    @Test
    fun `a running chat that closes keeps its copy until the turn ends`() {
        val sent = chat.withDraft(0, "Next step").send("send").acceptSubmission("send")
        val closed = sent.withTranscripts(persistentMapOf())
        assertEquals(1, closed.sentPrompts.size)
        val ended = closed.endSentPrompts("chat").withTranscripts(persistentMapOf())
        assertTrue(ended.sentPrompts.isEmpty())
    }

    @Test
    fun `a refused or failed submission leaves the feed and returns its draft`() {
        val sent = chat.withDraft(0, "Next step").send("send")
        val rejected = sent.rejectSubmission("send", "chat")
        assertEquals(history, rejected.feed())
        assertEquals("Next step", rejected.draft(0))
        assertTrue(rejected.sentPrompts.isEmpty())
    }

    @Test
    fun `a new chat shows the message once its session is created`() {
        val page = AiStudioScreenState(panes = persistentListOf(PaneUi(0)))
        val queued = page.withDraft(0, "Hello").send("send")
        assertTrue(queued.sentPrompts.isEmpty())
        val created = queued.prepareSubmission("send", "fresh")
            .copy(panes = persistentListOf(PaneUi(0, sessionId = "fresh")))
        assertEquals(listOf("Hello"), created.withSentPrompts("fresh", null)?.map { (it as MessageUi.Prompt).text })
        val echoed = created.withTranscripts(persistentMapOf("fresh" to persistentListOf(prompt("p1", "Hello"))))
        assertEquals(listOf("p1"), echoed.withSentPrompts("fresh", echoed.transcripts["fresh"])?.map { it.id })
    }

    @Test
    fun `the copy in an empty chat keeps its time while the screen clock ticks`() {
        val page = AiStudioScreenState(panes = persistentListOf(PaneUi(0)), now = time)
        val created = page.withDraft(0, "Hello").send("send").prepareSubmission("send", "fresh")
        val later = created.copy(now = Instant.fromEpochSeconds(61))
        assertEquals(created.withSentPrompts("fresh", null), later.withSentPrompts("fresh", null))
        assertEquals(time, later.withSentPrompts("fresh", null)?.single()?.createdAt)
    }

    @Test
    fun `quick sends are replaced one echo at a time in send order`() {
        val first = chat.withDraft(0, "One").send("a").acceptSubmission("a")
        val both = first.withDraft(0, "Two").send("b").acceptSubmission("b")
        assertEquals(listOf("sent:a", "sent:b"), both.feed().drop(2).map { it.id })

        val one = both.withTranscripts(persistentMapOf("chat" to history + prompt("p2", "One")))
        assertEquals(listOf("p1", "r1", "p2", "sent:b"), one.feed().map { it.id })
        val two = one.withTranscripts(persistentMapOf("chat" to history + prompt("p2", "One") + prompt("p3", "Two")))
        assertEquals(listOf("p1", "r1", "p2", "p3"), two.feed().map { it.id })
        assertTrue(two.sentPrompts.isEmpty())
    }

    @Test
    fun `a turn that ended without an echo drops the message with the next transcript change`() {
        val sent = chat.withDraft(0, "Next step").send("send").acceptSubmission("send")
        val ended = sent.endSentPrompts("chat")
        // The final history may still be on its way: the message stays until the transcript changes.
        assertEquals(sent.feed(), ended.feed())
        assertSame(ended.sentPrompts, ended.withTranscripts(ended.transcripts).sentPrompts)

        val failed = MessageUi.Failed("failure", time)
        val next = ended.withTranscripts(persistentMapOf("chat" to (history + failed)))
        assertEquals(listOf("p1", "r1", "failure"), next.feed().map { it.id })
        assertTrue(next.sentPrompts.isEmpty())
    }

    @Test
    fun `a message after an unechoed turn waits for its own echo`() {
        val ended = chat.withDraft(0, "Lost").send("a").acceptSubmission("a").endSentPrompts("chat")
        val again = ended.withDraft(0, "Retry").send("b").acceptSubmission("b")
        assertEquals(listOf("p1", "r1", "sent:b"), again.feed().map { it.id })
        val echoed = again.withTranscripts(persistentMapOf("chat" to history + prompt("p2", "Retry")))
        assertEquals(listOf("p1", "r1", "p2"), echoed.feed().map { it.id })
    }

    private fun AiStudioScreenState.send(id: String): AiStudioScreenState =
        queueSubmission(id, pendingSubmission(0, id))

    private fun AiStudioScreenState.withAttachment(): AiStudioScreenState =
        copy(draftAttachments = persistentMapOf("chat" to persistentListOf(file)))

    private fun AiStudioScreenState.feed(): List<MessageUi> = withSentPrompts("chat", transcripts["chat"]).orEmpty()

    private fun prompt(id: String, text: String): MessageUi = MessageUi.Prompt(id, time, text)

    private operator fun ImmutableList<MessageUi>.plus(message: MessageUi): ImmutableList<MessageUi> =
        (toList() + message).toImmutableList()
}
