package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

/**
 * Revision of the Codex configuration contract Heartbeat owns and writes into every app-server launch: the
 * typed `-c` overrides applied by this adapter and [CodexNativeOff] (`model_provider`, `model_catalog_json`,
 * `features.*`, `web_search`, `agents.enabled`, `default_mode_request_user_input`). Revision 0 is the current
 * layout, the only one this adapter has ever written. These numbers identify the configuration contract, never
 * the app or Codex build; an incompatible change needs a new revision plus a researched migration instead of
 * silently reinterpreting an older launch.
 */
internal object CodexConfigurationFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
