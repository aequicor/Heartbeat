package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import com.github.luben.zstd.ZstdInputStream
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.min
import kotlin.time.Instant

private val nativeJson = Json { ignoreUnknownKeys = true }

private const val SESSION_FILE = "session.v4.jsonl.zstd"

/**
 * Read-only native history for DeepSeek Harness sessions stored under `<home>/sessions/<encoded-cwd>/session-<id>/`.
 * Discovery reads only the bounded header line; a stored transcript streams concatenated zstd frames with a total
 * output cap, so a hostile or corrupt file cannot exhaust memory. Files are untrusted input: a directory that
 * cannot be listed fails the discovery (it is not an empty result), while a malformed entry is skipped alone.
 */
internal class DeepSeekNativeHistory(
    private val configuration: DeepSeekConfiguration,
    private val dispatchers: DispatcherProvider,
) : ListsSessions {
    private val log = Log.tag("DeepSeekNativeHistory")

    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = withContext(dispatchers.io) {
        val root = sessionsRoot(configuration)
        val summaries = mutableListOf<SessionSummary>()
        if (root.isDirectory) {
            for (workspaceDir in listDirs(root)) {
                for (dir in listDirs(workspaceDir)) {
                    currentCoroutineContext().ensureActive()
                    summary(dir)?.let(summaries::add)
                }
            }
        }
        val sorted = summaries
            .filter { matches(query, it) }
            .sortedWith(
                compareByDescending<SessionSummary> { it.times.updatedAt ?: it.times.createdAt }
                    .thenBy { it.ref.nativeId },
            )
        val anchor = request.cursor?.value
        val from = when {
            anchor == null -> 0
            else -> sorted.indexOfFirst { it.ref.nativeId == anchor }.takeIf { it >= 0 }?.plus(1) ?: sorted.size
        }
        val page = sorted.subList(from, min(from + request.limit, sorted.size))
        val next = if (from + request.limit < sorted.size && page.isNotEmpty()) {
            SessionCursor(page.last().ref.nativeId)
        } else {
            null
        }
        SessionPage(page, next, emptyList())
    }

    fun stored(ref: SessionRef): SessionHistory = DeepSeekStoredHistory(ref, configuration, dispatchers)

    private fun listDirs(file: File): List<File> = file.listFiles { entry -> entry.isDirectory }?.toList()
        ?: throw EngineException(EngineFailure.Unknown())

    private fun summary(dir: File): SessionSummary? {
        val header = try {
            readHeader(File(dir, SESSION_FILE))
        } catch (error: IOException) {
            log.w(error) { "DeepSeek Harness session header could not be read" }
            throw EngineException(EngineFailure.Unknown())
        } ?: return null
        val id = header.string("id")?.takeIf(String::isNotBlank) ?: return null
        if (header.string("origin") == "subagent" || header.string("parentSession") != null) return null
        val cwd = header.string("cwd")
        val createdAt = header.string("createdAt")?.toLongOrNull()
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

    /** Reads only the first JSONL line, bounded in length; decompression errors surface as [IOException]. */
    private fun readHeader(file: File): JsonObject? {
        if (!file.isFile) return null
        openFrames(file).use { stream ->
            val first = stream.readBoundedLine(MAX_LINE_BYTES) ?: return null
            return try {
                nativeJson.parseToJsonElement(first) as? JsonObject
            } catch (error: SerializationException) {
                log.w(error) { "DeepSeek Harness session header could not be parsed" }
                null
            }
        }
    }
}

/**
 * Read-only transcript of one stored DeepSeek Harness session. The file is parsed lazily on the first page or
 * watch, never in a constructor; while watched, it is re-polled and newly appended frames join the journal.
 */
internal class DeepSeekStoredHistory(
    private val ref: SessionRef,
    private val configuration: DeepSeekConfiguration,
    private val dispatchers: DispatcherProvider,
) : SessionHistory {
    private val log = Log.tag("DeepSeekStoredHistory")
    private val history = DeepSeekHistory()
    private val loading = Mutex()
    private var isLoaded = false

    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        ensureLoaded()
        return history.page(request)
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = channelFlow {
        ensureLoaded()
        launch { pollChanges() }
        history.watch(after).collect { send(it) }
    }

    private suspend fun ensureLoaded() {
        if (isLoaded) return
        loading.withLock {
            if (isLoaded) return
            val items = readItems()
            items.forEach(history::upsert)
            isLoaded = true
        }
    }

    private suspend fun pollChanges() {
        var stamp = transcriptStamp() ?: return
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            val current = transcriptStamp()
            if (current != null && current != stamp) {
                stamp = current
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        try {
            readItems().forEach { item -> if (history[item.info.id] == null) history.upsert(item) }
        } catch (error: EngineException) {
            log.w(error) { "DeepSeek Harness session transcript could not be refreshed" }
        }
    }

    private fun transcriptStamp(): Pair<Long, Long>? = locateFile()?.let { it.lastModified() to it.length() }

    private suspend fun readItems(): List<SessionItem> = withContext(dispatchers.io) {
        val file = locateFile()
            ?: throw EngineException(EngineFailure.History(HistoryFailureReason.Unavailable))
        val items = mutableListOf<SessionItem>()
        var position = 0L
        streamLines(file) { line ->
            parseLine(line, position)?.let {
                items += it
                position++
            }
        }
        items
    }

    /** Parses every complete frame; a truncated tail keeps the confirmed prefix, an unreadable head fails. */
    private suspend fun streamLines(file: File, onLine: (String) -> Unit) {
        var lines = 0L
        var bytes = 0L
        try {
            openFrames(file).use { stream ->
                while (bytes <= MAX_TRANSCRIPT_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val line = stream.readBoundedLine(MAX_LINE_BYTES) ?: return
                    bytes += line.length.toLong()
                    lines++
                    onLine(line)
                }
                log.w { "DeepSeek Harness session transcript exceeds the size limit" }
            }
        } catch (error: IOException) {
            if (lines == 0L) {
                log.w(error) { "DeepSeek Harness session transcript could not be read" }
                throw EngineException(EngineFailure.History(HistoryFailureReason.Unavailable))
            }
            log.w(error) { "DeepSeek Harness session transcript is truncated" }
        }
    }

    private fun locateFile(): File? {
        if (!SAFE_NATIVE_ID.matches(ref.nativeId)) return null
        val root = sessionsRoot(configuration)
        if (!root.isDirectory) return null
        val rootPath = root.canonicalPath
        for (workspaceDir in root.listFiles().orEmpty()) {
            if (!workspaceDir.isDirectory) continue
            val dir = File(workspaceDir, ref.nativeId)
            val candidate = File(dir, SESSION_FILE)
            if (candidate.isFile && dir.canonicalPath.startsWith(rootPath + File.separator)) return candidate
        }
        return null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        val SAFE_NATIVE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

private fun sessionsRoot(configuration: DeepSeekConfiguration): File = File(
    configuration.homeDirectory ?: File(System.getProperty("user.home"), ".dsh").path,
    "sessions",
)

/** Concatenated zstd frames over a buffered channel; callers bound how much decompressed output they accept. */
private fun openFrames(file: File): BufferedInputStream =
    BufferedInputStream(ZstdInputStream(FileInputStream(file)).apply { continuous = true })

/** One LF-terminated line of at most [maxBytes]; longer input is hostile and fails instead of growing memory. */
private fun BufferedInputStream.readBoundedLine(maxBytes: Int): String? {
    val out = ByteArrayOutputStream()
    while (out.size() < maxBytes) {
        val byte = read()
        if (byte < 0 || byte == '\n'.code) break
        out.write(byte)
    }
    if (out.size() >= maxBytes) throw IOException("Session line exceeds $maxBytes bytes")
    return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
}

private fun parseLine(line: String, position: Long): SessionItem? {
    val obj = try {
        nativeJson.parseToJsonElement(line) as? JsonObject
    } catch (error: SerializationException) {
        Log.tag("DeepSeekNativeHistory").w(error) { "DeepSeek Harness transcript line could not be parsed" }
        null
    } ?: return null
    return when (obj.string("type")) {
        "user/message" -> message(MessageRole.User, obj, position)
        "assistant/message" -> message(MessageRole.Assistant, obj, position)
        "tool/call" -> toolCall(obj, position)
        "tool/result" -> toolResult(obj, position)
        else -> null
    }
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

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private const val MAX_LINE_BYTES = 1024 * 1024
private const val MAX_TRANSCRIPT_BYTES = 64L * 1024 * 1024
