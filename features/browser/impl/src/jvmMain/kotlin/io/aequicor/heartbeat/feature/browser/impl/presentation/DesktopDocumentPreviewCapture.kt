package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewElement
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssue
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssueKind
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewMetrics
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** Capture is a fixed CDP protocol, scoped only to the temporary document; there is no caller-supplied command. */
internal suspend fun captureDocumentPreview(
    runtime: DesktopDocumentEngine,
    request: BrowserDocumentPreviewRequest,
): BrowserDocumentPreviewResult.Rendered {
    runtime.previewCommand(
        "Emulation.setDeviceMetricsOverride",
        buildJsonObject {
            put("width", request.width)
            put("height", request.height)
            put("deviceScaleFactor", 1)
            put("mobile", false)
        },
    )
    val frameTree = runtime.previewCommand("Page.getFrameTree", buildJsonObject {}).boundedObject()
    val frameId = frameTree.getValue("frameTree").jsonObject.getValue("frame").jsonObject.string("id")
    val world = runtime.previewCommand(
        "Page.createIsolatedWorld",
        buildJsonObject {
            put("frameId", frameId)
            put("worldName", "heartbeat-preview")
            put("grantUniveralAccess", false)
        },
    ).boundedObject().getValue("executionContextId").jsonPrimitive.int
    val evaluated = runtime.previewCommand(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", documentPreviewScript(request.resourcePolicy))
            put("contextId", world)
            put("awaitPromise", true)
            put("returnByValue", true)
        },
    ).boundedObject()
    check("exceptionDetails" !in evaluated) { "Preview inspection failed" }
    val inspected = evaluated.getValue("result").jsonObject.getValue("value").jsonObject
    val metrics = inspected.getValue("metrics").jsonObject.previewMetrics()
    check(
        metrics.viewportWidth == request.width && metrics.viewportHeight == request.height,
    ) { "Preview viewport changed" }
    val issues = inspected.getValue("issues").jsonArray.take(BrowserDocumentPreviewLimits.ISSUES).map {
        val issue = it.jsonObject
        BrowserDocumentPreviewIssue(
            BrowserDocumentPreviewIssueKind.valueOf(issue.string("kind")),
            issue.string("element"),
        )
    }.toMutableList()
    var isTruncated = inspected.getValue("issuesTruncated").jsonPrimitive.boolean
    if (runtime.scriptErrorCount > 0) {
        if (issues.size < BrowserDocumentPreviewLimits.ISSUES) {
            issues += BrowserDocumentPreviewIssue(BrowserDocumentPreviewIssueKind.ScriptError, "document")
        } else {
            isTruncated = true
        }
    }
    val samples = inspected.getValue("samples").jsonArray.take(BrowserDocumentPreviewLimits.SAMPLES).map {
        it.jsonObject.previewSample()
    }
    val encoded = capturePreviewPng(runtime, request)
    return BrowserDocumentPreviewResult.Rendered(encoded, metrics, issues, isTruncated, samples)
}

/** Both transport and decoded PNG are bounded before returning image content to an agent. */
private suspend fun capturePreviewPng(runtime: DesktopDocumentEngine, request: BrowserDocumentPreviewRequest): String {
    val raw = runtime.previewCommand(
        "Page.captureScreenshot",
        buildJsonObject {
            put("format", "png")
            put("captureBeyondViewport", false)
            put(
                "clip",
                buildJsonObject {
                    put("x", 0)
                    put("y", 0)
                    put("width", request.width)
                    put("height", request.height)
                    put("scale", 1)
                },
            )
        },
    )
    if (raw.length > MAX_IMAGE_REPLY_CHARS) throw PreviewImageTooLarge()
    val encoded = Json.parseToJsonElement(raw).jsonObject.string("data", MAX_IMAGE_REPLY_CHARS)
    val png = Base64.getDecoder().decode(encoded)
    if (png.size > BrowserDocumentPreviewLimits.IMAGE_BYTES) throw PreviewImageTooLarge()
    check(png.size >= PNG_HEADER_BYTES && PNG_SIGNATURE.indices.all { png[it] == PNG_SIGNATURE[it] })
    check(png.intAt(PNG_WIDTH_OFFSET) == request.width && png.intAt(PNG_HEIGHT_OFFSET) == request.height)
    return encoded
}

/** No page/native error message is retained by this expected bound failure. */
internal class PreviewImageTooLarge : IllegalStateException("Preview image limit exceeded")

/** Chromium responses are bounded before parsing, and every reported scalar is validated again on the host. */
private fun String.boundedObject(): JsonObject {
    check(length <= MAX_DIAGNOSTIC_CHARS) { "Preview diagnostics limit exceeded" }
    return Json.parseToJsonElement(this).jsonObject
}

private fun JsonObject.previewMetrics(): BrowserDocumentPreviewMetrics = BrowserDocumentPreviewMetrics(
    viewportWidth = coordinate("viewportWidth").toInt(),
    viewportHeight = coordinate("viewportHeight").toInt(),
    contentWidth = coordinate("contentWidth").toInt(),
    contentHeight = coordinate("contentHeight").toInt(),
    hasHorizontalOverflow = getValue("horizontalOverflow").jsonPrimitive.boolean,
)

private fun JsonObject.previewSample(): BrowserDocumentPreviewElement = BrowserDocumentPreviewElement(
    element = string("element"),
    tag = string("tag"),
    x = coordinate("x"),
    y = coordinate("y"),
    width = coordinate("width"),
    height = coordinate("height"),
    fontFamily = string("fontFamily"),
    fontSize = coordinate("fontSize"),
    lineHeight = string("lineHeight"),
    color = string("color"),
    backgroundColor = string("backgroundColor"),
)

private fun JsonObject.string(key: String, limit: Int = BrowserDocumentPreviewLimits.TEXT_CHARS): String =
    getValue(key).jsonPrimitive.content.also { check(it.length <= limit) { "Preview string limit exceeded" } }

private fun JsonObject.coordinate(key: String): Double = getValue(key).jsonPrimitive.double.also {
    check(it.isFinite() && it >= -BrowserDocumentPreviewLimits.METRIC && it <= BrowserDocumentPreviewLimits.METRIC)
}

private fun ByteArray.intAt(offset: Int): Int {
    var value = 0
    repeat(INTEGER_BYTES) { byte -> value = (value shl BITS_PER_BYTE) or (this[offset + byte].toInt() and BYTE_MASK) }
    return value
}

private const val MAX_DIAGNOSTIC_CHARS = 64 * 1024
private const val MAX_IMAGE_REPLY_CHARS = (BrowserDocumentPreviewLimits.IMAGE_BYTES + 2) / 3 * 4 + 128
private const val PNG_HEADER_BYTES = 24
private const val PNG_WIDTH_OFFSET = 16
private const val PNG_HEIGHT_OFFSET = 20
private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private const val INTEGER_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 255
