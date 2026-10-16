package io.aequicor.heartbeat.feature.browser.impl.ui

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssueKind
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.di.BrowserDocumentPreviewBindings
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssets
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentViews
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Window
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.JWindow
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Production API, CSS, isolation policy and CDP diagnostics/image capture; no OS capture or input. */
internal fun verifyNativeHiddenDocumentPreview(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) {
    verifyPreviewCancellation(dispatchers, scope)
    val requests = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(
            "/",
        ) { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }
    val fixture = NativePreviewFixture(resources, dispatchers, scope)
    try {
        val html = previewHtml("http://127.0.0.1:${server.address.port}")
        val narrow = fixture.render(html, width = 360)
        assertTrue(narrow.metrics.hasHorizontalOverflow)
        assertEquals(650, narrow.metrics.contentWidth)
        assertTrue(
            narrow.hasIssue(BrowserDocumentPreviewIssueKind.UnlabelledInput, "#missing"),
        )
        assertFalse(
            narrow.issues.any {
                it.kind == BrowserDocumentPreviewIssueKind.UnlabelledInput && "#labelled" in it.element
            },
        )
        assertTrue(
            narrow.hasIssue(BrowserDocumentPreviewIssueKind.ExternalResource, "#css"),
        )
        assertTrue(narrow.hasIssue(BrowserDocumentPreviewIssueKind.SmallText, "#tiny"))
        assertTrue(narrow.issues.any { it.kind == BrowserDocumentPreviewIssueKind.ScriptError })
        assertEquals(38.0, narrow.samples.single { "#sample" in it.element }.width, "Sample data must be available")
        assertEquals(
            38.0,
            narrow.samples.single { "#bridge" in it.element }.width,
            "Preview must have no owner bridge",
        )
        assertPreviewPng(narrow, 360)

        val wide = fixture.render(html, width = 900, isDark = true, fontScale = 1.5f)
        assertFalse(wide.metrics.hasHorizontalOverflow)
        assertEquals(900, wide.metrics.contentWidth)
        val smallField = narrow.samples.single { "#labelled" in it.element }
        val largeField = wide.samples.single { "#labelled" in it.element }
        assertEquals(smallField.fontSize * 1.5, largeField.fontSize)
        assertTrue(largeField.fontFamily.contains("system"))
        assertTrue(smallField.backgroundColor != largeField.backgroundColor, "Requested host themes must differ")
        assertPreviewPng(wide, 900)
        assertEquals(0, requests.get(), "CSS, fetch and image requests must stay inside document isolation")
        val crowded = fixture.render("<body>" + "<button></button>".repeat(100), width = 360)
        assertEquals(40, crowded.issues.size)
        assertTrue(crowded.issuesTruncated)
        assertEquals(20, crowded.samples.size)
        assertTrue(fixture.documents.documents.value.isEmpty())
        assertFalse(nativeEdt { Window.getWindows().any { it is JWindow && it.isDisplayable } })
    } finally {
        fixture.close()
        server.stop(0)
    }
}

/** Exercises admission and outer cancellation before native creation, through the real resource lifecycle. */
private fun verifyPreviewCancellation(dispatchers: DispatcherProvider, scope: CoroutineScope) {
    val starting = CompletableDeferred<Unit>()
    val fixture = NativePreviewFixture(
        DesktopBrowserResources {
            starting.complete(Unit)
            awaitCancellation()
        },
        dispatchers,
        scope,
    )
    try {
        runBlocking {
            withTimeout(10_000) {
                val request = BrowserDocumentPreviewRequest("<p>Cancellation</p>")
                val rendering = async { fixture.previews.preview(request) }
                starting.await()
                assertEquals(
                    BrowserDocumentPreviewResult.Failure(BrowserDocumentPreviewFailure.Busy),
                    fixture.previews.preview(request),
                )
                assertFalse(nativeEdt { Window.getWindows().filterIsInstance<JWindow>().any { it.isVisible } })
                rendering.cancelAndJoin()
                assertTrue(fixture.documents.documents.value.isEmpty())
                val timedOut = withTimeoutOrNull(100) { fixture.previews.preview(request) }
                assertEquals(null, timedOut, "Caller deadline must propagate cancellation, not return a render failure")
                assertTrue(fixture.documents.documents.value.isEmpty())
            }
        }
    } finally {
        fixture.close()
    }
}

