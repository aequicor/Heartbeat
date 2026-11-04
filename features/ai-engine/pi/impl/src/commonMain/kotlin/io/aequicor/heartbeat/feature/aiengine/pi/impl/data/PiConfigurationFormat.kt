package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

/**
 * Revision of the stored Pi configuration layout Heartbeat writes in the profile: [PiConfiguration] as the
 * `pi_engine` key-value entry (binding routes and workspace directories) plus the documents of [PiConfigSchema].
 * Revision 0 is the current layout, the only one this adapter has ever stored. These numbers identify the stored
 * contract, never the app or Pi version; an incompatible change needs a new revision plus a researched migration
 * instead of silently reinterpreting an older entry.
 */
internal object PiConfigurationFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
