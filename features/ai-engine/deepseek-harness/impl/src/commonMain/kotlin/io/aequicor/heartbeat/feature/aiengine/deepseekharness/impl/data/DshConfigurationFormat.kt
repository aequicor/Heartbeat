package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

/**
 * Revision of the DSH launch/configuration contract Heartbeat owns and writes for every process: the selected
 * `dsh` executable, the `acp` profile argument, the process-only `--patch` overlay created by [DeepSeekAcpPatch],
 * the `DSH_PERMISSION_MODE` floor, the optional `DSH_HOME` and the workspace/home working directory. Heartbeat
 * never writes the user's `~/.dsh` configuration; the temporary overlay is the only configuration layout this
 * adapter authors. Revision 0 is the current contract and the only one this adapter has ever produced. These
 * numbers identify the launch contract, never the app or DSH build; an incompatible change needs a new revision
 * plus a researched migration instead of silently reinterpreting an older launch.
 */
internal object DshConfigurationFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
