package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

/**
 * Revision of the stored Codex thread/turn journal Heartbeat owns in the profile: [CodexTurnSnapshot] serialized
 * as the `turn_*` string of [CodexTurnsSpec], together with its required `required_*` marker. Revision 0 is the
 * current layout, the only one this adapter has ever written. These numbers identify the stored contract, never
 * the app or Codex build; an incompatible change needs a new revision plus a researched migration instead of
 * silently reinterpreting an older journal entry.
 */
internal object CodexHistoryFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
