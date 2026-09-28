package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbChatSection
import io.aequicor.heartbeat.ds.components.HbChatTimeline
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbMessageAlignment
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolLabels
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.ds.theme.HbStudioTheme
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchMessageUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPartUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchToolStatus
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_assistant
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_collapse_tool
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_copied_answer
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_copy_answer
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_expand_tool
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_jump_latest
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_notice
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_reasoning
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_streaming
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_tool_cancelled
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_tool_complete
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_tool_failed
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_tool_pending
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_tool_running
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_you
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** A native answer remains one card while its tools and long outputs retain independent lazy rows. */
@Composable
internal fun ResearchTranscript(state: ResearchScreenState, modifier: Modifier = Modifier) {
    HbStudioTheme {
        val labels = ResearchMessageLabels(
            user = stringResource(Res.string.research_you),
            assistant = stringResource(Res.string.research_assistant),
            notice = stringResource(Res.string.research_notice),
            reasoning = stringResource(Res.string.research_reasoning),
            appearance = HbMessageAppearance(
                alignment = HbMessageAlignment.Center,
                widthFraction = 1f,
                background = HbTheme.studioColors.assistant,
                foreground = HbTheme.colors.textPrimary,
                isUnified = true,
            ),
        )
        val messages = remember(state.messages, labels) { state.messages.map { it.toChat(labels) }.toImmutableList() }
        val cache = remember { ResearchTimelineCache() }
        HbChatTranscript(
            timeline = cache.update(messages),
            modifier = modifier.testTag("research-transcript"),
            streamingLabel = stringResource(Res.string.research_streaming),
            jumpToLatestLabel = stringResource(Res.string.research_jump_latest),
            toolLabels = researchToolLabels(),
            showSectionHeaders = false,
        )
    }
}

private data class ResearchMessageLabels(
    val user: String,
    val assistant: String,
    val notice: String,
    val reasoning: String,
    val appearance: HbMessageAppearance,
)

private fun ResearchMessageUi.toChat(labels: ResearchMessageLabels): HbChatMessage = HbChatMessage(
    id = id,
    author = when {
        isUser -> labels.user
        isNotice -> labels.notice
        else -> labels.assistant
    },
    text = text,
    role = when {
        isUser -> HbChatRole.User
        isNotice -> HbChatRole.System
        else -> HbChatRole.Assistant
    },
    kind = when {
        isUser -> HbMessageKind.Text
        isNotice -> HbMessageKind.Notice
        else -> HbMessageKind.Markdown
    },
    status = if (isStreaming) HbMessageStatus.Streaming else HbMessageStatus.Complete,
    appearance = if (isUser || isNotice) HbMessageAppearance() else labels.appearance,
    parts = parts.map { it.toChat(labels.reasoning) }.toImmutableList(),
)

private fun ResearchPartUi.toChat(reasoning: String): HbMessagePart = when (this) {
    is ResearchPartUi.Text -> HbMessagePart.Text(id, text)

    is ResearchPartUi.Reasoning -> HbMessagePart.Tool(
        HbToolCall(
            id = "reasoning:$id",
            title = reasoning,
            summary = text.lineSequence().firstOrNull().orEmpty(),
            blocks = persistentListOf(HbToolBlock.Markdown(id, text)),
            kind = HbToolKind.Reasoning,
        ),
    )

    is ResearchPartUi.Tool -> HbMessagePart.Tool(
        HbToolCall(
            id = "tool:$id",
            title = title,
            status = status.toChat(),
            blocks = persistentListOf(HbToolBlock.Console(id, output)),
        ),
    )
}

private fun ResearchToolStatus.toChat(): HbToolStatus = when (this) {
    ResearchToolStatus.Pending -> HbToolStatus.Pending
    ResearchToolStatus.Running -> HbToolStatus.Running
    ResearchToolStatus.Complete -> HbToolStatus.Complete
    ResearchToolStatus.Failed -> HbToolStatus.Error
    ResearchToolStatus.Cancelled -> HbToolStatus.Cancelled
}

@Composable
private fun researchToolLabels(): HbToolLabels = HbToolLabels(
    expand = stringResource(Res.string.research_expand_tool),
    collapse = stringResource(Res.string.research_collapse_tool),
    pending = stringResource(Res.string.research_tool_pending),
    running = stringResource(Res.string.research_tool_running),
    complete = stringResource(Res.string.research_tool_complete),
    error = stringResource(Res.string.research_tool_failed),
    cancelled = stringResource(Res.string.research_tool_cancelled),
    copyMessage = stringResource(Res.string.research_copy_answer),
    messageCopied = stringResource(Res.string.research_copied_answer),
)

/** Reuses prepared history and tool payloads while the current native answer streams. */
internal class ResearchTimelineCache {
    private var messages: ImmutableList<HbChatMessage> = persistentListOf()
    private var timeline = HbChatTimeline.Empty
    private val section = HbChatSection("research", "")

    fun update(next: ImmutableList<HbChatMessage>): HbChatTimeline {
        if (next == messages) return timeline
        val previousLast = messages.lastIndex
        val isReusable = previousLast >= 0 && next.size >= messages.size &&
            next[previousLast].id == messages[previousLast].id &&
            (0 until previousLast).all { messages[it] == next[it] }
        timeline = if (isReusable) {
            next.drop(messages.size).fold(timeline.replaceLatest(next[previousLast])) { current, message ->
                current.append(section, message)
            }
        } else {
            HbChatTimeline.from(section, next)
        }
        messages = next
        return timeline
    }
}
