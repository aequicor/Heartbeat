package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BROWSER_FAVICON_PIXELS
import io.aequicor.heartbeat.feature.browser.impl.domain.chooseBrowserFavicon
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Finds the page's declared icon through CDP and fetches its bitmap through the tab's own profile. */
internal class DesktopBrowserFaviconNativeProbe(
    private val createEngine: (LinkedBlockingQueue<BrowserViewState>) -> DesktopBrowserEngine,
    private val createFrame: () -> JFrame,
    private val awaitState: (LinkedBlockingQueue<BrowserViewState>, (BrowserViewState) -> Boolean) -> BrowserViewState,
    private val stage: (String) -> Unit,
) {
    fun verify(base: String) {
        stage("favicon navigation")
        val states = LinkedBlockingQueue<BrowserViewState>()
        var engine: DesktopBrowserEngine? = null
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait {
            engine = createEngine(states)
            frame = createFrame().apply {
                add(assertNotNull(engine).component, BorderLayout.CENTER)
                validate()
            }
            assertNotNull(engine).start("$base/icon-page")
        }
        val runtime = assertNotNull(engine)
        try {
            awaitState(states) { it.isLoading && it.isAwaitingResponse }
            awaitState(states) { it.isLoading && !it.isAwaitingResponse }
            awaitState(states) { it.title == "Icon" && !it.isLoading }
            stage("favicon CDP and transfer")
            runBlocking {
                val candidates = DesktopBrowserFaviconProbe(runtime::devTools).candidates()
                val icon = assertNotNull(chooseBrowserFavicon("$base/icon-page", candidates))
                assertEquals("$base/icon.png", icon)
                val png = decodeBrowserFavicon(runtime.loadIcon(icon) { true })
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
                assertEquals(BROWSER_FAVICON_PIXELS, image.width)
                assertFailsWith<BrowserFaviconRejected> { runtime.loadIcon("$base/icon-page") { true } }
            }
        } finally {
            stage("favicon cleanup")
            SwingUtilities.invokeAndWait {
                runtime.release()
                frame?.dispose()
            }
            runBlocking { runtime.awaitClosed() }
        }
    }
}
