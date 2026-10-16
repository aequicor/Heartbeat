package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconCandidate
import io.aequicor.heartbeat.feature.browser.impl.domain.MAX_FAVICON_URL_LENGTH
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Reads the icon links of a tab's loaded main document through the tab's private CDP transport. The script runs in
 * its own isolated world, so page scripts cannot replace the DOM methods it calls; what it reads is still page content
 * and is validated by the caller. JCEF 152 reports no favicon URL of its own.
 */
internal class DesktopBrowserFaviconProbe(private val transport: suspend (String, JsonObject) -> String) {
    private val worldName = "heartbeat-icons-" + Uuid.random()

    suspend fun candidates(): List<BrowserFaviconCandidate> {
        val frame = call("Page.getFrameTree", JsonObject(emptyMap()))
            .getValue("frameTree").jsonObject.getValue("frame").jsonObject.getValue("id")
        val context = call(
            "Page.createIsolatedWorld",
            buildJsonObject {
                put("frameId", frame)
                put("worldName", worldName)
            },
        ).getValue("executionContextId")
        val response = call(
            "Runtime.evaluate",
            buildJsonObject {
                put("contextId", context)
                put("expression", SCRIPT)
                put("returnByValue", true)
            },
        )
        check(response["exceptionDetails"] == null) { "Page icons cannot be read" }
        val links = response["result"]?.jsonObject?.get("value") as? JsonArray ?: return emptyList()
        return links.take(MAX_LINKS).mapNotNull { link ->
            val fields = link as? JsonObject ?: return@mapNotNull null
            fun field(name: String) = fields[name]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_FIELD_LENGTH)
            BrowserFaviconCandidate(field("url"), field("rel"), field("sizes"), field("type"))
        }
    }

    private suspend fun call(method: String, args: JsonObject): JsonObject =
        Json.parseToJsonElement(transport(method, args)).jsonObject

    private companion object {
        const val MAX_LINKS = 64
        const val MAX_FIELD_LENGTH = MAX_FAVICON_URL_LENGTH + 1
        val SCRIPT = """
            Array.from(document.querySelectorAll('link[rel][href]'))
              .filter(l => /(^|\s)(icon|apple-touch-icon|apple-touch-icon-precomposed)(\s|$)/i.test(l.rel))
              .slice(0, $MAX_LINKS)
              .map(l => ({url: l.href, rel: l.rel, sizes: l.getAttribute('sizes') || '', type: l.type || ''}))
        """.trimIndent()
    }
}
