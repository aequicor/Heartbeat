package io.aequicor.heartbeat.feature.browser.impl.presentation

/** Tab commands bound to keys as in desktop Chromium. */
internal sealed interface BrowserShortcut {
    data object NewTab : BrowserShortcut

    data object CloseTab : BrowserShortcut

    /** Opens the most recently closed tab's address again; its native history does not survive closing. */
    data object ReopenClosedTab : BrowserShortcut

    data object NextTab : BrowserShortcut

    data object PreviousTab : BrowserShortcut

    /** [position] counts from 1 among the visible group's tabs; null selects its last tab. */
    data class SelectTab(val position: Int?) : BrowserShortcut
}

/**
 * Chromium's tab keys. [isAccelerator] is the host's command modifier alone (Cmd on macOS, Ctrl elsewhere); Ctrl+Tab
 * switches tabs on every host. [key] is the key's character in the US layout ('t', 'w', '1'…'9') or a tab character.
 */
internal fun browserShortcut(
    key: Char,
    isAccelerator: Boolean,
    isControl: Boolean,
    isShift: Boolean,
    isAlt: Boolean,
): BrowserShortcut? = when {
    isAlt -> null

    key == '\t' -> if (!isControl) {
        null
    } else if (isShift) {
        BrowserShortcut.PreviousTab
    } else {
        BrowserShortcut.NextTab
    }

    !isAccelerator -> null

    key == 't' -> if (isShift) BrowserShortcut.ReopenClosedTab else BrowserShortcut.NewTab

    isShift -> null

    key == 'w' -> BrowserShortcut.CloseTab

    key in '1'..'8' -> BrowserShortcut.SelectTab(key - '0')

    key == '9' -> BrowserShortcut.SelectTab(null)

    else -> null
}

/** Whether the host's command modifier is Cmd (macOS, iOS) rather than Ctrl. */
internal expect fun isBrowserMetaShortcut(): Boolean
