package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

/**
 * Revision of the observed-history/turn records this adapter owns for a Kimi session: the [KimiHistory] snapshot,
 * checkpoint and event journal (`SessionEvent.ItemUpserted`, `ItemRemoved`, `TurnStarted`, `TurnFinished`,
 * `PermissionRequested`, `HistoryInvalidated`) and the [KimiSession] turn records (`Turn` boundaries, outcome,
 * permission and hosted-call identities). Revision 0 is the current layout and the only one this adapter has ever
 * produced. The adapter writes no Kimi-specific profile transcript: records live in the live runtime/session, while
 * the studio mirror and session index are facade-owned layouts outside this revision. An incompatible field meaning,
 * required record or native session layout needs a new revision plus a researched migration/backup policy.
 */
internal object KimiHistoryFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
