package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
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
    fun `another session an edited history or new labels rebuild the timeline`() {
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

    @Test
    fun `worktree cards join the transcript at invocation and later entries stream below them`() {
        val cache = TimelineCache()
        val cards = listOf(card("build:compile"), card("build:test"))
        val retained = persistentSetOf("build:compile", "build:test")
        val first = cache.update(listOf(prompt, reply("partial", isStreaming = true)), labels, cards, retained)
        assertEquals(listOf("m1", "m2", "build:compile", "build:test"), first.messages.map { it.id })
        val repeated = listOf(prompt, reply("partial", isStreaming = true))
        assertSame(first, cache.update(repeated, labels, cards, retained))

        val streamed = cache.update(
            listOf(prompt, reply("partial answer", isStreaming = true)),
            labels,
            cards,
            retained,
        )
        assertSame(first.messages.first(), streamed.messages.first())
        assertEquals("partial answer", streamed.messages[1].text)
        assertEquals(listOf("m1", "m2", "build:compile", "build:test"), streamed.messages.map { it.id })

        val finished = listOf(prompt, reply("partial answer", isStreaming = false), stopped("m3", 4))
        val appended = cache.update(finished, labels, cards.takeLast(1), retained)
        assertEquals(listOf("m1", "m2", "build:compile", "build:test", "m3"), appended.messages.map { it.id })
    }

    @Test
    fun `returning to a session restores card positions and retained builds with a fresh cache`() {
        val state = StudioTimelineState()
        val cards = listOf(card("worktree:chat"), card("build:compile"), card("build:test"))
        val retained = setOf("build:compile", "build:test")
        TimelineCache(state.session("chat")).update(listOf(prompt), labels, cards, retained)

        // A different feed must not inherit cards or invocation points from the session just left.
        val other = TimelineCache(state.session("other")).update(listOf(prompt), labels, listOf(card("worktree:other")))
        assertEquals(listOf("m1", "worktree:other"), other.messages.map { it.id })

        val failed = cards.first().copy(text = "Failed")
        val restored = TimelineCache(state.session("chat")).update(
            listOf(prompt, reply("arrived while hidden", isStreaming = false)),
            labels.copy(you = "Reader"),
            listOf(failed, cards.last()),
            retained,
        )
        assertEquals(
            listOf("m1", "worktree:chat", "build:compile", "build:test", "m2"),
            restored.messages.map { it.id },
        )
        assertEquals("Failed", restored.messages[1].text)
        assertEquals("Reader", restored.messages.first().author)
    }

    @Test
    fun `a surviving cache restores cards introduced by a discarded replacement cache`() {
        val placement = TimelineWeaves()
        val surviving = TimelineCache(placement)
        surviving.update(listOf(prompt), labels)
        val messages = listOf(prompt, reply("Done", isStreaming = false))
        val cards = listOf(card("build:compile"))
        TimelineCache(placement).update(messages, labels, cards)

        val updated = surviving.update(messages, labels, cards)
        assertEquals(listOf("m1", "m2", "build:compile"), updated.messages.map { it.id })
        assertSame(updated, surviving.update(messages, labels, cards))
    }

    @Test
    fun `unchanged inputs still synchronize cards refreshed by another cache`() {
        val placement = TimelineWeaves()
        val surviving = TimelineCache(placement)
        val cards = listOf(card("build:compile"))
        surviving.update(listOf(prompt), labels, cards)
        val completed = cards.single().copy(text = "Completed")
        TimelineCache(placement).update(listOf(prompt), labels, listOf(completed))

        val updated = surviving.update(listOf(prompt), labels, nextRetained = setOf(completed.id))
        assertEquals(completed, updated.messages.last())
        TimelineCache(placement).update(
            listOf(prompt),
            labels,
            listOf(completed.copy(text = "Failed")),
        )
        val refreshed = surviving.update(listOf(prompt), labels, nextRetained = setOf(completed.id))
        assertEquals("Failed", refreshed.messages.last().text)
    }

    @Test
    fun `woven cards refresh in place and retire once neither offered nor retained`() {
        val cache = TimelineCache()
        val running = card("build:compile")
        val first = cache.update(
            listOf(prompt),
            labels,
            listOf(running, card("worktree:chat")),
            nextRetained = persistentSetOf("build:compile"),
        )
        assertEquals(listOf("m1", "build:compile", "worktree:chat"), first.messages.map { it.id })

        val failedCall = assertIs<HbMessagePart.Tool>(running.parts.single()).call.copy(status = HbToolStatus.Error)
        val failed = running.copy(parts = persistentListOf(HbMessagePart.Tool(failedCall)))
        val updated = cache.update(
            listOf(prompt),
            labels,
            listOf(failed, card("worktree:chat")),
            nextRetained = persistentSetOf("build:compile"),
        )
        assertNotSame(first, updated)
        assertEquals(listOf("m1", "build:compile", "worktree:chat"), updated.messages.map { it.id })
        val call = assertIs<HbMessagePart.Tool>(updated.messages[1].parts.single()).call
        assertEquals(HbToolStatus.Error, call.status)

        val cleared = cache.update(listOf(prompt), labels)
        assertEquals(listOf("m1"), cleared.messages.map { it.id })
    }

    @Test
    fun `a rebuild replays woven cards at their invocation points`() {
        val cache = TimelineCache()
        val cards = listOf(card("build:compile"))
        val retained = persistentSetOf("build:compile")
        val before = listOf(prompt, reply("one", isStreaming = false))
        cache.update(before, labels, cards, retained)
        val grown = cache.update(before + stopped("m3", 4), labels, cards, retained)
        assertEquals(listOf("m1", "m2", "build:compile", "m3"), grown.messages.map { it.id })

        val translated = cache.update(before + stopped("m3", 4), labels.copy(you = "Reader"), cards, retained)
        assertEquals(listOf("m1", "m2", "build:compile", "m3"), translated.messages.map { it.id })

        val edited = cache.update(
            listOf(prompt, reply("one!", isStreaming = false), stopped("m3", 4)),
            labels,
            cards,
            retained,
        )
        assertEquals(listOf("m1", "m2", "build:compile", "m3"), edited.messages.map { it.id })
        assertEquals("one!", edited.messages[1].text)

        val shrunk = cache.update(before, labels, cards, retained)
        assertEquals(listOf("m1", "m2", "build:compile"), shrunk.messages.map { it.id })
    }

    @Test
    fun `retiring the last card of an empty transcript leaves no orphan section`() {
        val cache = TimelineCache()
        cache.update(emptyList(), labels, listOf(card("worktree-journal-loading")))
        val cleared = cache.update(emptyList(), labels)

        assertEquals(0, cleared.messageCount)
        assertEquals(0, cleared.itemCount)
    }

    @Test
    fun `a transcript without messages shows only its worktree cards`() {
        val cache = TimelineCache()
        val grouped = labels.copy(isGroupedByDate = true)
        val cards = listOf(card("worktree:pane-7"))
        val timeline = cache.update(emptyList(), grouped, cards)
        assertEquals(listOf("worktree:pane-7"), timeline.messages.map { it.id })
        assertEquals(HbChatRole.System, timeline.messages.single().role)
        assertSame(timeline, cache.update(emptyList(), grouped, cards))
        val translated = cache.update(emptyList(), grouped.copy(section = "Сессия"), cards)
        assertNotSame(timeline, translated)
        assertEquals(cards, translated.messages)
    }

    private fun card(id: String) = HbChatMessage(
        id,
        "Studio",
        "",
        role = HbChatRole.System,
        kind = HbMessageKind.Tool,
        parts = persistentListOf(HbMessagePart.Tool(HbToolCall(id, id, kind = HbToolKind.Worktree))),
    )

    private fun reply(text: String, isStreaming: Boolean) =
        MessageUi.Reply("m2", Instant.fromEpochSeconds(1), text, persistentListOf(), isStreaming)

    private fun stopped(id: String, seconds: Int) = MessageUi.Stopped(id, Instant.fromEpochSeconds(2), seconds.seconds)
}
