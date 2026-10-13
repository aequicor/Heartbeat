package io.aequicor.heartbeat.chromiumplugin.panel

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelHost
import kotlinx.coroutines.await
import kotlin.js.Promise

/** `globalThis.heartbeatPanel`, installed by `chromium-plugin/src/sidepanel/panel-boot.js` before the panel starts. */
internal external interface PanelHostJs : JsAny {
    fun listen(callback: (String) -> Unit)

    fun post(json: String)

    fun capturePage(): Promise<JsString?>

    fun takeSelection(): Promise<JsString?>

    fun onSelection(callback: () -> Unit)
}

@JsFun("() => globalThis.heartbeatPanel ?? null")
internal external fun panelHostJs(): PanelHostJs?

/** [PanelHost] over the extension page; failed promises of Chrome APIs surface as [PageReadException]. */
internal class ChromePanelHost(private val js: PanelHostJs) : PanelHost {
    override fun listen(onMessage: (String) -> Unit) = js.listen(onMessage)

    override fun post(json: String) = js.post(json)

    override suspend fun capturePage(): String? = read { js.capturePage() }

    override suspend fun takeSelection(): String? = read { js.takeSelection() }

    override fun onSelection(onArrival: () -> Unit) = js.onSelection(onArrival)

    private suspend fun read(call: () -> Promise<JsString?>): String? = try {
        call().await<JsString?>()?.toString()
    } catch (e: JsException) {
        throw PageReadException(e)
    }
}

/** A Chrome API or page script failed while reading a page; the JS message may carry the page address. */
internal class PageReadException(cause: Throwable) : Exception("Page could not be read", cause)
