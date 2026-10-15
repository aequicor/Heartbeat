package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppBridgeCaptureTest {
    @Test
    fun `a page request received before first chat cannot acquire the rebound context`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val model = fixture.surface
        val document = fixture.web.opened.single()
        val bridge = document.document.bridge!!
        val pending = bridge.capture()

        fixture.bind(AppChatContext)
        runCurrent()
        assertSame(model, fixture.surface)
        assertIs<BrowserBridgeResult.Failure>(pending.call("old", JsonNull))
        assertTrue(fixture.calls.made.none { it.first == "old" })
        assertIs<BrowserBridgeResult.Value>(bridge.call("current", JsonNull))
        assertEquals(AppChatContext.call, fixture.calls.made.last().second)
        assertFalse(document.isClosed)
    }

    @Test
    fun `a request captured during a paused binding stays revoked after admission`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val bridge = fixture.web.opened.single().document.bridge!!
        fixture.surface.pause()
        val pending = bridge.capture()

        fixture.bind(AppChatContext)
        runCurrent()
        assertIs<BrowserBridgeResult.Failure>(pending.call("paused", JsonNull))
        assertTrue(fixture.calls.made.none { it.first == "paused" })
        assertIs<BrowserBridgeResult.Value>(bridge.capture().call("current", JsonNull))
        assertEquals(AppChatContext.call, fixture.calls.made.last().second)
    }
}
