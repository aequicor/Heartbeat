package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import com.github.luben.zstd.ZstdInputStream
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTimes
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.time.Instant

private val nativeJson = Json { ignoreUnknownKeys = true }

/**
 * Read-only native history for DeepSeek Harness sessions stored under `<home>/sessions/<encoded-cwd>/session-<id>/`.
 * Discovery reads only the header frame; a stored transcript decompresses every concatenated zstd frame.
 */
internal class DeepSeekNativeHistory(private val configuration: DeepSeekConfiguration) : ListsSessions {
    private val log = Log.tag("DeepSeekNativeHistory")

    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage {
        val root = sessionsRoot()
        val summaries = listSessionDirs(root)
            .mapNotNull { summary(it) }
            .filter { matches(query, it) }
            .sortedByDescending { it.times.updatedAt ?: it.times.createdAt }
        val page = summaries.take(request.limit)
        return SessionPage(page, null, emptyList())
    }

    fun stored(ref: SessionRef): SessionHistory = DeepSeekStoredHistory(ref, configuration)

    private fun sessionsRoot(): File = File(
        configuration.homeDirectory ?: File(System.getProperty("user.home"), ".dsh").path,
        "sessions",
    )

    private fun listSessionDirs(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .flatMap { workspaceDir -> workspaceDir.listFiles { file -> file.isDirectory }.orEmpty().toList() }
    }

    private fun summary(dir: File): SessionSummary? {
        val header = readHeader(File(dir, SESSION_FILE)) ?: return null
        val id = header.string("id") ?: return null
        if (header.string("origin") == "subagent" || header.string("parentSession") != null) return null
        val cwd = header.string("cwd")
        val createdAt = header["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return SessionSummary(
            SessionRef(DeepSeekEngine.Id, DeepSeekEngine.SessionSource, id),
            workspace = cwd?.let(::workspaceRef),
            origin = if (header.string("origin") == "heartbeat") SessionOrigin.Heartbeat else SessionOrigin.External,
            times = SessionTimes(createdAt = createdAt?.let(Instant::fromEpochMilliseconds)),
        )
    }

    private fun matches(query: SessionQuery, summary: SessionSummary): Boolean =
        (query.workspace == null || query.workspace == summary.workspace) &&
            (query.engines.isEmpty() || summary.ref.engine in query.engines)

    private fun workspaceRef(cwd: String): WorkspaceRef? =
        configuration.workspaces.entries.firstOrNull { it.value == cwd }?.key

    private fun readHeader(file: File): JsonObject? {
        if (!file.isFile) return null
        return try {
            ZstdInputStream(ByteArrayInputStream(file.readBytes())).bufferedReader().use { reader ->
                val first = reader.readLine() ?: return null
                nativeJson.parseToJsonElement(first) as? JsonObject
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "DeepSeek Harness session header could not be read" }
            null
        }
    }

    private companion object {
        const val SESSION_FILE = "session.v4.jsonl.zstd"
    }
}

/** Read-only transcript of one stored DeepSeek Harness session, loaded once into an in-memory journal. */
internal class DeepSeekStoredHistory(ref: SessionRef, configuration: DeepSeekConfiguration) : SessionHistory {
    private val history = DeepSeekHistory()

    init {
        load(ref, configuration)
    }

    private fun load(ref: SessionRef, configuration: DeepSeekConfiguration) {
        val root = File(
            configuration.homeDirectory ?: File(System.getProperty("user.home"), ".dsh").path,
            "sessions",
        )
        val dir = locateDir(root, ref.nativeId) ?: return
        val text = readText(File(dir, "session.v4.jsonl.zstd")) ?: return
        parseItems(text).forEach(history::upsert)
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage = history.page(request)
    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = history.watch(after)

    private fun locateDir(root: File, nativeId: String): File? {
        if (!root.isDirectory) return null
        for (workspaceDir in root.listFiles().orEmpty()) {
            if (!workspaceDir.isDirectory) continue
            val dir = File(workspaceDir, nativeId)
            if (dir.isDirectory) return dir
        }
        return null
    }
}

private fun readText(file: File): String? {
    if (!file.isFile) return null
    return try {
        ZstdInputStream(ByteArrayInputStream(file.readBytes())).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("DeepSeekNativeHistory").w(error) { "DeepSeek Harness session could not be read" }
        null
    }
}

private fun parseItems(text: String): List<SessionItem> {
    val items = mutableListOf<SessionItem>()
    var position = 0L
    text.lineSequence().filter(String::isNotBlank).forEach { line ->
        val obj = (nativeJson.parseToJsonElement(line) as? JsonObject) ?: return@forEach
        val item = when (obj.string("type")) {
            "user/message" -> message(MessageRole.User, obj, position)
            "assistant/message" -> message(MessageRole.Assistant, obj, position)
            "tool/call" -> toolCall(obj, position)
            "tool/result" -> toolResult(obj, position)
            else -> null
        }
        if (item != null) {
            items += item
            position++
        }
    }
    return items
}

private fun message(role: MessageRole, obj: JsonObject, position: Long): SessionItem? {
    val parts = obj.data()?.message()?.content()?.mapNotNull(::contentPart).orEmpty()
    if (parts.isEmpty()) return null
    return SessionItem.Message(ItemInfo(ItemId("native-$position"), position, 0), role, parts)
}

private fun contentPart(block: JsonObject): ContentPart? = when (block.string("type")) {
    "text" -> block.string("text")?.let(ContentPart::Text)
    "reasoning" -> block.string("text")?.let(ContentPart::Reasoning)
    else -> null
}

private fun toolCall(obj: JsonObject, position: Long): SessionItem? {
    val data = obj.data() ?: return null
    val callId = data.string("callId") ?: return null
    val name = data.string("name") ?: "tool"
    return SessionItem.ToolCall(
        ItemInfo(ItemId("tool-$callId"), position, 0),
        ToolCallId(callId),
        name,
        data["arguments"]?.toString() ?: "{}",
        ToolCallStatus.Succeeded,
    )
}

private fun toolResult(obj: JsonObject, position: Long): SessionItem? {
    val message = obj.data()?.message() ?: return null
    val callId = message.string("toolCallId") ?: return null
    val parts = message.content()?.mapNotNull(::contentPart).orEmpty()
    return SessionItem.ToolResult(ItemInfo(ItemId("result-$callId"), position, 0), ToolCallId(callId), parts)
}

private fun JsonObject.data(): JsonObject? = this["data"] as? JsonObject
private fun JsonObject.message(): JsonObject? = this["message"] as? JsonObject
private fun JsonObject.content(): List<JsonObject>? = (this["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
