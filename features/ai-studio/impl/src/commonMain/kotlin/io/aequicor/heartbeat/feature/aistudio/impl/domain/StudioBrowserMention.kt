package io.aequicor.heartbeat.feature.aistudio.impl.domain

/** Host mention selecting the browser of the current input surface. */
internal const val BROWSER_MENTION = "@browser"

/**
 * Resolves a standalone mention in user input. Emails, paths, longer names, inline-code tokens and slash-command
 * arguments are not browser requests. The host supplies [isExternal]; prompt text never selects a connection
 * or grants control of a tab. The original prompt stays visible, while this directive is hidden from history.
 */
internal fun browserMentionDirective(prompt: String, isExternal: Boolean): String? {
    if (prompt.trimStart().startsWith("/")) return null
    val hasMention = prompt.lineSequence().any { line ->
        line.splitToSequence(' ', '\t', '\r').any { it == BROWSER_MENTION }
    }
    if (!hasMention) return null
    val route = if (isExternal) {
        "the external browser hosting this Heartbeat panel. Use ex_control_* tools; " +
            "start with ex_control_active_tab when the task refers to the current page."
    } else {
        "Heartbeat's embedded browser. Use browser_* tools; start with browser_tabs to discover its tabs."
    }
    return "The user invoked @browser for this turn: perform the requested browser task in $route " +
        "This selection applies to the current turn, even if earlier turns used another browser. " +
        "If these tools or this browser are unavailable, explain what is unavailable and do not substitute " +
        "another browser or desktop control. This mention does not grant tab ownership or bypass approvals; " +
        "use the normal control request when needed."
}
