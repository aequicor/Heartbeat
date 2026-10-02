package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_COMMAND

/** [draft] starting with the `/remember` command once, keeping the text the user already typed. */
internal fun withRememberCommand(draft: String): String {
    val text = draft.trimStart().removePrefix(REMEMBER_COMMAND).trimStart()
    return "$REMEMBER_COMMAND $text"
}
