package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Builds history immediately before submission; no source binaries or private tool arguments leave storage. */
internal class StudioEngineSwitchContext(
    private val facade: EngineFacade,
    private val attachmentsCatalog: AttachmentsCatalog,
) {
    suspend fun prepare(
        current: StudioChatRecord,
        incoming: String,
        stored: List<SessionItem>,
        write: suspend ((List<SessionItem>) -> List<SessionItem>) -> Unit,
    ): String? {
        val boundary = current.pendingEngineContext ?: return null
        val end = stored.indexOfFirst { it.info.id.value == boundary }
        check(end >= 0) { "The engine replacement transcript boundary is missing" }
        val history = stored.take(end + 1)
        val ids = history.asSequence().flatMap { item ->
            when (item) {
                is SessionItem.Message -> item.parts

                is SessionItem.ToolResult -> item.parts

                is SessionItem.ToolCall, is SessionItem.Plan, is SessionItem.Notice,
                is SessionItem.UnsupportedItem,
                -> emptyList()
            }
        }.mapNotNull { part ->
            when (part) {
                is ContentPart.Image -> part.resource.id
                is ContentPart.Resource -> part.resource.id
                is ContentPart.Text, is ContentPart.Reasoning -> null
            }?.takeIf { it.startsWith("attachment:") }?.removePrefix("attachment:")?.takeIf { it.isNotBlank() }
        }.distinct().map(::AttachmentId).toList()
        val available = if (ids.isEmpty()) {
            emptySet()
        } else {
            attachmentsCatalog.observe(ids).first().mapTo(mutableSetOf()) { it.resource.id }
        }
        val target = checkNotNull(current.target)
        val limit = facade.models.observe(target.engine, target.binding).first { it.isLoaded }
            .models.firstOrNull { it.target == target }?.contextLimitTokens
        val context = engineSwitchTranscript(history, limit, incoming, available)
        if (context.isTruncated) {
            val noticeId = "$boundary/truncated"
            write { all ->
                if (all.any { it.info.id.value == noticeId }) {
                    all
                } else {
                    val notice = SessionItem.Notice(
                        ItemInfo(ItemId(noticeId), 0, 0),
                        Json.encodeToString(StudioEngineSwitchNotice("", "", isTruncated = true)),
                    )
                    all.take(end + 1) + notice + all.drop(end + 1)
                }
            }
        }
        return context.text
    }
}
