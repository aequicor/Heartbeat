package io.aequicor.heartbeat.core.desktopdialogs

import java.awt.KeyboardFocusManager
import java.awt.Window

/**
 * The window holding keyboard focus: the owner of the dialog, which makes it modal, centered on the
 * application and visible in front of it. Null before the first window is shown or in a headless run.
 */
internal fun focusedWindow(): Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

/** Explorer type mask for the dialog's type list: `*.png;*.jpg`. */
internal fun windowsFileMask(extensions: List<String>): String = extensions.joinToString(";") { "*.$it" }

/** Name filter of the AWT panels: an empty list accepts every file, matching is case-insensitive. */
internal fun matchesExtensions(name: String, extensions: List<String>): Boolean =
    extensions.isEmpty() || extensions.any { name.endsWith(".$it", ignoreCase = true) }
