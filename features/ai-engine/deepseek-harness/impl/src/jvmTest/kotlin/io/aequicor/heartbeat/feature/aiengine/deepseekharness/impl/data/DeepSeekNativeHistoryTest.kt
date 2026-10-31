package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import com.github.luben.zstd.Zstd
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekNativeHistoryTest {
    @Test
    fun `discovers top-level sessions and skips subagents`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc", "heartbeat"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", "subagent", "session-abc"))

        val history = DeepSeekNativeHistory(DeepSeekConfiguration(homeDirectory = home.path))
        val page = history.page(SessionQuery(), PageRequest())
        assertEquals(1, page.items.size)
        assertEquals("session-abc", page.items[0].ref.nativeId)
    }

    @Test
    fun `reads transcript items from a stored session`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        val events = """
            {"type":"user/message","seq":1,"data":{"message":{"content":[{"type":"text","text":"hello"}]}}}
            {"type":"assistant/message","seq":2,"data":{"message":{"content":[{"type":"text","text":"hi"}]}}}
        """.trimIndent()
        writeSession(workspaceDir, "session-abc", header("session-abc"), events)

        val configuration = DeepSeekConfiguration(homeDirectory = home.path)
        val stored = DeepSeekStoredHistory(
            SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, "session-abc"),
            configuration,
        )
        val page = stored.page(HistoryPageRequest())
        assertTrue(page.items.size >= 2)
    }

    private fun writeSession(dir: File, id: String, header: String, events: String = "") {
        val sessionDir = File(dir, id).apply { mkdirs() }
        val text = header + "\n" + events
        File(sessionDir, "session.v4.jsonl.zstd").writeBytes(Zstd.compress(text.toByteArray()))
    }

    private fun header(id: String, origin: String = "heartbeat", parent: String? = null): String = buildString {
        append("""{"type":"session","version":4,"id":"$id",""")
        append(""""createdAt":1700000000000,"cwd":"C:/u/proj",""")
        append(""""origin":"$origin","delegationDepth":${if (parent == null) 0 else 1}""")
        parent?.let { append(""","parentSession":"$it"""") }
        append("}")
    }
}
