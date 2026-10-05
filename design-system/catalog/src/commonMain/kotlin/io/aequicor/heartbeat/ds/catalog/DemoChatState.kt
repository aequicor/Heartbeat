package io.aequicor.heartbeat.ds.catalog

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbChatSection
import io.aequicor.heartbeat.ds.components.HbChatTimeline
import io.aequicor.heartbeat.ds.components.HbMessageAlignment
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbTone
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val log = Log.tag("DS/DemoChat")

internal data class ChatDemoCopy(
    val user: String,
    val agent: String,
    val tool: String,
    val studio: String,
    val prompt: String,
    val reply: String,
    val toolResult: String,
    val notice: String,
    val code: String,
    val codeLabel: String,
    val response: String,
    val section: String = "Today · Studio",
    val historySection: String = "Earlier session",
    /** Markdown with PlantUML fences shown through the catalog diagram renderer; blank skips the sample. */
    val diagramSample: String = "",
)

/** Local catalog state. Persistent timeline updates only the streaming tail, never the full history. */
@Stable
internal class DemoChatState(private val scope: CoroutineScope, initialCopy: ChatDemoCopy) {
    var timeline by mutableStateOf(seedTimeline(initialCopy))
        private set
    val messages: ImmutableList<HbChatMessage> get() = timeline.messages
    var draft by mutableStateOf("")
        private set
    var isStreaming by mutableStateOf(false)
        private set
    var isLoadingHistory by mutableStateOf(false)
        private set
    var appearance by mutableStateOf(HbMessageAppearance(alignment = HbMessageAlignment.Start))
        private set
    var areControlsExpanded by mutableStateOf(false)
        private set
    var isPlanning by mutableStateOf(false)
        private set
    var isConcise by mutableStateOf(false)
        private set
    private var streamJob: Job? = null
    private var historyJob: Job? = null
    private var nextId = 0
    private var historyPage = 0

    fun updateDraft(value: String) {
        log.d { "draft changed length=${value.length}" }
        draft = value
    }

    fun toggleControls() {
        log.i { "message controls expanded=${!areControlsExpanded}" }
        areControlsExpanded = !areControlsExpanded
    }

    fun selectPlanning(value: Boolean) {
        log.i { "planning mode=$value" }
        isPlanning = value
    }

    fun selectConcise(value: Boolean) {
        log.i { "concise response=$value" }
        isConcise = value
    }

    fun send(copy: ChatDemoCopy) {
        if (isStreaming || isLoadingHistory || draft.isBlank()) return
        val userId = "user-${nextId++}"
        val assistantId = "assistant-${nextId++}"
        log.i { "send accepted messageId=$userId" }
        val section = HbChatSection("current", copy.section)
        timeline = timeline.append(
            section,
            HbChatMessage(userId, copy.user, draft.trim(), role = HbChatRole.User, appearance = userAppearance()),
        ).append(
            section,
            HbChatMessage(
                assistantId,
                copy.agent,
                "",
                kind = HbMessageKind.Markdown,
                status = HbMessageStatus.Streaming,
            ),
        )
        draft = ""
        isStreaming = true
        val response = when {
            isPlanning -> "1. ${copy.prompt}\n2. ${copy.reply}\n3. ${copy.toolResult}"
            isConcise -> copy.response.substringBefore("\n\n")
            else -> copy.response
        }
        streamJob = scope.launch {
            for (chunk in response.chunked(STREAM_CHUNK_SIZE)) {
                delay(STREAM_INTERVAL_MILLIS)
                appendChunk(assistantId, chunk)
            }
            finishStream()
        }
    }

    fun stop() {
        log.i { "stream stop active=$isStreaming" }
        streamJob?.cancel()
        streamJob = null
        finishStream()
    }

    fun reset(copy: ChatDemoCopy) {
        stop()
        historyJob?.cancel()
        historyJob = null
        isLoadingHistory = false
        log.i { "conversation reset" }
        timeline = seedTimeline(copy)
        draft = ""
        appearance = HbMessageAppearance(alignment = HbMessageAlignment.Start)
        areControlsExpanded = false
    }

    fun addExample(copy: ChatDemoCopy, isTool: Boolean) {
        if (isStreaming || isLoadingHistory) return
        val id = "example-${nextId++}"
        log.i { "example added id=$id tool=$isTool" }
        val message = if (isTool) toolExample(copy, id) else markdownExample(copy, id)
        timeline = timeline.append(HbChatSection("current", copy.section), message)
    }

    fun loadEarlier(copy: ChatDemoCopy) {
        if (isLoadingHistory) return
        val page = ++historyPage
        log.i { "history page requested page=$page count=$HISTORY_PAGE_SIZE" }
        val older = historyMessages(copy, "page-$page", HISTORY_PAGE_SIZE)
        timeline = timeline.prepend(HbChatSection("page-$page", "${copy.historySection} · $page"), older)
    }

    fun loadLongSession(copy: ChatDemoCopy) {
        if (isLoadingHistory) return
        stop()
        isLoadingHistory = true
        log.i { "long session preparation started count=$LONG_SESSION_SIZE" }
        historyJob = scope.launch {
            try {
                var prepared = HbChatTimeline.Empty
                repeat(LONG_SESSION_SIZE / HISTORY_PAGE_SIZE) { page ->
                    val section = HbChatSection("long-$page", "${copy.historySection} · ${page + 1}")
                    for (message in historyMessages(copy, "long-$page", HISTORY_PAGE_SIZE)) {
                        prepared = prepared.append(section, message)
                    }
                    // Yield between bounded pages so preparation never monopolizes the event loop.
                    yield()
                }
                timeline = prepared.append(HbChatSection("current", copy.section), markdownExample(copy, "long-latest"))
                log.i { "long session ready count=${timeline.messageCount}" }
            } finally {
                isLoadingHistory = false
            }
        }
    }

    fun updateAppearance(value: HbMessageAppearance) {
        log.i { "appearance changed tone=${value.tone} width=${value.widthFraction} alignment=${value.alignment}" }
        appearance = value
    }

    private fun appendChunk(id: String, chunk: String) {
        val latest = timeline.latestMessage ?: return
        if (latest.id != id) return
        log.v { "stream chunk id=$id length=${chunk.length}" }
        timeline = timeline.replaceLatest(latest.copy(text = latest.text + chunk))
    }

    private fun finishStream() {
        log.i { "stream state active=false" }
        isStreaming = false
        val latest = timeline.latestMessage
        if (latest?.status == HbMessageStatus.Streaming) {
            timeline = timeline.replaceLatest(latest.copy(status = HbMessageStatus.Complete))
        }
    }

    private companion object {
        const val STREAM_CHUNK_SIZE = 8
        const val STREAM_INTERVAL_MILLIS = 70L
        const val HISTORY_PAGE_SIZE = 100
        const val LONG_SESSION_SIZE = 10_000
    }
}

private fun userAppearance() = HbMessageAppearance(tone = HbTone.Brand)

private fun historyMessages(copy: ChatDemoCopy, prefix: String, count: Int): ImmutableList<HbChatMessage> =
    List(count) { index ->
        val isUser = index % 2 == 0
        HbChatMessage(
            id = "$prefix-$index",
            author = if (isUser) copy.user else copy.agent,
            text = "${index + 1}. ${if (isUser) copy.prompt else copy.toolResult}",
            role = if (isUser) HbChatRole.User else HbChatRole.Assistant,
            appearance = if (isUser) userAppearance() else HbMessageAppearance(),
        )
    }.toImmutableList()
