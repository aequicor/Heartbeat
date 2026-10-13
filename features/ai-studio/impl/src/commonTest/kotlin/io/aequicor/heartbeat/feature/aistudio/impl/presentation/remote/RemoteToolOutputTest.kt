package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeTranscriptUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class RemoteToolOutputTest {
    @Test
    fun `short and exactly bounded outputs remain unchanged`() {
        listOf("", "Привет 🚀", "x".repeat(256 * 1024)).forEach { text ->
            assertSame(text, text.remoteOutputTail())
        }
    }

    @Test
    fun `unicode output is a valid bounded tail with an explicit omission marker`() {
        listOf("x", "Я", "🚀", "漢字").forEach { unit ->
            val original = "secret prefix" + unit.repeat(300_000) + "END"
            val result = original.remoteOutputTail()
            assertTrue(result.startsWith("[... earlier tool output omitted ...]\n"))
            assertTrue(result.endsWith("END"))
            assertTrue(result.encodeToByteArray().size <= 256 * 1024)
            val tail = result.substringAfter('\n')
            assertTrue(original.endsWith(tail), "No partial Unicode character or replacement text")
        }
    }

    @Test
    fun `all remote transcript sources are capped without mutating native output or prose`() {
        val text = "full output ".repeat(30_000)
        val tool = ToolUi("tool", "Command", ToolStatusUi.Done, text, "diff")
        val reply = MessageUi.Reply(
            "reply",
            Instant.fromEpochMilliseconds(0),
            text,
            persistentListOf(tool),
            false,
            parts = persistentListOf(ReplyPartUi.Text("text", text), ReplyPartUi.Tool(tool)),
        )
        val messages = persistentListOf<MessageUi>(reply)
        val state = AiStudioScreenState(
            transcripts = persistentMapOf("chat" to messages),
            subTranscripts = persistentMapOf("organism" to messages),
            nativeTranscripts = persistentMapOf("child" to NativeTranscriptUi("child", messages)),
        )
        val remote = state.withRemoteToolOutput()
        val feeds = listOf(
            remote.transcripts.getValue("chat"),
            remote.subTranscripts.getValue("organism"),
            remote.nativeTranscripts.getValue("child").messages,
        )
        feeds.forEach { feed ->
            val rendered = feed.single() as MessageUi.Reply
            assertEquals(text, rendered.text)
            assertEquals(text, (rendered.parts.first() as ReplyPartUi.Text).text)
            assertEquals(text.remoteOutputTail(), rendered.tools.single().output)
            assertEquals(rendered.tools.single(), (rendered.parts.last() as ReplyPartUi.Tool).tool)
        }
        assertEquals(text, (state.transcripts.getValue("chat").single() as MessageUi.Reply).tools.single().output)
        assertEquals("diff", tool.diff)
    }
}
