package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StudioTimelineTest {
    private val durations = DurationLabels(secondsTemplate = "%1\$d s", minutesTemplate = "%1\$d min %2\$d s")
    private val labels = TimelineLabels(
        section = "Session",
        you = "You",
        agent = "Agent",
        studio = "Studio",
        stoppedTemplate = "Stopped after %1\$s",
        failed = FailureLabels(unknown = "Failed", limit = "Limit"),
        durations = durations,
    )
    private val prompt = MessageUi.Prompt("m1", Instant.fromEpochSeconds(0), "Fix the build")

    @Test
    fun `the latest reasoning remains running until answer text or turn completion arrives`() {
        val cache = TimelineCache()
        val thinking = reply("", isStreaming = true).copy(
            parts = persistentListOf(ReplyPartUi.Reasoning("reasoning", "Check the public API")),
        )
        val running = cache.update(listOf(thinking), labels).messages.single()
        assertEquals(HbToolStatus.Running, assertIs<HbMessagePart.Tool>(running.parts.single()).call.status)

        val answering = thinking.copy(
            parts = persistentListOf(thinking.parts.single(), ReplyPartUi.Text("answer", "Answer")),
        )
        val answered = cache.update(listOf(answering), labels).messages.single()
        assertEquals(HbToolStatus.Complete, assertIs<HbMessagePart.Tool>(answered.parts.first()).call.status)
        assertEquals(HbMessageStatus.Streaming, answered.status)

        val completed = cache.update(listOf(thinking.copy(isStreaming = false)), labels).messages.single()
        assertEquals(HbToolStatus.Complete, assertIs<HbMessagePart.Tool>(completed.parts.single()).call.status)
    }

    @Test
    fun `failed runs show the notice of their failure class`() {
        val at = Instant.fromEpochSeconds(0)
        val messages = TimelineCache().update(
            listOf(MessageUi.Failed("f1", at, FailureUi.Limit), MessageUi.Failed("f2", at)),
            labels,
        ).messages
        assertEquals(listOf("Limit", "Failed"), messages.map { it.text })
    }

    @Test
    fun `durations show seconds below a minute and minutes with seconds above`() {
        assertEquals("0 s", durations.format((-5).seconds))
        assertEquals("59 s", durations.format(59.seconds))
        assertEquals("1 min 1 s", durations.format(61.seconds))
        assertEquals("a 2 a", fill("%2\$s %1\$d %2\$s", 2, "a"))
        assertEquals("x-3", fill("%1\$s-%2\$d", "x", 3))
    }

    @Test
    fun `a streamed chunk replaces only the latest message and new entries are appended`() {
        val cache = TimelineCache()
        cache.update(listOf(prompt, reply("partial", isStreaming = true)), labels)
        val streamed = cache.update(listOf(prompt, reply("partial answer", isStreaming = true)), labels)
        assertEquals(listOf("You", "Agent"), streamed.messages.map { it.author })
        assertEquals("partial answer", streamed.messages.last().text)
        assertEquals(HbMessageStatus.Streaming, streamed.messages.last().status)

        val finished = cache.update(
            listOf(prompt, reply("partial answer", isStreaming = false), stopped("m3", 61)),
            labels,
        )
        assertEquals(listOf("m1", "m2", "m3"), finished.messages.map { it.id })
        assertEquals(HbMessageStatus.Complete, finished.messages[1].status)
        assertEquals("Stopped after 1 min 1 s", finished.messages.last().text)
    }

    @Test
    fun `repeating the same input keeps the prepared timeline`() {
        val cache = TimelineCache()
        val messages = listOf(prompt, reply("done", isStreaming = false))
        val first = cache.update(messages, labels)
        assertSame(first, cache.update(messages, labels))
    }

    @Test
    fun `another session, an edited history or new labels rebuild the timeline`() {
        val cache = TimelineCache()
        cache.update(listOf(prompt, reply("one", isStreaming = false)), labels)

        val swapped = cache.update(listOf(prompt, stopped("m9", 3)), labels)
        assertEquals(listOf("m1", "m9"), swapped.messages.map { it.id })

        val edited = cache.update(listOf(prompt.copy(text = "Edited"), stopped("m9", 3)), labels)
        assertEquals("Edited", edited.messages.first().text)

        val shrunk = cache.update(listOf(prompt), labels)
        assertEquals(listOf("m1"), shrunk.messages.map { it.id })

        val translated = cache.update(listOf(prompt), labels.copy(you = "Вы"))
        assertEquals("Вы", translated.messages.single().author)
    }

    private fun reply(text: String, isStreaming: Boolean) =
        MessageUi.Reply("m2", Instant.fromEpochSeconds(1), text, persistentListOf(), isStreaming)

    private fun stopped(id: String, seconds: Int) = MessageUi.Stopped(id, Instant.fromEpochSeconds(2), seconds.seconds)
}
