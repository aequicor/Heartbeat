package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Limits console output only in remote frames; native state and stored transcripts retain their full text. */
internal fun AiStudioScreenState.withRemoteToolOutput(): AiStudioScreenState = copy(
    transcripts = transcripts.mapValues { (_, messages) -> messages.remoteOutputs() }.toImmutableMap(),
    subTranscripts = subTranscripts.mapValues { (_, messages) -> messages.remoteOutputs() }.toImmutableMap(),
    nativeTranscripts = nativeTranscripts.mapValues { (_, transcript) ->
        transcript.copy(messages = transcript.messages.remoteOutputs())
    }.toImmutableMap(),
)

private fun ImmutableList<MessageUi>.remoteOutputs(): ImmutableList<MessageUi> = map { message ->
    if (message is MessageUi.Reply) {
        message.copy(
            tools = message.tools.map(ToolUi::remoteOutput).toImmutableList(),
            parts = message.parts.map { part ->
                if (part is ReplyPartUi.Tool) part.copy(tool = part.tool.remoteOutput()) else part
            }.toImmutableList(),
        )
    } else {
        message
    }
}.toImmutableList()

private fun ToolUi.remoteOutput(): ToolUi {
    val tail = output.remoteOutputTail()
    return if (tail == output) this else copy(output = tail)
}

/** At most 256 KiB including the omission marker; a UTF-8 code point is never cut in half. */
internal fun String.remoteOutputTail(): String {
    // A UTF-8 tail cannot need more UTF-16 code units than bytes. Bound allocation even for enormous outputs.
    val bytes = takeLast(MAX_REMOTE_TOOL_BYTES).encodeToByteArray()
    if (length <= MAX_REMOTE_TOOL_BYTES && bytes.size <= MAX_REMOTE_TOOL_BYTES) return this
    var start = (bytes.size - MAX_REMOTE_TOOL_BYTES + OMITTED_TOOL_PREFIX.length).coerceAtLeast(0)
    while (start < bytes.size && bytes[start].toInt() and UTF8_PREFIX_MASK == UTF8_CONTINUATION) start++
    return OMITTED_TOOL_PREFIX + bytes.decodeToString(startIndex = start)
}

private const val MAX_REMOTE_TOOL_BYTES = 256 * 1024
private const val OMITTED_TOOL_PREFIX = "[... earlier tool output omitted ...]\n"
private const val UTF8_PREFIX_MASK = 0xc0
private const val UTF8_CONTINUATION = 0x80
