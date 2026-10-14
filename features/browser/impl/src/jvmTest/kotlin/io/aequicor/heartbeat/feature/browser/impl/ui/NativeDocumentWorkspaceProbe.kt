package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPresentation
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.RootPaneContainer
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the real Compose interop and owned HbDialog layer, including the document's top-level scroll. */
internal fun verifyNativeDocumentWorkspace(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    parent: CoroutineScope,
) {
    val probe = NativeDocumentWorkspaceProbe(resources, dispatchers, parent)
    try {
        probe.verify()
    } finally {
        probe.close()
    }
}

private class NativeDocumentWorkspaceProbe(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    parent: CoroutineScope,
) : AutoCloseable {
    private val reports = LinkedBlockingQueue<JsonObject>()
    private val viewports = LinkedBlockingQueue<JsonObject>()
    private val overlays = LinkedBlockingQueue<JsonObject>()
    private var initialShape: NativeDocumentShape? = null
    private val documents = ProfileBrowserDocuments()
    private val handle = assertNotNull(documents.open(workspaceDocument(reports, viewports, overlays)))
    private val owner = BrowserTestScope(
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])),
    )
    private val closed = CompletableFuture<Unit>()
    private val views = object : DesktopDocumentViews {
        override fun register(panel: DesktopDocumentPanel) = Unit
        override fun closed(panel: DesktopDocumentPanel) {
            closed.complete(Unit)
        }
    }
    private val panels = nativeEdt { DesktopDocumentPanels(resources, dispatchers, owner, owner, views, documents) }
    private val surface = BrowserDocumentSurface(
        documents.documents.value.getValue(handle.route.id),
        panels,
        BrowserDocumentPresentation.Content,
    )
    private var isExpanded by mutableStateOf(false)
    private var isShown by mutableStateOf(true)
    private val window = nativeEdt {
        ComposeWindow().apply {
            title = "Heartbeat retained Compose workspace verification (no input)"
            setSize(900, 720)
            setContent { Content() }
            isVisible = true
        }
    }

    fun verify() {
        awaitReport(0)
        awaitClippedDocument()
        if (OS.isMacintosh()) initialShape = nativeEdt { NativeDocumentClipSnapshot().shapes(window.rootPane).single() }
        handle.update(command(1, "seed"))
        val seeded = awaitReport(1)
        assertEquals(JsonPrimitive(650), seeded["scroll"])
        for (request in 2..5) {
            nativeEdt { isExpanded = !isExpanded }
            awaitClippedDocument()
            handle.update(command(request, "check"))
            assertRetained(seeded, awaitReport(request))
        }
        nativeEdt { isShown = false }
        if (OS.isMacintosh()) NativeDocumentClipSnapshot().await(window.rootPane, 0)
        nativeEdt { isShown = true }
        awaitClippedDocument()
        handle.update(command(6, "check"))
        assertRetained(seeded, awaitReport(6))
        verifyIntrinsicHeight(seeded)
    }

    private fun verifyIntrinsicHeight(seeded: JsonObject) {
        nativeEdt { isExpanded = true }
        awaitClippedDocument()
        val settledHeights = mutableMapOf<Int, Int>()
        for ((request, height) in listOf(7 to 100, 8 to 300, 9 to 2000, 10 to 80, 11 to 300, 12 to 80)) {
            handle.update(command(request, "check", height))
            val report = awaitReport(request)
            for (name in listOf("marker", "input", "tab")) assertEquals(seeded[name], report[name])
            val viewport = awaitViewport { data ->
                val actual = data.getValue("height").jsonPrimitive.content.toInt()
                data["request"] == JsonPrimitive(request) &&
                    data["contentHeight"] == JsonPrimitive(height) &&
                    // Swing rounds fractional Compose edges outwards to whole native points.
                    (if (height == 2000) actual in 301 until 720 else actual in height..height + 1)
            }
            val actual = viewport.getValue("height").jsonPrimitive.content.toInt()
            assertTrue(actual <= nativeEdt { window.height }, "Content exceeded its host viewport")
            settledHeights.put(height, actual)?.let { previous ->
                assertEquals(previous, actual, "Native rounding must not accumulate after content changes")
            }
        }
        nativeEdt { isExpanded = false }
        awaitClippedDocument()
        awaitViewport {
            it["request"] == JsonPrimitive(12) && it.getValue("height").jsonPrimitive.content.toInt() > 300
        }
        handle.update(command(13, "check", 80))
        val restored = awaitReport(13)
        assertEquals(seeded["marker"], restored["marker"])
        assertEquals(seeded["input"], restored["input"])
    }

    @Composable
    private fun Content() {
        HbTheme(darkTheme = true) {
            if (isExpanded) {
                HbDialog(
                    "Retained app",
                    { isExpanded = false },
                    width = HbDialogWidth.Wide,
                    isContentScrollable = false,
                    isContentEdgeToEdge = true,
                    isTitleVisible = false,
                    headerActions = { HbIconButton(HbIcons.Close, "Close", { isExpanded = false }) },
                ) {
                    BrowserDocumentView(surface, Modifier.fillMaxWidth())
                }
            } else if (isShown) {
                BrowserDocumentView(surface, Modifier.fillMaxSize())
            }
        }
    }

    private fun awaitClippedDocument() {
        if (!OS.isMacintosh()) return
        val snapshot = NativeDocumentClipSnapshot()
        if (nativeEdt { isExpanded }) {
            snapshot.await(window.rootPane, 0)
            val modal = awaitModal()
            snapshot.await(modal.rootPane, 1, areAllClipped = true)
            awaitShape(modal, isRounded = true)
            awaitOverlay(isOccupied = true)
        } else {
            snapshot.await(window.rootPane, 1, areAllClipped = true)
            awaitShape(window, isRounded = false)
            awaitOverlay(isOccupied = false)
        }
    }

    private fun awaitModal(): RootPaneContainer {
        val result = CompletableFuture<RootPaneContainer>()
        val timer = nativeEdt {
            Timer(20) {
                // Popup Close has a distinct owned window; only the document host owns a CEF content group.
                val modal = window.ownedWindows.filterIsInstance<RootPaneContainer>().singleOrNull {
                    it.rootPane.isShowing && NativeDocumentClipSnapshot().read(it.rootPane).size == 1
                }
                if (modal is RootPaneContainer) result.complete(modal)
            }.apply { start() }
        }
        return try {
            result.awaitNative()
        } finally {
            nativeEdt(timer::stop)
        }
    }

    private fun awaitShape(host: RootPaneContainer, isRounded: Boolean) {
        val result = CompletableFuture<NativeDocumentShape>()
        val snapshot = NativeDocumentClipSnapshot()
        val timer = nativeEdt {
            Timer(20) {
                try {
                    val shape = snapshot.shapes(host.rootPane).singleOrNull()
                    if (shape != null && (shape.radius > 0 && shape.isMasked) == isRounded) result.complete(shape)
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }.apply { start() }
        }
        try {
            val shape = result.awaitNative()
            val prior = initialShape
            if (!isRounded && prior != null) {
                assertEquals(prior.radius, shape.radius, "Modal corner radius leaked into Start")
                assertEquals(prior.isMasked, shape.isMasked, "Modal mask leaked into Start")
            }
        } finally {
            nativeEdt(timer::stop)
        }
    }

    private fun awaitOverlay(isOccupied: Boolean) {
        awaitViewport(overlays) {
            val width = it.getValue("overlayWidth").jsonPrimitive.content.toDouble()
            val height = it.getValue("overlayHeight").jsonPrimitive.content.toDouble()
            if (isOccupied) width > 0 && height > 0 else width == 0.0 && height == 0.0
        }
    }

    private fun awaitReport(request: Int): JsonObject {
        val report = assertNotNull(reports.poll(30, TimeUnit.SECONDS), "Missing workspace report $request")
        assertEquals(request.toString(), report.getValue("request").jsonPrimitive.content)
        assertEquals(JsonPrimitive(request + 1), report["renders"], "Mount delivered application data again")
        return report
    }

    private fun awaitViewport(
        source: LinkedBlockingQueue<JsonObject> = viewports,
        matches: (JsonObject) -> Boolean,
    ): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var latest: JsonObject? = null
        while (System.nanoTime() < deadline) {
            latest = source.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
            if (matches(latest)) return latest
        }
        error("Native content viewport did not settle: $latest")
    }

    private fun assertRetained(initial: JsonObject, moved: JsonObject) {
        for (name in listOf("marker", "input", "tab", "scroll")) {
            assertEquals(initial[name], moved[name], "Compose modal transfer lost $name")
        }
    }

    override fun close() {
        handle.close()
        try {
            closed.awaitNative()
        } finally {
            nativeEdt { window.dispose() }
            owner.close()
            owner.coroutineScope.cancel()
        }
    }
}

