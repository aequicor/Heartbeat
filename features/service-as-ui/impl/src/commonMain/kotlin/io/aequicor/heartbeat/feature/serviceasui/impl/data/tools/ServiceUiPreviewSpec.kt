package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One disposable WebView render; its schema provides no owner, URL, bridge or arbitrary evaluation command. */
internal val ServiceUiPreviewSpec = AgentToolSpec(
    name = ServiceUiTools.PREVIEW,
    action = AgentToolAction.Command,
    description = "Render an HTML draft with Heartbeat's renderer. Runs scripts and may load public HTTPS assets; " +
        "uses the profile permission setting. Returns layout diagnostics and an optional PNG. " +
        "No owner methods, saved interfaces, project or harness are required. WebView only.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("html") }
        putJsonObject("properties") {
            putJsonObject("html") {
                put("type", "string")
                put(
                    "description",
                    "Complete web.html draft including inline CSS and JavaScript, at most 1 MiB in UTF-8.",
                )
            }
            putJsonObject("data") {
                put("description", "Optional sample JSON supplied as heartbeat.data; no owner data is loaded.")
            }
            dimension("width", BrowserDocumentPreviewLimits.MIN_WIDTH, BrowserDocumentPreviewLimits.MAX_WIDTH, 900)
            dimension("height", BrowserDocumentPreviewLimits.MIN_HEIGHT, BrowserDocumentPreviewLimits.MAX_HEIGHT, 640)
            putJsonObject("theme") {
                put("type", "string")
                putJsonArray("enum") {
                    add("light")
                    add("dark")
                }
                put("default", "light")
                put("description", "Host theme for this isolated preview.")
            }
            putJsonObject("fontScale") {
                put("type", "number")
                put("minimum", BrowserDocumentPreviewLimits.MIN_FONT_SCALE)
                put("maximum", BrowserDocumentPreviewLimits.MAX_FONT_SCALE)
                put("default", JsonPrimitive(1))
                put("description", "Host text scale; typography tokens follow it.")
            }
            putJsonObject("includeScreenshot") {
                put("type", "boolean")
                put("default", false)
                put(
                    "description",
                    "Set true only when the model supports image tool results. Default false returns text " +
                        "diagnostics only, which cannot establish visual quality.",
                )
            }
        }
    },
)

private fun JsonObjectBuilder.dimension(name: String, minimum: Int, maximum: Int, default: Int) {
    putJsonObject(name) {
        put("type", "integer")
        put("minimum", minimum)
        put("maximum", maximum)
        put("default", default)
        put("description", "Viewport $name in CSS pixels; the image covers this viewport.")
    }
}