internal class NativePreviewFixture(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
    assets: BrowserDocumentAssets = BrowserDocumentAssets.None,
) {
    val documents = ProfileBrowserDocuments()
    private val owner = BrowserTestScope(
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
    )
    private val views = object : DesktopDocumentViews {
        override fun register(panel: DesktopDocumentPanel) = Unit
        override fun closed(panel: DesktopDocumentPanel) = Unit
    }
    private val panels = nativeEdt {
        DesktopDocumentPanels(
            resources,
            dispatchers,
            owner,
            owner,
            views,
            documents,
            assets,
        )
    }
    val previews = DesktopBrowserDocumentPreviews(
        documents,
        documents,
        panels,
        dispatchers,
        BrowserDocumentPreviewBindings.themes(),
        resources,
        owner,
    )

    fun render(
        html: String,
        width: Int,
        isDark: Boolean = false,
        fontScale: Float = 1f,
        resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
    ): BrowserDocumentPreviewResult.Rendered = runBlocking {
        withTimeout(30_000) {
            assertIs<BrowserDocumentPreviewResult.Rendered>(
                previews.preview(
                    BrowserDocumentPreviewRequest(
                        html,
                        buildJsonObject { put("width", 38) },
                        width,
                        480,
                        isDark,
                        fontScale,
                        resourcePolicy,
                    ),
                ),
            )
        }
    }

    fun close() {
        nativeEdt { owner.close() }
        owner.coroutineScope.cancel()
    }
}

private fun previewHtml(url: String): String = """
    <!doctype html><style id="css">
    @import url('$url/theme.css');
    body { margin:0; }
    #wide { width:650px;height:18px; }
    #tiny { font-size:6px; }
    #red,#green,#blue { position:fixed;top:460px;width:12px;height:12px; }
    #red { left:0;background:rgb(32,32,32); }
    #green { left:12px;background:rgb(128,128,128); }
    #blue { left:24px;background:rgb(224,224,224); }
    </style><body><main id="wide"></main><h1 id="sample">Private sample</h1>
    <h2 id="bridge">Bridge check</h2><label for="labelled">Name</label><input id="labelled">
    <input id="missing"><p id="tiny">Small text</p><img src="$url/image.png" alt="">
    <i id="red"></i><i id="green"></i><i id="blue"></i><script>
    document.getElementById('sample').style.width=heartbeat.data.width+'px';
    document.getElementById('bridge').style.width=(typeof heartbeat.call==='undefined'?38:99)+'px';
    fetch('$url/fetch').catch(()=>{});console.error('Private error is never returned');
    </script>
    """.trimIndent()

private fun assertPreviewPng(result: BrowserDocumentPreviewResult.Rendered, width: Int) {
    val bytes = Base64.getDecoder().decode(result.imageBase64)
    val image = ImageIO.read(ByteArrayInputStream(bytes))
    assertEquals(width, image.width)
    assertEquals(480, image.height)
    // Neutral markers avoid macOS display-profile changes to saturated colors; three levels reject blank captures.
    for ((x, rgb) in listOf(6 to 0x202020, 18 to 0x808080, 30 to 0xE0E0E0)) {
        val actual = image.getRGB(x, 466)
        for (shift in listOf(16, 8, 0)) {
            assertTrue(
                abs(((actual shr shift) and 255) - ((rgb shr shift) and 255)) <= 8,
                "Rendered marker at $x: $actual vs $rgb",
            )
        }
    }
}

private fun BrowserDocumentPreviewResult.Rendered.hasIssue(kind: BrowserDocumentPreviewIssueKind, id: String): Boolean =
    issues.any { it.kind == kind && id in it.element }
