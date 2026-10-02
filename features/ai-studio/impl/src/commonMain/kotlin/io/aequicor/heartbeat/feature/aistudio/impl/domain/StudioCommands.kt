package io.aequicor.heartbeat.feature.aistudio.impl.domain

/** Composer command that asks the agent to save the following text as a learned instruction. */
internal const val REMEMBER_COMMAND = "/remember"

/** The text after a leading [REMEMBER_COMMAND], or null when [prompt] is not such a command or has no text. */
internal fun rememberText(prompt: String): String? {
    val trimmed = prompt.trimStart()
    if (!trimmed.startsWith(REMEMBER_COMMAND)) return null
    val rest = trimmed.removePrefix(REMEMBER_COMMAND)
    if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null
    return rest.trim().takeIf { it.isNotEmpty() }
}

/** Host directive sent with a `/remember` prompt; the transcript shows only what the user typed. */
internal const val REMEMBER_DIRECTIVE: String =
    "The user typed /remember: save the text after the command as a learned instruction now. Call the remember " +
        "tool exactly once before anything else. Keep the user's meaning and wording; pick the kind (general, " +
        "model when it concerns only you as this engine or model, skill for a multi-step procedure) and rate " +
        "safety honestly. Then confirm in one short sentence and do no other work in this turn."
