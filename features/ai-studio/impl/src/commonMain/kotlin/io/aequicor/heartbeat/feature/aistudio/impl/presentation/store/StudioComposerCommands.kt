package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_COMMAND

/** [draft] starting with the `/remember` command once, keeping the text the user already typed. */
internal fun withRememberCommand(draft: String): String {
    val typed = draft.trimStart()
    val rest = typed.removePrefix(REMEMBER_COMMAND)
    // `/rememberance` is text, not the command: only a separate command word is removed.
    val text = if (rest.length < typed.length && rest.firstOrNull()?.isWhitespace() != false) rest else typed
    return "$REMEMBER_COMMAND ${text.trimStart()}"
}
