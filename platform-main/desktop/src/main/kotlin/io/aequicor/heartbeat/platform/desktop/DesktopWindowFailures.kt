package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure

/**
 * Saves the failing frame's stack before Compose opens its modal error dialog and closes the window.
 * Exception messages may contain rendered user content; the persistent copy retains only type and frames.
 * The original exception still reaches the host's handler, including its existing fatal-error policy.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun loggedWindowExceptionHandler(delegate: WindowExceptionHandler): WindowExceptionHandler =
    WindowExceptionHandler { error ->
        Log.tag("DesktopWindow").e(error.redactedFailure()) { "Unhandled window failure" }
        delegate.onException(error)
    }
