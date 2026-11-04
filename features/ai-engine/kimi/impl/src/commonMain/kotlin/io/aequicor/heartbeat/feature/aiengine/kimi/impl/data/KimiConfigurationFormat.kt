package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

/**
 * Revision of the Kimi launch/runtime contract Heartbeat owns and enforces for every process: the controlled
 * `kimi acp` executable selected from the adapter's pinned Heartbeat Kimi release (managed or bundled, never an
 * arbitrary path), the `acp` argument and workspace of the launch configuration, and the `runtime.properties` pin
 * (`version`, `sourceCommit`, `permissionGate`, `sha256`, `target`) the adapter validates before use. Revision 0 is
 * the current contract and the only one this adapter has ever enforced. These numbers identify the launch contract,
 * never the app or Kimi build; an incompatible change needs a new revision plus a researched migration instead of
 * silently reinterpreting an older launch.
 */
internal object KimiConfigurationFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
