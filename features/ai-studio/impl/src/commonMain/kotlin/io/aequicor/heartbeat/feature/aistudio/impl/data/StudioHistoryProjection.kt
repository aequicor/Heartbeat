package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import kotlin.time.Instant

/** Maps authoritative native snapshots without appending a second local copy of prompts. */
internal fun List<SessionItem>.toStudioMessages(time: Instant, isRunning: Boolean): List<StudioMessage> = map { item ->
    val id = item.info.id.value
    when (item) {
        is SessionItem.Message -> if (item.role == MessageRole.User) {
            StudioMessage.Prompt(id, time, item.parts.text())
        } else {
            StudioMessage.Reply(id, time, item.parts.text(), isStreaming = isRunning && item == lastOrNull())
        }

        is SessionItem.ToolCall -> StudioMessage.Reply(
            id,
            time,
            tools = listOf(
                StudioToolRun(
                    item.call.value,
                    item.name,
                    when (item.status) {
                        ToolCallStatus.Pending, ToolCallStatus.Running -> ToolRunStatus.Running
                        ToolCallStatus.Succeeded -> ToolRunStatus.Done
                        ToolCallStatus.Failed, ToolCallStatus.Cancelled -> ToolRunStatus.Failed
                    },
                    item.arguments,
                ),
            ),
        )

        is SessionItem.ToolResult -> StudioMessage.Reply(
            id,
            time,
            tools = listOf(
                StudioToolRun(
                    item.call.value,
                    item.call.value,
                    if (item.failure == null) ToolRunStatus.Done else ToolRunStatus.Failed,
                    item.parts.text(),
                ),
            ),
        )

        is SessionItem.Plan -> StudioMessage.Reply(id, time, item.steps.joinToString("\n") { it.text })

        is SessionItem.Notice -> StudioMessage.Reply(id, time, item.text)

        is SessionItem.UnsupportedItem -> StudioMessage.Reply(id, time, "[${item.kind}]")
    }
}

private fun List<ContentPart>.text(): String = joinToString("\n") {
    when (it) {
        is ContentPart.Text -> it.text
        is ContentPart.Reasoning -> it.text
        is ContentPart.Image -> "[${it.resource.mediaType}]"
        is ContentPart.Resource -> "[${it.resource.mediaType}]"
    }
}
