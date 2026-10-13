package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Browser documents of the `web` nodes one surface draws. A node gets its document while it is drawn and web
 * documents are on; a new HTML or bridge setting replaces the document, a new data binding value updates it, and the
 * document closes when the node leaves, web documents turn off or the surface closes ([close]). Only a node with
 * `bridge` gets [bridge] (null for surfaces that may not call methods). A document is named by its modal's [title] or
 * the surface's. Confined to the store of the surface: its start, state changes and stop call it one at a time.
 */
internal class SurfaceWebDocuments(
    private val documents: UiWebDocuments,
    private val bridge: BrowserDocumentBridge?,
    private val title: String,
) {
    private val log = Log.tag("ServiceUiWeb")
    private val open = mutableMapOf<String, Opened>()
    private var drawn: List<Drawn> = emptyList()
    private var isEnabled = false
    private var isClosed = false
    private val routes = MutableStateFlow<ImmutableMap<String, String>>(persistentMapOf())

    /** Route ids of the open documents by the key of their node. */
    val shown: StateFlow<ImmutableMap<String, String>> = routes.asStateFlow()

    /** The surface now draws [state]. */
    fun show(state: SurfaceState) = sync { drawn = state.webs() }

    /** Web documents were turned on or off. */
    fun enable(isEnabled: Boolean) = sync { this.isEnabled = isEnabled }

    /** The surface closed: its documents close for good. */
    fun close() = sync { isClosed = true }

    private fun sync(change: () -> Unit) {
        change()
        val wanted = if (isEnabled && !isClosed) drawn.associateBy { it.key } else emptyMap()
        open.keys.filter { key -> wanted[key]?.let { open.getValue(key).isSame(it) } != true }.forEach { key ->
            open.remove(key)?.handle?.close()
            log.d { "Web document of a surface closed" }
        }
        wanted.values.forEach { node ->
            val current = open[node.key]
            if (current == null) {
                opened(node)?.let { open[node.key] = it }
            } else if (current.data != node.data) {
                current.handle.update(node.data)
                open[node.key] = current.copy(data = node.data)
            }
        }
        routes.value = open.mapValues { it.value.handle.route.id }.toImmutableMap()
    }

    private fun opened(node: Drawn): Opened? {
        val document = BrowserDocument(node.html, node.title, node.data, bridge.takeIf { node.hasBridge })
        val handle = documents.open(document)
        if (handle == null) {
            log.w { "Web document of a surface refused by the browser" }
            return null
        }
        log.d { "Web document of a surface opened (bridge=${document.bridge != null})" }
        return Opened(handle, node.html, node.hasBridge, node.data)
    }

    /** A `web` node as drawn: the document opens with [title] as its accessible name. */
    private data class Drawn(
        val key: String,
        val html: String,
        val hasBridge: Boolean,
        val data: JsonElement?,
        val title: String,
    )

    private data class Opened(
        val handle: BrowserDocumentHandle,
        val html: String,
        val hasBridge: Boolean,
        val data: JsonElement?,
    ) {
        /** The same document still fits [node]; data alone is updated in place. */
        fun isSame(node: Drawn): Boolean = html == node.html && hasBridge == node.hasBridge
    }

    private fun SurfaceState.webs(): List<Drawn> = root.webs(title) + modal?.body.webs(modal?.title ?: title)

    private fun UiNodeUi?.webs(title: String): List<Drawn> = when (this) {
        is UiNodeUi.Web -> listOf(Drawn(key, html, hasBridge, data, title))

        is UiNodeUi.Column -> children.flatMap { it.webs(title) }

        is UiNodeUi.Row -> children.flatMap { it.webs(title) }

        is UiNodeUi.Card -> children.flatMap { it.webs(this.title ?: title) }

        is UiNodeUi.Section -> children.flatMap { it.webs(this.title) }

        // Only layouts hold other nodes; the rest and an absent root draw no documents.
        null, is UiNodeUi.Content, is UiNodeUi.Input, is UiNodeUi.Divider, is UiNodeUi.Spacer -> emptyList()
    }
}
