package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDocumentBrowsersTest {
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Swing
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    // Real time: views close on the Swing thread, which virtual time would outrun.
    @Test
    fun `the runtime's teardown releases every view still registered`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val browsers = DesktopDocumentBrowsers(dispatchers)
        val documents = ProfileBrowserDocuments()
        val released = mutableListOf<DesktopDocumentPanel>()
        val panels = withContext(Dispatchers.Swing) {
            List(2) { index ->
                val handle = checkNotNull(documents.open(BrowserDocument("<p>$index</p>", "Page $index")))
                DesktopDocumentPanel(
                    documents.documents.value.getValue(handle.route.id),
                    DesktopBrowserResources { awaitCancellation() },
                    dispatchers,
                    scope,
                ) { released += it }.also(browsers::register)
            }
        }
        browsers.closed(panels.first())

        browsers.closeDocuments()

        assertEquals(listOf(panels.last()), released)
        browsers.closed(panels.last())
        browsers.closeDocuments()
        assertEquals(1, released.size)
        scope.cancel()
    }
}
