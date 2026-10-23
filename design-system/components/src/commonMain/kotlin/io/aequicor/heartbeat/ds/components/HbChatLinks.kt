package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

private val log = Log.tag("DS/ChatLinks")

/** Opens external chat links through the platform; local destinations require a caller-owned handler. */
@Composable
internal fun rememberChatLinkHandler(): (String) -> Unit = rememberHbLinkHandler()

/**
 * Link handler of chat Markdown: web, mail and phone links open through the platform; file links go to [onFile]
 * (a preview owned by the host) and stay inert without it. Anchors and unsupported destinations do nothing.
 */
@Composable
public fun rememberHbLinkHandler(onFile: ((HbLinkTarget.File) -> Unit)? = null): (String) -> Unit {
    val handler = LocalUriHandler.current
    val latestOnFile = rememberUpdatedState(onFile)
    return remember(handler) { { destination -> openChatLink(handler, destination, latestOnFile.value) } }
}

internal fun openChatLink(handler: UriHandler, destination: String, onFile: ((HbLinkTarget.File) -> Unit)? = null) {
    when (val target = parseHbLink(destination)) {
        is HbLinkTarget.Web, is HbLinkTarget.Mail, is HbLinkTarget.Phone -> openExternal(handler, target.destination)

        is HbLinkTarget.File -> if (onFile != null) {
            onFile(target)
        } else {
            log.d { "File link has no preview in this host" }
        }

        is HbLinkTarget.Anchor, null -> log.d { "Chat link has no action" }
    }
}

private fun openExternal(handler: UriHandler, destination: String) {
    try {
        handler.openUri(destination)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Chat link could not be opened" }
    }
}
