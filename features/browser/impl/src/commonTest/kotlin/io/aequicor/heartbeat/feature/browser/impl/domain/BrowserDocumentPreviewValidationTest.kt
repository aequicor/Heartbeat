package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserDocumentPreviewValidationTest {
    @Test
    fun `bounded viewport and scaling endpoints are accepted`() {
        val request = BrowserDocumentPreviewRequest("<p>Preview</p>")
        assertTrue(request.isValidPreview())
        for (width in listOf(BrowserDocumentPreviewLimits.MIN_WIDTH, BrowserDocumentPreviewLimits.MAX_WIDTH)) {
            for (height in listOf(BrowserDocumentPreviewLimits.MIN_HEIGHT, BrowserDocumentPreviewLimits.MAX_HEIGHT)) {
                for (scale in listOf(0.8f, 2f)) {
                    assertTrue(request.copy(width = width, height = height, fontScale = scale).isValidPreview())
                }
            }
        }
    }

    @Test
    fun `oversized viewports and nonfinite scaling are rejected before rendering`() {
        val request = BrowserDocumentPreviewRequest("<p/>")
        assertFalse(request.copy(html = " \n\t").isValidPreview())
        for (width in listOf(-1, 0, 319, 1601, Int.MAX_VALUE)) {
            assertFalse(request.copy(width = width).isValidPreview())
        }
        for (height in listOf(-1, 0, 239, 1201, Int.MAX_VALUE)) {
            assertFalse(request.copy(height = height).isValidPreview())
        }
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, 0.79f, 2.01f)) {
            assertFalse(request.copy(fontScale = scale).isValidPreview())
        }
    }

    @Test
    fun `deep direct API data is rejected before recursive serialization`() {
        var data: JsonElement = JsonPrimitive("sample")
        repeat(10_000) { data = JsonArray(listOf(data)) }
        assertFalse(BrowserDocumentPreviewRequest("<p/>", data = data).isValidPreview())
    }

    @Test
    fun `HTML and sample data are bounded by encoded bytes`() {
        val html = "я".repeat(BrowserDocumentLimits.HTML_BYTES / 2)
        val request = BrowserDocumentPreviewRequest(html)
        assertTrue(request.isValidPreview())
        assertFalse(request.copy(html = html + "я").isValidPreview())
        val sample = "a".repeat(BrowserDocumentLimits.DATA_BYTES - 2)
        assertTrue(request.copy(data = JsonPrimitive(sample)).isValidPreview())
        assertFalse(request.copy(data = JsonPrimitive(sample + "a")).isValidPreview())
    }
}
