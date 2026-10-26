package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.screenmodel.FailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
        compacted = "Context compacted",
        rolledOver = "Continued in a new session",
        collapsedTools = "Tool calls: %1\$d",
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
    fun `visible reasoning before running collapsed calls does not pose as the latest activity`() {
        val collapsed = reply("", isStreaming = true).copy(
            parts = persistentListOf(
                ReplyPartUi.CollapsedTools("collapsed:r", 2, isRunning = true),
                ReplyPartUi.Reasoning("reasoning", "Check the public API"),
            ),
        )
        val parts = TimelineCache().update(listOf(collapsed), labels).messages.single().parts.map {
            assertIs<HbMessagePart.Tool>(it).call
        }

        assertEquals(listOf(HbToolStatus.Running, HbToolStatus.Complete), parts.map { it.status })
        assertEquals("Tool calls: 2", parts.first().title)
    }

    @Test
    fun `an organism capture is a host message with the mergeable snapshot`() {
        val result = MessageUi.OrganismResult("result", "branch", "base", "snapshot")
        val message = result.toHb(labels.copy(organismResult = "%1\$s %2\$s git merge --no-ff %3\$s"))
        assertEquals("Studio", message.author)
        assertEquals(HbChatRole.System, message.role)
        assertEquals(HbMessageKind.Markdown, message.kind)
        assertEquals("branch base git merge --no-ff snapshot", message.text)
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
    fun `connection failures keep their recovery notice in the timeline`() {
        val recovery = labels.copy(
            failed = FailureLabels(
                unknown = "Unknown",
                authentication = "Sign in again",
                credentialsMissing = "Add the key on this device",
                connectionChanged = "Check the selected connection",
                quota = "Check the subscription",
                access = "Check model access",
                requestRejected = "Check the server address",
                engineUnavailable = "Install or restart the engine",
                network = "Check the connection",
                serviceUnavailable = "Try the server later",
            ),
        )
        val failures = listOf(
            FailureUi.Authentication,
            FailureUi.CredentialsMissing,
            FailureUi.ConnectionChanged,
            FailureUi.Quota,
            FailureUi.Access,
            FailureUi.RequestRejected,
            FailureUi.EngineUnavailable,
            FailureUi.Network,
            FailureUi.ServiceUnavailable,
        )
        val messages = TimelineCache().update(
            failures.map { MessageUi.Failed(it.name, Instant.fromEpochSeconds(0), it) },
            recovery,
        ).messages
        assertEquals(
            listOf(
                "Sign in again",
                "Add the key on this device",
                "Check the selected connection",
                "Check the subscription",
                "Check model access",
                "Check the server address",
                "Install or restart the engine",
                "Check the connection",
                "Try the server later",
            ),
            messages.map { it.text },
        )
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

    @Test
    fun `a build card follows the run_build call that started it inside the reply`() {
        val cache = TimelineCache()
        fun tool(id: String, title: String, output: String) =
            ReplyPartUi.Tool(ToolUi(id, title, ToolStatusUi.Done, output, null))
        val turn = reply("", isStreaming = true).copy(
            parts = persistentListOf(
                tool("t1", "run_build", "{\"id\":\"op1\",\"command\":\"verify\"}"),
                tool("t2", "build_status", "{\"id\":\"op1\",\"phase\":\"Running\"}"),
            ),
        )
        val running = card("build:op1")
        val timeline = cache.update(
            listOf(prompt, turn),
            labels,
            persistentListOf(running),
            persistentSetOf("build:op1"),
        )
        assertEquals(listOf("m1", "m2"), timeline.messages.map { it.id })
        val ids = timeline.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:op1", "t2"), ids)

        val finished = running.copy(
            parts = persistentListOf(HbMessagePart.Tool(HbToolCall("build:op1", "done", kind = HbToolKind.Worktree))),
        )
        val updated = cache.update(
            listOf(prompt, turn),
            labels,
            persistentListOf(finished),
            persistentSetOf("build:op1"),
        )
        val call = assertIs<HbMessagePart.Tool>(updated.messages.last().parts[1]).call
        assertEquals("done", call.title)
    }

    @Test
    fun `a build card follows the run_build call that still waits for it`() {
        val cache = TimelineCache()
        fun tool(id: String, status: ToolStatusUi, output: String) =
            ReplyPartUi.Tool(ToolUi(id, "run_build", status, output, null))
        val earlier = tool("t1", ToolStatusUi.Done, "{\"id\":\"op0\",\"phase\":\"Completed\"}")
        fun turn(last: ReplyPartUi.Tool) = reply("", isStreaming = true).copy(parts = persistentListOf(earlier, last))
        val cards = persistentListOf(card("build:op0"), card("build:stale"), card("build:op1"))
        val retained = persistentSetOf("build:op0", "build:stale", "build:op1")

        val waiting = cache.update(listOf(prompt, turn(tool("t2", ToolStatusUi.Running, ""))), labels, cards, retained)
        val parts = waiting.messages.single { it.id == "m2" }.parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:op0", "t2", "build:op1"), parts)
        assertTrue(waiting.messages.any { it.id == "build:stale" })

        val done = tool("t2", ToolStatusUi.Done, "{\"id\":\"op1\",\"phase\":\"Completed\"}")
        val finished = cache.update(listOf(prompt, turn(done)), labels, cards, retained)
        val after = finished.messages.single { it.id == "m2" }.parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:op0", "t2", "build:op1"), after)
    }

    @Test
    fun `a build card follows the run_build call the engine cut off by the command it ran`() {
        val cache = TimelineCache()
        val cut = ReplyPartUi.Tool(
            ToolUi("t1", "run_build", ToolStatusUi.Failed, "{\"command\": \"wiring\"}\nThe operation timed out.", null),
        )
        val status = ReplyPartUi.Tool(ToolUi("t2", "build_status", ToolStatusUi.Failed, "UnknownBuildOperation", null))
        val turn = reply("", isStreaming = true).copy(parts = persistentListOf(cut, status))
        val cards = persistentListOf(
            card("build:old", "wiring"),
            card("build:check", "check"),
            card("build:op9", "wiring"),
        )
        val retained = persistentSetOf("build:old", "build:check", "build:op9")

        val timeline = cache.update(listOf(prompt, turn), labels, cards, retained)
        val parts = timeline.messages.single { it.id == "m2" }.parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:op9", "t2"), parts)
        assertTrue(timeline.messages.any { it.id == "build:check" } && timeline.messages.any { it.id == "build:old" })
    }

    @Test
    fun `a waiting run_build call never takes a build card that appeared before its reply`() {
        val cache = TimelineCache()
        val done = reply("Built", isStreaming = false)
        val older = card("build:old")
        cache.update(listOf(prompt, done), labels, listOf(older), setOf(older.id))

        val waiting = ReplyPartUi.Tool(ToolUi("t2", "run_build", ToolStatusUi.Running, "", null))
        val next = MessageUi.Reply("m4", Instant.fromEpochSeconds(3), "", persistentListOf(), isStreaming = true)
            .copy(parts = persistentListOf(waiting))
        val messages = listOf(prompt, done, MessageUi.Prompt("m3", Instant.fromEpochSeconds(2), "Again"), next)
        val kept = cache.update(messages, labels, listOf(older), setOf(older.id))
        assertEquals(listOf("m1", "m2", "build:old", "m3", "m4"), kept.messages.map { it.id })
        assertEquals(listOf("t2"), kept.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id })

        val newer = card("build:new")
        val anchored = cache.update(messages, labels, listOf(older, newer), setOf(older.id, newer.id))
        assertEquals(listOf("m1", "m2", "build:old", "m3", "m4"), anchored.messages.map { it.id })
        val parts = anchored.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t2", "build:new"), parts)
    }

    @Test
    fun `a build card shown before the transcript loaded still joins the call waiting for it`() {
        val cache = TimelineCache()
        val early = card("build:early")
        val empty = cache.update(emptyList(), labels, listOf(early), setOf(early.id))
        assertEquals(listOf("build:early"), empty.messages.map { it.id })

        val waiting = ReplyPartUi.Tool(ToolUi("t1", "run_build", ToolStatusUi.Running, "", null))
        val turn = reply("", isStreaming = true).copy(parts = persistentListOf(waiting))
        val loaded = cache.update(listOf(prompt, turn), labels, listOf(early), setOf(early.id))
        assertEquals(listOf("m1", "m2"), loaded.messages.map { it.id })
        val parts = loaded.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:early"), parts)
    }

    @Test
    fun `a build card shown before the transcript loaded does not join a call of a later turn`() {
        val placement = TimelineWeaves()
        val cache = TimelineCache(placement)
        val early = card("build:early")
        cache.update(emptyList(), labels, listOf(early), setOf(early.id))
        val loaded = listOf(prompt, reply("Done", isStreaming = false))
        cache.update(loaded, labels, listOf(early), setOf(early.id))
        assertEquals(mapOf("build:early" to 1), placement.loadedWith)

        val waiting = ReplyPartUi.Tool(ToolUi("t2", "run_build", ToolStatusUi.Running, "", null))
        val later = reply("", isStreaming = true).copy(id = "m4", parts = persistentListOf(waiting))
        val next = loaded + MessageUi.Prompt("m3", Instant.fromEpochSeconds(3), "Build again") + later
        val timeline = cache.update(next, labels, listOf(early), setOf(early.id))
        assertEquals(listOf("t2"), timeline.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id })
        assertEquals(listOf("build:early", "m1", "m2", "m3", "m4"), timeline.messages.map { it.id })
        assertEquals(setOf("build:early"), placement.shown)
    }

    @Test
    fun `cards drawn inside a reply count as shown for the host`() {
        val placement = TimelineWeaves()
        val cache = TimelineCache(placement)
        val call = ReplyPartUi.Tool(ToolUi("t1", "run_build", ToolStatusUi.Done, "{\"id\":\"op1\"}", null))
        val turn = reply("", isStreaming = true).copy(parts = persistentListOf(call))
        cache.update(listOf(prompt, turn), labels, listOf(card("build:op1"), card("worktree:chat")))

        assertEquals(setOf("worktree:chat"), placement.cards.keys)
        assertEquals(setOf("build:op1", "worktree:chat"), placement.shown)
    }

    @Test
    fun `a card leaving its reply returns to the point it appeared at`() {
        val cache = TimelineCache()
        fun turn(status: ToolStatusUi, output: String) = reply("", isStreaming = true).copy(
            parts = persistentListOf(ReplyPartUi.Tool(ToolUi("t1", "run_build", status, output, null))),
        )
        val borrowed = card("build:a")
        val first = cache.update(listOf(prompt, turn(ToolStatusUi.Running, "")), labels, listOf(borrowed))
        assertEquals(listOf("m1", "m2"), first.messages.map { it.id })
        assertEquals(
            listOf("t1", "build:a"),
            first.messages.last().parts.map { assertIs<HbMessagePart.Tool>(it).call.id },
        )

        val own = card("build:b")
        val messages = listOf(prompt, turn(ToolStatusUi.Done, "{ \"id\" : \"b\" }"), stopped("m3", 4))
        val cards = listOf(borrowed, own)
        val returned = cache.update(messages, labels, cards)
        assertEquals(listOf("m1", "m2", "build:a", "m3"), returned.messages.map { it.id })
        val parts = returned.messages[1].parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:b"), parts)
        assertSame(returned, cache.update(messages, labels, cards))
    }

    private fun card(id: String, command: String = id) = HbChatMessage(
        id,
        "Studio",
        "",
        role = HbChatRole.System,
        kind = HbMessageKind.Tool,
        parts = persistentListOf(HbMessagePart.Tool(HbToolCall(id, command, kind = HbToolKind.Worktree))),
    )

    private fun reply(text: String, isStreaming: Boolean) =
        MessageUi.Reply("m2", Instant.fromEpochSeconds(1), text, persistentListOf(), isStreaming)

    private fun stopped(id: String, seconds: Int) = MessageUi.Stopped(id, Instant.fromEpochSeconds(2), seconds.seconds)
}
