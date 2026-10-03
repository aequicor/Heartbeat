package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame

/**
 * Collects one round's logical calls from raw deltas, retaining calls from transports emitting only Complete.
 * Koog may flush a call at every reasoning delta and substitute `{}` for absent arguments. Complete frames cannot
 * distinguish that placeholder from real arguments, so raw deltas are authoritative whenever available.
 */
internal class KoogToolCalls {
    private val calls = mutableListOf<PendingCall>()

    fun append(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.ToolCallDelta -> appendDelta(frame)

            is StreamFrame.ToolCallComplete -> appendComplete(frame)

            is StreamFrame.TextDelta,
            is StreamFrame.TextComplete,
            is StreamFrame.ReasoningDelta,
            is StreamFrame.ReasoningComplete,
            is StreamFrame.End,
            -> Unit
        }
    }

    fun complete(): List<StreamFrame.ToolCallComplete> = calls.map { it.complete() }

    private fun appendDelta(frame: StreamFrame.ToolCallDelta) {
        val owner = calls.lastOrNull { it.isRaw && it.matches(frame.id, frame.index) }
        val call = owner ?: PendingCall(frame.index, true).also { calls += it }
        call.update(frame.id, frame.name, frame.content.orEmpty())
    }

    private fun appendComplete(frame: StreamFrame.ToolCallComplete) {
        if (calls.any { it.isRaw && it.matches(frame.id, frame.index) }) return
        // Without raw deltas a nameless continuation can be joined only by a real index.
        val owner = if (frame.name.isBlank() && frame.id.isNullOrBlank() && frame.index != null) {
            calls.lastOrNull { !it.isRaw && it.index == frame.index }
        } else {
            null
        }
        val call = owner ?: PendingCall(frame.index, false).also { calls += it }
        call.update(frame.id, frame.name, frame.content)
    }
}

private class PendingCall(val index: Int?, val isRaw: Boolean) {
    private var id: String? = null
    private var name = ""
    private val arguments = StringBuilder()

    fun matches(nextId: String?, nextIndex: Int?): Boolean = when {
        !nextId.isNullOrBlank() && !id.isNullOrBlank() -> nextId == id
        nextIndex != null -> nextIndex == index
        else -> index == null
    }

    fun update(nextId: String?, nextName: String?, content: String) {
        if (!nextId.isNullOrBlank()) id = nextId
        if (!nextName.isNullOrBlank()) name = nextName
        arguments.append(content)
    }

    fun complete(): StreamFrame.ToolCallComplete = StreamFrame.ToolCallComplete(
        id,
        name,
        if (isRaw && arguments.isEmpty()) "{}" else arguments.toString(),
        index,
    )
}
