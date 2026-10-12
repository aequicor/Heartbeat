package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFeedCollapse
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The feed as [collapse] presents it: reasoning disappears and the tool calls of each reply become one line at the
 * position of the first. Learning cards and calls awaiting feedback stay, since the user acts on them. The
 * transcript itself, and so the agent's context, never changes.
 */
@HighFrequency
internal fun ImmutableList<MessageUi>.collapsed(collapse: StudioFeedCollapse): ImmutableList<MessageUi> {
    if (!collapse.isReasoningCollapsed && !collapse.isToolCallsCollapsed) return this
    return map { message -> (message as? MessageUi.Reply)?.collapsed(collapse) ?: message }.toImmutableList()
}

private fun MessageUi.Reply.collapsed(collapse: StudioFeedCollapse): MessageUi.Reply {
    val visible = parts.filterNot { collapse.isReasoningCollapsed && it is ReplyPartUi.Reasoning }
    if (!collapse.isToolCallsCollapsed) return copy(parts = visible.toImmutableList())
    val hidden = visible.filterIsInstance<ReplyPartUi.Tool>().filter { it.tool.isCollapsible }
    if (hidden.isEmpty()) return copy(parts = visible.toImmutableList())
    val first = visible.indexOf(hidden.first())
    val line = ReplyPartUi.CollapsedTools(
        "collapsed:$id",
        hidden.size,
        isRunning = hidden.any { it.tool.status == ToolStatusUi.Running || it.tool.status == ToolStatusUi.Pending },
    )
    val kept = visible.filterNot { it in hidden }.toMutableList()
    kept.add(first.coerceAtMost(kept.size), line)
    return copy(parts = kept.toImmutableList(), tools = tools.filterNot { it.isCollapsible }.toImmutableList())
}

private val ToolUi.isCollapsible: Boolean get() = learning == null && feedback == null
