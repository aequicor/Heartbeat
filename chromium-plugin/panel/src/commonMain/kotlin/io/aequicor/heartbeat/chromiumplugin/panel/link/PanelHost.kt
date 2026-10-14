package io.aequicor.heartbeat.chromiumplugin.panel.link

/**
 * The extension side of the panel (`chromium-plugin/src/sidepanel/panel-host.js`): the port to the service worker,
 * page capture and selections sent from the context menu. Values cross the wasm boundary as JSON strings.
 */
internal interface PanelHost {
    /** Delivers every message of the service worker as JSON, in order; messages received earlier come first. */
    fun listen(onMessage: (String) -> Unit)

    /** Sends a message (JSON object) to the service worker. */
    fun post(json: String)

    /** The active tab as a page attachment (JSON), `null` when it cannot be attached; throws when capture fails. */
    suspend fun capturePage(): String?

    /** A selection the context menu sent to this window (JSON), taken at most once; `null` when there is none. */
    suspend fun takeSelection(): String?

    /** Opens a local PNG diagram in an active browser tab; throws if the host cannot open it. */
    suspend fun openDiagram(json: String)

    /** Calls [onArrival] whenever the context menu sends a selection to this window. */
    fun onSelection(onArrival: () -> Unit)
}
