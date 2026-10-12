package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome

/**
 * Carries a studio conversation across changes of its native context, under the chat's run reservation and
 * conversation lock. The repository supplies its storage: the record and the items of a conversation, their writes and
 * `detach`, which closes and forgets the native handle.
 */
internal class StudioContextCarry(
    private val locks: StudioConversationLocks,
    private val record: suspend (String) -> StudioChatRecord,
    private val items: suspend (String) -> List<SessionItem>,
    private val write: suspend (String, (List<SessionItem>) -> List<SessionItem>) -> Unit,
    private val update: suspend (String, StudioChatRecord.() -> StudioChatRecord) -> Unit,
    private val detach: suspend (String) -> Unit,
) {
    /**
     * Freezes the transcript and detaches the native session; the next turn creates a new session and receives the
     * essence of this one, or with [isBriefed] its continuation brief. The old native session is kept.
     */
    suspend fun rollover(id: String, isBriefed: Boolean): RunOutcome = locks.withLock(id) {
        val record = record(id).takeIf { it.isRolloverAllowed } ?: return@withLock RunOutcome.Failed
        val items = items(id)
        val seed = if (isBriefed) continuationSeed(items) else rolloverSeed(items)
        write(id) { it.frozen(record.contextSegment + 1) }
        update(id) { rolledOver(seed) }
        detach(id)
        RunOutcome.Completed
    }

    /** The engine replaced its context with its own summary; the next turn restores what the summary may have lost. */
    suspend fun brief(id: String) = locks.withLock(id) {
        val brief = compactionBrief(items(id))
        update(id) { copy(pendingBrief = brief) }
    }
}