private fun command(request: Int, operation: String, height: Int = 2400): JsonElement = buildJsonObject {
    put("request", request)
    put("operation", operation)
    put("height", height)
}

private fun workspaceDocument(
    reports: LinkedBlockingQueue<JsonObject>,
    viewports: LinkedBlockingQueue<JsonObject>,
    overlays: LinkedBlockingQueue<JsonObject>,
) = BrowserDocument(
    title = "Retained native workspace",
    data = command(0, "check"),
    bridge = { method, arguments ->
        when (method) {
            "viewport" -> viewports.offer(arguments.jsonObject)
            "overlay" -> overlays.offer(arguments.jsonObject)
            else -> reports.offer(arguments.jsonObject)
        }
        BrowserBridgeResult.Value(JsonNull)
    },
    html = """
        <!doctype html><body style="margin:0"><main id="screen"></main><script>
        const marker = crypto.randomUUID();
        let tab = 'program', draft = '', renders = 0;
        function render(height) {
          document.getElementById('screen').innerHTML = '<div style="height:' + height + 'px;background:#20334a">' +
            '<input id="entry" style="margin-top:16px"><div>Retained workspace</div></div>';
          document.getElementById('entry').value = draft;
          renders++;
        }
        async function report(data) {
          if (data.operation === 'seed') { draft = 'unsaved 42 / 11'; tab = 'training'; }
          render(data.height);
          if (data.operation === 'seed') {
            document.getElementById('entry').focus();
            window.scrollTo(0, 650);
          }
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          await heartbeat.call('report', {request:data.request, marker, renders,
            input:document.getElementById('entry').value, tab, scroll:scrollY});
          reportViewport();
        }
        function reportViewport() {
              heartbeat.call('viewport', {request:heartbeat.data.request, height:innerHeight, width:innerWidth,
                contentHeight:document.getElementById('screen').getBoundingClientRect().height});
              heartbeat.call('overlay', {
                overlayWidth:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--hb-overlay-width')) || 0,
                overlayHeight:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--hb-overlay-height')) || 0});
        }
        addEventListener('resize', reportViewport);
        addEventListener('heartbeat:theme', reportViewport);
        addEventListener('heartbeat:data', event => report(event.detail));
        report(heartbeat.data);
        </script></body>
    """.trimIndent(),
)
