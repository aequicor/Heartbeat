package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

/**
 * Revision of the stored Pi session history layout this adapter reads and writes: the native transcript branch
 * returned by `get_entries` ([PiStoredBranch]: `entries`, `leafId`, `parentId` links) together with the profile's
 * turn records ([PiTurnSnapshot]: turn boundaries, process ownership and answer correlations).
 *
 * Revision 0 is the current layout and the only one this adapter has ever stored; the native JSONL file itself has
 * no revision field, so this number names the layout implemented here. An incompatible field meaning, required
 * entry or native session layout needs a new revision plus a researched migration/backup policy. These numbers are
 * neither Pi's release version nor another connector's history revision.
 */
internal object PiHistoryFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
