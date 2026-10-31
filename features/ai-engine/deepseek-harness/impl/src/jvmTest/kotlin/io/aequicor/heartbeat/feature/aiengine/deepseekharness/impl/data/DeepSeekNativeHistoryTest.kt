package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import com.github.luben.zstd.Zstd
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekNativeHistoryTest {
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Test
    fun `discovers top-level sessions and skips subagents`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc", "heartbeat"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", "subagent", "session-abc"))

        val history = DeepSeekNativeHistory(DeepSeekConfiguration(homeDirectory = home.path), dispatchers)
        val page = history.page(SessionQuery(), PageRequest())
        assertEquals(1, page.items.size)
        assertEquals("session-abc", page.items[0].ref.nativeId)
    }

    @Test
    fun `malformed session header is skipped alone`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-broken", """{"type":"session","id":{"nested":1}}""")
        writeSession(workspaceDir, "session-good", header("session-good"))

        val history = DeepSeekNativeHistory(DeepSeekConfiguration(homeDirectory = home.path), dispatchers)
        val page = history.page(SessionQuery(), PageRequest())
        assertEquals(listOf("session-good"), page.items.map { it.ref.nativeId })
    }

    @Test
    fun `discovery paginates with a cursor`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        repeat(3) { writeSession(workspaceDir, "session-$it", header("session-$it")) }

        val history = DeepSeekNativeHistory(DeepSeekConfiguration(homeDirectory = home.path), dispatchers)
        val first = history.page(SessionQuery(), PageRequest(limit = 2))
        assertEquals(2, first.items.size)
        val second = history.page(SessionQuery(), PageRequest(SessionCursor(first.items.last().ref.nativeId), 2))
        assertEquals(1, second.items.size)
        assertNull(second.next)
        assertTrue(first.items.none { it.ref in second.items.map { entry -> entry.ref } })
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

        val stored = storedHistory(home, "session-abc")
        val page = stored.page(HistoryPageRequest())
        assertTrue(page.items.size >= 2)
    }

    @Test
    fun `reads concatenated zstd frames and skips malformed lines`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        val first = header("session-abc") + "\n" +
            """{"type":"user/message","seq":1,"data":{"message":{"content":[{"type":"text","text":"hello"}]}}}""" + "\n"
        val second = "not json at all\n" +
            """{"type":"assistant/message","seq":2,"data":{"message":{"content":[{"type":"text","text":"hi"}]}}}"""
        val sessionDir = File(workspaceDir, "session-abc").apply { mkdirs() }
        File(sessionDir, "session.v4.jsonl.zstd")
            .writeBytes(Zstd.compress(first.toByteArray()) + Zstd.compress(second.toByteArray()))

        val stored = storedHistory(home, "session-abc")
        val page = stored.page(HistoryPageRequest())
        assertTrue(page.items.size >= 2)
    }

    @Test
    fun `truncated tail keeps the confirmed prefix`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        val complete = header("session-abc") + "\n" +
            """{"type":"user/message","seq":1,"data":{"message":{"content":[{"type":"text","text":"hello"}]}}}""" + "\n"
        val tail = Zstd.compress("""{"type":"assistant/message","seq":2}""".toByteArray())
        val sessionDir = File(workspaceDir, "session-abc").apply { mkdirs() }
        File(sessionDir, "session.v4.jsonl.zstd")
            .writeBytes(Zstd.compress(complete.toByteArray()) + tail.copyOf(tail.size / 2))

        val stored = storedHistory(home, "session-abc")
        val page = stored.page(HistoryPageRequest())
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun `hostile native id never escapes the sessions root`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))

        val stored = storedHistory(home, "..%2F..%2Fsecret")
        val error = assertFailsWith<EngineException> { stored.page(HistoryPageRequest()) }
        assertTrue(error.failure is EngineFailure.History)
    }

    private fun storedHistory(home: File, nativeId: String) = DeepSeekStoredHistory(
        SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, nativeId),
        DeepSeekConfiguration(homeDirectory = home.path),
        dispatchers,
    )

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
