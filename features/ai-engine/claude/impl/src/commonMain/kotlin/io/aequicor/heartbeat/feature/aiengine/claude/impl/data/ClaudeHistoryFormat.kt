package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

/**
 * Revision of the stored Claude history layout this adapter reads and writes in the profile: the `ClaudeRecord`
 * entries of the `claude_sessions` key-value store (observed item journal, replay events, turn and launch state,
 * native store fingerprint, observed children and native inputs) together with the `ClaudeModelCapabilities` of the
 * last discovery. Revision 0 is the current layout and the only one this adapter has ever stored. The CLI's own
 * JSONL transcripts are read-only observations and are not this layout. An incompatible field meaning, required entry
 * or native session layout needs a new revision plus a researched migration/backup policy.
 */
internal object ClaudeHistoryFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
