package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalDensity
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.ds.components.HbDesktopPopupRendering
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.hbSurface
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
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_close_tab
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
import org.jetbrains.compose.resources.stringResource
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
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
    private var isDocumentOnly by mutableStateOf(false)
    private var isShown by mutableStateOf(true)
    private var overlayInset = 0.0
    private val window = nativeEdt {
        ComposeWindow().apply {
            title = "Heartbeat retained Compose workspace verification (no input)"
            setSize(900, 720)
            val host = this
            setContent { Content(host) }
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
            val modal = awaitModal()
            awaitOverlayPosition(modal)
            awaitShape(modal, isRounded = true)
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
    private fun Content(host: Window) {
        HbDesktopPopupRendering(host)
        LaunchedEffect(isExpanded) {
            isDocumentOnly = false
            if (isExpanded) {
                // AppScreen discovers the loaded root through a separate surface-state subscription.
                withFrameNanos { }
                isDocumentOnly = true
            }
        }
        HbTheme(darkTheme = true) {
            val inset = with(LocalDensity.current) { HbTheme.spacing.s.toPx() }
            SideEffect { overlayInset = inset / host.graphicsConfiguration.defaultTransform.scaleX }
            if (isExpanded) {
                HbDialog(
                    "Retained app",
                    { isExpanded = false },
                    width = HbDialogWidth.Wide,
                    isContentScrollable = false,
                    isContentEdgeToEdge = isDocumentOnly,
                    isTitleVisible = !isDocumentOnly,
                    headerActions = {
                        HbIconButton(
                            HbIcons.Close,
                            stringResource(Res.string.browser_close_tab, "Retained app"),
                            { isExpanded = false },
                            modifier = if (isDocumentOnly) {
                                Modifier.hbSurface(HbTheme.colors.surface, HbTheme.shapes.small)
                            } else {
                                Modifier
                            },
                        )
                    },
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
            awaitOverlay(isOccupied = true)
            awaitShape(modal, isRounded = true)
            awaitOverlayPosition(modal)
        } else {
            snapshot.await(window.rootPane, 1, areAllClipped = true)
            awaitShape(window, isRounded = false)
            awaitOverlay(isOccupied = false)
        }
        nativeEdt {
            val panel = assertNotNull(panels.retain(surface.document))
            val attachment = panel.createComponent().parent as JComponent
            val interop = attachment.parent as JComponent
            assertEquals(!isExpanded, attachment.isOpaque, "Attachment backdrop ignored its current host")
            assertEquals(!isExpanded, interop.isOpaque, "Compose interop backdrop ignored its current host")
        }
    }

    private fun awaitOverlayPosition(modal: RootPaneContainer) {
        val result = CompletableFuture<Unit>()
        var latest = "No visible document or owned Close popup"
        val timer = nativeEdt {
            Timer(20) {
                val component = assertNotNull(panels.retain(surface.document)).createComponent()
                val popup = (modal as Window).ownedWindows.singleOrNull {
                    it.isShowing && !it.focusableWindowState && it.type == Window.Type.POPUP
                }
                if (component.isShowing && popup != null) {
                    val page = Rectangle(component.visibleRect).apply {
                        translate(component.locationOnScreen.x, component.locationOnScreen.y)
                    }
                    val close = popup.bounds
                    latest = "Page=$page, Close=$close, inset=$overlayInset"
                    // Layer draw bounds and native document clipping can round opposite fractional edges.
                    val isMeasured = close.width > 1 && close.height > 1
                    val isAtCorner = abs(page.maxX - close.maxX - overlayInset) <= 2 &&
                        abs(close.y - page.y - overlayInset) <= 2
                    if (isMeasured && isAtCorner) result.complete(Unit)
                }
            }.apply { start() }
        }
        try {
            result.awaitNative()
        } catch (error: TimeoutException) {
            throw AssertionError("Close did not follow the visible page: $latest", error)
        } finally {
            nativeEdt(timer::stop)
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
                    val rounded = shape != null && shape.radius > 0 && shape.isMasked
                    if (shape != null && rounded == isRounded &&
                        shape.matchesFrame(nativeDocumentFrame(host))
                    ) {
                        result.complete(shape)
                    }
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
        } catch (error: TimeoutException) {
            val shapes = nativeEdt { snapshot.shapes(host.rootPane) }
            val expected = nativeEdt { nativeDocumentFrame(host) }
            throw AssertionError("Rounded=$isRounded, expected frame=$expected, last native shape=$shapes", error)
        } finally {
            nativeEdt(timer::stop)
        }
    }

    private fun nativeDocumentFrame(host: RootPaneContainer): Rectangle {
        val component = assertNotNull(panels.retain(surface.document)).createComponent()
        val visible = component.visibleRect
        val origin = SwingUtilities.convertPoint(component, visible.location, host.rootPane)
        return Rectangle(origin.x, host.rootPane.height - origin.y - visible.height, visible.width, visible.height)
    }

    private fun awaitOverlay(isOccupied: Boolean) {
        try {
            awaitViewport(overlays) {
                val width = it.getValue("overlayWidth").jsonPrimitive.content.toDouble()
                val height = it.getValue("overlayHeight").jsonPrimitive.content.toDouble()
                if (isOccupied) width > 0 && height > 0 else width == 0.0 && height == 0.0
            }
        } catch (error: IllegalStateException) {
            throw AssertionError(nativeEdt { describeWindow(window) }, error)
        }
    }

    private fun describeWindow(host: Window): String = buildString {
        append("${host.javaClass.simpleName} bounds=${host.bounds} showing=${host.isShowing}")
        append(" focusable=${host.focusableWindowState}")
        append(" client=${(host as? RootPaneContainer)?.contentPane?.bounds}")
        append(host.ownedWindows.joinToString(prefix = " children=[", postfix = "]", transform = ::describeWindow))
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

/** The mask must be on the current visible native rectangle, not merely a retained layer with the right flags. */
private fun NativeDocumentShape.matchesFrame(expected: Rectangle): Boolean {
    val expectedSize = listOf(expected.width.toDouble(), expected.height.toDouble())
    val matchingSizes = listOf(viewFrame, viewBounds, layerFrame, layerBounds).all { rect ->
        listOf(rect.width, rect.height).zip(expectedSize).all { (actual, wanted) -> abs(actual - wanted) <= 1 }
    }
    val matchingOrigin = abs(viewFrame.x - expected.x) <= 1 && abs(viewFrame.y - expected.y) <= 1
    return hasLayer && matchingSizes && matchingOrigin
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
