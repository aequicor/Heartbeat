package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

private val log = Log.tag("DS/ChatLinks")

/** Opens external chat links through the platform; local destinations require a caller-owned handler. */
@Composable
internal fun rememberChatLinkHandler(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return remember(handler) { { destination -> openChatLink(handler, destination) } }
}

internal fun openChatLink(handler: UriHandler, destination: String) {
    if (destination.substringBefore(':', "").lowercase() !in setOf("https", "http", "mailto")) return
    try {
        handler.openUri(destination)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Chat link could not be opened" }
    }
}
