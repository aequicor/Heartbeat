package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
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
    private val documents = ProfileBrowserDocuments()
    private val handle = assertNotNull(documents.open(workspaceDocument(reports)))
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
        BrowserDocumentPresentation.Fill,
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
    }

    @Composable
    private fun Content() {
        HbTheme(darkTheme = true) {
            if (isExpanded) {
                HbDialog(
                    "Retained app",
                    { isExpanded = false },
                    width = HbDialogWidth.Wide,
                    isContentFill = true,
                    isContentScrollable = false,
                ) {
                    BrowserDocumentView(surface, Modifier.fillMaxSize())
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
        } else {
            snapshot.await(window.rootPane, 1, areAllClipped = true)
        }
    }

    private fun awaitModal(): RootPaneContainer {
        val result = CompletableFuture<RootPaneContainer>()
        val timer = nativeEdt {
            Timer(20) {
                val modal = window.ownedWindows.singleOrNull { it.isVisible }
                if (modal is RootPaneContainer) result.complete(modal)
            }.apply { start() }
        }
        return try {
            result.awaitNative()
        } finally {
            nativeEdt(timer::stop)
        }
    }

    private fun awaitReport(request: Int): JsonObject {
        val report = assertNotNull(reports.poll(30, TimeUnit.SECONDS), "Missing workspace report $request")
        assertEquals(request.toString(), report.getValue("request").jsonPrimitive.content)
        assertEquals(JsonPrimitive(request + 1), report["renders"], "Mount delivered application data again")
        return report
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

private fun command(request: Int, operation: String): JsonElement = buildJsonObject {
    put("request", request)
    put("operation", operation)
}

private fun workspaceDocument(reports: LinkedBlockingQueue<JsonObject>) = BrowserDocument(
    title = "Retained native workspace",
    data = command(0, "check"),
    bridge = { _, arguments ->
        reports.offer(arguments.jsonObject)
        BrowserBridgeResult.Value(JsonNull)
    },
    html = """
        <!doctype html><body style="margin:0"><main id="screen"></main><script>
        const marker = crypto.randomUUID();
        let tab = 'program', draft = '', renders = 0;
        function render() {
          document.getElementById('screen').innerHTML = '<div style="height:2400px;background:#20334a">' +
            '<input id="entry" style="margin-top:16px"><div>Retained workspace</div></div>';
          document.getElementById('entry').value = draft;
          renders++;
        }
        async function report(data) {
          if (data.operation === 'seed') { draft = 'unsaved 42 / 11'; tab = 'training'; }
          render();
          if (data.operation === 'seed') {
            document.getElementById('entry').focus();
            window.scrollTo(0, 650);
          }
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          await heartbeat.call('report', {request:data.request, marker, renders,
            input:document.getElementById('entry').value, tab, scroll:scrollY});
        }
        addEventListener('heartbeat:data', event => report(event.detail));
        report(heartbeat.data);
        </script></body>
    """.trimIndent(),
)
