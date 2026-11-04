package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

/**
 * Wire revisions of the profile's `ai_koog_sessions/sessions` JSON array, decoded with ignoreUnknownKeys and
 * encoded without defaults. Revision 0 is the original untagged KoogRecord; it is never assumed to be revision 1.
 * Revision 1 uses the same payload plus explicit schemaRevision=1. New native sessions write revision 1;
 * checkpoints of existing legacy sessions retain revision 0, so reading or resuming does not migrate them.
 *
 * Incompatible field meanings, required fields or native session layout require a new revision and a researched
 * migration/backup policy. Unsupported revisions fail decoding and stay as raw elements in StoredJsonList,
 * including when another session is written. These numbers identify a real format, never app/SDK versions.
 */
internal object KoogHistoryFormat {
    const val LEGACY_REVISION: Int = 0
    const val CURRENT_REVISION: Int = 1

    fun isReadable(revision: Int): Boolean = revision == LEGACY_REVISION || revision == CURRENT_REVISION
}
