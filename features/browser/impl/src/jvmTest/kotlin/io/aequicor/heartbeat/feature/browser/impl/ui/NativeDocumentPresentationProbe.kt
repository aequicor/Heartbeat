package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentAttachment
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.OS
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JDialog
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Production document engine, page bootstrap, bridge and layout observer; no JavaScript or measurement fakes. */
internal fun verifyNativeDocumentPresentation(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) {
    val reports = LinkedBlockingQueue<JsonObject>()
    val documents = ProfileBrowserDocuments()
    val handle = assertNotNull(documents.open(presentationDocument(reports)))
    val owner = BrowserTestScope(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
    val closed = CompletableFuture<Unit>()
    var nativePages = 0
    val views = object : DesktopDocumentViews {
        override fun register(panel: DesktopDocumentPanel) {
            nativePages++
        }
        override fun closed(panel: DesktopDocumentPanel) {
            closed.complete(Unit)
        }
    }
    val panels = nativeEdt { DesktopDocumentPanels(resources, dispatchers, owner, owner, views, documents) }
    val document = documents.documents.value.getValue(handle.route.id)
    val panel = nativeEdt {
        assertNotNull(panels.retain(document))
    }
    val start = nativeEdt { panel.newAttachment() }
    val frame = nativeEdt {
        panel.updateTheme(DesktopDocumentTheme(false, mapOf("--hb-text" to "rgb(10, 20, 30)")))
        JFrame("Heartbeat document presentation verification (no input)").apply {
            add(start.createComponent())
            setSize(640, 480)
            isVisible = true
        }
    }
    try {
        val marker = verifyNativeDocumentContent(panel, handle, reports)
        val relocation = NativeDocumentRelocation(panel, frame, reports) {
            handle.update(
                buildJsonObject {
                    put("height", 80)
                    put("request", it)
                },
            )
        }
        relocation.verify(marker, start)
        assertSame(panel, nativeEdt { panels.retain(document) })
        assertEquals(1, nativePages, "Moving, hiding and reopening must use exactly one native page")
    } finally {
        handle.close()
        try {
            closed.awaitNative()
            assertNull(panel.contentSize.value, "Closing revokes the retained page's measurements")
            assertNull(nativeEdt { panels.retain(document) })
        } finally {
            nativeEdt {
                panel.release()
                frame.dispose()
            }
            runBlocking { withTimeout(30_000) { panel.awaitClosed() } }
            owner.close()
            owner.coroutineScope.cancel()
        }
    }
}

private fun verifyNativeDocumentContent(
    panel: DesktopDocumentPanel,
    handle: BrowserDocumentHandle,
    reports: LinkedBlockingQueue<JsonObject>,
): JsonElement {
    val initial = report(reports, "ready")
    val marker = initial.getValue("marker")
    assertEquals(JsonPrimitive(100), initial["height"])
    assertEquals(JsonPrimitive("light"), initial["theme"])
    awaitHeight(panel, 100)
    for (height in listOf(300, 80)) {
        handle.update(buildJsonObject { put("height", height) })
        val resized = report(reports, "data")
        assertEquals(JsonPrimitive(height), resized["height"])
        assertEquals(marker, resized["marker"])
        awaitHeight(panel, height)
    }
    nativeEdt { panel.updateTheme(DesktopDocumentTheme(true, mapOf("--hb-text" to "rgb(220, 230, 240)"))) }
    val themed = report(reports, "theme")
    assertEquals(JsonPrimitive("dark"), themed["theme"])
    assertEquals(JsonPrimitive("rgb(220, 230, 240)"), themed["color"])
    assertEquals(marker, themed["marker"], "Theme changes must retain the actual DOM instance")
    assertEquals(JsonPrimitive("retained input"), themed["input"])
    assertEquals(buildJsonObject { put("height", 80) }, themed["data"])
    awaitHeight(panel, 80)
    return marker
}

/** Moves the actual production page through native windows; the input, tab and scroll live only in its DOM. */
private class NativeDocumentRelocation(
    private val panel: DesktopDocumentPanel,
    private val startWindow: JFrame,
    private val reports: LinkedBlockingQueue<JsonObject>,
    private val request: (Int) -> Unit,
) {
    private val clipping = if (OS.isMacintosh()) NativeDocumentClipSnapshot() else null

    fun verify(marker: kotlinx.serialization.json.JsonElement, start: DesktopDocumentAttachment) {
        clipping?.await(startWindow.rootPane, 1, areAllClipped = true)
        var prior = start
        for (iteration in 1..2) {
            val attachment = nativeEdt { panel.newAttachment() }
            val modal = nativeEdt {
                JDialog(startWindow, "Retained document modal verification", false).apply {
                    add(attachment.createComponent())
                    setSize(900, 650)
                    isVisible = true
                }
            }
            try {
                nativeEdt { prior.close() }
                clipping?.await(startWindow.rootPane, 0)
                clipping?.await(modal.rootPane, 1, areAllClipped = true)
                request(iteration)
                assertRetained(report(reports, "data"), marker, nativeEdt { modal.contentPane.size })
                nativeEdt {
                    attachment.close()
                    assertNull(panel.createComponent().parent)
                    assertFalse(panel.createComponent().isVisible)
                }
                clipping?.await(modal.rootPane, 0)
            } finally {
                nativeEdt {
                    attachment.close()
                    modal.dispose()
                }
            }
            prior = attachment
        }
        val restored = nativeEdt {
            panel.newAttachment().also {
                startWindow.contentPane.removeAll()
                startWindow.add(it.createComponent())
                startWindow.validate()
            }
        }
        try {
            clipping?.await(startWindow.rootPane, 1, areAllClipped = true)
            request(3)
            assertRetained(report(reports, "data"), marker, nativeEdt { startWindow.contentPane.size })
        } finally {
            nativeEdt(restored::close)
        }
    }

    private fun assertRetained(report: JsonObject, marker: kotlinx.serialization.json.JsonElement, size: Dimension) {
        assertEquals(marker, report["marker"], "Reparent must preserve the live DOM instance")
        assertEquals(JsonPrimitive("retained input"), report["input"])
        assertEquals(JsonPrimitive("history"), report["tab"])
        assertEquals(JsonPrimitive(120), report["scroll"])
        assertEquals(JsonPrimitive(size.width), report["width"])
        assertEquals(JsonPrimitive(size.height), report["viewportHeight"])
    }
}

private fun awaitHeight(panel: DesktopDocumentPanel, expected: Int) = runBlocking {
    withTimeout(30_000) { panel.contentSize.first { it?.height == expected && it.width > 0 } }
}

private fun report(reports: LinkedBlockingQueue<JsonObject>, expected: String): JsonObject {
    val report = assertNotNull(reports.poll(30, TimeUnit.SECONDS), "Missing document report: $expected")
    assertEquals(expected, report.getValue("kind").jsonPrimitive.content)
    return report
}

private fun presentationDocument(reports: LinkedBlockingQueue<JsonObject>) = BrowserDocument(
    title = "Document presentation",
    data = buildJsonObject { put("height", 100) },
    sizing = BrowserDocumentSizing.Content,
    bridge = { _, arguments ->
        reports.offer(arguments.jsonObject)
        BrowserBridgeResult.Value(JsonNull)
    },
    html = """
        <!doctype html><body><div id="root" style="height:100px"><input id="entry">
        <div id="scroll" style="height:30px;overflow:auto"><div style="height:400px">History</div></div></div><script>
        const root = document.getElementById('root');
        const entry = document.getElementById('entry');
        window.marker = crypto.randomUUID();
        entry.value = 'retained input';
        window.activeTab = 'history';
        const scroller = document.getElementById('scroll');
        scroller.scrollTop = 120;
        async function report(kind) {
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          await heartbeat.call('report', {kind, marker: window.marker, input: entry.value,
            height: root.getBoundingClientRect().height, theme: heartbeat.theme.colorScheme,
            color: getComputedStyle(root).color, data: heartbeat.data,
            tab: window.activeTab, scroll: scroller.scrollTop, width: innerWidth, viewportHeight: innerHeight});
        }
        window.addEventListener('heartbeat:data', event => {
          root.style.height = event.detail.height + 'px';
          report('data');
        });
        window.addEventListener('heartbeat:theme', () => report('theme'));
        report('ready');
        </script></body>
    """.trimIndent(),
)
