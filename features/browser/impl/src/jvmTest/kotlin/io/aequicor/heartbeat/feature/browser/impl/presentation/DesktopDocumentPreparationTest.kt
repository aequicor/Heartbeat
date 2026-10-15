package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cef.CefApp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDocumentPreparationTest {
    @Test
    fun `document can retry offline preparation once and closing revokes progress and further retries`() = runTest {
        val documents = ProfileBrowserDocuments()
        val handle = assertNotNull(documents.open(BrowserDocument("<p>Local page</p>", "Page")))
        val resources = RetryingResources()
        var released = 0
        val panel = DesktopDocumentPanel(
            documents.documents.value.getValue(handle.route.id),
            resources,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
            backgroundScope,
        ) { released++ }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        assertTrue(panel.unavailable.value)
        assertEquals(BrowserRuntimeUi.Failed(BrowserRuntimeProblem.Network), panel.preparation.value)
        SwingUtilities.invokeAndWait {
            panel.retry()
            panel.retry()
        }
        runCurrent()
        assertEquals(2, resources.attempts)
        assertFalse(panel.unavailable.value)
        resources.preparation.value = BrowserRuntimePreparation.Downloading(1024 * 1024, 4 * 1024 * 1024)
        runCurrent()
        assertEquals(BrowserRuntimeUi.Downloading(1, 25), panel.preparation.value)
        SwingUtilities.invokeAndWait { panel.release() }
        runCurrent()
        val last = panel.preparation.value
        resources.preparation.value = BrowserRuntimePreparation.Failed(BrowserRuntimeFailure.InvalidRuntime)
        runCurrent()
        SwingUtilities.invokeAndWait { panel.retry() }
        assertEquals(last, panel.preparation.value)
        assertEquals(2, resources.attempts)
        assertEquals(1, released)
        panel.awaitClosed()
        handle.close()
    }

    private class RetryingResources : DesktopBrowserResources {
        override val preparation = MutableStateFlow<BrowserRuntimePreparation>(BrowserRuntimePreparation.Idle)
        var attempts = 0

        override suspend fun app(): CefApp {
            attempts++
            if (attempts == 1) {
                preparation.value = BrowserRuntimePreparation.Failed(BrowserRuntimeFailure.Network)
                error("offline")
            }
            preparation.value = BrowserRuntimePreparation.Downloading(0, null)
            awaitCancellation()
        }
    }
}
