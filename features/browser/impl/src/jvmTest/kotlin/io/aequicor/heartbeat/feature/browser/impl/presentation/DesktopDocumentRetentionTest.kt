package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.ui.nativeEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.swing.Swing
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopDocumentRetentionTest {
    @Test
    fun `one registration retains one panel while old lease disposal cannot detach the new host`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val owner = BrowserTestScope(scope)
        val documents = ProfileBrowserDocuments()
        val handle = assertNotNull(documents.open(BrowserDocument("<p/>", "Retained")))
        val released = CompletableFuture<Unit>()
        var registrations = 0
        val views = object : DesktopDocumentViews {
            override fun register(panel: DesktopDocumentPanel) {
                registrations++
            }
            override fun closed(panel: DesktopDocumentPanel) {
                released.complete(Unit)
            }
        }
        try {
            nativeEdt {
                val panels = DesktopDocumentPanels(
                    DesktopBrowserResources { awaitCancellation() },
                    BrowserTestDispatchers(Dispatchers.Swing),
                    owner,
                    owner,
                    views,
                    documents,
                )
                val document = documents.documents.value.getValue(handle.route.id)
                val panel = assertNotNull(panels.retain(document))
                val start = panel.newAttachment()
                val first = start.createComponent()
                val content = first.getComponent(0)
                assertSame(panel, panels.retain(document))
                val modal = panel.newAttachment()
                val second = modal.createComponent()
                assertEquals(0, first.componentCount)
                assertSame(content, second.getComponent(0))
                start.close()
                assertSame(second, content.parent)
                assertTrue(content.isVisible)
                modal.close()
                assertNull(content.parent)
                assertFalse(content.isVisible)
                val reopened = panel.newAttachment().createComponent()
                assertSame(content, reopened.getComponent(0))
                assertEquals(1, registrations)
                handle.close()
                assertNull(panels.retain(document), "A stale composition cannot resurrect its closed registration")
            }
            released.get(10, TimeUnit.SECONDS)
        } finally {
            owner.close()
            scope.cancel()
        }
    }
}
