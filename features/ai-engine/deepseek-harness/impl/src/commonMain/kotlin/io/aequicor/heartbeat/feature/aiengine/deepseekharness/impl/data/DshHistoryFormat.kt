package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

/**
 * Revision of the observed-history/turn records this adapter writes for a DSH session: the [DeepSeekHistory]
 * snapshot, checkpoint and journal (`SessionEvent.ItemUpserted`, `ItemRemoved`, `TurnStarted`, `TurnFinished`,
 * `PermissionRequested`, `HistoryInvalidated`) and the [DeepSeekSession] turn records. Revision 0 is the current
 * layout and the only one this adapter has ever produced. DSH's native on-disk `session.v4.jsonl.zstd` store is
 * read-only input owned by DSH and is outside this revision; the adapter writes no DSH-specific profile
 * transcript. An incompatible field meaning, required record or native session layout needs a new revision plus a
 * researched migration/backup policy.
 */
internal object DshHistoryFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
