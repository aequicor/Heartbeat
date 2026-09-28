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
            is StreamFrame.TextDelta -> block(frame.index, isReasoning = false).text += frame.text

            is StreamFrame.TextComplete -> block(frame.index, isReasoning = false).text = frame.text

            is StreamFrame.ReasoningDelta -> block(frame.index, isReasoning = true).apply {
                text += frame.text.orEmpty()
                summary += frame.summary.orEmpty()
            }

            is StreamFrame.ReasoningComplete -> block(frame.index, isReasoning = true).apply {
                text = frame.content.joinToString("\n\n")
                summary = frame.summary?.joinToString("\n\n") ?: summary
            }

            is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete, is StreamFrame.End -> Unit
        }
        return previous != parts
    }

    private fun block(index: Int?, isReasoning: Boolean): StreamBlock =
        blocks.getOrPut((index ?: 0) to isReasoning) { StreamBlock() }

    private class StreamBlock(var text: String = "", var summary: String = "")
}
