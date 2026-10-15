package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.WebHeightUi
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
 * the surface's. A document the browser refuses (over its limits) is not asked for again until its node changes or
 * web documents are turned on again; the browser logs refusals and openings itself. Confined to the store of the
 * surface: its plugin calls run on the store's single main context with `parallelIntents = false`
 * (`HeartbeatStoreFactory`), one at a time.
 */
internal class SurfaceWebDocuments(
    private val documents: UiWebDocuments,
    private val bridge: BrowserDocumentBridge?,
    private val title: String,
) {
    private val log = Log.tag("ServiceUiWeb")
    private val open = mutableMapOf<String, Opened>()
    private val refused = mutableMapOf<String, Drawn>()
    private var drawn: List<Drawn> = emptyList()
    private var configuration = UiWebConfiguration()
    private var isClosed = false
    private val routes = MutableStateFlow<ImmutableMap<String, String>>(persistentMapOf())

    /** Route ids of the open documents by the key of their node. */
    val shown: StateFlow<ImmutableMap<String, String>> = routes.asStateFlow()

    /** The surface now draws [state]: every state change of the store, so nothing here logs. */
    @HighFrequency
    fun show(state: SurfaceState) = sync { drawn = state.webs() }

    /** A policy change revokes retained pages, including their old scripts, drafts and pending bridge calls. */
    fun configure(configuration: UiWebConfiguration) = sync {
        if (this.configuration.resourcePolicy != configuration.resourcePolicy) {
            open.values.forEach { it.handle.close() }
            open.clear()
        }
        this.configuration = configuration
        refused.clear()
    }

    /** The surface closed: its documents close for good. */
    fun close() = sync { isClosed = true }

    @HighFrequency
    private fun sync(change: () -> Unit) {
        change()
        val wanted = if (configuration.isEnabled && !isClosed) drawn.associateBy { it.key } else emptyMap()
        open.keys.filter { key -> wanted[key]?.let { open.getValue(key).isSame(it) } != true }.forEach { key ->
            open.remove(key)?.handle?.close()
        }
        refused.keys.retainAll { key -> wanted[key]?.let { refused.getValue(key).isSameDocument(it) } == true }
        wanted.values.forEach { node ->
            val current = open[node.key]
            if (current == null) {
                if (node.key !in refused) {
                    val document = opened(node)
                    if (document == null) refused[node.key] = node else open[node.key] = document
                }
            } else if (current.node.data != node.data) {
                current.handle.update(node.data)
                open[node.key] = current.copy(node = node)
            }
        }
        val next = open.mapValues { it.value.handle.route.id }.toImmutableMap()
        if (next != routes.value) {
            routes.value = next
            log.v { "Web documents of a surface: ${next.size}" }
        }
    }

    private fun opened(node: Drawn): Opened? {
        val document = BrowserDocument(
            node.html,
            node.title,
            node.data,
            bridge.takeIf { node.hasBridge },
            node.sizing,
            configuration.resourcePolicy,
        )
        return documents.open(document)?.let { Opened(it, node) }
    }

    /** A `web` node as drawn: the document opens with [title] as its accessible name. */
    private data class Drawn(
        val key: String,
        val html: String,
        val hasBridge: Boolean,
        val data: JsonElement?,
        val title: String,
        val sizing: BrowserDocumentSizing,
    ) {
        /** [node] needs the same document, whatever its data. */
        fun isSameDocument(node: Drawn): Boolean =
            html == node.html && hasBridge == node.hasBridge && sizing == node.sizing
    }

    private data class Opened(val handle: BrowserDocumentHandle, val node: Drawn) {
        /** The same document still fits [next]; data alone is updated in place. */
        fun isSame(next: Drawn): Boolean = node.isSameDocument(next)
    }

    private fun SurfaceState.webs(): List<Drawn> = root.webs(title) + modal?.body.webs(modal?.title ?: title)

    private fun UiNodeUi?.webs(title: String): List<Drawn> = when (this) {
        is UiNodeUi.Web -> listOf(
            Drawn(
                key,
                html,
                hasBridge,
                data,
                title,
                if (height == WebHeightUi.Content) BrowserDocumentSizing.Content else BrowserDocumentSizing.Fixed,
            ),
        )

        is UiNodeUi.Column -> children.flatMap { it.webs(title) }

        is UiNodeUi.Row -> children.flatMap { it.webs(title) }

        is UiNodeUi.Card -> children.flatMap { it.webs(this.title ?: title) }

        is UiNodeUi.Section -> children.flatMap { it.webs(this.title) }

        // Only layouts hold other nodes; the rest and an absent root draw no documents.
        null, is UiNodeUi.Content, is UiNodeUi.Input, is UiNodeUi.Divider, is UiNodeUi.Spacer -> emptyList()
    }
}
