package io.aequicor.heartbeat.chromiumplugin.panel

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The extension page as tests drive it: delivered messages and what the panel posted. */
internal class FakePanelHost : PanelHost {
    private var listener: ((String) -> Unit)? = null
    private val early = mutableListOf<String>()
    val posted = mutableListOf<JsonObject>()

    override fun listen(onMessage: (String) -> Unit) {
        listener = onMessage
        early.toList().also { early.clear() }.forEach(onMessage)
    }

    override fun post(json: String) {
        posted += Json.parseToJsonElement(json).jsonObject
    }

    var capture: suspend () -> String? = { null }
    var selection: String? = null
    var selectionArrival: () -> Unit = {}

    override suspend fun capturePage(): String? = capture()

    override suspend fun takeSelection(): String? = selection.also { selection = null }

    override fun onSelection(onArrival: () -> Unit) {
        selectionArrival = onArrival
    }

    /** A message of the service worker; kept until the panel listens, like `panel-host.js`. */
    fun deliver(json: String) {
        listener?.invoke(json) ?: early.add(json)
    }
}
