package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart

/** Visible provider content only; opaque reasoning signatures never enter the transcript. */
internal class KoogStreamParts {
    private val blocks = mutableMapOf<Pair<Int, Boolean>, StreamBlock>()

    val text: String get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    val parts: List<ContentPart>
        get() = buildList {
            blocks.entries.sortedBy { it.key.first }.forEach { (key, block) ->
                if (key.second) {
                    block.summary.ifBlank { block.text }.takeIf(String::isNotBlank)?.let {
                        add(ContentPart.Reasoning(it))
                    }
                } else {
                    val previous = lastOrNull() as? ContentPart.Text
                    if (previous == null) {
                        add(ContentPart.Text(block.text))
                    } else {
                        set(lastIndex, ContentPart.Text(previous.text + block.text))
                    }
                }
            }
        }

    fun append(frame: StreamFrame): Boolean {
        val previous = parts
        when (frame) {
            is StreamFrame.TextDelta -> update(frame.index, isReasoning = false) {
                it.copy(text = it.text + frame.text)
            }

            is StreamFrame.TextComplete -> update(frame.index, isReasoning = false) { it.copy(text = frame.text) }

            is StreamFrame.ReasoningDelta -> update(frame.index, isReasoning = true) {
                it.copy(text = it.text + frame.text.orEmpty(), summary = it.summary + frame.summary.orEmpty())
            }

            is StreamFrame.ReasoningComplete -> update(frame.index, isReasoning = true) {
                it.copy(
                    text = frame.content.joinToString("\n\n"),
                    summary = frame.summary?.joinToString("\n\n") ?: it.summary,
                )
            }

            is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete, is StreamFrame.End -> Unit
        }
        return previous != parts
    }

    private fun update(index: Int?, isReasoning: Boolean, change: (StreamBlock) -> StreamBlock) {
        val key = (index ?: 0) to isReasoning
        blocks[key] = change(blocks[key] ?: StreamBlock())
    }

    private data class StreamBlock(val text: String = "", val summary: String = "")
}
