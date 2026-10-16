package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserZoom

/** Tab commands bound to keys as in desktop Chromium. */
internal sealed interface BrowserShortcut {
    data class Zoom(val direction: BrowserZoomDirection) : BrowserShortcut

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

    key in "=+-0" -> browserZoomShortcut(key)

    isShift -> null

    key == 'w' -> BrowserShortcut.CloseTab

    key in '1'..'8' -> BrowserShortcut.SelectTab(key - '0')

    key == '9' -> BrowserShortcut.SelectTab(null)

    else -> null
}

/** Whether the host's command modifier is Cmd (macOS, iOS) rather than Ctrl. */
internal expect fun isBrowserMetaShortcut(): Boolean

/** Presentation commands shared by Compose and native browser input. */
internal enum class BrowserZoomDirection {
    In,
    Out,
    Reset,
}

internal fun BrowserZoomDirection.toDomain(): BrowserZoom = when (this) {
    BrowserZoomDirection.In -> BrowserZoom.In
    BrowserZoomDirection.Out -> BrowserZoom.Out
    BrowserZoomDirection.Reset -> BrowserZoom.Reset
}

private fun browserZoomShortcut(key: Char): BrowserShortcut.Zoom = BrowserShortcut.Zoom(
    when (key) {
        '+', '=' -> BrowserZoomDirection.In
        '-' -> BrowserZoomDirection.Out
        else -> BrowserZoomDirection.Reset
    },
)
