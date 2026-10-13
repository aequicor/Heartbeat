package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

/** The `/remember` command of the composer; equals the domain `REMEMBER_COMMAND`. */
const val COMPOSER_REMEMBER_COMMAND: String = "/remember"

/** [draft] starting with the `/remember` command once, keeping the text the user already typed. */
fun withRememberCommand(draft: String): String {
    val typed = draft.trimStart()
    val rest = typed.removePrefix(COMPOSER_REMEMBER_COMMAND)
    // `/rememberance` is text, not the command: only a separate command word is removed.
    val text = if (rest.length < typed.length && rest.firstOrNull()?.isWhitespace() != false) rest else typed
    return "$COMPOSER_REMEMBER_COMMAND ${text.trimStart()}"
}
