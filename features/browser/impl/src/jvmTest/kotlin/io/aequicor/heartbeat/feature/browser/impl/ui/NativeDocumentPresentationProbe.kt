package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Production document engine, page bootstrap, bridge and layout observer; no JavaScript or measurement fakes. */
internal fun verifyNativeDocumentPresentation(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) {
    val reports = LinkedBlockingQueue<JsonObject>()
    val documents = ProfileBrowserDocuments()
    val handle = assertNotNull(documents.open(presentationDocument(reports)))
    val panel = nativeEdt {
        DesktopDocumentPanel(documents.documents.value.getValue(handle.route.id), resources, dispatchers, scope) {}
    }
    val frame = nativeEdt {
        panel.updateTheme(DesktopDocumentTheme(false, mapOf("--hb-text" to "rgb(10, 20, 30)")))
        JFrame("Heartbeat document presentation verification (no input)").apply {
            add(panel.createComponent())
            setSize(640, 480)
            isVisible = true
        }
    }
    try {
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
    } finally {
        nativeEdt {
            panel.release()
            frame.dispose()
        }
        runBlocking { withTimeout(30_000) { panel.awaitClosed() } }
        handle.close()
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
        <!doctype html><body><div id="root" style="height:100px"><input id="entry"></div><script>
        const root = document.getElementById('root');
        const entry = document.getElementById('entry');
        window.marker = crypto.randomUUID();
        entry.value = 'retained input';
        async function report(kind) {
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          await heartbeat.call('report', {kind, marker: window.marker, input: entry.value,
            height: root.getBoundingClientRect().height, theme: heartbeat.theme.colorScheme,
            color: getComputedStyle(root).color, data: heartbeat.data});
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
