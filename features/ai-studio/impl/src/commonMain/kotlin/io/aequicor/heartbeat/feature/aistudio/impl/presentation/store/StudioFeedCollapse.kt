package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFeedCollapse
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.concurrent.Volatile

/**
 * The feed as [collapse] presents it: reasoning disappears and the tool calls of each reply become one line at the
 * position of the first. Learning cards, calls awaiting feedback and failed or cancelled calls stay, since the user
 * acts on them or must see them. The transcript itself, and so the agent's context, never changes.
 *
 * Runs in composition on every state emission, so it is cheap there: a reply already collapsed with the same
 * settings returns its memoized instance, a reply with nothing to hide is returned itself, and a feed without any
 * change is returned as is. Unchanged entries keep their identity and equality checks downstream stay shallow.
 */
@HighFrequency
internal fun ImmutableList<MessageUi>.collapsed(collapse: StudioFeedCollapse): ImmutableList<MessageUi> {
    if (!collapse.isReasoningCollapsed && !collapse.isToolCallsCollapsed) return this
    var isChanged = false
    val shown = map { message ->
        val reply = message as? MessageUi.Reply ?: return@map message
        reply.collapseMemo.of(collapse) { reply.collapsed(collapse) }.also { if (it !== reply) isChanged = true }
    }
    return if (isChanged) shown.toImmutableList() else this
}

/**
 * One-entry memo of a reply's collapsed presentation, owned by that reply instance: the same instance asked with the
 * same settings returns the same result. It never takes part in equality of the reply.
 */
internal class CollapseMemo {
    @Volatile
    private var entry: Pair<StudioFeedCollapse, MessageUi.Reply>? = null

    @HighFrequency
    fun of(collapse: StudioFeedCollapse, compute: () -> MessageUi.Reply): MessageUi.Reply {
        entry?.let { (key, value) -> if (key == collapse) return value }
        return compute().also { entry = collapse to it }
    }
}

/**
 * One pass over the parts. While the reply streams and its latest part is hidden, the collapsed line shows the
 * hidden work as running, so the in-flight reply never looks finished.
 */
@HighFrequency
private fun MessageUi.Reply.collapsed(collapse: StudioFeedCollapse): MessageUi.Reply {
    val kept = ArrayList<ReplyPartUi>(parts.size)
    var linePosition = -1
    var hiddenCalls = 0
    var isRunning = false
    var isLatestHidden = false
    for (part in parts) {
        isLatestHidden = collapse.hides(part)
        when {
            !isLatestHidden -> kept += part

            part is ReplyPartUi.Tool -> {
                if (hiddenCalls == 0) linePosition = kept.size
                hiddenCalls++
                isRunning = isRunning || part.tool.isActive
            }
        }
    }
    return when {
        kept.size == parts.size -> this

        hiddenCalls == 0 -> copy(parts = kept.toImmutableList())

        else -> {
            val line = ReplyPartUi.CollapsedTools(
                "collapsed:$id",
                hiddenCalls,
                isRunning = isRunning || (isStreaming && isLatestHidden),
            )
            kept.add(linePosition, line)
            copy(parts = kept.toImmutableList(), tools = tools.filterNot { it.isCollapsible }.toImmutableList())
        }
    }
}

private fun StudioFeedCollapse.hides(part: ReplyPartUi): Boolean = when (part) {
    is ReplyPartUi.Reasoning -> isReasoningCollapsed
    is ReplyPartUi.Tool -> isToolCallsCollapsed && part.tool.isCollapsible
    is ReplyPartUi.Text, is ReplyPartUi.CollapsedTools -> false
}

/** Only quiet calls fold: the user acts on cards and feedback, and failed or cancelled calls must stay visible. */
private val ToolUi.isCollapsible: Boolean
    get() = learning == null && feedback == null && status != ToolStatusUi.Failed && status != ToolStatusUi.Cancelled

private val ToolUi.isActive: Boolean get() = status == ToolStatusUi.Running || status == ToolStatusUi.Pending
