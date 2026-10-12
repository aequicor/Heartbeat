package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopBrowserAutomationTest {
    @Test
    fun `hidden or covered element reference cannot dispatch input to another control`() = runTest {
        listOf(false to true, true to false).forEach { (isVisible, isTarget) ->
            val transport = Transport().apply {
                isElementVisible = isVisible
                isElementTarget = isTarget
            }
            val driver = DesktopBrowserAutomation(transport::call)
            val read = driver.perform(request(BrowserOperation.Read))
            assertFailsWith<IllegalArgumentException> {
                driver.perform(
                    request(
                        BrowserOperation.Click,
                        buildJsonObject {
                            put("snapshotId", read.data.getValue("snapshotId"))
                            put("element", 0)
                        },
                    ),
                )
            }
            assertTrue(transport.calls.none { it.first.startsWith("Input.") })
        }
    }

    @Test
    fun `visible hit tested element receives click before focused text insertion`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        driver.perform(
            request(
                BrowserOperation.Click,
                buildJsonObject {
                    put("snapshotId", read.data.getValue("snapshotId"))
                    put("element", 0)
                },
            ),
        )
        driver.perform(request(BrowserOperation.Type, buildJsonObject { put("text", "Search text") }))
        val input = transport.calls.filter { it.first.startsWith("Input.") }
        assertEquals(
            listOf("mousePressed", "mouseReleased"),
            input.take(2).map { it.second["type"]?.jsonPrimitive?.content },
        )
        assertTrue(
            input.take(2).all { it.second["x"] == JsonPrimitive(40.0) && it.second["y"] == JsonPrimitive(30.0) },
        )
        assertEquals("Input.insertText", input.last().first)
        assertEquals(JsonPrimitive("Search text"), input.last().second["text"])
    }

    @Test
    fun `document recovery uses original URL without reading an unrelated or blank page`() = runTest {
        val transport = Transport()
        val document = buildJsonObject {
            put("kind", "document")
            put("mediaType", "application/pdf")
            put("base64", "JVBERi0=")
        }
        val urls = mutableListOf<String>()
        val driver = DesktopBrowserAutomation(transport::call) { url ->
            urls += url
            document
        }
        val result = driver.captureResource(
            request(BrowserOperation.CaptureResource, buildJsonObject { put("url", "https://example.com/paper") }),
        )
        assertEquals(listOf("https://example.com/paper"), urls)
        assertEquals(document, result.data)
        assertTrue(transport.calls.isEmpty(), "Document loading must not depend on the active DOM")
    }

    @Test
    fun `read uses an isolated execution world`() = runTest {
        val transport = Transport()
        DesktopBrowserAutomation(transport::call).perform(request(BrowserOperation.Read))
        assertTrue(transport.calls.any { it.first == "Page.createIsolatedWorld" })
        assertTrue(
            transport.calls
                .filter { it.first == "Runtime.evaluate" }
                .all { it.second["contextId"] == JsonPrimitive(7) },
        )
    }

    @Test
    fun `mutation rejects old coordinates before any mouse input`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        transport.revision++
        assertFailsWith<IllegalArgumentException> {
            driver.perform(
                request(
                    BrowserOperation.Click,
                    buildJsonObject {
                        put("snapshotId", read.data.getValue("snapshotId"))
                        put("x", 10)
                        put("y", 10)
                    },
                ),
            )
        }
        assertTrue(transport.calls.none { it.first == "Input.dispatchMouseEvent" })
    }

    @Test
    fun `screenshot cannot reuse element references from an earlier read`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        driver.perform(request(BrowserOperation.Read))
        val shot = driver.perform(request(BrowserOperation.Screenshot))
        assertFailsWith<IllegalArgumentException> {
            driver.perform(
                request(
                    BrowserOperation.Click,
                    buildJsonObject {
                        put("snapshotId", shot.data.getValue("snapshotId"))
                        put("element", 0)
                    },
                ),
            )
        }
        assertTrue(transport.calls.none { it.first == "Input.dispatchMouseEvent" })
    }

    @Test
    fun `cancelled mouse press still releases the button`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        transport.cancelPress = true
        assertFailsWith<CancellationException> {
            driver.perform(
                request(
                    BrowserOperation.Click,
                    buildJsonObject {
                        put("snapshotId", read.data.getValue("snapshotId"))
                        put("x", 10)
                        put("y", 10)
                    },
                ),
            )
        }
        assertEquals(
            listOf("mousePressed", "mouseReleased"),
            transport.calls
                .filter { it.first == "Input.dispatchMouseEvent" }
                .map { it.second.getValue("type").jsonPrimitive.content },
        )
    }

    private fun request(operation: BrowserOperation, args: JsonObject = JsonObject(emptyMap())) =
        BrowserRequest("request", operation, "tab", arguments = args)

    private class Transport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var revision = 0
        var cancelPress = false
        var isElementVisible = true
        var isElementTarget = true

        suspend fun call(method: String, args: JsonObject): String {
            calls += method to args
            return when (method) {
                "Page.getFrameTree" ->
                    """{"frameTree":{"frame":{"id":"frame","loaderId":"loader"}}}"""

                "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

                "Page.captureScreenshot" -> """{"data":"iVBORw0KGgoAAAANSUhEUgAAAyAAAAJY"}"""

                "Input.dispatchMouseEvent" -> {
                    if (cancelPress && args["type"]?.jsonPrimitive?.content == "mousePressed") {
                        throw CancellationException("cancelled")
                    }
                    "{}"
                }

                "Runtime.evaluate" -> {
                    val value =
                        if (
                            args
                                .getValue("expression")
                                .jsonPrimitive
                                .content
                                .contains("MutationObserver")
                        ) {
                            """{"width":800,"height":600,"revision":$revision}"""
                        } else if (args.getValue("expression").jsonPrimitive.content.contains("elementFromPoint")) {
                            """{"x":40,"y":30,"isVisible":$isElementVisible,"isTarget":$isElementTarget}"""
                        } else {
                            """{"url":"https://example.com","title":"Example","content":"Text"}"""
                        }
                    """{"result":{"value":$value}}"""
                }

                else -> "{}"
            }
        }
    }
}
