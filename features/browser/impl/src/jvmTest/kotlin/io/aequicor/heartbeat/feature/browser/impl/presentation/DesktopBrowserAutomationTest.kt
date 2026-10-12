package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `element rewritten after the read is refused before any input`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        transport.isElementChanged = true
        val failure = assertFailsWith<IllegalArgumentException> {
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
        assertEquals("Element changed; read the page again", failure.message)
        assertTrue(transport.calls.none { it.first.startsWith("Input.") })
        val scripts = transport.calls
            .filter { it.first == "Runtime.evaluate" }
            .map { it.second.getValue("expression").jsonPrimitive.content }
        // The read records what each reference means; the click compares it and the nearest interactive ancestor.
        assertTrue(scripts.first { "querySelectorAll" in it }.contains("__heartbeatFingerprints="))
        val check = scripts.first { "elementFromPoint" in it }
        assertTrue(check.contains("__heartbeatFingerprints?.[0]"))
        assertTrue(check.contains("getAttribute('href')"))
        assertTrue(check.contains("hit.closest("))
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
    fun `scrolled or navigated page rejects old coordinates before any mouse input`() = runTest {
        for (change in listOf<(Transport) -> Unit>({ it.scrollY += 100 }, { it.url = "https://example.com/next" })) {
            val transport = Transport()
            val driver = DesktopBrowserAutomation(transport::call)
            val read = driver.perform(request(BrowserOperation.Read))
            change(transport)
            assertFailsWith<IllegalArgumentException> {
                driver.perform(request(BrowserOperation.Click, coordinates(read.data.getValue("snapshotId"))))
            }
            assertTrue(transport.calls.none { it.first == "Input.dispatchMouseEvent" })
        }
    }

    @Test
    fun `DOM mutations on a dynamic page keep read and click usable`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        transport.isMutatingOnEvaluate = true
        val read = driver.perform(request(BrowserOperation.Read))
        driver.perform(request(BrowserOperation.Click, coordinates(read.data.getValue("snapshotId"))))
        val element = driver.perform(request(BrowserOperation.Read))
        driver.perform(
            request(
                BrowserOperation.Click,
                buildJsonObject {
                    put("snapshotId", element.data.getValue("snapshotId"))
                    put("element", 0)
                },
            ),
        )
        assertEquals(4, transport.calls.count { it.first == "Input.dispatchMouseEvent" })
        assertFalse(read.data.containsKey("view"))
    }

    @Test
    fun `screenshot retries while the viewport moves and gives up after a bound`() = runTest {
        val settling = Transport().apply { scrollDuringCaptures = 1 }
        val shot = DesktopBrowserAutomation(settling::call).perform(request(BrowserOperation.Screenshot))
        assertEquals(2, settling.calls.count { it.first == "Page.captureScreenshot" })
        assertTrue(shot.data.containsKey("snapshotId"))

        val scrolling = Transport().apply { scrollDuringCaptures = Int.MAX_VALUE }
        val driver = DesktopBrowserAutomation(scrolling::call)
        assertFailsWith<IllegalStateException> { driver.perform(request(BrowserOperation.Screenshot)) }
        assertEquals(3, scrolling.calls.count { it.first == "Page.captureScreenshot" })
        assertFailsWith<IllegalArgumentException> {
            driver.perform(request(BrowserOperation.Click, coordinates(JsonPrimitive("any"))))
        }
    }

    @Test
    fun `point outside the viewport sends neither press nor release`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        assertFailsWith<IllegalArgumentException> {
            driver.perform(
                request(
                    BrowserOperation.Click,
                    buildJsonObject {
                        put("snapshotId", read.data.getValue("snapshotId"))
                        put("x", 5_000)
                        put("y", 10)
                    },
                ),
            )
        }
        assertTrue(transport.calls.none { it.first.startsWith("Input.") })
    }

    @Test
    fun `release timeout fails the click instead of cancelling it`() = runTest {
        val transport = Transport().apply { isReleaseHanging = true }
        val driver = DesktopBrowserAutomation(transport::call)
        val read = driver.perform(request(BrowserOperation.Read))
        val failure = assertFailsWith<IllegalStateException> {
            driver.perform(request(BrowserOperation.Click, coordinates(read.data.getValue("snapshotId"))))
        }
        assertTrue(failure !is CancellationException)
    }

    @Test
    fun `failed evaluation recreates the isolated world`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call)
        driver.perform(request(BrowserOperation.Read))
        transport.isEvaluateFailing = true
        assertFailsWith<IllegalStateException> { driver.perform(request(BrowserOperation.Read)) }
        transport.isEvaluateFailing = false
        driver.perform(request(BrowserOperation.Read))
        assertEquals(2, transport.calls.count { it.first == "Page.createIsolatedWorld" })
    }

    @Test
    fun `source navigation timeout is a failure rather than a cancellation`() = runTest {
        val transport = Transport()
        val driver = DesktopBrowserAutomation(transport::call) { url ->
            buildJsonObject {
                put("kind", "html")
                put("url", url)
            }
        }
        transport.navigationLoader = "never-committed"
        val failure = assertFailsWith<IllegalStateException> {
            driver.captureResource(
                request(BrowserOperation.CaptureResource, buildJsonObject { put("url", "https://example.com/paper") }),
            )
        }
        assertEquals("Source navigation timed out", failure.message)
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

    private fun coordinates(snapshotId: JsonElement) = buildJsonObject {
        put("snapshotId", snapshotId)
        put("x", 10)
        put("y", 10)
    }

    private class Transport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var url = "https://example.com"
        var scrollY = 0
        var mutations = 0
        var isMutatingOnEvaluate = false
        var scrollDuringCaptures = 0
        var cancelPress = false
        var isReleaseHanging = false
        var isEvaluateFailing = false
        var navigationLoader = "loader"
        var isElementVisible = true
        var isElementTarget = true
        var isElementChanged = false

        private val geometry: String
            get() = """{"document":"doc","url":"$url","width":800,"height":600,"x":0,"y":$scrollY}"""

        suspend fun call(method: String, args: JsonObject): String {
            calls += method to args
            return when (method) {
                "Page.getFrameTree" ->
                    """{"frameTree":{"frame":{"id":"frame","loaderId":"loader"}}}"""

                "Page.navigate" -> """{"loaderId":"$navigationLoader"}"""

                "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

                "Page.captureScreenshot" -> {
                    if (scrollDuringCaptures > 0) {
                        scrollDuringCaptures--
                        scrollY++
                    }
                    """{"data":"iVBORw0KGgoAAAANSUhEUgAAAyAAAAJY"}"""
                }

                "Input.dispatchMouseEvent" -> mouse(args["type"]?.jsonPrimitive?.content)

                "Runtime.evaluate" -> """{"result":{"value":${evaluate(args)}}}"""

                else -> "{}"
            }
        }

        private suspend fun mouse(type: String?): String {
            if (cancelPress && type == "mousePressed") throw CancellationException("cancelled")
            if (isReleaseHanging && type == "mouseReleased") awaitCancellation()
            return "{}"
        }

        private fun evaluate(args: JsonObject): String {
            check(!isEvaluateFailing) { "Cannot find context with specified id" }
            if (isMutatingOnEvaluate) mutations++
            val expression = args.getValue("expression").jsonPrimitive.content
            return when {
                "querySelectorAll" in expression ->
                    """{"view":$geometry,"url":"$url","title":"Example","content":"Text $mutations"}"""

                "elementFromPoint" in expression && isElementChanged -> """{"isChanged":true}"""

                "elementFromPoint" in expression ->
                    """{"x":40,"y":30,"isChanged":false,"isVisible":$isElementVisible,"isTarget":$isElementTarget}"""

                "__heartbeatDocument" in expression -> geometry

                else -> "true"
            }
        }
    }
}
