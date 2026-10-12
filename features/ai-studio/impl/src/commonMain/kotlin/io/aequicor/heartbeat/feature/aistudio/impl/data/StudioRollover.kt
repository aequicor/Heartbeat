package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.sessionEssence

/**
 * Rollover continues a chat in a new native session of the same engine, workspace and model. The transcript of
 * the previous sessions is frozen: its items keep their order under ids prefixed with [FROZEN_PREFIX] and a
 * [NoticeKind.ContextRollover] notice closes the segment, so the history mirror of the new session never replaces
 * them. The essence of the old session ([rolloverSeed]) reaches the first accepted turn of the new session as a
 * host directive. Scheduled wakes addressed to an earlier session still find the chat by
 * [StudioChatRecord.previousRefs]; harness connections of the earlier session are not carried over.
 */
internal const val FROZEN_PREFIX = "hb.seg."

/** Largest essence carried into the new session. */
internal const val ROLLOVER_SEED_CHARS = 40_000

/** Whether [this] item belongs to a frozen segment of an earlier native session. */
internal val SessionItem.isFrozenSegment: Boolean get() = info.id.value.startsWith(FROZEN_PREFIX)

/** Chats whose context the studio compacts: helpers and organisms keep their turns and identity to their owners. */
internal val StudioChatRecord.isCompactable: Boolean get() = helper == null && organismId == null

/** Chats that may continue in a new session. */
internal val StudioChatRecord.isRolloverAllowed: Boolean get() = isCompactable && ref != null

/** The record after a rollover: detached from its session, which joins [StudioChatRecord.previousRefs]. */
internal fun StudioChatRecord.rolledOver(seed: String?): StudioChatRecord {
    log.i { "Conversation continues in a new native session: segment=${contextSegment + 1} seed=${seed?.length ?: 0}" }
    return copy(
        ref = null,
        previousRefs = previousRefs + listOfNotNull(ref),
        contextSegment = contextSegment + 1,
        pendingSeed = seed,
    )
}

/** The transcript after a rollover into segment [segment]: every item frozen, closed by a rollover notice. */
internal fun List<SessionItem>.frozen(segment: Int): List<SessionItem> {
    val frozen = map { item ->
        if (item.isFrozenSegment) {
            item
        } else {
            item.withId(
                FROZEN_PREFIX + "$segment/" + item.info.id.value,
            )
        }
    }
    val notice = SessionItem.Notice(
        ItemInfo(ItemId("$FROZEN_PREFIX$segment/rollover"), size.toLong(), 0),
        ROLLOVER_NOTICE,
        NoticeKind.ContextRollover,
    )
    return frozen + notice
}

/** Host directive for the first turn of the new session; it also marks that turn as the first one. */
internal fun rolloverSeed(items: List<SessionItem>): String {
    val essence = sessionEssence(items.filterNot { it.isFrozenSegment }, ROLLOVER_SEED_CHARS)
    if (essence.text.isBlank()) return SEED_HEADER
    val omitted = if (essence.omittedItems > 0) "\nEarlier parts were omitted to keep it short." else ""
    return "$SEED_HEADER$omitted\n\n--- Earlier conversation ---\n\n${essence.text}\n\n$SEED_END"
}

private fun SessionItem.withId(id: String): SessionItem {
    val info = info.copy(id = ItemId(id))
    return when (this) {
        is SessionItem.Message -> copy(info = info)
        is SessionItem.ToolCall -> copy(info = info)
        is SessionItem.ToolResult -> copy(info = info)
        is SessionItem.Plan -> copy(info = info)
        is SessionItem.Notice -> copy(info = info)
        is SessionItem.UnsupportedItem -> copy(info = info)
    }
}

private val log = Log.tag("StudioRollover")
private const val ROLLOVER_NOTICE = "Continued in a new session"
private const val SEED_END = "--- End of earlier conversation ---"
private const val SEED_HEADER =
    "This conversation continues from an earlier session that was closed to free the context window. Its essence " +
        "follows, oldest first. Treat it as context only: do not repeat the tool calls or actions it describes; " +
        "check the current state of files and tasks before relying on it."
