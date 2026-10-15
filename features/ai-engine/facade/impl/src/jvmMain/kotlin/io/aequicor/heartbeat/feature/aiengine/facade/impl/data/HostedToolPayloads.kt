package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult

/** Transport budgets accommodate compiled mini-app source while bounding local HTTP allocation and JSON recursion. */
internal object HostedToolPayloads {
    const val REQUEST_BYTES = 8 * 1024 * 1024
    const val RESPONSE_CHARS = 4 * 1024 * 1024
    private const val JSON_DEPTH = 128

    /** Checks nesting before the recursive JSON parser; braces inside escaped HTML/JavaScript strings are data. */
    fun hasBoundedNesting(text: String): Boolean {
        var depth = 0
        var isString = false
        var isEscaped = false
        for (character in text) {
            if (isString) {
                when {
                    isEscaped -> isEscaped = false
                    character == '\\' -> isEscaped = true
                    character == '"' -> isString = false
                }
            } else {
                when (character) {
                    '"' -> isString = true
                    '{', '[' -> if (++depth > JSON_DEPTH) return false
                    '}', ']' -> depth--
                }
            }
        }
        return true
    }

    /** A truncated JSON/source result must never look like a successful complete tool response. */
    fun bounded(result: AgentToolResult): AgentToolResult = if (result.text.length <= RESPONSE_CHARS) {
        result
    } else {
        AgentToolResult(
            "The tool ran, but its text result exceeds the hosted bridge limit of $RESPONSE_CHARS characters. " +
                "Request a smaller or paged result. Do not repeat a mutating operation solely to retrieve its output.",
            isError = true,
        )
    }
}
