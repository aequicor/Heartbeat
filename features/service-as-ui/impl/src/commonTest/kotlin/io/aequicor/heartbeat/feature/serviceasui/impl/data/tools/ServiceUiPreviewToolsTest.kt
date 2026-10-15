package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewElement
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssue
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssueKind
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewMetrics
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceUiPreviewToolsTest {
    private val renderer = Renderer()
    private val toggles = Toggles()
    private val tools = tools(HostPlatform.MacOs)
    private val context = AgentToolContext(
        SessionRef(EngineId("pi"), SessionSourceId("source"), "session"),
        workspace = null,
        TurnId("turn"),
    )

    @Test
    fun `preview is a read tool without project owner or library dependencies`() = runTest {
        val declaration = tools.specifications(null).single()
        assertTrue(tools.isDetachedSupported)
        assertEquals(ServiceUiTools.PREVIEW, declaration.name)
        assertEquals(AgentToolAction.Read, declaration.action)
        assertEquals(listOf(ServiceUiTools.PREVIEW), tools.catalog.map { it.name })
        assertNull(tools.automaticAuthorization(context, declaration, arguments()))
        assertEquals(JsonArray(listOf(JsonPrimitive("html"))), declaration.inputSchema.getValue("required").jsonArray)
        assertEquals(JsonPrimitive(false), declaration.inputSchema["additionalProperties"])
        assertEquals(
            setOf("html", "data", "width", "height", "theme", "fontScale", "includeScreenshot"),
            declaration.inputSchema.getValue("properties").jsonObject.keys,
        )
        val result = call()
        assertFalse(result.isError)
        assertTrue(result.images.isEmpty(), "The default must work with text-only models")
        assertTrue(result.text.contains("No image delivered"))
        val report = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(JsonPrimitive("light"), report["theme"])
        assertEquals(JsonPrimitive(1f), report["fontScale"])
        assertEquals(BrowserDocumentPreviewRequest(HTML), renderer.requests.single())
    }

    @Test
    fun `instructions follow only the preview declaration and both feature gates`() = runTest {
        val scope = AgentToolScope(null, declared = setOf(ServiceUiTools.PREVIEW))
        assertTrue(tools.instructions(scope).contains("ui_preview"))
        assertEquals("", tools.instructions(scope.copy(declared = emptySet())))
        assertEquals("", tools.instructions(scope.copy(declared = setOf(ServiceUiTools.PUT))))
        for (key in listOf(ServiceUiEnabled.key, ServiceUiWeb.key)) {
            toggles.disabled += key
            assertEquals(emptyList(), tools.specifications(null))
            assertEquals("", tools.instructions(scope))
            assertTrue(call().isError)
            toggles.disabled -= key
        }
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun `desktop hosts expose preview while mobile execution fails closed`() = runTest {
        for (platform in listOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux)) {
            assertEquals(1, tools(platform).specifications(null).size)
        }
        for (platform in listOf(HostPlatform.Android, HostPlatform.Ios)) {
            val mobile = tools(platform)
            assertEquals(emptyList(), mobile.specifications(null))
            assertEquals("", mobile.instructions(null))
            assertTrue(mobile.execute(context, ServiceUiTools.PREVIEW, arguments()).isError)
        }
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun `sample data theme and dimensions reach renderer and image returns with bounded diagnostics`() = runTest {
        val data = buildJsonObject { put("note", "Private draft") }
        renderer.result = BrowserDocumentPreviewResult.Rendered(
            "AQID",
            BrowserDocumentPreviewMetrics(400, 500, 700, 900, true),
            listOf(BrowserDocumentPreviewIssue(BrowserDocumentPreviewIssueKind.HorizontalOverflow, "main")),
            issuesTruncated = true,
            samples = listOf(
                BrowserDocumentPreviewElement(
                    "#note", "textarea", 16.0, 120.0, 368.0, 140.0,
                    "system-ui", 14.0, "20px", "rgb(230,230,230)", "rgb(30,30,30)",
                ),
            ),
        )
        val result = call(
            arguments(
                "data" to data,
                "width" to JsonPrimitive(400),
                "height" to JsonPrimitive(500),
                "theme" to JsonPrimitive("dark"),
                "fontScale" to JsonPrimitive(1.5),
                "includeScreenshot" to JsonPrimitive(true),
            ),
        )
        assertFalse(result.isError)
        assertEquals(BrowserDocumentPreviewRequest(HTML, data, 400, 500, true, 1.5f), renderer.requests.single())
        assertEquals("image/png", result.images.single().mimeType)
        assertEquals("AQID", result.images.single().data)
        val report = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(JsonPrimitive(true), report["issuesTruncated"])
        assertEquals(JsonPrimitive(true), report["imageIncluded"])
        assertEquals(JsonPrimitive("dark"), report["theme"])
        assertEquals(JsonPrimitive(1.5f), report["fontScale"])
        assertEquals(JsonPrimitive(HTML.encodeToByteArray().size), report["htmlBytes"])
        assertEquals(JsonPrimitive(UiSpecLimits.HTML_BYTES), report["htmlLimitBytes"])
        assertEquals(JsonPrimitive(true), report.getValue("content").jsonObject["horizontalOverflow"])
        assertEquals(1, report.getValue("issues").jsonArray.size)
        val sample = report.getValue("samples").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("#note"), sample["element"])
        assertEquals(JsonPrimitive(120.0), sample["y"])
        assertEquals(JsonPrimitive(140.0), sample["height"])
        assertEquals(JsonPrimitive(14.0), sample["fontSize"])
        assertEquals(JsonPrimitive("system-ui"), sample["fontFamily"])
        assertEquals(JsonPrimitive("rgb(30,30,30)"), sample["backgroundColor"])
        assertFalse(result.text.contains("Private draft"))
        assertFalse(result.text.contains(HTML))
        val textOnly = call()
        assertTrue(textOnly.images.isEmpty())
        assertEquals(report["samples"], Json.parseToJsonElement(textOnly.text).jsonObject["samples"])
    }

    @Test
    fun `malformed arguments never reach renderer`() = runTest {
        val invalid = listOf(
            JsonObject(emptyMap()),
            arguments("html" to JsonPrimitive(" ")),
            arguments("html" to JsonPrimitive(5)),
            arguments("html" to JsonNull),
            arguments("width" to JsonPrimitive("900")),
            arguments("width" to JsonPrimitive(900.5)),
            arguments("width" to JsonPrimitive(319)),
            arguments("width" to JsonPrimitive(1601)),
            arguments("height" to JsonPrimitive(239)),
            arguments("height" to JsonPrimitive(1201)),
            arguments("height" to JsonNull),
            arguments("theme" to JsonPrimitive("system")),
            arguments("theme" to JsonPrimitive(false)),
            arguments("fontScale" to JsonPrimitive("1")),
            arguments("fontScale" to JsonPrimitive(0.79)),
            arguments("fontScale" to JsonPrimitive(2.01)),
            arguments("fontScale" to JsonPrimitive(Double.NaN)),
            arguments("fontScale" to JsonPrimitive(Double.POSITIVE_INFINITY)),
            arguments("includeScreenshot" to JsonPrimitive("true")),
            arguments("includeScreenshot" to JsonPrimitive(1)),
            arguments("includeScreenshot" to JsonNull),
            arguments("owner" to JsonPrimitive("harness:existing")),
            arguments("bridge" to JsonPrimitive(true)),
            arguments("url" to JsonPrimitive("https://example.com")),
            arguments("spec" to JsonObject(emptyMap())),
        )
        invalid.forEach { args -> assertTrue(call(args).isError, args.keys.toString()) }
        assertTrue(tools.execute(context, "unknown", arguments()).isError)
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun `document byte and nesting bounds reject large Unicode or deeply nested data`() = runTest {
        val html = arguments("html" to JsonPrimitive("я".repeat(UiSpecLimits.HTML_BYTES / 2 + 1)))
        val data = arguments("data" to JsonPrimitive("я".repeat(BrowserDocumentLimits.DATA_BYTES / 2 + 1)))
        var nested: JsonElement = JsonNull
        repeat(BrowserDocumentLimits.JSON_DEPTH + 2) { nested = JsonArray(listOf(nested)) }
        for (args in listOf(html, data, arguments("data" to nested))) assertTrue(call(args).isError)
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun `viewport bounds and arbitrary JSON sample values remain usable`() = runTest {
        val samples = listOf(JsonNull, JsonPrimitive("sample"), JsonArray(listOf(JsonPrimitive(1))))
        for (sample in samples) {
            val result = call(
                arguments(
                    "data" to sample,
                    "width" to JsonPrimitive(320),
                    "height" to JsonPrimitive(240),
                    "fontScale" to JsonPrimitive(0.8),
                ),
            )
            assertFalse(result.isError)
            assertEquals(sample, renderer.requests.last().data)
        }
        val result = call(
            arguments(
                "width" to JsonPrimitive(1600),
                "height" to JsonPrimitive(1200),
                "fontScale" to JsonPrimitive(2),
            ),
        )
        assertFalse(result.isError)
    }

    @Test
    fun `unavailability and render failures return no image and cancellation propagates`() = runTest {
        renderer.result = BrowserDocumentPreviewResult.Unavailable
        assertTrue(call().isError)
        for (reason in BrowserDocumentPreviewFailure.entries) {
            renderer.result = BrowserDocumentPreviewResult.Failure(reason)
            val result = call()
            assertTrue(result.isError)
            assertTrue(result.images.isEmpty())
            assertTrue(result.text.contains(reason.name))
        }
        renderer.cancel = true
        assertFailsWith<CancellationException> { call() }
    }

    private fun tools(host: HostPlatform): ServiceUiPreviewTools = ServiceUiPreviewTools(
        renderer,
        toggles,
        object : PlatformInfo {
            override val host = host
        },
    )

    private suspend fun call(args: Map<String, JsonElement> = arguments()) =
        tools.execute(context, ServiceUiTools.PREVIEW, JsonObject(args))

    private class Renderer : BrowserDocumentPreviews {
        val requests = mutableListOf<BrowserDocumentPreviewRequest>()
        var result: BrowserDocumentPreviewResult = BrowserDocumentPreviewResult.Rendered(
            "AQID",
            BrowserDocumentPreviewMetrics(900, 640, 900, 640, false),
            emptyList(),
        )
        var cancel = false

        override suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult {
            if (cancel) throw CancellationException("preview cancelled")
            requests += request
            return result
        }
    }

    private class Toggles : FeatureToggles {
        val disabled = mutableSetOf<String>()

        @Suppress("UNCHECKED_CAST") // This fixture exposes only the two boolean interface flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf((toggle.key !in disabled) as T)

        @Suppress("UNCHECKED_CAST") // This fixture exposes only the two boolean interface flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = (toggle.key !in disabled) as T
    }

    private companion object {
        const val HTML = "<!doctype html><p>Preview</p>"

        fun arguments(vararg replacements: Pair<String, JsonElement>): JsonObject =
            JsonObject(mapOf("html" to JsonPrimitive(HTML)) + replacements)
    }
}
