package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.impl.data.DefaultBrowserUrlResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.network.CefURLRequest.Status
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserDownloadTest {
    @Test
    fun `completed standalone request validates its explicit hop without resource callbacks`() {
        val policy = DesktopBrowserTransferPolicy("https://source.test/paper.pdf", DefaultBrowserUrlResolver())
        assertFailsWith<IllegalStateException> { policy.finalUrl() }
        assertTrue(policy.accept(200, Status.UR_SUCCESS, ErrorCode.ERR_NONE, null))
        assertEquals("https://source.test/paper.pdf", policy.finalUrl())
    }

    @Test
    fun `stopped redirects resolve relative and cross origin locations before starting another hop`() {
        val policy = DesktopBrowserTransferPolicy("http://source.test/articles/paper", DefaultBrowserUrlResolver())
        assertFalse(policy.accept(302, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, "../document"))
        assertEquals("http://source.test/document", policy.currentUrl)
        assertFalse(policy.accept(307, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, "https://cdn.test/paper.pdf"))
        assertFailsWith<IllegalStateException> { policy.finalUrl() }
        assertTrue(policy.accept(200, Status.UR_SUCCESS, ErrorCode.ERR_NONE, null))
        assertEquals("https://cdn.test/paper.pdf", policy.finalUrl())
    }

    @Test
    fun `failed or automatically followed redirects cannot authorize another request`() {
        listOf(Status.UR_SUCCESS, Status.UR_FAILED).forEach { status ->
            val policy = DesktopBrowserTransferPolicy("https://source.test/paper", DefaultBrowserUrlResolver())
            assertFailsWith<IllegalStateException> { policy.accept(302, status, ErrorCode.ERR_NONE, "/next") }
            assertEquals("https://source.test/paper", policy.currentUrl)
        }
        val cancelled = DesktopBrowserTransferPolicy("https://source.test/paper", DefaultBrowserUrlResolver())
        assertFailsWith<IllegalStateException> {
            cancelled.accept(200, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, null)
        }
    }

    @Test
    fun `query and fragment redirect references retain the current document path`() {
        val policy = DesktopBrowserTransferPolicy(
            "https://source.test/articles/paper?old=1",
            DefaultBrowserUrlResolver(),
        )
        assertFalse(policy.accept(302, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, "?download=1"))
        assertEquals("https://source.test/articles/paper?download=1", policy.currentUrl)
        assertFalse(policy.accept(302, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, "#document"))
        assertEquals("https://source.test/articles/paper?download=1#document", policy.currentUrl)
    }

    @Test
    fun `redirect locations reject malformed URLs missing headers forbidden schemes and HTTPS downgrade`() {
        listOf(null, "", "file:///tmp/paper", "http://source.test/paper", "https://user@source.test/paper").forEach {
            val policy = DesktopBrowserTransferPolicy("https://source.test/paper", DefaultBrowserUrlResolver())
            assertFailsWith<IllegalStateException> { policy.accept(302, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, it) }
            assertEquals("https://source.test/paper", policy.currentUrl)
        }
        val invalid = DesktopBrowserTransferPolicy("https://source.test/paper", DefaultBrowserUrlResolver())
        assertFailsWith<IllegalArgumentException> {
            invalid.accept(302, Status.UR_CANCELED, ErrorCode.ERR_ABORTED, "https://source.test/%invalid")
        }
    }

    @Test
    fun `closing waits for native cancellation acknowledgement and rejects new transfers`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val downloader = DesktopBrowserDownload(dispatchers(StandardTestDispatcher(testScheduler))) {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finished.await() }
            }
        }
        val capture = launch { downloader.capture("https://source.test/paper.pdf") }
        entered.await()
        downloader.cancel()
        val closing = async { downloader.awaitClosed() }
        runCurrent()
        assertFalse(capture.isCompleted)
        assertFalse(closing.isCompleted)
        assertFailsWith<IllegalStateException> { downloader.capture("https://source.test/another.pdf") }
        finished.complete(Unit)
        closing.await()
        capture.join()
    }

    @Test
    fun `capture uses the requested source independently of any committed page`() = runTest {
        val received = mutableListOf<String>()
        val downloader = DesktopBrowserDownload(dispatchers(StandardTestDispatcher(testScheduler))) {
            received.add(it)
            JsonObject(emptyMap())
        }
        downloader.capture("https://source.test/direct.pdf")
        assertEquals(listOf("https://source.test/direct.pdf"), received)
        downloader.awaitClosed()
    }

    @Test
    fun `source DOI publisher and CDN redirect chain preserves native final URL`() {
        val redirects = DesktopBrowserRedirects("https://source.test/paper")
        assertTrue(redirects.redirect("https://doi.test/10.123/paper"))
        assertTrue(redirects.redirect("https://PUBLISHER.test:443/paper"))
        assertTrue(redirects.redirect("https://cdn.test/paper.pdf"))
        assertFailsWith<IllegalStateException> { redirects.finalUrl() }
        assertTrue(redirects.response("https://cdn.test/paper.pdf"))
        assertEquals("https://cdn.test/paper.pdf", redirects.finalUrl())
    }

    @Test
    fun `HTTPS downgrade stays rejected even after redirecting back to a secure origin`() {
        val redirects = DesktopBrowserRedirects("https://source.test/paper")
        assertFalse(redirects.redirect("http://other.test/paper.pdf"))
        assertFalse(redirects.redirect("https://other.test/paper.pdf"))
        assertFalse(redirects.response("https://source.test/back.pdf"))
        assertFailsWith<IllegalStateException> { redirects.finalUrl() }
    }

    @Test
    fun `HTTP upgrade latches HTTPS across all later requests redirects and responses`() {
        val redirects = DesktopBrowserRedirects("http://source.test/paper")
        assertTrue(redirects.redirect("http://doi.test/paper"))
        assertTrue(redirects.redirect("https://publisher.test/paper"))
        assertFalse(redirects.allows("http://cdn.test/paper"))
        assertFalse(redirects.response("http://cdn.test/paper"))
        assertFailsWith<IllegalStateException> { redirects.finalUrl() }
        val upgradedRequest = DesktopBrowserRedirects("http://source.test/paper")
        assertTrue(upgradedRequest.allows("https://source.test/paper"))
        assertFalse(upgradedRequest.redirect("http://source.test/paper"))
    }

    @Test
    fun `source rejects unvalidated results forbidden schemes credentials and redirect loops`() {
        val redirects = DesktopBrowserRedirects("https://source.test/paper")
        assertFailsWith<IllegalStateException> { redirects.finalUrl() }
        listOf("file:///tmp/paper", "https://user@source.test/paper", "http://source.test/paper").forEach {
            assertFalse(redirects.allows(it))
            val rejected = DesktopBrowserRedirects("https://source.test/paper")
            assertFalse(rejected.redirect(it))
            assertFalse(rejected.response("https://source.test/paper"))
        }
        repeat(10) { assertTrue(redirects.redirect("https://source.test/repeated")) }
        assertFalse(redirects.redirect("https://source.test/repeated"))
    }

    @Test
    fun `HTML recovery follows the native DOI result instead of the currently open unrelated page`() = runTest {
        val fixture = HtmlRecoveryFixture()
        val result = fixture.capture()
        assertEquals("https://doi.test/paper", fixture.requested)
        assertEquals("https://publisher.test/paper", fixture.navigated)
        assertEquals("Publisher article", result.data.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `HTML recovery rejects an arbitrary page opened after the validated source navigation`() = runTest {
        val fixture = HtmlRecoveryFixture(isReplaced = true)
        assertFailsWith<IllegalStateException> { fixture.capture() }
        assertEquals("https://doi.test/paper", fixture.requested)
        assertEquals("https://publisher.test/paper", fixture.navigated)
    }

    @Test
    fun `HTML recovery reads the document while the host resizes the page viewport`() = runTest {
        val fixture = HtmlRecoveryFixture(geometries = listOf(geometry(height = 574), geometry(height = 490)))
        val result = fixture.capture()
        assertEquals("Publisher article", result.data.getValue("content").jsonPrimitive.content)
        assertEquals("tab", result.data.getValue("tabId").jsonPrimitive.content)
    }

    @Test
    fun `HTML recovery rejects a document mutated while it was read`() = runTest {
        val fixture = HtmlRecoveryFixture(geometries = listOf(geometry(revision = 0), geometry(revision = 1)))
        val failure = assertFailsWith<IllegalStateException> { fixture.capture() }
        assertEquals("Page changed while reading; read it again", failure.message)
    }

    @Test
    fun `chunked response without content length cannot exceed the byte limit`() {
        val rejected = mutableListOf<String>()
        val body = DesktopBrowserDocumentBody { rejected.add(it) }
        val chunk = ByteArray(1_000_000)
        repeat(10) { body.append(chunk, chunk.size) }
        body.append(byteArrayOf(1), 1)
        body.append(chunk, chunk.size)
        assertEquals(1, rejected.size)
        assertFailsWith<IllegalStateException> { body.result("https://source.test/paper", "text/plain") }
    }

    @Test
    fun `declared oversized content is rejected before body bytes arrive`() {
        var isRejected = false
        val body = DesktopBrowserDocumentBody { isRejected = true }
        body.progress(0, 10_000_001)
        assertTrue(isRejected)
        assertFailsWith<IllegalStateException> { body.result("https://source.test/paper", "application/pdf") }
    }

    @Test
    fun `PDF requires actual signature and returns exactly received bytes`() {
        val url = "https://source.test/paper.pdf"
        val invalid = DesktopBrowserDocumentBody { error(it) }
        invalid.append("<html>captcha</html>".encodeToByteArray(), 20)
        assertFailsWith<IllegalStateException> { invalid.result(url, "application/pdf") }
        val valid = DesktopBrowserDocumentBody { error(it) }
        val bytes = "%PDF-1.4\nfixture\n%%EOF".encodeToByteArray()
        valid.append(bytes, bytes.size)
        val result = valid.result(url, "application/pdf")
        assertEquals("document", result.getValue("kind").jsonPrimitive.content)
        assertContentEquals(bytes, Base64.getDecoder().decode(result.getValue("contentBase64").jsonPrimitive.content))
    }

    @Test
    fun `HTML is handed to page validation and unsupported documents are not imported`() {
        val body = DesktopBrowserDocumentBody { error(it) }
        val data = "verification required".encodeToByteArray()
        body.append(data, data.size)
        assertEquals(
            "html",
            body.result("https://source.test/locked", "text/html").getValue("kind").jsonPrimitive.content,
        )
        assertFailsWith<IllegalStateException> { body.result("https://source.test/file", "application/zip") }
    }

    private fun dispatchers(dispatcher: CoroutineDispatcher): DispatcherProvider = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }

    private fun geometry(height: Int = 600, revision: Int = 0) =
        """{"document":"doc","url":"https://publisher.test/paper","width":800,"height":$height,"revision":$revision}"""

    private class HtmlRecoveryFixture(
        private val isReplaced: Boolean = false,
        geometries: List<String> = listOf("""{"width":800,"height":600,"revision":0}"""),
    ) {
        private val geometry = geometries.iterator()
        private var lastGeometry = geometries.first()
        var requested: String? = null
        var navigated: String? = null
        private var location = "https://unrelated.test/current"
        private val driver = DesktopBrowserAutomation(::call) { url ->
            requested = url
            buildJsonObject {
                put("kind", "html")
                put("url", "https://publisher.test/paper")
            }
        }

        suspend fun capture() = driver.captureResource(
            BrowserRequest(
                "capture",
                BrowserOperation.CaptureResource,
                "tab",
                arguments = buildJsonObject { put("url", "https://doi.test/paper") },
            ),
        )

        private suspend fun call(method: String, arguments: JsonObject): String = when (method) {
            "Page.navigate" -> {
                navigated = arguments.getValue("url").jsonPrimitive.content
                if (!isReplaced) location = requireNotNull(navigated)
                """{"loaderId":"source"}"""
            }

            "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"frame","loaderId":"source"}}}"""

            "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

            "Runtime.evaluate" -> {
                val expression = arguments.getValue("expression").jsonPrimitive.content
                val value = when {
                    "MutationObserver" in expression -> {
                        if (geometry.hasNext()) lastGeometry = geometry.next()
                        lastGeometry
                    }

                    "document.readyState" in expression -> "true"

                    else -> """{"url":"$location","title":"Paper","content":"Publisher article"}"""
                }
                """{"result":{"value":$value}}"""
            }

            else -> error("Unexpected CDP call: $method")
        }
    }
}
