package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import com.github.luben.zstd.Zstd
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeepSeekSessionTreesTest {
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Test
    fun `discovers subagents and their parents across the store`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc"))
        writeSession(workspaceDir, "subagent-2", header("subagent-2", parent = "subagent-1"))

        val snapshot = trees(home).snapshot(root("session-abc"), access())

        assertEquals(setOf("subagent-1", "subagent-2"), snapshot.descendants().map { it.node.ref.nativeId }.toSet())
        val nested = snapshot.descendants().first { it.node.ref.nativeId == "subagent-2" }
        assertEquals("subagent-1", nested.node.parent?.nativeId)
        assertEquals(2, nested.depth)
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `a stored title becomes the subagent name`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc", title = "Reviewer"))

        val snapshot = trees(home).snapshot(root("session-abc"), access())
        val child = snapshot.descendants().single { it.node.ref.nativeId == "subagent-1" }
        assertEquals("Reviewer", child.node.name)
    }

    @Test
    fun `root without subagents is a partial family, not a confirmed zero`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))

        val snapshot = trees(home).snapshot(root("session-abc"), access())
        assertTrue(snapshot.descendants().isEmpty())
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `observe emits the family`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc"))

        val snapshot = trees(home).observe(root("session-abc"), access()).first()
        assertEquals(listOf("subagent-1"), snapshot.descendants().map { it.node.ref.nativeId })
    }

    @Test
    fun `history reads a subagent transcript`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        val events = """
            {"type":"user/message","seq":1,"data":{"message":{"content":[{"type":"text","text":"review"}]}}}
        """.trimIndent()
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc"), events)

        val page = trees(home).history(root("session-abc"), root("subagent-1"), access()).page(HistoryPageRequest())
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun `history rejects a non-member session`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "other", header("other"))

        val failure = assertFailsWith<EngineException> {
            trees(home).history(root("session-abc"), root("other"), access())
        }
        assertIs<EngineFailure.Session>(failure.failure)
        assertEquals(SessionFailureReason.NotFound, (failure.failure as EngineFailure.Session).reason)
    }

    @Test
    fun `a corrupt session header is skipped without hiding the family`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc"))
        val sessionDir = File(workspaceDir, "broken").apply { mkdirs() }
        File(sessionDir, "session.v4.jsonl.zstd").writeBytes("not a zstd frame".toByteArray())

        val snapshot = trees(home).snapshot(root("session-abc"), access())

        assertEquals(listOf("subagent-1"), snapshot.descendants().map { it.node.ref.nativeId })
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `the stored root title names the root node`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc", title = "Main chat"))

        val snapshot = trees(home).snapshot(root("session-abc"), access())

        assertEquals("Main chat", snapshot.nodes.single { it.ref == root("session-abc") }.name)
    }

    @Test
    fun `a parent cycle terminates and keeps each session once`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val workspaceDir = File(File(home, "sessions"), "--C-Users-u-proj--")
        writeSession(workspaceDir, "session-abc", header("session-abc", parent = "subagent-1"))
        writeSession(workspaceDir, "subagent-1", header("subagent-1", parent = "session-abc"))

        val snapshot = trees(home).snapshot(root("session-abc"), access())

        assertEquals(listOf("subagent-1"), snapshot.descendants().map { it.node.ref.nativeId })
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `an unreadable store is a read failure, not a missing member`() = runBlocking {
        val home = Files.createTempDirectory("dsh-home").toFile()
        val unavailable = trees(home, scan = { throw IOException("store unavailable") })

        val snapshot = unavailable.snapshot(root("session-abc"), access())
        assertEquals(SessionTreeCoverage.Unavailable, snapshot.coverage)
        assertTrue(snapshot.nodes.isEmpty())

        val failure = assertFailsWith<EngineException> {
            unavailable.history(root("session-abc"), root("subagent-1"), access())
        }
        assertIs<EngineFailure.History>(failure.failure)
        assertEquals(HistoryFailureReason.Unavailable, (failure.failure as EngineFailure.History).reason)
    }

    private fun trees(home: File, scan: (suspend () -> Map<String, DeepSeekFamily>)? = null): DeepSeekSessionTrees {
        val history = DeepSeekNativeHistory(DeepSeekConfiguration(homeDirectory = home.path), dispatchers)
        return if (scan == null) DeepSeekSessionTrees(history) else DeepSeekSessionTrees(history, scan)
    }

    private fun root(id: String) = SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, id)

    private fun access() = SessionTreeAccess(
        EngineTarget(DeepSeekEngine.Id, EngineBindingId("binding"), ModelId("model")),
    )

    private fun writeSession(dir: File, id: String, header: String, events: String = "") {
        val sessionDir = File(dir, id).apply { mkdirs() }
        val text = header + "\n" + events
        File(sessionDir, "session.v4.jsonl.zstd").writeBytes(Zstd.compress(text.toByteArray()))
    }

    private fun header(id: String, parent: String? = null, title: String? = null): String = buildString {
        append("""{"type":"session","version":4,"id":"$id",""")
        append(""""createdAt":1700000000000,"cwd":"C:/u/proj",""")
        append(""""origin":"${if (parent == null) "heartbeat" else "subagent"}",""")
        append(""""delegationDepth":${if (parent == null) 0 else 1}""")
        title?.let { append(""","title":"$it"""") }
        parent?.let { append(""","parentSession":"$it"""") }
        append("}")
    }
}
