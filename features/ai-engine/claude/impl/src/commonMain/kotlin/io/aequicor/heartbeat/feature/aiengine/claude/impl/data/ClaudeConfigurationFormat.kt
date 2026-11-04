package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

/**
 * Revision of the Claude launch contract Heartbeat owns and writes into every CLI process: the base
 * `--print --verbose --output-format stream-json` flags, `--setting-sources=` and `--strict-mcp-config`, the generated
 * tool selections (`--tools`, `--allowedTools`, `--disallowedTools`), model/effort/session flags, the hosted and
 * search MCP bridge files and the native gate flags. Revision 0 is the current contract, the only one this adapter
 * has ever written. These numbers identify the launch contract, never the app or Claude Code build; an incompatible
 * change needs a new revision plus a researched migration instead of silently reinterpreting an older launch.
 */
internal object ClaudeConfigurationFormat {
    const val CURRENT_REVISION: Int = 0

    fun isReadable(revision: Int): Boolean = revision == CURRENT_REVISION
}
