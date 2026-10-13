package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory documents of the profile: they live until their handles close them or the profile closes. Ids are random
 * 128-bit hex, a valid host label for the document's own origin.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<BrowserDocuments>())
@ContributesBinding(ProfileScope::class, binding = binding<BrowserDocumentRegistry>())
@Inject
internal class ProfileBrowserDocuments :
    BrowserDocuments,
    BrowserDocumentRegistry {
    private val log = Log.tag("BrowserDocuments")
    private val open = MutableStateFlow<Map<String, OpenBrowserDocument>>(emptyMap())

    override val documents: StateFlow<Map<String, OpenBrowserDocument>> = open.asStateFlow()

    override fun open(document: BrowserDocument): BrowserDocumentHandle? {
        if (document.html.encodeToByteArray().size > BrowserDocumentLimits.HTML_BYTES || document.data.isOversized()) {
            log.w { "Document rejected: its HTML or data exceeds the limit" }
            return null
        }
        val id = newDocumentId()
        val data = MutableStateFlow(document.data)
        val entry = OpenBrowserDocument(id, document, data.asStateFlow())
        var isAdded = false
        open.update { current ->
            isAdded = current.size < BrowserDocumentLimits.DOCUMENTS
            if (isAdded) current + (id to entry) else current
        }
        if (!isAdded) {
            log.w { "Document rejected: ${BrowserDocumentLimits.DOCUMENTS} documents are open" }
            return null
        }
        log.d { "Document opened (bridge=${document.bridge != null})" }
        return Handle(id, data)
    }

    private inner class Handle(private val id: String, private val data: MutableStateFlow<JsonElement?>) :
        BrowserDocumentHandle {
        override val route: BrowserDocumentRoute = BrowserDocumentRoute(id)

        override fun update(data: JsonElement?) {
            when {
                id !in open.value -> Unit
                data.isOversized() -> log.w { "Document data ignored: it exceeds the limit" }
                else -> this.data.value = data
            }
        }

        override fun close() {
            var isRemoved = false
            open.update { current ->
                isRemoved = id in current
                current - id
            }
            if (isRemoved) log.d { "Document closed" }
        }
    }
}

private fun JsonElement?.isOversized(): Boolean =
    this != null && toString().encodeToByteArray().size > BrowserDocumentLimits.DATA_BYTES

@OptIn(ExperimentalUuidApi::class)
private fun newDocumentId(): String = Uuid.random().toHexString()
